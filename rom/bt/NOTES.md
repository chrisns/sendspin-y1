# Bluetooth beacon vs Wi-Fi on the Y1 (MT6572), 26 Oct 2026

## Fix (in the app since 26 Oct)

Never send LE Set Advertising Parameters. Sequence: adv off, FC1A (public BD address = key's first 6 bytes, top two
bits set, LSB first), HCI reset, LE Set Advertising Data, adv on (and off/on again 2 s later). The controller runs on the
spec defaults (ADV_IND, 1.28 s, channels 37-39, public address). Every command is answered; a 10-minute soak with the
beacon on had 0% Wi-Fi loss and no chip resets, and Wi-Fi does not blip when the helper starts or stops (250/250 pings).
ADV_IND is connectable, so FindMyBeacon hangs up on any LE Connection Complete it sees in the event log.
Works on the stock 2013 patch; the newer patches below are not needed (kept in candidates/, not installed).

## Beacon alongside Bluetooth headphones (shim)

Android's Bluetooth (mtkbt) needs /dev/stpbt to itself, so the raw-HCI helper steps aside whenever it is on. To keep the
beacon up during headphone use, `java/android/native/btshim.c` replaces `/system/lib/libbluetoothdrv.so`, the wrapper mtkbt
loads (the old one was a hex logger around libbluetoothdrv_real.so; kept in rom/bt/orig/). It passes everything through, and:
- reassembles HCI packets in `mtk_bt_read` so it can remove the Command Complete events of its own two commands (LE Set
  Advertising Data, LE Set Advertise Enable) and any LE Connection Complete (it hangs up with Disconnect; hidden too);
  mtkbt still reads the same byte stream. Android's device search takes 62 s with or without the shim.
- injects them when the host has been quiet 2 s and again after every host HCI reset; re-enables every 5 min.
- sets the Bluetooth address itself: `mtk_bt_enable` returns the fd, then it sends FC1A (address) + reset and reads the
  replies synchronously. The address cannot come from /data/nvram/APCFG/APRDEB/BT_Addr: edit it and MediaTek's NVRAM
  layer returns all zeros ("Nvram data all zero", Bluetooth then fails to start), so keep that file as it was.
- config: /data/local/tmp/fmbeacon.hex = 64 hex chars of advertising data + 12 hex chars of address (MSB first), written
  by the app (`FindMyBeacon.writeShimConfig`), world-readable because mtkbt runs as `bluetooth`. No file, no beacon.
Install/remove: `rom/bt/install_shim.sh [remove]` (also touches /system/etc/fmshim, which the app checks).
Verified 26 Oct: Android reports the key's address, ESP32 proxies and the Mac see the key's payload from it, Wi-Fi 0% loss
with Bluetooth on, the app's Bluetooth switch hands over cleanly both ways (raw-HCI beacon with Bluetooth off, shim with it on).
Not verified: headphone audio with the beacon on, and Apple finder devices reporting the key (public address type, since
0x2006 cannot set a random one).
Headphones (WH-1000XM3) verified 26 Oct: pair, A2DP connect, audio streaming with the beacon on air and Wi-Fi at 0% loss.
Why connecting failed from the UI: on this build the system's BluetoothBondState/BluetoothA2dpService broadcast as user -1
without clearing the caller's identity, so any call from our (non-system) app throws SecurityException (INTERACT_ACROSS_USERS)
midway: createBond still pairs, but A2DP `connect` aborts before connecting. `Sys.connect` now ignores the createBond error,
waits for the bond, and connects as root (`service call bluetooth_a2dp 1 i32 1 s16 ADDR`; 2 = disconnect). Note that
connect on an already-connected sink disconnects it (headphones usually auto-reconnect after a reboot). After restarting
mtkbt by hand, A2DP fails with "Transport endpoint is not connected" until the iPod is rebooted.
Shim self-healing (26 Oct): its count of host commands awaiting a reply drifts up in real use (4 stale after a session with
headphones), which silently stopped injection; unanswered for 4 s now counts as dead, advertising is re-enabled every minute, and
it logs `state:` every 2 min (`logcat -s FMSHIM:I`). Verified 2.5 min of beacon + connected headphones + playback, Wi-Fi 0% loss.
Gotchas: the shim is freestanding (own hex/IO, no libc headers) and linked against empty stand-in libc/libdl/liblog so that
bionic's loader records them as needed (otherwise `cannot locate symbol pthread_create`); `service call bluetooth 3` while
fmhci holds the radio fails with "Can't open serial port" and can leave Wi-Fi wedged until a reboot.

## Firmware hunt

All MT6572/MT6582 consys patches found on GitHub target hw 0x8a00 like ours: 2014-01 (Lenovo A7-30HC), 2014-04
(Starmobile), 2014-06 (Huawei G750), 2014-08 (Android One sprout), 2014-09 (bq Krillin, Wiko), 2015-03 (GM eTab5 KK),
2015-04/06 (ROMv1_ naming, kernel 3.10). The 2015-03 pair (e1_0 + e1_1) loads on the Y1's kernel and Bluetooth works,
but it hangs on 0x2006 exactly like the 2013 one. Other LE commands (0x2002/3/7/B/C) answer on both.
Not the cause either: chip sleep (`echo 0 0 > /proc/driver/wmt_dbg`), quick sleep (`1 0`), Wi-Fi power save
(`native/wpactl SET ps 0`), the advertising interval, type or address type, and the full stock setup replayed first.

## What actually happens

The "beacon starves Wi-Fi" problem is a radio firmware crash, not airtime.

- Raw HCI through /dev/stpbt (native/fmhci.c). Every step up to LE Set Random Address (0x2005) is answered and Wi-Fi is fine.
- LE Set Advertising Parameters (0x2006) is never answered. The shared connectivity MCU (consys: Wi-Fi, BT, GPS, FM) hangs, the
  Wi-Fi driver logs `Driver own fail`, STP-BTM force-asserts the firmware and WMT does a whole-chip reset. Wi-Fi drops and
  sometimes does not come back (`Firmware scatter download failed`, needs a reboot). Kernel logs: dmesg_crash.txt, dmesg_crash2.txt.
- The interval does not matter: 100 ms, 1.28 s and 2 s all crash. The stock radio setup does not help either (below).
- With Wi-Fi off, the beacon can still go on air (seen at -49 dBm), but 0x2006 is still unanswered and stopping the helper later
  triggered the same reset.
- Consys firmware patch: /system/etc/firmware/mt6572_82_patch_e1_0_hdr.bin, built 20130523, ROM E2, hw 0x8a01.

## Stock stack

- /system/lib/libbluetoothdrv.so is an HCI logging shim (added Aug 2026) around libbluetoothdrv_real.so: logcat tags BTCTRL/BTREAD.
  It misses writes made inside libbluetooth_mtk.so and some mtkbt paths. Captures: stock_enable*.log, stock_discoverable.log.
- libbluetooth_mtk.so `bt_init_script_6572` = FC1A (BD addr, LSB first), FC79 radio (NVRAM 0x0c-0x11), FC93 TX power offset
  (0x1b-0x1d), FC7A sleep (0x12-0x18), then HCI reset. From BT_Addr: FC79 07 80 00 06 05 07, FC93 ff ff ff,
  FC7A 03 40 1f 40 1f 00 04. Replaying these before 0x2006 does not prevent the crash.
- mtkbt has LE advertising code (BtBmLeAdvEnabled, ME_LE_SetAccessible) but this build never advertises (`LESupport is 0`):
  going discoverable sends no 0x20xx commands.
- Disable/enable BT from a shell: `service call bluetooth 5 i32 1` / `service call bluetooth 3`; scan mode `service call bluetooth 11 i32 23 i32 120`.

## Why a newer Android is not the fix

Android 5's BLE advertising API ends in the same HCI commands (0x2006/0x2008/0x200a) to the same consys firmware. There is no
official Lollipop for MT6572; community 5.1 ports (kernel 3.4.5) exist for other phones. A port only helps if it carries a newer
consys patch, and that blob can be tried on 4.2 directly.

## Test harness

/data/local/tmp/coex2.sh (one HCI step at a time with pings), coex4.sh, coex5.sh (adv before/after Wi-Fi). Toolbox `sleep`
takes whole seconds only: use `busybox usleep`. Park the app's keys first (findmy.bin -> findmy.bin.off) or its beacon joins in
when the test knocks Wi-Fi out.
