#!/usr/bin/env python3
"""Set a player up with Home Assistant: its read-only user, its mobile_app registration, its identity helpers, the
grown-up unlock secret, and the ha.json it reads. Driven by a private config file (see device.example.json):

  DEVICE_CONFIG=deploy/secrets/bedtime/device.json   (the default)

  push_bedtime.py secret     make the 5-digit TOTP secret once (secrets/bedtime/totp, chmod 600)
  push_bedtime.py op VAULT   create the 1Password item showing the rolling grown-up code
  push_bedtime.py check      print the current code from our secret and from 1Password: they must match
  push_bedtime.py child      make the read-only Home Assistant user (config child_user, once) and register the device
                             as its mobile_app device, so the webhook acts as a user who can read but not control things.
                             Password kept in secrets/bedtime/<user>_ha_password (nobody needs to type it)
  push_bedtime.py register   register the device with an admin token instead (prefer `child`)
  push_bedtime.py identity   create the input_text helpers the device reads its identity from (owner name, device
                             name, "if found" contact, home Wi-Fi, speakers HA counts) and set them from config
                             "identity"; edit them in Home Assistant afterwards
  push_bedtime.py push       write files/ha.json on the device over adb (webhook, unlock secret, entity names, identity)

Nothing secret is printed except by `check`, which shows only the current 5-digit code.
"""
import base64, hashlib, hmac, json, os, re, secrets, struct, subprocess, sys, time, urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
SEC = os.path.join(HERE, "secrets", "bedtime")
CFG = json.load(open(os.environ.get("DEVICE_CONFIG", os.path.join(SEC, "device.json"))))
LOCAL = CFG["ha_url"].rstrip("/")      # tried first; the Nabu Casa cloudhook from the registration is the fallback
DEVICE_ID = CFG.get("device_id", "y1-player")
DEVICE_NAME = CFG.get("identity", {}).get("device_name") or "Y1"
CHILD = CFG.get("child_user", "Child")
TITLE = CFG.get("otp_title", DEVICE_NAME + " grown-up code")
APP = "me.cns.sendspin.android"
ADB = os.environ.get("ADB") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
DEV = ["-e"] if os.environ.get("EMULATOR") else ["-d"]   # EMULATOR=1 to try it on the emulator

# generic helper names (y1_*); config "entities" overrides any of them (keep the device's HaClient defaults in step)
ENTITIES = {
    "window_start": "input_datetime.y1_bedtime_start", "window_end": "input_datetime.y1_bedtime_end",
    "cap": "input_number.y1_bedtime_cap_minutes", "used": "input_number.y1_bedtime_used_minutes",
    "find": "input_button.y1_find", "owner_name": "input_text.y1_owner_name", "device_name": "input_text.y1_device_name",
    "lost_contact": "input_text.y1_lost_contact", "home_wifi": "input_text.y1_home_wifi",
    "counted_speakers": "input_text.y1_counted_speakers",
}
ENTITIES.update(CFG.get("entities", {}))
IDENTITY_KEYS = ["owner_name", "device_name", "lost_contact", "home_wifi", "counted_speakers"]


def read(name):
    return open(os.path.join(SEC, name)).read().strip()


def admin_token():
    return os.environ.get("HA_TOKEN") or open(os.path.expanduser(CFG.get("ha_admin_token_file", "~/.ha_token"))).read().strip()


def code(secret_b32, t=None):
    key = base64.b32decode(secret_b32 + "=" * (-len(secret_b32) % 8))
    h = hmac.new(key, struct.pack(">Q", int((t or time.time()) // 30)), hashlib.sha1).digest()
    o = h[-1] & 15
    return "%05d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7fffffff) % 100000)


def otpauth(secret_b32):
    label = urllib.parse.quote(DEVICE_NAME + ":grown-up code")
    return "otpauth://totp/%s?secret=%s&digits=5&period=30&algorithm=SHA1&issuer=%s" % (label, secret_b32, urllib.parse.quote(DEVICE_NAME))


def ws(msgs, token):
    """Run Home Assistant websocket commands in order; their results."""
    import asyncio, websockets

    async def go():
        out = []
        async with websockets.connect(LOCAL.replace("https", "wss").replace("http", "ws") + "/api/websocket") as w:
            await w.recv(); await w.send(json.dumps({"type": "auth", "access_token": token})); await w.recv()
            for i, m in enumerate(msgs, 1):
                await w.send(json.dumps(dict(m, id=i)))
                while True:
                    r = json.loads(await w.recv())
                    if r.get("id") == i: out.append(r); break
        return out
    return asyncio.run(go())


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    os.makedirs(SEC, mode=0o700, exist_ok=True)
    if cmd == "secret":
        f = os.path.join(SEC, "totp")
        if os.path.exists(f): sys.exit("already exists: keep it, or delete it on purpose first")
        fd = os.open(f, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.write(fd, base64.b32encode(secrets.token_bytes(20)).decode().rstrip("=").encode()); os.close(fd)
        print("made", f)
    elif cmd == "op":
        vault = sys.argv[2]
        subprocess.run(["op", "item", "create", "--category", "password", "--title", TITLE, "--vault", vault,
                        "one-time password[otp]=" + otpauth(read("totp")),
                        "notesPlain=Unlocks %s for 8 hours during bedtime. Each code works once; type it with the wheel on the bedtime screen." % DEVICE_NAME],
                       check=True, stdout=subprocess.DEVNULL)
        print("created", repr(TITLE), "in", vault)
    elif cmd == "check":
        mine = code(read("totp"))
        try: theirs = subprocess.run(["op", "item", "get", TITLE, "--otp"], capture_output=True, text=True, check=True).stdout.strip()
        except Exception as e: theirs = "(1Password: %s)" % e
        print("ours", mine, "| 1Password", theirs, "| match" if mine == theirs else "| MISMATCH")
    elif cmd == "register":
        import urllib.request
        out = os.path.join(SEC, "ha_registration.json")
        if os.path.exists(out) and "--force" not in sys.argv:
            sys.exit("already registered (re-registering replaces the webhook): delete it from HA first, or pass --force")
        body = {"device_id": DEVICE_ID, "app_id": APP, "app_name": DEVICE_NAME, "app_version": "1.0",
                "device_name": DEVICE_NAME, "manufacturer": "Innioasis", "model": "Y1", "os_name": "Android",
                "os_version": "4.2.2", "supports_encryption": True, "app_data": {}}   # payloads sealed: Nabu Casa sees ciphertext
        req = urllib.request.Request(LOCAL + "/api/mobile_app/registrations", data=json.dumps(body).encode(), method="POST",
                                     headers={"Authorization": "Bearer " + admin_token(), "Content-Type": "application/json"})
        reg = json.load(urllib.request.urlopen(req, timeout=30))
        fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.write(fd, json.dumps(reg).encode()); os.close(fd)
        print("registered; cloudhook:", "yes" if reg.get("cloudhook_url") else "none (Nabu Casa not connected?)")
    elif cmd == "child":
        import urllib.request
        admin = admin_token()
        login = re.sub(r"[^a-z0-9]", "", CHILD.lower()) or "child"
        pw_file = os.path.join(SEC, login + "_ha_password")
        if not os.path.exists(pw_file):
            fd = os.open(pw_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            os.write(fd, secrets.token_urlsafe(24).encode()); os.close(fd)
        pw = read(login + "_ha_password")
        users = ws([{"type": "config/auth/list"}], admin)[0]["result"]
        user = next((u for u in users if u["name"] == CHILD and not u.get("system_generated")), None)
        if not user:
            r = ws([{"type": "config/auth/create", "name": CHILD, "group_ids": ["system-read-only"], "local_only": False}], admin)[0]
            user = r["result"]["user"]
            r = ws([{"type": "config/auth_provider/homeassistant/create", "user_id": user["id"], "username": login, "password": pw}], admin)[0]
            if not r.get("success"): sys.exit("could not give %s a login: %s" % (CHILD, r))
            print("made the read-only user", CHILD)
        elif user.get("group_ids") != ["system-read-only"]:
            sys.exit("a user called %s exists but is not read-only (%s): not touching it" % (CHILD, user.get("group_ids")))
        # an access token for the child user through the normal login flow (it is only used to register the device)
        def post(path, data, form=False):
            body = urllib.parse.urlencode(data).encode() if form else json.dumps(data).encode()
            req = urllib.request.Request(LOCAL + path, data=body, method="POST",
                                         headers={"Content-Type": "application/x-www-form-urlencoded" if form else "application/json"})
            return json.load(urllib.request.urlopen(req, timeout=30))
        cid = LOCAL + "/"
        flow = post("/auth/login_flow", {"client_id": cid, "handler": ["homeassistant", None], "redirect_uri": cid})
        res = post("/auth/login_flow/" + flow["flow_id"], {"client_id": cid, "username": login, "password": pw})
        if res.get("type") != "create_entry": sys.exit("%s's login failed: %s" % (CHILD, res.get("errors")))
        tok = post("/auth/token", {"grant_type": "authorization_code", "code": res["result"], "client_id": cid}, form=True)["access_token"]
        os.environ["HA_TOKEN"] = tok
        sys.argv = [sys.argv[0], "register", "--force"]
        main()
    elif cmd == "identity":
        admin = admin_token()
        have = {h["id"]: h for h in ws([{"type": "input_text/list"}], admin)[0].get("result", [])}
        for k in IDENTITY_KEYS:
            eid = ENTITIES[k]
            obj = eid.split(".", 1)[1]
            if obj not in have:
                r = ws([{"type": "input_text/create", "name": obj.replace("_", " "), "max": 255, "icon": "mdi:account-music"}], admin)[0]
                made = r.get("result", {}).get("id")
                if made != obj:
                    print("  created %s as input_text.%s (not %s): put that id in the config's entities" % (k, made, eid))
                    eid = "input_text." + str(made)
            value = CFG.get("identity", {}).get(k)
            if value:
                ws([{"type": "call_service", "domain": "input_text", "service": "set_value",
                     "target": {"entity_id": eid}, "service_data": {"value": value}}], admin)
            print("  %-16s %s" % (k, eid))
        print("identity helpers ready: edit them in Home Assistant; the device reads them on every sync")
    elif cmd == "push":
        reg = json.load(open(os.path.join(SEC, "ha_registration.json")))
        cfg = {"local_url": LOCAL, "webhook_id": reg["webhook_id"], "cloudhook_url": reg.get("cloudhook_url"),
               "totp": read("totp"), "entities": ENTITIES, "events": CFG.get("events", {}),
               "sensor_prefix": CFG.get("sensor_prefix", "y1_"),
               "identity": CFG.get("identity", {})}    # first-boot values, until Home Assistant has been read
        if reg.get("secret"): cfg["secret"] = reg["secret"]
        tmp = "/data/local/tmp/ha.json"
        # adb push from a private temp file: piping into `adb shell cat` echoes the secret back on some adb/device pairs
        import tempfile
        fd, local = tempfile.mkstemp(prefix="ha", suffix=".json")
        try:
            os.write(fd, json.dumps(cfg).encode()); os.close(fd)
            subprocess.run([ADB] + DEV + ["push", local, tmp], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        finally:
            os.remove(local)
        # no stat/awk on Android 4.2: read the app's uid from dumpsys here, then copy and hand the file to the app
        dump = subprocess.run([ADB] + DEV + ["shell", "dumpsys package " + APP], capture_output=True, text=True, check=True).stdout
        uid = re.search(r"userId=(\d+)", dump).group(1)
        f = "/data/data/%s/files/ha.json" % APP
        sh = "cp %s %s && chown %s:%s %s && chmod 600 %s; rm -f %s; restorecon %s 2>/dev/null; true" % (tmp, f, uid, uid, f, f, tmp, f)
        subprocess.run([ADB] + DEV + ["shell", "su -c '%s' 2>/dev/null || (%s)" % (sh, sh)], check=True)
        print("pushed: the device picks it up within a minute")
    else:
        print(__doc__)


main()
