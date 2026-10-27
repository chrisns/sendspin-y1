#!/usr/bin/env python3
"""Turn an Innioasis Y1 into a sendspin-y1 player: fetch what we may not redistribute, flash, build, provision.

  provision/y1.py doctor       what is installed, what is missing (nothing is changed)
  provision/y1.py fetch        download the Solar ROM (checked against its published sha256) and the flashing tool
  provision/y1.py flash        write Solar to a Y1 in flash mode (asks first; never touches preloader, nvram or pro_info)
  provision/y1.py build        build the app and the native helpers (needs the Android SDK, JDK 17 and zig)
  provision/y1.py provision    install and set up the player on a Y1 that is booted with Solar and visible to adb
  provision/y1.py all          fetch, flash, build, provision

Options for provision: --config FILE (default provision/y1.json, else asked), --shim (Bluetooth shim for the Find My
beacon), --voice (on-device speech to text), --prebuilt DIR (use the apk and helpers from a release bundle instead of building).

What is NOT in this repository, and why: Solar (the Y1 ROM we build on) and Innioasis Updater's MediaTek flashing tool
carry no licence that lets us redistribute them, and they are large; this script downloads them from their own projects
at pinned versions. MediaTek's Bluetooth and Wi-Fi libraries are not needed: they are in Solar's ROM and the player only
wraps them on the device.
"""
import argparse, getpass, hashlib, json, os, platform, shutil, subprocess, sys, time, urllib.error, urllib.request, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.environ.get("Y1_CACHE") or os.path.join(os.path.expanduser("~"), ".cache", "sendspin-y1")
SOLAR_TAG = "20260819-0956"
SOLAR_URL = "https://github.com/thesolarproject/solar/releases/download/%s/rom.zip" % SOLAR_TAG
SOLAR_SHA256 = "630c158b10ec1da54b3c22f9aca49f3623586ff16166123018fe544338ddd798"   # the release's published digest
UPDATER_GIT = "https://github.com/y1-community/Innioasis-Updater.git"
UPDATER_COMMIT = "c25838c06bba45672a9ceeab4e7b491ef8a3ff24"      # the version this was proven with
# the versions this was proven with (pip freeze of the working venv); their own dependencies are not locked
PIP = ["wheel==0.48.0", "setuptools==84.2.1", "pyusb==1.3.1", "pycryptodome==4.2.0", "pycryptodomex==3.24.1", "colorama==0.4.6",
       "pyserial==3.5", "keystone-engine==0.9.2", "capstone==5.0.9", "unicorn==2.1.4", "requests==2.34.2", "numpy==2.5.4",
       "pillow==13.0.0", "mock==5.2.0"]
# what gets written, as partition=file inside rom.zip: never preloader, nvram, pro_info (they hold this unit's identity)
FLASH = [("logo", "logo.bin"), ("uboot", "lk.bin"), ("bootimg", "boot.img"), ("recovery", "recovery.img"),
         ("android", "system.img"), ("usrdata", "userdata.img")]
APP = "me.cns.sendspin.android"


def say(m): print(m, flush=True)
def die(m): sys.exit("error: " + m)


def run(cmd, **kw):
    kw.setdefault("check", True)
    return subprocess.run(cmd, **kw)


def sdk_dir():
    for c in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), os.path.expanduser("~/Library/Android/sdk"),
              os.path.expanduser("~/Android/Sdk"), os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")):
        if c and os.path.isdir(c): return c
    return None


def adb_path():
    sdk = sdk_dir()
    for c in (shutil.which("adb"), sdk and os.path.join(sdk, "platform-tools", "adb")):
        if c and os.path.exists(c): return c
    return None


def setup_env():
    """The scripts we call (install_zoneinfo.sh, install_shim.sh, stt/build.sh, build_apk.sh) find adb and the SDK through
    PATH, ADB and ANDROID_HOME: hand them what was found here, so they work with an SDK that is not on PATH."""
    a, sdk = adb_path(), sdk_dir()
    if a:
        os.environ["PATH"] = os.path.dirname(a) + os.pathsep + os.environ.get("PATH", "")
        os.environ["ADB"] = a
    if sdk: os.environ["ANDROID_HOME"] = os.environ["ANDROID_SDK_ROOT"] = sdk


# ------------------------------------------------------------------ doctor

def doctor(_a=None):
    ok = True
    def row(name, good, how):
        nonlocal ok
        say("  %-26s %s" % (name, "ok" if good else "MISSING: " + how)); ok = ok and bool(good)
    say("Tools:")
    row("python 3.9+", sys.version_info >= (3, 9), "install Python 3.9 or newer")
    row("adb", adb_path(), "install Android platform-tools")
    row("git", shutil.which("git"), "install git (to fetch the flashing tool)")
    sdk = sdk_dir()
    row("Android SDK android-17", sdk and os.path.exists(os.path.join(sdk, "platforms", "android-17", "android.jar")),
        "sdkmanager 'platforms;android-17' (only to build; --prebuilt skips this)")
    row("build-tools 34.0.0", sdk and os.path.isdir(os.path.join(sdk, "build-tools", "34.0.0")), "sdkmanager 'build-tools;34.0.0'")
    row("JDK 17", shutil.which("javac") and shutil.which("keytool"), "install a JDK, version 17")
    row("zig", shutil.which("zig"), "install zig (builds the ARM helpers; --prebuilt skips this)")
    row("sh, zip, curl, cmake", all(shutil.which(t) for t in ("sh", "zip", "curl", "cmake")),
        "a POSIX shell, zip, curl and cmake (the scripts are shell; Windows needs WSL or Git Bash, and is not tested)")
    if platform.system() == "Darwin":
        row("libusb", os.path.exists("/opt/homebrew/lib/libusb-1.0.dylib") or os.path.exists("/usr/local/lib/libusb-1.0.dylib"), "brew install libusb (flashing)")
    elif platform.system() == "Linux":
        import ctypes.util
        row("libusb", ctypes.util.find_library("usb-1.0"), "install libusb-1.0 (flashing; you also need a udev rule or sudo for the MediaTek USB ids 0e8d:*)")
    say("Downloads (cache %s):" % CACHE)
    say("  Solar rom.zip              %s" % ("present" if os.path.exists(os.path.join(CACHE, "rom.zip")) else "not fetched yet"))
    say("  flashing tool              %s" % ("present" if os.path.isdir(os.path.join(CACHE, "Innioasis-Updater")) else "not fetched yet"))
    a = adb_path()
    if a:
        out = subprocess.run([a, "devices"], capture_output=True, text=True).stdout
        devs = [l for l in out.splitlines()[1:] if l.strip()]
        say("Devices seen by adb:" + ("".join("\n  " + l for l in devs) if devs else " none"))
    return ok


# ------------------------------------------------------------------ fetch

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()


def download(url, dest, want):
    if os.path.exists(dest) and sha256(dest) == want:
        say("  %s already here and correct" % os.path.basename(dest)); return
    part = dest + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    req = urllib.request.Request(url, headers={"Range": "bytes=%d-" % have} if have else {})
    try: r = urllib.request.urlopen(req)
    except urllib.error.HTTPError as e:
        if e.code == 416 and have:       # the .part is already full size (or longer): check it, and start again if it is wrong
            if sha256(part) == want: os.replace(part, dest); return
            os.remove(part); return download(url, dest, want)
        raise
    with r, open(part, "ab" if have and r.status == 206 else "wb") as f:
        total = int(r.headers.get("Content-Length", 0)) + (have if r.status == 206 else 0)
        done = have if r.status == 206 else 0
        while True:
            b = r.read(1 << 20)
            if not b: break
            f.write(b); done += len(b)
            print("\r  %s %d / %d MB" % (os.path.basename(dest), done >> 20, total >> 20), end="", flush=True)
    print()
    if sha256(part) != want:
        os.remove(part)      # never resume from bad data
        die("%s does not match its published sha256: deleted it, run fetch again" % os.path.basename(dest))
    os.replace(part, dest)


def venv_python():
    d = os.path.join(CACHE, "venv")
    return os.path.join(d, "Scripts" if os.name == "nt" else "bin", "python")


def fetch(_a=None):
    os.makedirs(CACHE, exist_ok=True)
    say("Solar %s (thesolarproject/solar), about 940 MB:" % SOLAR_TAG)
    download(SOLAR_URL, os.path.join(CACHE, "rom.zip"), SOLAR_SHA256)
    tool = os.path.join(CACHE, "Innioasis-Updater")
    if not os.path.isdir(tool):
        say("Flashing tool (y1-community/Innioasis-Updater, MediaTek client by B. Kerler, GPLv3) at %s:" % UPDATER_COMMIT[:10])
        run(["git", "clone", "--quiet", UPDATER_GIT, tool])
    run(["git", "-C", tool, "checkout", "--quiet", UPDATER_COMMIT])
    if subprocess.run(["git", "-C", tool, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip() != UPDATER_COMMIT:
        die("the flashing tool is not at the pinned commit %s" % UPDATER_COMMIT[:10])
    venv, ok = os.path.join(CACHE, "venv"), os.path.join(CACHE, "venv", ".installed")
    if not os.path.exists(ok):       # a venv without the marker is a failed install: start again
        say("Python packages for it:")
        shutil.rmtree(venv, ignore_errors=True)
        try:
            run([sys.executable, "-m", "venv", venv])
            run([venv_python(), "-m", "pip", "install", "--quiet"] + PIP)
        except subprocess.CalledProcessError:
            shutil.rmtree(venv, ignore_errors=True); die("pip failed: nothing was kept, run fetch again")
        open(ok, "w").close()
    say("fetched.")


# ------------------------------------------------------------------ flash

def flash(a=None):
    rom, tool = os.path.join(CACHE, "rom.zip"), os.path.join(CACHE, "Innioasis-Updater")
    if not (os.path.exists(rom) and os.path.isdir(tool) and os.path.exists(venv_python())): die("run fetch first")
    if sha256(rom) != SOLAR_SHA256: die("rom.zip does not match the pinned sha256: delete it and run fetch")
    say("""This writes Solar over the Y1's system, boot, recovery, bootloader, logo and user data: everything on it is lost.
It does not touch the preloader, nvram or pro_info, which hold this unit's own identity, so a stock image can be put back.
This ROM is for the Type A Y1 (Type B and A5 units need other Solar ROMs). The Y1 must be switched OFF: start this, then
plug in the USB cable. Do not unplug it until it says it has finished.""")
    if not (a and getattr(a, "yes", False)) and input("Type FLASH to go ahead: ").strip() != "FLASH": die("not flashing")
    # always extract afresh into a folder named for this ROM's hash, and check every size: an image left half-written by an
    # interrupted or full-disk extraction must never be flashed
    img = os.path.join(CACHE, "rom-" + SOLAR_SHA256[:12])
    for d in (img, img + ".tmp"): shutil.rmtree(d, ignore_errors=True)
    with zipfile.ZipFile(rom) as z:
        for _p, f in FLASH:
            z.extract(f, img + ".tmp")
            if os.path.getsize(os.path.join(img + ".tmp", f)) != z.getinfo(f).file_size: die("%s did not extract completely (disk full?)" % f)
    os.replace(img + ".tmp", img)
    env = dict(os.environ)
    if platform.system() == "Darwin": env["DYLD_FALLBACK_LIBRARY_PATH"] = "/opt/homebrew/lib:/usr/local/lib"
    cmd = [venv_python(), "-u", "mtk.py", "w", ",".join(p for p, _f in FLASH), ",".join(os.path.join(img, f) for _p, f in FLASH)]
    say("Waiting for the Y1: plug it in now (switched off).")
    run(cmd, cwd=tool, env=env)
    say("flashed. Let it boot (the first start takes a few minutes), then provision.")


# ------------------------------------------------------------------ build

def build(_a=None):
    java = os.path.join(ROOT, "java")
    say("native helpers (zig):")
    run(["sh", os.path.join(java, "android", "native", "build.sh")])
    say("app:")
    run(["sh", os.path.join(java, "build_apk.sh")])
    return os.path.join(java, "build", "sendspin-y1.apk")


# ------------------------------------------------------------------ provision

def ask(cfg, key, prompt, secret=False, default=None):
    if cfg.get(key): return cfg[key]
    d = " [%s]" % default if default else ""
    v = (getpass.getpass if secret else input)("%s%s: " % (prompt, d)).strip() or default
    if not v: die("%s is needed" % prompt)
    cfg[key] = v
    return v


def provision(a):
    adb = adb_path() or die("adb not found")
    cfgf = a.config or os.path.join(ROOT, "provision", "y1.json")
    cfg = json.load(open(cfgf)) if os.path.exists(cfgf) else {}
    say("Music Assistant (the server that streams to the player):")
    ask(cfg, "ma_host", "Music Assistant address, e.g. 192.168.1.20")
    ask(cfg, "ma_token", "the child's Music Assistant token", secret=True)
    ask(cfg, "gw_prefix", "file server path prefix (the secret folder name music-sync serves)", secret=True)
    cfg.setdefault("ma_port", 8095); cfg.setdefault("gw_port", 18099)
    def sh(*c, **kw): return run([adb, "-d"] + list(c), **kw)
    # before anything is changed: the tools the later steps use, and the answers (kept, so a failure does not lose them)
    missing = [t for t in ("sh",) + (("zip", "cmake", "curl") if a.voice and not a.prebuilt else ()) if not shutil.which(t)]
    if missing: die("needed for the later steps and not found: " + ", ".join(missing) + " (run doctor)")
    fd = os.open(cfgf, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f: json.dump(cfg, f, indent=1)
    sh("wait-for-device")
    say("waiting for the Y1 to finish booting (the first start after flashing takes a few minutes)...")
    t0 = time.time()
    while True:
        boot = subprocess.run([adb, "-d", "shell", "getprop sys.boot_completed"], capture_output=True, text=True).stdout.strip()
        root = "uid=0" in subprocess.run([adb, "-d", "shell", "su -c id"], capture_output=True, text=True).stdout if boot == "1" else False
        if root: break
        if time.time() - t0 > 600: die("no root over adb after 10 minutes: is this a Solar-flashed Y1 that has finished its first boot?")
        time.sleep(5)
    apk = a.prebuilt and os.path.join(a.prebuilt, "sendspin-y1.apk") or build()
    ov = os.path.join(ROOT, "rom", "overlay")
    say("Solar's missing native libraries (its own first-boot crash, upstream issue 83):")
    tmp = os.path.join(CACHE, "solarlibs"); os.makedirs(tmp, exist_ok=True)
    have = subprocess.run([adb, "-d", "shell", "ls /system/lib/libwolfssl.so /system/lib/libc++_shared.so"], capture_output=True, text=True).stdout
    if "No such file" not in have and "libwolfssl.so" in have and "libc++_shared.so" in have:
        say("  already there (this Y1 was provisioned before)")      # slim.sh removes the APK they are copied from
    else:
        if sh("pull", "/system/app/com.solar.launcher.apk", os.path.join(tmp, "s.apk"), stdout=subprocess.DEVNULL, check=False).returncode:
            die("Solar's launcher is gone and the libraries are not in /system/lib: reflash Solar, then provision")
        with zipfile.ZipFile(os.path.join(tmp, "s.apk")) as z:
            for f in ("libwolfssl.so", "libc++_shared.so"):
                open(os.path.join(tmp, f), "wb").write(z.read("lib/armeabi-v7a/" + f))
                sh("push", os.path.join(tmp, f), "/data/local/tmp/", stdout=subprocess.DEVNULL)
        sh("shell", "su -c 'mount -o remount,rw /system; cp /data/local/tmp/libwolfssl.so /data/local/tmp/libc++_shared.so /system/lib/; "
                    "chmod 644 /system/lib/libwolfssl.so /system/lib/libc++_shared.so'")
    say("the player:")
    sh("install", "-r", apk)
    sh("shell", "am force-stop " + APP)
    sh("shell", "am start -n %s/.MainActivity --es host %s --ei port %s --es token %s --es gw_prefix %s --ei gw_port %s" % (
        APP, cfg["ma_host"], cfg["ma_port"], cfg["ma_token"], cfg["gw_prefix"], cfg["gw_port"]), stdout=subprocess.DEVNULL)
    say("making it the only app (Solar's launcher and helpers are removed; reflash Solar to undo):")
    for f in ("99PlayerInit.sh", "slim.sh"): sh("push", os.path.join(ov, f), "/data/local/tmp/", stdout=subprocess.DEVNULL)
    sh("shell", "su -c 'sh /data/local/tmp/slim.sh; pm enable %s'" % APP, stdout=subprocess.DEVNULL)
    say("time zone rules:")
    run(["sh", os.path.join(ROOT, "rom", "tzdata", "install_zoneinfo.sh")])
    if a.voice:
        say("on-device speech to text:")
        run(["sh", os.path.join(ROOT, "java", "android", "native", "stt", "build.sh"), "install"])
    if a.shim:
        say("Bluetooth shim for the Find My beacon (reboots the Y1 to load it):")
        run(["sh", os.path.join(ROOT, "rom", "bt", "install_shim.sh")])
    else:
        sh("reboot")
    say("Done: the Y1 reboots into the player; join Wi-Fi in Settings and the music and audiobooks sync by themselves.\n"
        "Home Assistant (settings and the grown-up code): see the README, step 5 (custom_components/y1_player, deploy/y1_setup.py).")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("cmd", choices=["doctor", "fetch", "flash", "build", "provision", "all"])
    p.add_argument("--config"); p.add_argument("--prebuilt"); p.add_argument("--shim", action="store_true")
    p.add_argument("--voice", action="store_true"); p.add_argument("--yes", action="store_true", help="skip the flash confirmation")
    a = p.parse_args()
    setup_env()
    if a.cmd == "all":
        fetch(); flash(a); provision(a)
    else:
        {"doctor": doctor, "fetch": fetch, "flash": flash, "build": lambda x: build(), "provision": provision}[a.cmd](a)


if __name__ == "__main__":
    main()
