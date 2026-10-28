"""Y1 Player: a click-wheel music player (sendspin-y1) as a Home Assistant device.

The player talks only to this integration, over an encrypted, signed webhook (crypto.py): it reports its sensors,
location, bedtime minutes and grown-up unlocks, and reads its settings, the bedtime budget and "find" requests. It
holds no Home Assistant token and can do nothing else. All its settings are entities on its device page.
"""
from __future__ import annotations

import asyncio
import base64
import logging
import time as _time
from datetime import datetime, timedelta
from typing import Any, Callable

import voluptuous as vol
from aiohttp import web

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, ServiceCall, ServiceResponse, SupportsResponse, callback
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.event import async_track_state_change_event, async_track_time_change
from homeassistant.helpers.service import async_register_admin_service
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from . import crypto
from .const import (BEDTIME_DEFAULTS, CONF_CLOUDHOOK, CONF_DEVICE_PUB, CONF_SERVER_PRIV, CONF_SERVER_PUB, CONF_WEBHOOK_ID,
                    DOMAIN, EVENT_UNLOCKED, OPT_LINK, OPT_NOTIFY, OPT_STT, SETTINGS)

_LOGGER = logging.getLogger(__name__)
MAX_PCM = 960000          # voice search: 30 s of 16 kHz mono 16-bit
MAX_VOICE_PER_MIN = 6
SKEW_MS = 5 * 60 * 1000   # a device setting stamped further ahead than this is a bad clock, not a change


def _time_of(v: Any) -> str | None:
    """HH:MM:SS from a time or datetime helper state (the date part, if any, is ignored)."""
    t = dt_util.parse_time(str(v))
    if t is None:
        d = dt_util.parse_datetime(str(v))
        t = d.time() if d else None
    return t.strftime("%H:%M:%S") if t else None


def _valid_setting(k: str, v: Any) -> Any:
    """The value if it fits the setting's definition (a select's value is kept as its option string), else None."""
    d = SETTINGS[k]
    kind = d["kind"]
    if kind == "text":
        return v if isinstance(v, str) and len(v) <= 500 else None
    if kind == "switch":
        return v if isinstance(v, bool) else None
    if isinstance(v, bool) or not isinstance(v, (int, float, str)):
        return None
    if kind == "select":
        o = str(int(v)) if isinstance(v, float) and v == int(v) else str(v)
        return o if o in d["options"] else None
    try:
        n = float(v)
    except ValueError:
        return None
    return int(n) if d["min"] <= n <= d["max"] and n == int(n) else None
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
        self._lock = asyncio.Lock()
        self._voice: list[float] = []

    # ---- state

    async def async_load(self) -> None:
        self.data = await self.store.async_load() or {}
        self.data.setdefault("counter", 0)
        self.data.setdefault("settings", {})          # key -> [value, changed_ms]
        self.data.setdefault("sensors", {})
        self.data.setdefault("location", {})
        self.data.setdefault("bedtime", dict(BEDTIME_DEFAULTS))
        self.data.setdefault("pending_minutes", 0)
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
            try:
                cb()
            except Exception:   # one entity that cannot write its state must not stop the rest or the reply
                _LOGGER.exception("%s: an entity failed to update", self.name)

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
            if key in ("bedtime_start", "bedtime_end"):
                return _time_of(st.state)
            try:
                return float(st.state)
            except ValueError:
                return None
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

    def roll_night(self) -> None:
        """Own counter: zero it when a new night has begun, even if HA was down when the window opened."""
        if self.linked("bedtime_used"):
            return
        n = self.night()
        if n == "none" or n == self.data.get("used_night"):
            return
        if "used_night" in self.data:
            self.data["bedtime"]["bedtime_used"] = 0
        self.data["used_night"] = n
        self.save()

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
            self.data["used_night"] = self.night()
            self.save()

        self._unsub_reset = async_track_time_change(self.hass, reset, hour=int(h), minute=int(m), second=0)

    # ---- the webhook

    async def handle(self, hass: HomeAssistant, webhook_id: str, request: web.Request) -> web.Response:
        try:
            msg = crypto.open_(self.k_in, await request.json())
        except Exception:   # not from this device (or tampered): say nothing more
            return web.Response(status=400)
        try:
            c = int(msg.get("c", 0))
        except (TypeError, ValueError):
            c = 0
        if c <= self.data["counter"]:
            # an old or replayed message; the (sealed) reply lets a player that lost its counter catch up ("rc" binds
            # it to this request, so an old reply cannot be replayed for a new one)
            _LOGGER.warning("%s: replayed or old message (counter %s, last %s) refused", self.name, c, self.data["counter"])
            return web.json_response(crypto.seal(self.k_out, {"last": self.data["counter"], "rc": c}), status=409)
        self.data["counter"] = c
        try:
            await self.apply(msg)
        except Exception:   # the counter has moved: a failure here must not leave the player without an answer
            _LOGGER.exception("%s: could not apply a message", self.name)
        self.save()
        self.changed()
        try:
            out = self.reply(c)
        except Exception:
            _LOGGER.exception("%s: could not build the reply", self.name)
            out = {"c": c, "now": int(_time.time() * 1000), "error": "reply"}
        if msg.get("transcribe"):
            out["transcript"] = await self.transcribe_msg(msg, out)
        return web.json_response(crypto.seal(self.k_out, out))

    async def transcribe_msg(self, msg: dict[str, Any], out: dict[str, Any]) -> str:
        """Check a voice search message (size, rate, format), then transcribe it; a refusal is a sealed error field."""
        now = _time.monotonic()
        self._voice = [t for t in self._voice if now - t < 60]
        try:
            if msg.get("rate") not in (None, 16000):
                raise ValueError("rate")
            pcm = base64.b64decode(str(msg["transcribe"]), validate=True)
            if len(pcm) > MAX_PCM:
                raise ValueError("too long")
        except Exception:
            out["error"] = "transcribe"
            return ""
        if len(self._voice) >= MAX_VOICE_PER_MIN:
            out["error"] = "rate"
            return ""
        self._voice.append(now)
        try:
            return await self.transcribe(pcm)
        except Exception:
            _LOGGER.exception("%s: voice search failed", self.name)
            return ""

    async def transcribe(self, pcm: bytes) -> str:
        """Voice search: 16 kHz mono 16-bit speech to text with a Home Assistant speech-to-text entity."""
        from homeassistant.components import stt
        eid = self.entry.options.get(OPT_STT)
        if not eid:
            ids = [s.entity_id for s in self.hass.states.async_all("stt")]
            eid = "stt.home_assistant_cloud" if "stt.home_assistant_cloud" in ids else (ids[0] if ids else None)
        ent = stt.async_get_speech_to_text_entity(self.hass, eid) if eid else None
        if ent is None:
            _LOGGER.warning("%s: voice search needs a speech-to-text entity", self.name)
            return ""
        langs = list(ent.supported_languages)
        if not langs:
            _LOGGER.warning("%s: the speech-to-text entity lists no languages", self.name)
            return ""
        lang = next((x for x in ("en-GB", "en-gb", "en-US", "en") if x in langs), next((x for x in langs if x.lower().startswith("en")), langs[0]))
        meta = stt.SpeechMetadata(language=lang, format=stt.AudioFormats.WAV, codec=stt.AudioCodecs.PCM,
                                  bit_rate=stt.AudioBitRates.BITRATE_16, sample_rate=stt.AudioSampleRates.SAMPLERATE_16000,
                                  channel=stt.AudioChannels.CHANNEL_MONO)

        async def chunks():
            for i in range(0, len(pcm), 3200):
                yield pcm[i:i + 3200]
        try:
            run = getattr(ent, "internal_async_process_audio_stream", None) or ent.async_process_audio_stream
            res = await run(meta, chunks())
        except Exception:
            _LOGGER.exception("%s: speech-to-text failed", self.name)
            return ""
        return (res.text or "") if res.result == stt.SpeechResultState.SUCCESS else ""

    async def flush_minutes(self) -> None:
        """Played minutes not yet in the shared counter (its helper was unavailable) go in as soon as it is back."""
        async with self._lock:
            pending = int(self.data.get("pending_minutes") or 0)
            used = self.bedtime("bedtime_used")
            if pending <= 0 or used is None:
                return
            try:
                await self.async_set_bedtime("bedtime_used", used + pending)
            except Exception:
                _LOGGER.warning("%s: could not add %s played minutes to the bedtime counter yet", self.name, pending, exc_info=True)
                return
            self.data["pending_minutes"] = 0

    async def _notify(self, notify: str, until: datetime) -> None:
        domain, service = notify.split(".", 1)
        local = dt_util.as_local(until).strftime("%H:%M")
        data = {"title": "Bedtime unlocked", "message": f"The grown-up code was used on {self.name}, until {local}."}
        try:
            await self.hass.services.async_call(domain, service, data, blocking=True)
        except Exception:
            _LOGGER.warning("%s: the notify service %s failed, so the unlock alert went to the notifications panel", self.name, notify)
            await self.hass.services.async_call("persistent_notification", "create", data)

    async def apply(self, msg: dict[str, Any]) -> None:
        self.data["last_seen"] = dt_util.utcnow().isoformat()
        self.roll_night()
        if isinstance(msg.get("sensors"), dict):
            self.data["sensors"].update({k: v for k, v in msg["sensors"].items() if isinstance(v, (str, int, float, bool, dict, type(None)))})
        if isinstance(msg.get("location"), dict):
            self.data["location"] = msg["location"]
        played = msg.get("played")
        if isinstance(played, dict):   # seq repeats when a reply was lost: those minutes are already counted
            try:
                minutes, seq = int(played.get("minutes") or 0), int(played.get("seq") or 0)
            except (TypeError, ValueError):
                minutes = seq = 0
            if 1 <= minutes <= 60 and seq > int(self.data.get("played_seq", 0)):
                # counted: held here until a linked helper can take them, so a failed write never loses them
                self.data["played_seq"] = seq
                self.data["pending_minutes"] = int(self.data.get("pending_minutes") or 0) + minutes
        await self.flush_minutes()
        if msg.get("unlocked_until"):
            try:
                until = datetime.fromtimestamp(int(msg["unlocked_until"]) / 1000, dt_util.UTC)
            except (TypeError, ValueError, OverflowError, OSError):
                until = None
            if until:
                self.data["unlocked_until"] = until.isoformat()
                self.hass.bus.async_fire(EVENT_UNLOCKED, {"device": self.name, "until": until.isoformat()})
                notify = self.entry.options.get(OPT_NOTIFY)
                if notify and "." in notify:
                    self.hass.async_create_task(self._notify(notify, until))
        horizon = int(_time.time() * 1000) + SKEW_MS
        for k, v in (msg.get("settings") or {}).items():     # changed on the device itself: newest wins
            if not (k in SETTINGS and isinstance(v, list) and len(v) == 2):
                continue
            try:
                ts = int(v[1] or 0)
            except (TypeError, ValueError):
                continue
            val = _valid_setting(k, v[0])
            if val is None or ts > horizon:     # a bad value, or a clock that is ahead: not a change to believe
                continue
            have = self.data["settings"][k]
            # newer on the device, or never set here yet (a new pairing adopts what the player already has)
            if ts > int(have[1]) or (int(have[1]) == 0 and val != have[0]):
                self.data["settings"][k] = [val, ts]
        if msg.get("app"):
            self.data["app"] = str(msg["app"])
        self.sync_device_registry()

    def sync_device_registry(self) -> None:
        """The device page follows the player: its name and app version."""
        reg = dr.async_get(self.hass)
        dev = reg.async_get_device(identifiers={(DOMAIN, self.entry.entry_id)})
        if dev and (dev.name != self.name or dev.sw_version != self.data.get("app")):
            reg.async_update_device(dev.id, name=self.name, sw_version=self.data.get("app"))

    @property
    def seen_recently(self) -> bool:
        """Heard from within the last 10 minutes (it checks in every one to five)."""
        seen = dt_util.parse_datetime(self.data.get("last_seen") or "")
        return bool(seen) and dt_util.utcnow() - seen < timedelta(minutes=10)

    def reply(self, c: int) -> dict[str, Any]:
        def hhmm(v: Any, d: str) -> str:
            return str(v or d)[:5]

        def minutes(key: str) -> int:   # -1: a linked helper is unavailable (the player then keeps counting itself)
            v = self.bedtime(key)
            return -1 if v is None else int(v)
        return {
            "c": c,
            "now": int(_time.time() * 1000),
            "played_seq": int(self.data.get("played_seq", 0)),
            "settings": self.data["settings"],
            "bedtime": {
                "start": hhmm(self.bedtime("bedtime_start"), "19:45"), "end": hhmm(self.bedtime("bedtime_end"), "06:30"),
                "cap": minutes("bedtime_cap"), "used": minutes("bedtime_used"),
                "night": self.night(),
            },
            "find": self.data.get("find", ""),
            "gift": self.data.get("gift", ""),
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
    start = dev.linked("bedtime_start")
    if start:   # the window moved: the own counter's reset moves with it
        entry.async_on_unload(async_track_state_change_event(hass, [start], lambda _e: dev.schedule_reset()))
    entry.async_on_unload(entry.add_update_listener(_options_updated))
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    if not hass.services.has_service(DOMAIN, "pairing_info"):
        async def pairing_info(call: ServiceCall) -> ServiceResponse:
            """What the player needs (for deploy/y1_setup.py): webhook, cloudhook, the integration's public key."""
            only = call.data.get("device_public_key")
            out = {}
            for e in hass.config_entries.async_entries(DOMAIN):
                if only and e.data[CONF_DEVICE_PUB] != only:
                    continue
                out[e.data[CONF_DEVICE_PUB]] = {"entry_id": e.entry_id, "title": e.title, "webhook_id": e.data[CONF_WEBHOOK_ID],
                                                "cloudhook_url": e.data.get(CONF_CLOUDHOOK), "server_public_key": e.data[CONF_SERVER_PUB]}
            return {"devices": out}
        # admins only: the webhook ids and cloudhook URLs it returns are what guard the player's endpoint
        async_register_admin_service(hass, DOMAIN, "pairing_info", pairing_info,
                                     schema=vol.Schema({vol.Optional("device_public_key"): str}),
                                     supports_response=SupportsResponse.ONLY)
    return True


async def _options_updated(hass: HomeAssistant, entry: ConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    dev: Y1Device = hass.data[DOMAIN][entry.entry_id]
    await dev.store.async_save(dev.data)   # a reload must not lose the last second (counter, played seq, settings)
    ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if ok:
        webhook.async_unregister(hass, entry.data[CONF_WEBHOOK_ID])
        if dev._unsub_reset:
            dev._unsub_reset()
        hass.data[DOMAIN].pop(entry.entry_id)
        if not hass.data[DOMAIN]:
            hass.services.async_remove(DOMAIN, "pairing_info")
    return ok


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """The player was deleted: its cloudhook (a public URL in the Nabu Casa account) and stored state go too."""
    if entry.data.get(CONF_CLOUDHOOK) and "cloud" in hass.config.components:
        try:
            from homeassistant.components import cloud
            await cloud.async_delete_cloudhook(hass, entry.data[CONF_WEBHOOK_ID])
        except Exception:
            _LOGGER.warning("%s: could not delete its Nabu Casa cloudhook; remove it in Settings > Home Assistant Cloud", entry.title)
    await Store(hass, 1, f"{DOMAIN}.{entry.entry_id}").async_remove()
