"""Find it: the player chimes on its next check-in (it checks every minute or so while on Wi-Fi)."""
from __future__ import annotations

import time as _time

from homeassistant.components.button import ButtonEntity

from .const import DOMAIN
from .entity import Y1Entity


async def async_setup_entry(hass, entry, add):
    add([Find(hass.data[DOMAIN][entry.entry_id], "find", "Find it")])


class Find(Y1Entity, ButtonEntity):
    _attr_icon = "mdi:bell-ring"

    async def async_press(self) -> None:
        self.dev.data["find"] = str(int(_time.time() * 1000))
        self.dev.save()
