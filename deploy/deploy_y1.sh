#!/bin/sh
# Install the audiobook app on the connected Y1 and provision it (server, the child's Music Assistant token, file-server path).
# Secrets stay in deploy/secrets (gitignored), never in the APK.
set -e
cd "$(dirname "$0")"
[ -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools" ] && export PATH="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools:$PATH"
HOST="${MA_HOST:?set MA_HOST to the Music Assistant server address}"
command -v timeout >/dev/null 2>&1 || timeout() { shift; "$@"; }     # macOS has no timeout unless coreutils is installed
adb kill-server >/dev/null 2>&1 || true
adb start-server >/dev/null 2>&1
# the ARM helpers go into the app: build them first (needs zig) on a fresh checkout
[ -f ../java/android/native/fmhci ] || sh ../java/android/native/build.sh
sh ../java/build_apk.sh >/dev/null
timeout 120 adb -d install -r ../java/build/sendspin-y1.apk
timeout 30 adb -d shell am force-stop me.cns.sendspin.android
timeout 30 adb -d shell am start -n me.cns.sendspin.android/.MainActivity \
  --es host "$HOST" --ei port 8095 --es token "$(cat "${MA_TOKEN_FILE:-secrets/ma_token}")" \
  --es gw_prefix "$(cat secrets/gw_prefix)" --ei gw_port 18099
