#!/bin/sh
# Put the Find My + Bluetooth shim (java/android/native/btshim.c) on the iPod as /system/lib/libbluetoothdrv.so, so the
# beacon keeps running while Bluetooth headphones are in use. Run with the iPod on USB (adb -d, root). Idempotent.
#   rom/bt/install_shim.sh            install
#   rom/bt/install_shim.sh remove     put the original back (the logging wrapper, rom/bt/orig/)
# The library is loaded for the life of mtkbt, and restarting mtkbt by hand leaves A2DP broken ("Transport endpoint is
# not connected") until a reboot (rom/bt/NOTES.md), so the file is swapped under the running mtkbt (mv: it keeps the old
# copy mapped) and the iPod reboots to load it.
set -e
cd "$(dirname "$0")"
ADB="adb -d"
if [ "$1" = remove ]; then SRC=orig/libbluetoothdrv_logging.so; else
  # build the shim when zig is here; a release bundle brings it already built
  if command -v zig >/dev/null 2>&1; then sh ../../java/android/native/build.sh; fi
  SRC=../../java/android/native/libbluetoothdrv.so; fi
$ADB push "$SRC" /data/local/tmp/libbluetoothdrv.new >/dev/null
$ADB shell "su -c '
  [ -f /data/local/tmp/drv-orig/libbluetoothdrv.so ] || { mkdir -p /data/local/tmp/drv-orig; cp /system/lib/libbluetoothdrv.so /data/local/tmp/drv-orig/; }
  mount -o remount,rw /system
  cp /data/local/tmp/libbluetoothdrv.new /system/lib/libbluetoothdrv.so.new
  chmod 644 /system/lib/libbluetoothdrv.so.new
  mv /system/lib/libbluetoothdrv.so.new /system/lib/libbluetoothdrv.so
  rm -f /data/local/tmp/libbluetoothdrv.new
  if [ \"$1\" = remove ]; then rm -f /system/etc/fmshim; else echo 1 > /system/etc/fmshim; chmod 644 /system/etc/fmshim; fi
  sync; ls -l /system/lib/libbluetoothdrv.so
'"
echo "rebooting the iPod to load it."
$ADB reboot
[ "$1" = remove ] && exit 0
echo "done. The app writes /data/local/tmp/fmbeacon.hex from the Find My key and pins the Bluetooth address once in"
echo "/data/local/tmp/fmbeacon.addr; with Bluetooth on the beacon stays up. Headphones paired under another address"
echo "(before the pin, or before the shim) need pairing again once."
