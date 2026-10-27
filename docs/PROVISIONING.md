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

## What is not in this repository, and why

| What | Why it is not here | What happens instead |
|---|---|---|
| Solar ROM (`rom.zip`, 940 MB, thesolarproject/solar release 20260819-0956) | No licence on the project, very large, not ours to redistribute | `fetch` downloads it and checks the published sha256 |
| Innioasis Updater's MediaTek flashing tool (mtkclient fork, GPLv3, with MediaTek's download agent) | Third party, large, needs its own Python environment | `fetch` clones it at a pinned commit into a private venv (`~/.cache/sendspin-y1`) |
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
`v*` tags). CI signs the app with a key it generates per run, so a Y1 that has another build on it needs the old app
uninstalled first; build from source with your own key to update in place.

## Browser flashing

Not built yet. Two separate problems:

1. **Provisioning a booted Y1 from a browser** (install the app, fill in the config, run the overlay scripts) is feasible
   with WebUSB ADB (Tango / `@yume-chan/adb`). It needs the bundle's files served from the same site (GitHub Releases
   send no CORS headers), and the Y1's adbd to accept the browser's key.
2. **Flashing Solar from a browser** means re-implementing the MediaTek BROM/preloader and download-agent protocol
   (what `mtk.py w` does) over WebUSB for the MT6572, then streaming the 940 MB ROM, which is not ours to host, from a
   file the user picks. Possible, large, and it needs a spare Y1 to develop against: a bad write can brick a unit.
