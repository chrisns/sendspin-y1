#!/bin/sh
# Put the Find My + Bluetooth shim (java/android/native/btshim.c) on the iPod as /system/lib/libbluetoothdrv.so, so the
# beacon keeps running while Bluetooth headphones are in use. Run with the iPod on USB (adb -d, root). Idempotent.
#   rom/bt/install_shim.sh            install
#   rom/bt/install_shim.sh remove     put the original back: rom/bt/orig/ (not in git or the bundle), else the copy this script
#                                     made on the first install, kept on the computer in $Y1_CACHE/drv-orig/
# The library is loaded for the life of mtkbt, and restarting mtkbt by hand leaves A2DP broken ("Transport endpoint is
# not connected") until a reboot (rom/bt/NOTES.md), so the file is swapped under the running mtkbt (mv: it keeps the old
# copy mapped) and the iPod reboots to load it.
set -e
cd "$(dirname "$0")"
ADB="${ADB:-adb} -d"
SAVE="${Y1_CACHE:-$HOME/.cache/sendspin-y1}/drv-orig"
if [ "$1" = remove ]; then
  SRC=orig/libbluetoothdrv_logging.so
  [ -f "$SRC" ] || SRC="$SAVE/libbluetoothdrv.so"
  [ -f "$SRC" ] || { echo "no original to put back (neither rom/bt/orig/ nor $SAVE): reflash Solar instead" >&2; exit 1; }
else
  # the shim loads the real driver as libbluetoothdrv_real.so: without it Bluetooth is dead after the reboot
  $ADB shell "su -c 'ls /system/lib/libbluetoothdrv_real.so'" 2>&1 | grep -q "No such file" &&
    { echo "/system/lib/libbluetoothdrv_real.so is missing on the iPod: not installing the shim (Bluetooth would stop working)" >&2; exit 1; }
  # build the shim when zig and the sources are here; a release bundle brings it already built
  if command -v zig >/dev/null 2>&1 && [ -f ../../java/android/native/fmhci.c ]; then sh ../../java/android/native/build.sh; fi
  SRC=../../java/android/native/libbluetoothdrv.so
  [ -f "$SRC" ] || { echo "$SRC is missing: build it (provision/y1.py build) or use a release bundle" >&2; exit 1; }
  # keep the original, but never a copy of the shim itself (the fmshim marker says the shim is what is installed)
  if [ ! -f "$SAVE/libbluetoothdrv.so" ] && ! $ADB shell "su -c 'ls /system/etc/fmshim'" 2>&1 | grep -qv "No such file"; then
    mkdir -p "$SAVE"; $ADB shell "su -c 'cp /system/lib/libbluetoothdrv.so /data/local/tmp/drv-orig.so; chmod 644 /data/local/tmp/drv-orig.so'"
    $ADB pull /data/local/tmp/drv-orig.so "$SAVE/libbluetoothdrv.so" >/dev/null; $ADB shell rm -f /data/local/tmp/drv-orig.so
  fi
fi
$ADB push "$SRC" /data/local/tmp/libbluetoothdrv.new >/dev/null
$ADB shell "su -c '
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
