#!/usr/bin/env python3
"""Make the key timetable the iPod's Find My beacon broadcasts, and push it to the iPod.

  findmy_keys.py haystack            new OpenHaystack-style key (static); private key kept in deploy/secrets/findmy
  findmy_keys.py accessory FILE.json  keys of a real Find My item exported from your Apple account (FindMy.py JSON,
                                      e.g. from stek29/export-findmy); writes 15-minute rotating keys for DAYS ahead
  findmy_keys.py compact FILE.json    the same item as a 112-byte file: master PUBLIC key and the secret for now (see below), no end date
  findmy_keys.py push                 copy the file to the iPod (/sdcard/FindMy/findmy.bin) and tell the app

Timetable format (little endian): b"FMB1", u32 count, i64 start_epoch_s, u32 interval_s (0 = one static key),
then count x 28-byte advertisement keys. The iPod derives the BLE address and payload from each key.

Compact format (little endian): b"FMB2", u32 interval_s (900), u64 index of the secret, i64 epoch_s that index began,
56 bytes master public key (x then y), 32 bytes secret = 112 bytes. The iPod steps the secret forward and works out each key
itself (java/.../FindMyDerive.java), so it never runs out. The owner's private key is not in it: it is not needed to broadcast,
and without it a lost iPod cannot be used to read where the item has been. The secret only moves forward, so the file exposes
keys from that index on, never earlier ones.
An extra 28 bytes after the 112 (140 in all, unless --no-fixed) is a fixed key: what the iPod advertises while its Bluetooth is on,
and whose address its Bluetooth address is pinned to. Owners only look for a key for a week after its slot, so it is good for about
a week; make a new file (and pair the headphones again) when it has lapsed.
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


def compact(path):
    import hashlib
    from findmy import FindMyAccessory
    from cryptography.hazmat.primitives.asymmetric import ec
    acc = FindMyAccessory.from_json(path)
    now = dt.datetime.now(dt.timezone.utc)
    i0 = acc.get_max_index(now)
    t0 = acc._alignment_date + dt.timedelta(minutes=15) * (i0 - acc._alignment_index)   # when that index begins, on the accessory's clock
    sk = acc.skn
    for _ in range(i0):
        sk = hashlib.sha256(sk + b"\x00\x00\x00\x01" + b"update").digest()
    n = ec.derive_private_key(int.from_bytes(acc.master_key, "big"), ec.SECP224R1()).public_key().public_numbers()
    pub = n.x.to_bytes(28, "big") + n.y.to_bytes(28, "big")
    os.makedirs(SECRETS, exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(b"FMB2" + struct.pack("<IQq", 900, i0, int(t0.timestamp())) + pub + sk)
        if "--no-fixed" not in sys.argv:
            f.write(acc._primary_key_at(i0).adv_key_bytes)    # the key to carry while Bluetooth is on; the iPod's Bluetooth address becomes its address
    os.chmod(OUT, 0o600)
    print("wrote %s: %d bytes, secret at index %d (began %s)%s" % (OUT, os.path.getsize(OUT), i0, t0.strftime("%Y-%m-%d %H:%M:%S Z"),
          "" if "--no-fixed" in sys.argv else ", plus the fixed key for index %d (good for about a week)" % i0))


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
    elif cmd == "compact" and len(sys.argv) > 2: compact(sys.argv[2])
    elif cmd == "push": push()
    else: print(__doc__)
