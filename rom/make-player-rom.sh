#!/bin/sh
# Turn a freshly flashed Solar Y1 (Type A, 20260819-0956) into a dedicated music and audiobook player. Run with the Y1 on USB, booted, adb visible.
#   1. flash Solar:      flash/flash_solar.sh   (stock backup: flash/backup.sh)
#   2. this script:      rom/make-player-rom.sh
# What it does: fixes Solar's first-boot crash (libs missing from /system/lib, upstream issue #83), joins Wi-Fi is left to the app,
# replaces Solar's boot script with rom/overlay/99PlayerInit.sh, removes Solar's launcher and its helper/overlay/updater apps
# (keeps Y1Bridge for Bluetooth controls), installs and provisions the player, which becomes the home screen, and
# installs current time zone rules (Solar ships 2012h).
set -e
cd "$(dirname "$0")"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb wait-for-device
# 1. Solar's missing native libraries (copied out of its own APK)
TMP=$(mktemp -d); adb pull /system/app/com.solar.launcher.apk "$TMP/s.apk" >/dev/null
( cd "$TMP" && unzip -q -o s.apk 'lib/armeabi-v7a/libwolfssl.so' 'lib/armeabi-v7a/libc++_shared.so' )
for f in libwolfssl.so libc++_shared.so; do adb push "$TMP/lib/armeabi-v7a/$f" /data/local/tmp/ >/dev/null; done
adb shell "su -c 'mount -o remount,rw /system; cp /data/local/tmp/libwolfssl.so /data/local/tmp/libc++_shared.so /system/lib/; chmod 644 /system/lib/libwolfssl.so /system/lib/libc++_shared.so'"
# 2. the player first (so there is a home screen), then strip Solar
../deploy/deploy_y1.sh
adb push overlay/99PlayerInit.sh /data/local/tmp/ >/dev/null
adb push overlay/slim.sh /data/local/tmp/ >/dev/null
adb shell "su -c 'sh /data/local/tmp/slim.sh; pm enable me.cns.sendspin.android'"
# 3. time zone rules: Android 4.2 reads zoneinfo.dat/.idx/.version, and 2012h puts Turkey, Mexico, Brazil... an hour out
#    (rebuild rom/tzdata/legacy with tzdata/make_android_tzdata.py when the IANA rules change)
sh tzdata/install_zoneinfo.sh
adb reboot
echo "Rebooting into a dedicated music and audiobook player. Join Wi-Fi in Settings if needed; music and audiobooks then sync by themselves."
