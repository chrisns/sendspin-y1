#!/usr/bin/env python3
"""Set a player up with Home Assistant through the Y1 Player integration, and its grown-up unlock code. Driven by a
private config file (see device.example.json):

  DEVICE_CONFIG=deploy/secrets/bedtime/device.json   (the default)

  y1_setup.py secret     make the 5-digit TOTP secret once (secrets/bedtime/totp, chmod 600)
  y1_setup.py op VAULT   create the 1Password item showing the rolling grown-up code
  y1_setup.py check      print the current code from our secret and from 1Password: they must match
  y1_setup.py pair       read the player's public key over adb, add it to the Y1 Player integration (config "name"),
                         link its bedtime controls to existing helpers (config "links", optional), set its starting
                         settings (config "settings", optional), then push files/ha.json to the player
  y1_setup.py push       push files/ha.json again (webhook, the integration's public key, unlock secret)

The player holds no Home Assistant token; the admin token here is only used on this computer, to pair it.
Nothing secret is printed except by `check`, which shows only the current 5-digit code.
"""
import base64, hashlib, hmac, json, os, re, secrets, struct, subprocess, sys, time, urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
SEC = os.path.join(HERE, "secrets", "bedtime")
CFG = json.load(open(os.environ.get("DEVICE_CONFIG", os.path.join(SEC, "device.json"))))
LOCAL = CFG["ha_url"].rstrip("/")      # tried first; the integration's Nabu Casa cloudhook is the fallback
DEVICE_NAME = CFG.get("name") or "Y1"
TITLE = CFG.get("otp_title", DEVICE_NAME + " grown-up code")
APP = "me.cns.sendspin.android"
ADB = os.environ.get("ADB") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
DEV = ["-e"] if os.environ.get("EMULATOR") else ["-d"]   # EMULATOR=1 to try it on the emulator
LINKS = ("bedtime_start", "bedtime_end", "bedtime_cap", "bedtime_used")


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


def http(method, path, token, body=None):
    import urllib.request
    req = urllib.request.Request(LOCAL + path, data=json.dumps(body).encode() if body is not None else None, method=method,
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=30))


def entry_for(pub, token):
    """The integration's config entry for this player key (unique id), or None."""
    try:
        info = http("POST", "/api/services/y1_player/pairing_info?return_response", token, {})
    except Exception:   # the integration is not set up yet (no entries)
        return None
    return (info.get("service_response") or info).get("devices", {}).get(pub)


def adb(*args, **kw):
    return subprocess.run([ADB] + DEV + list(args), check=True, capture_output=True, text=True, **kw).stdout


def adb_su(sh):
    return adb("shell", "su -c '%s' 2>/dev/null || (%s)" % (sh, sh))


def push(pub, token):
    """files/ha.json for the player: where the integration is, its public key, and the unlock secret."""
    info = http("POST", "/api/services/y1_player/pairing_info?return_response", token, {})
    dev = (info.get("service_response") or info).get("devices", {}).get(pub)
    if not dev: sys.exit("this player is not paired: run pair")
    cfg = {"local_url": LOCAL, "webhook_id": dev["webhook_id"], "cloudhook_url": dev.get("cloudhook_url"),
           "server_public_key": dev["server_public_key"], "totp": read("totp")}
    tmp = "/data/local/tmp/ha.json"
    # adb push from a private temp file: piping into `adb shell cat` echoes the secret back on some adb/device pairs
    import tempfile
    fd, local = tempfile.mkstemp(prefix="ha", suffix=".json")
    try:
        os.write(fd, json.dumps(cfg).encode()); os.close(fd)
        adb("push", local, tmp)
    finally:
        os.remove(local)
    # no stat/awk on Android 4.2: read the app's uid from dumpsys here, then copy and hand the file to the app
    uid = re.search(r"userId=(\d+)", adb("shell", "dumpsys package " + APP)).group(1)
    f = "/data/data/%s/files/ha.json" % APP
    adb_su("cp %s %s && chown %s:%s %s && chmod 600 %s; rm -f %s; restorecon %s 2>/dev/null; true" % (tmp, f, uid, uid, f, f, tmp, f))
    print("pushed: the player syncs within a minute", "(cloudhook too)" if dev.get("cloudhook_url") else "(home network only)")


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
    elif cmd == "pair":
        admin = admin_token()
        pub = adb_su("cat /data/data/%s/files/device.pub" % APP).strip()
        if len(base64.b64decode(pub)) != 32: sys.exit("no player key: is the app installed and started once?")
        entry = entry_for(pub, admin)
        if entry:
            print("already paired as", repr(entry["title"]))
        else:
            r = http("POST", "/api/config/config_entries/flow", admin, {"handler": "y1_player"})
            r = http("POST", "/api/config/config_entries/flow/" + r["flow_id"], admin, {"name": DEVICE_NAME, "device_public_key": pub})
            if r.get("type") != "create_entry": sys.exit("Home Assistant refused it: %s" % (r.get("errors") or r.get("reason") or r))
            entry = entry_for(pub, admin)
            print("paired as", repr(DEVICE_NAME))
        opts = {"link_" + k: v for k, v in CFG.get("links", {}).items() if k in LINKS and v}
        if CFG.get("notify"): opts["notify_service"] = CFG["notify"]
        if opts:
            r = http("POST", "/api/config/config_entries/options/flow", admin, {"handler": entry["entry_id"]})
            http("POST", "/api/config/config_entries/options/flow/" + r["flow_id"], admin, opts)
            print("linked:", ", ".join(sorted(opts)))
        if CFG.get("settings"):
            time.sleep(2)   # the entry reloads after its options change
            ents = ws([{"type": "config/entity_registry/list"}], admin)[0]["result"]
            mine = {e["unique_id"][len(entry["entry_id"]) + 1:]: e["entity_id"] for e in ents if e.get("config_entry_id") == entry["entry_id"]}
            for k, v in CFG["settings"].items():
                eid = mine.get(k)
                if not eid: print("  no setting", k); continue
                dom = eid.split(".")[0]
                call = {"text": ("set_value", "value"), "number": ("set_value", "value"), "select": ("select_option", "option")}.get(dom)
                if dom == "switch": call = ("turn_on" if v else "turn_off", None)
                data = {call[1]: v} if call[1] else {}
                ws([{"type": "call_service", "domain": dom, "service": call[0], "target": {"entity_id": eid}, "service_data": data}], admin)
                print("  %-18s %s" % (k, eid))
        push(pub, admin)
    elif cmd == "push":
        admin = admin_token()
        push(adb_su("cat /data/data/%s/files/device.pub" % APP).strip(), admin)
    else:
        print(__doc__)


main()
