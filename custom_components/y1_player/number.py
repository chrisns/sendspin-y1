"""Number settings: volume limit, and the bedtime allowance and minutes used."""
from __future__ import annotations

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.const import EntityCategory, UnitOfTime

from .const import DOMAIN, SETTINGS
from .entity import Y1Entity


async def async_setup_entry(hass, entry, add):
    dev = hass.data[DOMAIN][entry.entry_id]
    add([Setting(dev, k, d) for k, d in SETTINGS.items() if d["kind"] == "number"]
        + [Bedtime(dev, "bedtime_cap", "Bedtime allowance", 0, 240, 5, "mdi:timer-cog"),
           Bedtime(dev, "bedtime_used", "Bedtime minutes used", 0, 240, 1, "mdi:timer")])


class Setting(Y1Entity, NumberEntity):
    _attr_entity_category = EntityCategory.CONFIG
    _attr_mode = NumberMode.SLIDER

    def __init__(self, dev, key, d):
        super().__init__(dev, key, d["name"])
        self.key, self._attr_icon = key, d["icon"]
        self._attr_native_min_value, self._attr_native_max_value = d["min"], d["max"]
        self._attr_native_step, self._attr_native_unit_of_measurement = d["step"], d.get("unit")

    @property
    def native_value(self):
        return self.dev.setting(self.key)

    async def async_set_native_value(self, value: float) -> None:
        self.dev.set_setting(self.key, int(value))


class Bedtime(Y1Entity, NumberEntity):
    """The integration's own value, or a mirror of the linked helper (writes go to the helper)."""
    _attr_mode = NumberMode.BOX
    _attr_native_unit_of_measurement = UnitOfTime.MINUTES

    def __init__(self, dev, key, name, lo, hi, step, icon):
        super().__init__(dev, key, name)
        self.key, self._attr_icon = key, icon
        self._attr_native_min_value, self._attr_native_max_value, self._attr_native_step = lo, hi, step
        if key == "bedtime_cap":
            self._attr_entity_category = EntityCategory.CONFIG

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        eid = self.dev.linked(self.key)
        if eid:
            from homeassistant.helpers.event import async_track_state_change_event
            self.async_on_remove(async_track_state_change_event(self.hass, [eid], lambda _e: self.async_write_ha_state()))

    @property
    def extra_state_attributes(self):
        return {"linked_to": self.dev.linked(self.key)} if self.dev.linked(self.key) else None

    @property
    def native_value(self):
        return self.dev.bedtime(self.key)

    async def async_set_native_value(self, value: float) -> None:
        await self.dev.async_set_bedtime(self.key, int(value))
