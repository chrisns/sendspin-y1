"""Y1 Player: a click-wheel music player (sendspin-y1) as a Home Assistant device.

The player talks only to this integration, over an encrypted, signed webhook (crypto.py): it reports its sensors,
location, bedtime minutes and grown-up unlocks, and reads its settings, the bedtime budget and "find" requests. It
holds no Home Assistant token and can do nothing else. All its settings are entities on its device page.
"""
from __future__ import annotations

import logging
import time as _time
from datetime import datetime, timedelta
from typing import Any, Callable

from aiohttp import web

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, ServiceCall, ServiceResponse, SupportsResponse, callback
from homeassistant.helpers.event import async_track_time_change
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from . import crypto
from .const import (BEDTIME_DEFAULTS, CONF_CLOUDHOOK, CONF_DEVICE_PUB, CONF_SERVER_PRIV, CONF_SERVER_PUB, CONF_WEBHOOK_ID,
                    DOMAIN, EVENT_UNLOCKED, OPT_LINK, OPT_NOTIFY, SETTINGS)

_LOGGER = logging.getLogger(__name__)
PLATFORMS = [Platform.SENSOR, Platform.BINARY_SENSOR, Platform.DEVICE_TRACKER, Platform.TEXT, Platform.NUMBER,
             Platform.SELECT, Platform.SWITCH, Platform.TIME, Platform.BUTTON]


class Y1Device:
    """One player: its stored state, the webhook, and what its entities show."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.hass, self.entry = hass, entry
        self.store = Store(hass, 1, f"{DOMAIN}.{entry.entry_id}")
        self.data: dict[str, Any] = {}
        self._listeners: list[Callable[[], None]] = []
        self.k_in, self.k_out = crypto.session_keys(entry.data[CONF_SERVER_PRIV], entry.data[CONF_DEVICE_PUB])
        self._unsub_reset: Callable[[], None] | None = None

    # ---- state

    async def async_load(self) -> None:
        self.data = await self.store.async_load() or {}
        self.data.setdefault("counter", 0)
        self.data.setdefault("settings", {})          # key -> [value, changed_ms]
        self.data.setdefault("sensors", {})
        self.data.setdefault("location", {})
        self.data.setdefault("bedtime", dict(BEDTIME_DEFAULTS))
        for k, d in SETTINGS.items():
            self.data["settings"].setdefault(k, [d["default"], 0])

    def save(self) -> None:
        self.store.async_delay_save(lambda: self.data, 1)

    @callback
    def listen(self, cb: Callable[[], None]) -> Callable[[], None]:
        self._listeners.append(cb)
        return lambda: self._listeners.remove(cb)

    @callback
    def changed(self) -> None:
        for cb in list(self._listeners):
            cb()

    @property
    def name(self) -> str:
        return str(self.setting("device_name") or self.entry.title)

    def setting(self, key: str) -> Any:
        return self.data["settings"][key][0]

    @callback
    def set_setting(self, key: str, value: Any) -> None:
        """A change made in Home Assistant: newer than anything the device has, so it wins."""
        self.data["settings"][key] = [value, int(_time.time() * 1000)]
        self.save()
        self.changed()

    # ---- bedtime: the integration's own values, or linked helpers shared with other automations

    def linked(self, key: str) -> str | None:
        return self.entry.options.get(OPT_LINK[key]) or None

    def bedtime(self, key: str) -> Any:
        eid = self.linked(key)
        if eid:
            st = self.hass.states.get(eid)
            if st is None or st.state in ("unknown", "unavailable"):
                return None
            return st.state if key in ("bedtime_start", "bedtime_end") else float(st.state)
        return self.data["bedtime"][key]

    async def async_set_bedtime(self, key: str, value: Any) -> None:
        eid = self.linked(key)
        if eid:
            domain = eid.split(".")[0]
            if key in ("bedtime_start", "bedtime_end"):
                await self.hass.services.async_call(domain, "set_datetime", {"entity_id": eid, "time": str(value)}, blocking=True)
            else:
                await self.hass.services.async_call(domain, "set_value", {"entity_id": eid, "value": value}, blocking=True)
        else:
            self.data["bedtime"][key] = value
            self.save()
            if key == "bedtime_start":
                self.schedule_reset()
        self.changed()

    def night(self) -> str:
        """The night the counter belongs to, in Home Assistant's time zone ("none" outside the window)."""
        s, e = str(self.bedtime("bedtime_start") or "19:45:00"), str(self.bedtime("bedtime_end") or "06:30:00")
        now = dt_util.now()
        t = now.strftime("%H:%M:%S")
        if t >= s:
            return now.date().isoformat()
        if t < e:
            return (now - timedelta(days=1)).date().isoformat()
        return "none"

    @callback
    def schedule_reset(self) -> None:
        """Own counter: back to zero when the window opens (a linked counter is reset by its own automation)."""
        if self._unsub_reset:
            self._unsub_reset()
            self._unsub_reset = None
        if self.linked("bedtime_used"):
            return
        h, m, *_ = (str(self.bedtime("bedtime_start") or "19:45:00") + ":00").split(":")

        async def reset(_now: datetime) -> None:
            await self.async_set_bedtime("bedtime_used", 0)

        self._unsub_reset = async_track_time_change(self.hass, reset, hour=int(h), minute=int(m), second=0)

    # ---- the webhook

    async def handle(self, hass: HomeAssistant, webhook_id: str, request: web.Request) -> web.Response:
        try:
            msg = crypto.open_(self.k_in, await request.json())
        except Exception:   # not from this device (or tampered): say nothing more
            return web.Response(status=400)
        c = int(msg.get("c", 0))
        if c <= self.data["counter"]:
            # an old or replayed message; the (sealed) reply lets a player that lost its counter catch up
            _LOGGER.warning("%s: replayed or old message (counter %s, last %s) refused", self.name, c, self.data["counter"])
            return web.json_response(crypto.seal(self.k_out, {"last": self.data["counter"]}), status=409)
        self.data["counter"] = c
        await self.apply(msg)
        self.save()
        self.changed()
        return web.json_response(crypto.seal(self.k_out, self.reply(c)))

    async def apply(self, msg: dict[str, Any]) -> None:
        self.data["last_seen"] = dt_util.utcnow().isoformat()
        if isinstance(msg.get("sensors"), dict):
            self.data["sensors"].update(msg["sensors"])
        if isinstance(msg.get("location"), dict):
            self.data["location"] = msg["location"]
        played = msg.get("played")
        if isinstance(played, dict):   # seq repeats when a reply was lost: those minutes are already counted
            minutes, seq = int(played.get("minutes") or 0), int(played.get("seq") or 0)
            if 1 <= minutes <= 60 and seq > int(self.data.get("played_seq", 0)):
                self.data["played_seq"] = seq
                used = float(self.bedtime("bedtime_used") or 0)
                await self.async_set_bedtime("bedtime_used", used + minutes)
        if msg.get("unlocked_until"):
            until = datetime.fromtimestamp(int(msg["unlocked_until"]) / 1000, dt_util.UTC)
            self.data["unlocked_until"] = until.isoformat()
            self.hass.bus.async_fire(EVENT_UNLOCKED, {"device": self.name, "until": until.isoformat()})
            notify = self.entry.options.get(OPT_NOTIFY)
            if notify and "." in notify:
                domain, service = notify.split(".", 1)
                local = dt_util.as_local(until).strftime("%H:%M")
                self.hass.async_create_task(self.hass.services.async_call(domain, service, {
                    "title": "Bedtime unlocked", "message": f"The grown-up code was used on {self.name}, until {local}."}))
        for k, v in (msg.get("settings") or {}).items():     # changed on the device itself: newest wins
            if not (k in SETTINGS and isinstance(v, list) and len(v) == 2):
                continue
            have = self.data["settings"][k]
            # newer on the device, or never set here yet (a new pairing adopts what the player already has)
            if int(v[1] or 0) > int(have[1]) or (int(have[1]) == 0 and v[0] is not None and v[0] != have[0]):
                self.data["settings"][k] = [v[0], int(v[1] or 0)]
        if msg.get("app"):
            self.data["app"] = str(msg["app"])

    def reply(self, c: int) -> dict[str, Any]:
        def hhmm(v: Any, d: str) -> str:
            return str(v or d)[:5]

        def minutes(key: str) -> int:   # -1: a linked helper is unavailable (the player then keeps counting itself)
            v = self.bedtime(key)
            return -1 if v is None else int(v)
        return {
            "c": c,
            "now": int(_time.time() * 1000),
            "settings": self.data["settings"],
            "bedtime": {
                "start": hhmm(self.bedtime("bedtime_start"), "19:45"), "end": hhmm(self.bedtime("bedtime_end"), "06:30"),
                "cap": minutes("bedtime_cap"), "used": minutes("bedtime_used"),
                "night": self.night(),
            },
            "find": self.data.get("find", ""),
        }


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    dev = Y1Device(hass, entry)
    await dev.async_load()
    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = dev
    webhook.async_register(hass, DOMAIN, entry.title, entry.data[CONF_WEBHOOK_ID], dev.handle, allowed_methods=["POST"])
    # a Nabu Casa cloudhook, so the player reaches us away from home (it only ever carries ciphertext)
    if not entry.data.get(CONF_CLOUDHOOK) and "cloud" in hass.config.components:
        try:
            from homeassistant.components import cloud
            if cloud.async_active_subscription(hass):
                url = await cloud.async_get_or_create_cloudhook(hass, entry.data[CONF_WEBHOOK_ID])
                hass.config_entries.async_update_entry(entry, data={**entry.data, CONF_CLOUDHOOK: url})
        except Exception:   # no subscription or not logged in: home network only
            _LOGGER.info("%s: no Nabu Casa cloudhook, the player is reachable on the home network only", entry.title)
    dev.schedule_reset()
    entry.async_on_unload(entry.add_update_listener(_options_updated))
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    if not hass.services.has_service(DOMAIN, "pairing_info"):
        async def pairing_info(call: ServiceCall) -> ServiceResponse:
            """What the player needs (for deploy/push_bedtime.py): webhook, cloudhook, the integration's public key."""
            out = {}
            for e in hass.config_entries.async_entries(DOMAIN):
                out[e.data[CONF_DEVICE_PUB]] = {"entry_id": e.entry_id, "title": e.title, "webhook_id": e.data[CONF_WEBHOOK_ID],
                                                "cloudhook_url": e.data.get(CONF_CLOUDHOOK), "server_public_key": e.data[CONF_SERVER_PUB]}
            return {"devices": out}
        hass.services.async_register(DOMAIN, "pairing_info", pairing_info, supports_response=SupportsResponse.ONLY)
    return True


async def _options_updated(hass: HomeAssistant, entry: ConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    webhook.async_unregister(hass, entry.data[CONF_WEBHOOK_ID])
    dev: Y1Device = hass.data[DOMAIN][entry.entry_id]
    if dev._unsub_reset:
        dev._unsub_reset()
    ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if ok:
        hass.data[DOMAIN].pop(entry.entry_id)
    return ok
