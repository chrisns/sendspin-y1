"""Where the player is: home Wi-Fi, another network, or its last GPS fix."""
from __future__ import annotations

from homeassistant.components.device_tracker import SourceType, TrackerEntity

from .const import DOMAIN
from .entity import Y1Entity


async def async_setup_entry(hass, entry, add):
    add([Where(hass.data[DOMAIN][entry.entry_id], "location", None)])   # named as the device: device_tracker.<device>


class Where(Y1Entity, TrackerEntity):
    _attr_icon = "mdi:music-box"

    @property
    def source_type(self):
        return SourceType.GPS

    @property
    def latitude(self):
        g = self.dev.data["location"].get("gps")
        return g[0] if g else None

    @property
    def longitude(self):
        g = self.dev.data["location"].get("gps")
        return g[1] if g else None

    @property
    def location_accuracy(self):
        return int(self.dev.data["location"].get("accuracy") or 0)

    @property
    def location_name(self):
        return None if self.dev.data["location"].get("gps") else self.dev.data["location"].get("zone")

    @property
    def battery_level(self):
        return self.dev.data["sensors"].get("battery")
