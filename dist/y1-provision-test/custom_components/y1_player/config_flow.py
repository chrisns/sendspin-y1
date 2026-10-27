"""Config flow: pair a player by its public key; options link the bedtime controls to existing helpers."""
from __future__ import annotations

import base64
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.core import callback
from homeassistant.helpers import selector

from . import crypto
from .const import CONF_DEVICE_PUB, CONF_SERVER_PRIV, CONF_SERVER_PUB, CONF_WEBHOOK_ID, DOMAIN, OPT_LINK, OPT_NOTIFY, OPT_STT


class Y1ConfigFlow(ConfigFlow, domain=DOMAIN):
    """Add a player: its name and the public key it shows (or that the setup script reads off it)."""

    VERSION = 1

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            pub = user_input[CONF_DEVICE_PUB].strip()
            try:
                ok = len(base64.b64decode(pub, validate=True)) == 32
            except Exception:
                ok = False
            if not ok:
                errors[CONF_DEVICE_PUB] = "invalid_key"
            else:
                await self.async_set_unique_id(pub)
                self._abort_if_unique_id_configured()
                priv, spub = crypto.new_keypair()
                from homeassistant.components import webhook
                return self.async_create_entry(title=user_input["name"], data={
                    CONF_DEVICE_PUB: pub, CONF_SERVER_PRIV: priv, CONF_SERVER_PUB: spub,
                    CONF_WEBHOOK_ID: webhook.async_generate_id()})
        return self.async_show_form(step_id="user", errors=errors, data_schema=vol.Schema({
            vol.Required("name", default="Y1"): str,
            vol.Required(CONF_DEVICE_PUB): str,
        }))

    @staticmethod
    @callback
    def async_get_options_flow(entry: ConfigEntry) -> OptionsFlow:
        return Y1OptionsFlow()


class Y1OptionsFlow(OptionsFlow):
    """Link bedtime controls to helpers other automations already use (else the integration keeps its own)."""

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(data={k: v for k, v in user_input.items() if v})
        o = self.config_entry.options
        time_sel = selector.EntitySelector(selector.EntitySelectorConfig(domain="input_datetime"))
        num_sel = selector.EntitySelector(selector.EntitySelectorConfig(domain=["input_number", "number"]))
        return self.async_show_form(step_id="init", data_schema=vol.Schema({
            vol.Optional(OPT_LINK["bedtime_start"], description={"suggested_value": o.get(OPT_LINK["bedtime_start"])}): time_sel,
            vol.Optional(OPT_LINK["bedtime_end"], description={"suggested_value": o.get(OPT_LINK["bedtime_end"])}): time_sel,
            vol.Optional(OPT_LINK["bedtime_cap"], description={"suggested_value": o.get(OPT_LINK["bedtime_cap"])}): num_sel,
            vol.Optional(OPT_LINK["bedtime_used"], description={"suggested_value": o.get(OPT_LINK["bedtime_used"])}): num_sel,
            vol.Optional(OPT_NOTIFY, description={"suggested_value": o.get(OPT_NOTIFY)}): str,
            vol.Optional(OPT_STT, description={"suggested_value": o.get(OPT_STT)}):
                selector.EntitySelector(selector.EntitySelectorConfig(domain="stt")),
        }))
