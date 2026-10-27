"""What the player reports: battery, now playing, bedtime minutes left and so on."""
from __future__ import annotations

from homeassistant.components.sensor import SensorDeviceClass, SensorEntity, SensorStateClass
from homeassistant.const import PERCENTAGE, EntityCategory, UnitOfTime
from homeassistant.util import dt as dt_util

from .const import DOMAIN
from .entity import Y1Entity

# device report key, name, icon, device class, unit, state class
SENSORS = [
    ("battery", "Battery", None, SensorDeviceClass.BATTERY, PERCENTAGE, SensorStateClass.MEASUREMENT),
    ("now_playing", "Now playing", "mdi:music", None, None, None),
    ("listen_on", "Listen on", "mdi:speaker", None, None, None),
    ("bedtime_left", "Bedtime minutes left", "mdi:timer-sand", None, UnitOfTime.MINUTES, None),
    ("find_my_beacon", "Find My beacon status", "mdi:map-marker-radius", None, None, None),
    ("wifi_network", "Wi-Fi network", "mdi:wifi", None, None, None),
]


async def async_setup_entry(hass, entry, add):
    dev = hass.data[DOMAIN][entry.entry_id]
    add([Reported(dev, *s) for s in SENSORS] + [When(dev, "last_seen", "Last seen", lambda d: d.data.get("last_seen")),
         When(dev, "last_gps_fix", "Last GPS fix", lambda d: d.data["sensors"].get("last_gps_fix")),
         When(dev, "unlocked_until", "Bedtime unlocked until", lambda d: d.data.get("unlocked_until"))])


class Reported(Y1Entity, SensorEntity):
    _live = True

    def __init__(self, dev, key, name, icon, dclass, unit, sclass):
        super().__init__(dev, key, name)
        self.key = key
        self._attr_icon, self._attr_device_class = icon, dclass
        self._attr_native_unit_of_measurement, self._attr_state_class = unit, sclass

    @property
    def native_value(self):
        return self.dev.data["sensors"].get(self.key)

    @property
    def extra_state_attributes(self):
        a = self.dev.data["sensors"].get(self.key + "_attributes")
        return a if isinstance(a, dict) else None


class When(Y1Entity, SensorEntity):
    _attr_device_class = SensorDeviceClass.TIMESTAMP

    def __init__(self, dev, key, name, get):
        super().__init__(dev, key, name)
        self.get = get
        if key == "last_seen":
            self._attr_entity_category = EntityCategory.DIAGNOSTIC

    @property
    def native_value(self):
        v = self.get(self.dev)
        return dt_util.parse_datetime(v) if v else None
