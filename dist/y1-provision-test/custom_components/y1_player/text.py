"""Text settings: name, "if found" contact, home Wi-Fi, speakers counted elsewhere."""
from __future__ import annotations

from homeassistant.components.text import TextEntity
from homeassistant.const import EntityCategory

from .const import DOMAIN, SETTINGS
from .entity import Y1Entity


async def async_setup_entry(hass, entry, add):
    dev = hass.data[DOMAIN][entry.entry_id]
    add([Setting(dev, k, d) for k, d in SETTINGS.items() if d["kind"] == "text"])


class Setting(Y1Entity, TextEntity):
    _attr_entity_category = EntityCategory.CONFIG
    _attr_native_max = 255

    def __init__(self, dev, key, d):
        super().__init__(dev, key, d["name"])
        self.key, self._attr_icon = key, d["icon"]

    @property
    def native_value(self):
        return str(self.dev.setting(self.key) or "")

    async def async_set_value(self, value: str) -> None:
        self.dev.set_setting(self.key, value.strip())
