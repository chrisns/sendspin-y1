"""On/off things the player reports."""
from __future__ import annotations

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity

from .const import DOMAIN
from .entity import Y1Entity

BINARY = [
    ("charging", "Charging", BinarySensorDeviceClass.BATTERY_CHARGING, None),
    ("playing", "Playing", BinarySensorDeviceClass.RUNNING, "mdi:play"),
    ("bedtime_locked", "Bedtime locked", BinarySensorDeviceClass.LOCK, None),
    ("on_hold", "Buttons on hold", None, "mdi:lock"),
]


async def async_setup_entry(hass, entry, add):
    dev = hass.data[DOMAIN][entry.entry_id]
    add([Flag(dev, *b) for b in BINARY])


class Flag(Y1Entity, BinarySensorEntity):
    _live = True

    def __init__(self, dev, key, name, dclass, icon):
        super().__init__(dev, key, name)
        self.key, self._attr_device_class, self._attr_icon = key, dclass, icon

    @property
    def is_on(self):
        v = self.dev.data["sensors"].get(self.key)
        if self.key == "bedtime_locked" and v is not None:
            return not v      # the lock device class reads on = unlocked
        return v
