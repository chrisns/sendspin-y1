#!/bin/sh
# Install the 2026e time zone files on the iPod (Android 4.2 reads zoneinfo.dat/.idx/.version). Run with the iPod on USB,
# then reboot it. The old files are kept in /data/local/tmp/zoneinfo-2012h. Build them first:
#   python3 rom/tzdata/make_android_tzdata.py        (writes rom/tzdata/legacy)
set -e
cd "$(dirname "$0")"
for f in dat idx version; do adb -d push legacy/zoneinfo.$f /data/local/tmp/zoneinfo.$f.new >/dev/null; done
adb -d shell "su -c '
  mount -o remount,rw /system
  [ -d /data/local/tmp/zoneinfo-2012h ] || { mkdir -p /data/local/tmp/zoneinfo-2012h; cp /system/usr/share/zoneinfo/zoneinfo.* /data/local/tmp/zoneinfo-2012h/; }
  for f in dat idx version; do
    cp /data/local/tmp/zoneinfo.\$f.new /system/usr/share/zoneinfo/zoneinfo.\$f.new
    chmod 644 /system/usr/share/zoneinfo/zoneinfo.\$f.new
    mv /system/usr/share/zoneinfo/zoneinfo.\$f.new /system/usr/share/zoneinfo/zoneinfo.\$f
    rm /data/local/tmp/zoneinfo.\$f.new
  done
  sync; cat /system/usr/share/zoneinfo/zoneinfo.version'"
echo "installed; reboot the iPod."
