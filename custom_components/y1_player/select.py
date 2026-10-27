"""Choice settings: hold after, unlock speed, wheel click."""
from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.const import EntityCategory

from .const import DOMAIN, SETTINGS
from .entity import Y1Entity


async def async_setup_entry(hass, entry, add):
    dev = hass.data[DOMAIN][entry.entry_id]
    add([Setting(dev, k, d) for k, d in SETTINGS.items() if d["kind"] == "select"])


class Setting(Y1Entity, SelectEntity):
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, dev, key, d):
        super().__init__(dev, key, d["name"])
        self.key, self._attr_icon, self.choices = key, d["icon"], d["options"]
        self._attr_options = list(d["options"].values())

    @property
    def current_option(self):
        return self.choices.get(str(self.dev.setting(self.key)))

    async def async_select_option(self, option: str) -> None:
        self.dev.set_setting(self.key, next(k for k, v in self.choices.items() if v == option))
