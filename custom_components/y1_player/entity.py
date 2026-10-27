"""Base entity: everything sits on one device per player."""
from __future__ import annotations

from datetime import timedelta

from homeassistant.core import callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity import Entity
from homeassistant.helpers.event import async_track_time_interval

from . import Y1Device
from .const import DOMAIN


class Y1Entity(Entity):
    _attr_has_entity_name = True
    _attr_should_poll = False
    _live = False   # what the player reports: unavailable once it has not checked in for a while (settings stay available)

    def __init__(self, dev: Y1Device, key: str, name: str) -> None:
        self.dev = dev
        self._attr_unique_id = f"{dev.entry.entry_id}_{key}"
        self._attr_name = name
        self._attr_device_info = DeviceInfo(identifiers={(DOMAIN, dev.entry.entry_id)}, name=dev.name,
                                            manufacturer="Innioasis", model="Y1 (sendspin-y1)",
                                            sw_version=dev.data.get("app"))

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(self.dev.listen(self.async_write_ha_state))
        if self._live:
            @callback
            def refresh(_now) -> None:       # a plain lambda would run in a worker thread
                self.async_write_ha_state()
            self.async_on_remove(async_track_time_interval(self.hass, refresh, timedelta(minutes=1)))

    @property
    def available(self) -> bool:
        return self.dev.seen_recently if self._live else True
