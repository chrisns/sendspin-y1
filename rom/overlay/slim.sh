#!/system/bin/sh
# One-off: turn the Solar ROM into the player. Reflash Solar to undo.
set -x
mount -o remount,rw /system 2>/dev/null
cp /data/local/tmp/99PlayerInit.sh /system/etc/init.d/99PlayerInit.sh && chmod 755 /system/etc/init.d/99PlayerInit.sh
rm -f /system/etc/init.d/99SolarInit.sh /system/etc/init.d/99Y1ButtonScript
for p in com.solar.launcher com.solar.launcher.homehelper com.solar.launcher.globalcontext \
         com.solar.launcher.xposed.bridge.y1 com.solar.launcher.xposed.rockbox.ime com.solar.launcher.xposed.themefont com.solar.updater; do
    pm disable "$p" 2>/dev/null
done
cd /system/app && rm -f com.solar.launcher.apk SolarHomeHelper.apk SolarGlobalContextModal.apk SolarContextBridgeY1.apk \
    SolarRockboxIme.apk SolarThemeFont.apk com.solar.updater.apk
settings put secure default_input_method "" 2>/dev/null
settings put secure enabled_accessibility_services "" 2>/dev/null
sync
ls /system/app | wc -l
ls /system/etc/init.d
