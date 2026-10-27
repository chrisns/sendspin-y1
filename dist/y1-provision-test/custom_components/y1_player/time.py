"""The bedtime window: start and end."""
from __future__ import annotations

from datetime import time as dtime

from homeassistant.components.time import TimeEntity
from homeassistant.const import EntityCategory
from homeassistant.helpers.event import async_track_state_change_event

from .const import DOMAIN
from .entity import Y1Entity


async def async_setup_entry(hass, entry, add):
    dev = hass.data[DOMAIN][entry.entry_id]
    add([Window(dev, "bedtime_start", "Bedtime starts", "mdi:weather-night"),
         Window(dev, "bedtime_end", "Bedtime ends", "mdi:weather-sunset-up")])


class Window(Y1Entity, TimeEntity):
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, dev, key, name, icon):
        super().__init__(dev, key, name)
        self.key, self._attr_icon = key, icon

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        eid = self.dev.linked(self.key)
        if eid:
            self.async_on_remove(async_track_state_change_event(self.hass, [eid], lambda _e: self.async_write_ha_state()))

    @property
    def native_value(self):
        v = self.dev.bedtime(self.key)
        if not v:
            return None
        h, m, *s = str(v).split(":")
        return dtime(int(h), int(m), int(s[0]) if s else 0)

    async def async_set_value(self, value: dtime) -> None:
        await self.dev.async_set_bedtime(self.key, value.strftime("%H:%M:%S"))
