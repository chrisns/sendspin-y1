"""Constants for the Y1 Player integration."""
DOMAIN = "y1_player"

CONF_DEVICE_PUB = "device_public_key"
CONF_SERVER_PRIV = "server_private_key"
CONF_SERVER_PUB = "server_public_key"
CONF_WEBHOOK_ID = "webhook_id"
CONF_CLOUDHOOK = "cloudhook_url"

# options: link a bedtime control to an existing helper instead of the integration's own value
OPT_LINK = {
    "bedtime_start": "link_bedtime_start",
    "bedtime_end": "link_bedtime_end",
    "bedtime_cap": "link_bedtime_cap",
    "bedtime_used": "link_bedtime_used",
}
OPT_NOTIFY = "notify_service"
OPT_STT = "stt_entity"   # speech-to-text for voice search when the player has no local model

EVENT_UNLOCKED = "y1_player_unlocked"

# settings the device reads (and may change itself, parent-gated): key -> definition. Newest change wins, by timestamp.
SETTINGS = {
    "owner_name": {"kind": "text", "name": "Owner name", "default": "", "icon": "mdi:account"},
    "device_name": {"kind": "text", "name": "Device name", "default": "", "icon": "mdi:music-box"},
    "lost_contact": {"kind": "text", "name": "If found contact", "default": "", "icon": "mdi:card-account-phone",
                     "help": "lines separated by |"},
    "home_wifi": {"kind": "text", "name": "Home Wi-Fi", "default": "", "icon": "mdi:wifi", "help": "comma separated"},
    "counted_speakers": {"kind": "text", "name": "Speakers counted elsewhere", "default": "", "icon": "mdi:speaker",
                         "help": "speakers whose listening HA already counts towards bedtime, comma separated"},
    "volume_limit": {"kind": "number", "name": "Volume limit", "default": 100, "min": 10, "max": 100, "step": 5,
                     "unit": "%", "icon": "mdi:volume-high"},
    "hold_after": {"kind": "select", "name": "Hold after", "default": "60", "icon": "mdi:lock-clock",
                   "options": {"0": "Never", "30": "30 seconds", "60": "1 minute", "120": "2 minutes", "300": "5 minutes"}},
    "unlock_speed": {"kind": "select", "name": "Unlock speed", "default": "1000", "icon": "mdi:gesture-tap-button",
                     "options": {"500": "0.5 seconds", "750": "0.75 seconds", "1000": "1 second", "1500": "1.5 seconds",
                                 "2000": "2 seconds"}},
    "wheel_click": {"kind": "select", "name": "Wheel click", "default": "3", "icon": "mdi:vibrate",
                    "options": {"0": "Off", "1": "Sound", "2": "Vibration", "3": "Sound and vibration"}},
    "click_loudness": {"kind": "select", "name": "Click loudness", "default": "0", "icon": "mdi:volume-low",
                       "options": {"0": "Soft", "1": "Medium", "2": "Loud"}},
    "wheel_reversed": {"kind": "switch", "name": "Wheel reversed", "default": False, "icon": "mdi:rotate-left"},
    "find_my": {"kind": "switch", "name": "Find My beacon", "default": True, "icon": "mdi:map-marker-radius"},
    "speaker_always_on": {"kind": "switch", "name": "Speaker always on", "default": False, "icon": "mdi:speaker-wireless"},
    "keep_offline": {"kind": "switch", "name": "Keep everything offline", "default": True, "icon": "mdi:download"},
}

BEDTIME_DEFAULTS = {"bedtime_start": "19:45:00", "bedtime_end": "06:30:00", "bedtime_cap": 20, "bedtime_used": 0}
