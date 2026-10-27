"""Base entity: everything sits on one device per player."""
from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity import Entity

from . import Y1Device
from .const import DOMAIN


class Y1Entity(Entity):
    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, dev: Y1Device, key: str, name: str) -> None:
        self.dev = dev
        self._attr_unique_id = f"{dev.entry.entry_id}_{key}"
        self._attr_name = name
        self._attr_device_info = DeviceInfo(identifiers={(DOMAIN, dev.entry.entry_id)}, name=dev.entry.title,
                                            manufacturer="Innioasis", model="Y1 (sendspin-y1)",
                                            sw_version=dev.data.get("app"))

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(self.dev.listen(self.async_write_ha_state))
