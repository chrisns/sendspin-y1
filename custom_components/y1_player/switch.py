"""On/off settings: Find My beacon, speaker always on, keep everything offline."""
from __future__ import annotations

from homeassistant.components.switch import SwitchEntity
from homeassistant.const import EntityCategory

from .const import DOMAIN, SETTINGS
from .entity import Y1Entity


async def async_setup_entry(hass, entry, add):
    dev = hass.data[DOMAIN][entry.entry_id]
    add([Setting(dev, k, d) for k, d in SETTINGS.items() if d["kind"] == "switch"])


class Setting(Y1Entity, SwitchEntity):
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, dev, key, d):
        super().__init__(dev, key, d["name"])
        self.key, self._attr_icon = key, d["icon"]

    @property
    def is_on(self):
        return bool(self.dev.setting(self.key))

    async def async_turn_on(self, **kw) -> None:
        self.dev.set_setting(self.key, True)

    async def async_turn_off(self, **kw) -> None:
        self.dev.set_setting(self.key, False)
