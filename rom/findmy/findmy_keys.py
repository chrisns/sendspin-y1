#!/usr/bin/env python3
"""Make the key timetable the iPod's Find My beacon broadcasts, and push it to the iPod.

  findmy_keys.py haystack            new OpenHaystack-style key (static); private key kept in deploy/secrets/findmy
  findmy_keys.py accessory FILE.json  keys of a real Find My item exported from your Apple account (FindMy.py JSON,
                                      e.g. from stek29/export-findmy); writes 15-minute rotating keys for DAYS ahead
  findmy_keys.py push                 copy the timetable to the iPod (/sdcard/FindMy/findmy.bin) and tell the app

Timetable format (little endian): b"FMB1", u32 count, i64 start_epoch_s, u32 interval_s (0 = one static key),
then count x 28-byte advertisement keys. The iPod derives the BLE address and payload from each key.
"""
import datetime as dt
import json
import os
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SECRETS = os.path.join(HERE, "..", "..", "deploy", "secrets", "findmy")
OUT = os.path.join(SECRETS, "findmy.bin")
DAYS = int(os.environ.get("DAYS", "400"))


def write(start, interval, keys):
    os.makedirs(SECRETS, exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(b"FMB1" + struct.pack("<IqI", len(keys), int(start), int(interval)))
        for k in keys:
            assert len(k) == 28
            f.write(k)
    os.chmod(OUT, 0o600)
    print("wrote %s: %d key(s), interval %ss" % (OUT, len(keys), interval))


def haystack():
    from findmy import KeyPair
    k = KeyPair.new()
    rec = {"name": os.environ.get("DEVICE_NAME", "Y1"), "privateKey": k.private_key_b64, "advertisementKey": k.adv_key_b64,
           "hashedAdvertisementKey": k.hashed_adv_key_b64, "findmy_py": k.to_json()}
    path = os.path.join(SECRETS, "haystack.json")
    with open(path, "w") as f:
        json.dump(rec, f, indent=1)
    os.chmod(path, 0o600)
    print("private key saved to", path, "(OpenHaystack / macless-haystack / FindMy.py can fetch its reports)")
    write(0, 0, [k.adv_key_bytes])


def accessory(path):
    from findmy import FindMyAccessory
    acc = FindMyAccessory.from_json(path)
    now = dt.datetime.now(dt.timezone.utc).replace(second=0, microsecond=0)
    start = now - dt.timedelta(minutes=now.minute % 15)
    n = DAYS * 96
    keys = []
    for i in range(n):
        t = start + dt.timedelta(minutes=15 * i)
        ind = acc.get_max_index(t)                 # where the real item's schedule would be at that moment
        keys.append(acc._primary_key_at(ind).adv_key_bytes)
    print("item:", acc.name, "paired", acc.paired_at)
    write(start.timestamp(), 900, keys)


def push():
    adb = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
    # /sdcard is not writable by adb's shell user here, so stage the file and copy it as root
    subprocess.check_call([adb, "push", OUT, "/data/local/tmp/findmy.bin"])
    subprocess.check_call([adb, "shell", "su -c 'mkdir -p /sdcard/FindMy && cp /data/local/tmp/findmy.bin /sdcard/FindMy/findmy.bin && rm /data/local/tmp/findmy.bin'"])
    subprocess.call([adb, "shell", "su -c 'am broadcast -a me.cns.ab.FINDMY_RELOAD >/dev/null'"])
    print("pushed; the iPod picks it up within a minute")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "haystack": haystack()
    elif cmd == "accessory" and len(sys.argv) > 2: accessory(sys.argv[2])
    elif cmd == "push": push()
    else: print(__doc__)
