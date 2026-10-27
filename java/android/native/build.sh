#!/bin/sh
# Rebuild the native helpers as static ARMv7 binaries (needs zig: brew install zig).
#   fmhci   Find My radio helper
#   wpactl  one command to wpa_supplicant (Wi-Fi power save off while the beacon runs)
#   libbluetoothdrv.so  the beacon inside Android's Bluetooth (btshim.c; rom/bt/install_shim.sh puts it on the device)
cd "$(dirname "$0")" || exit 1
for b in fmhci wpactl; do zig cc -target arm-linux-musleabi -mcpu=cortex_a7 -Os -static -s -o $b $b.c || exit 1; done
# bionic's loader only resolves a library's imports against what it lists as needed, so link against empty stand-ins
# named libc.so / libdl.so / liblog.so (the real ones are on the device)
stubs=$(mktemp -d)
for l in "libc:pthread_create usleep open read close memcpy memset clock_gettime" "libdl:dlopen dlsym" "liblog:__android_log_print"; do
  n=${l%%:*}; : > "$stubs/$n.c"; for s in ${l#*:}; do echo "void $s(void) {}" >> "$stubs/$n.c"; done
  zig cc -target arm-linux-gnueabi -mcpu=cortex_a7 -shared -nostdlib -fno-builtin -Wl,-soname,$n.so -o "$stubs/$n.so" "$stubs/$n.c" || exit 1
done
zig cc -target arm-linux-gnueabi -mcpu=cortex_a7 -Os -shared -fPIC -nostdlib -ffreestanding -fno-builtin -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables -fvisibility=hidden \
  -Wl,-soname,libbluetoothdrv.so -Wl,--hash-style=both -o libbluetoothdrv.so btshim.c "$stubs/libc.so" "$stubs/libdl.so" "$stubs/liblog.so" || exit 1
rm -rf "$stubs"
