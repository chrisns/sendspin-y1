#!/usr/bin/env python3
"""Turn a raw browser screenshot of the Home Assistant device page into a publishable PNG.

  docs/screenshots/ha_page.py KIND RAW.jpg      KIND: ha-device-top | ha-device-config | ha-device-config-more

The browser captures are taken with the Claude-in-Chrome tools (see .claude/skills/y1-docs/SKILL.md): open
/config/devices/device/<the player's device id> in a 1342x896 window, dismiss any toast, and take the top, the middle
and the bottom of the page. This crops off the sidebar (it shows your whole Home Assistant) and paints over the Wi-Fi
network names, phone number and email (coordinates in the 1342x896 capture), then writes NN-KIND.png and updates
manifest.json. Raw captures are never kept in the repository.
"""
import json, os, sys
from PIL import Image, ImageFilter
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from capture import ORDER, HERE, blur   # noqa: E402

KINDS = {
    "ha-device-top": ("The player in Home Assistant", "Its own device page: battery, whether bedtime is locked, minutes left, what it is playing and where, and a Find it button.",
                      [(770, 724, 905, 748)]),
    "ha-device-config": ("Every setting, in Home Assistant", "Bedtime window and allowance, click sound, names, Find My, hold time, the 'if found' contact and more. Change one and the player has it within a minute.",
                         [(770, 150, 905, 172), (660, 658, 900, 676), (660, 710, 900, 728)]),
    "ha-device-config-more": ("More settings", "Speakers counted elsewhere, unlock speed, voice on the iPod only, volume limit, wheel click and direction.",
                              [(660, 177, 900, 191), (660, 229, 890, 243)]),
}

kind, raw = sys.argv[1], sys.argv[2]
title, caption, boxes = KINDS[kind]
im = Image.open(raw).convert("RGB")
if im.size != (1342, 896): sys.exit("expected a 1342x896 capture, got %s" % (im.size,))
im = blur(im, boxes).crop((186, 0, 1342, 896))
fn = "%02d-%s.png" % (ORDER.index(kind) + 1, kind)
im.save(os.path.join(HERE, fn))
mf = os.path.join(HERE, "manifest.json")
have = {m["name"]: m for m in (json.load(open(mf)) if os.path.exists(mf) else [])}
have[kind] = {"file": fn, "name": kind, "title": title, "caption": caption}
json.dump(sorted(have.values(), key=lambda m: ORDER.index(m["name"])), open(mf, "w"), indent=1)
print(fn)
