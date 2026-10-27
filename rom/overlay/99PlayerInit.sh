#!/system/bin/sh
# The player: the parts of Solar's boot that are still needed once Solar's launcher is removed.
# (Root is started by install-recovery.sh; Xposed by 99XposedInit.sh.)
# adb only for computers whose key is in /data/misc/adb/adb_keys (Chris's Mac): adb here is root, so any other computer
# would get the grown-up code secret and the Home Assistant webhook. No keys file: left open, so nobody is locked out.
if [ -s /data/misc/adb/adb_keys ] && [ "$(getprop ro.adb.secure)" != 1 ]; then
    setprop ro.adb.secure 1; stop adbd; start adbd
fi
# MediaTek's xlog filter drops to errors only whenever adbd restarts (xlog debug-changed): keep info logs for the player
(sleep 8; /system/bin/xlog filter-set info) &
settings put system accelerometer_rotation 0 2>/dev/null
settings put system user_rotation 0 2>/dev/null
settings put system sound_effects_enabled 0 2>/dev/null     # the player makes its own wheel click
[ -f /system/etc/solar/enable-gpu-performance.sh ] && sh /system/etc/solar/enable-gpu-performance.sh
[ -f /system/etc/solar/sync-y1-keymap.sh ] && sh /system/etc/solar/sync-y1-keymap.sh   # wheel/button key layouts
# Bluetooth (AVRCP) track info slot: Y1Bridge and the patched libextavrcp_jni.so (path hardcoded) map it to answer
# headphones' play status and title. Solar's launcher wrote it; nothing does since slim.sh removed it, so it stays zeroed
# (headphones see "stopped", no title) until the player writes it. Still created, and writable, for that.
mkdir -p /data/data/com.innioasis.y1/files
chmod 711 /data/data/com.innioasis.y1 /data/data/com.innioasis.y1/files
[ -f /data/data/com.innioasis.y1/files/y1-track-info ] || \
    dd if=/dev/zero of=/data/data/com.innioasis.y1/files/y1-track-info bs=1 count=2213 2>/dev/null
chmod 666 /data/data/com.innioasis.y1/files/y1-track-info
# storage folders the player uses
i=0
while [ "$i" -lt 30 ] && [ ! -d /storage/sdcard0 ]; do sleep 1; i=$((i + 1)); done
mkdir -p /storage/sdcard0/Music /storage/sdcard0/Audiobooks 2>/dev/null
