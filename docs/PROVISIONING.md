# Provisioning a Y1

`provision/y1.py` does it from a checkout (or from a release bundle with `--prebuilt`):

```
python3 provision/y1.py doctor      # what is installed, what is missing
python3 provision/y1.py fetch       # Solar ROM + flashing tool, from their own projects, checksummed
python3 provision/y1.py flash       # Y1 switched off, start this, plug in USB; asks you to type FLASH
python3 provision/y1.py provision   # booted Y1 on USB: asks for your Music Assistant details, installs everything
python3 provision/y1.py all
```

Add `--shim` for the Bluetooth shim (Find My beacon alongside headphones) and `--voice` for on-device speech to text.
Home Assistant (settings, grown-up code, gifting) is the last step: README, step 5.

`provision` can be run again on a Y1 that is already set up (it skips the Solar library rescue, whose source APK the
slimming removes). `all` waits for the first boot to finish before provisioning, and your answers are saved in
`provision/y1.json` (0600) before anything is changed. Windows is not supported (the helper scripts are POSIX shell;
WSL or Git Bash may work, untested); on Linux the flasher also needs libusb and a udev rule or sudo for the MediaTek USB ids.

## Security of adb

The Y1's adb is root. The boot script `rom/overlay/99PlayerInit.sh` restricts it to the computers whose keys are listed in
`/data/misc/adb/adb_keys` on the device, but only when that file exists and is not empty. Provisioning does not create it,
so a new unit leaves adb open to any computer that plugs in (which also means anyone could read the grown-up code secret
and Home Assistant webhook from it). To lock it, append your computer's `~/.android/adbkey.pub` to that file as root
(`adb push ~/.android/adbkey.pub /data/local/tmp/ && adb shell "su -c 'cat /data/local/tmp/adbkey.pub >> /data/misc/adb/adb_keys; chmod 640 /data/misc/adb/adb_keys'"`), keep a second key
there if you use two computers, and reboot. Anyone not listed gets "unauthorized". Losing the key means reflashing.

## What is not in this repository, and why

| What | Why it is not here | What happens instead |
|---|---|---|
| Solar ROM (`rom.zip`, 940 MB, thesolarproject/solar release 20260819-0956) | No licence on the project, very large, not ours to redistribute | `fetch` downloads it and checks the published sha256 (and `flash` checks it again and re-extracts the images every time) |
| Innioasis Updater's MediaTek flashing tool (mtkclient fork, GPLv3, with MediaTek's download agent) | Third party, large, needs its own Python environment | `fetch` clones it at a pinned commit into a private venv (`~/.cache/sendspin-y1`) with pinned package versions (not hash-locked) |
| Stock firmware backup of one unit (`y1-stock-backup`, 725 MB) | Innioasis's firmware, and it holds that unit's nvram (Bluetooth and Wi-Fi addresses, calibration) | Nothing: back up your own with the flashing tool before flashing (`mtk.py r ...`) |
| MediaTek Bluetooth and Wi-Fi libraries (`rom/bt`: `libbluetooth_mtk.so`, `mtkbt`, `libextavrcp*`...) | MediaTek's, and pulled from the device | Not needed: they are in Solar's ROM. The Bluetooth shim only wraps `/system/lib/libbluetoothdrv_real.so` on the device |
| Solar's boot scripts (`rom/solar-initd`) | Solar's | Left on the device; `rom/overlay/99PlayerInit.sh` replaces only what it must |
| Signing key, tokens, Home Assistant registration, private manifests | Secrets | `deploy/secrets/` (gitignored) and `provision/y1.json` (0600) |

The Wi-Fi and Bluetooth "drivers" are therefore not redistributed and not modified, apart from the shim we build ourselves
(`java/android/native/btshim.c`) and install in front of MediaTek's library.

## Other Y1 variants

The pinned ROM is the Type A Y1. Type B and A5 units need `rom_type_b.zip` / `rom_a5.zip` from the same Solar release
(different partition images): untested here.

## Release bundle (GitHub Actions)

`.github/workflows/build.yml` builds the app, the ARM helpers and the speech engine on every push, runs the unit tests,
and `provision/package.sh` zips them with the scripts (`y1-provision-*.zip`, a workflow artifact, and a release asset on
`v*` tags, with `SHA256SUMS` and a build provenance attestation). CI signs the app with a key it generates per run, so
every release has a different signing key: a Y1 that has another build on it needs the old app uninstalled first (its
settings and pairing go with it); build from source with your own key to update in place. Workflows run read-only; only
the release job can write, and every action is pinned to a commit.

## Browser flashing

Not built yet. Two separate problems:

1. **Provisioning a booted Y1 from a browser** (install the app, fill in the config, run the overlay scripts) is feasible
   with WebUSB ADB (Tango / `@yume-chan/adb`). It needs the bundle's files served from the same site (GitHub Releases
   send no CORS headers), and the Y1's adbd to accept the browser's key.
2. **Flashing Solar from a browser** means re-implementing the MediaTek BROM/preloader and download-agent protocol
   (what `mtk.py w` does) over WebUSB for the MT6572, then streaming the 940 MB ROM, which is not ours to host, from a
   file the user picks. Possible, large, and it needs a spare Y1 to develop against: a bad write can brick a unit.
