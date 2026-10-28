"""Find it: the player chimes on its next check-in (it checks every minute or so while on Wi-Fi)."""
from __future__ import annotations

import time as _time

from homeassistant.components.button import ButtonEntity

from .const import DOMAIN
from .entity import Y1Entity


async def async_setup_entry(hass, entry, add):
    dev = hass.data[DOMAIN][entry.entry_id]
    add([Find(dev, "find", "Find it"), ArmGift(dev, "arm_gift", "Arm for gifting")])


class ArmGift(Y1Entity, ButtonEntity):
    """Arm for gifting: the player arms and switches itself off at its next check-in; switched on again, it plays the Help
    video at full volume once and disarms. Needs a Help video on the player (else it says so and stays on)."""
    _attr_icon = "mdi:gift"

    async def async_press(self) -> None:
        self.dev.data["gift"] = str(int(_time.time() * 1000))
        self.dev.save()


class Find(Y1Entity, ButtonEntity):
    _attr_icon = "mdi:bell-ring"

    async def async_press(self) -> None:
        self.dev.data["find"] = str(int(_time.time() * 1000))
        self.dev.save()
