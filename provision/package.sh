#!/bin/sh
# Make the provisioning bundle (what a release carries): the built app and helpers plus the scripts that put them on a
# Y1, laid out like the repository so provision/y1.py works from it with --prebuilt. Run after provision/y1.py build.
#   provision/package.sh [VERSION]      writes dist/y1-provision-VERSION.zip
set -e
cd "$(dirname "$0")/.."
V="${1:-dev}"
D="dist/y1-provision-$V"
rm -rf dist && mkdir -p "$D/prebuilt" "$D/java/android/native" "$D/java/android/native/stt" "$D/rom/tzdata" "$D/rom/bt" "$D/deploy"
cp java/build/sendspin-y1.apk "$D/prebuilt/"
for f in btshim.c fmhci.c wpactl.c build.sh libbluetoothdrv.so fmhci wpactl; do cp "java/android/native/$f" "$D/java/android/native/"; done
[ -f java/android/native/stt/y1-stt ] && cp java/android/native/stt/y1-stt java/android/native/stt/y1-stt.cpp "$D/java/android/native/stt/"
cp java/android/native/stt/build.sh "$D/java/android/native/stt/"
cp -R rom/overlay rom/make-player-rom.sh "$D/rom/"
mkdir -p "$D/rom/tzdata"; cp -R rom/tzdata/legacy rom/tzdata/install_zoneinfo.sh "$D/rom/tzdata/"
cp rom/bt/install_shim.sh rom/bt/NOTES.md "$D/rom/bt/"
cp -R provision "$D/"; rm -rf "$D/provision/package.sh" "$D/provision/y1.json" "$D/provision/__pycache__"
cp deploy/y1_setup.py deploy/device.example.json deploy/deploy_y1.sh "$D/deploy/"
cp -R custom_components "$D/"
cp LICENSE THIRD_PARTY_NOTICES.md README.md "$D/"
mkdir -p "$D/licenses"; cp licenses/* java/android/libs/*-LICENSE.txt "$D/licenses/"
cat > "$D/START-HERE.txt" <<TXT
sendspin-y1 $V: provisioning bundle.

  python3 provision/y1.py doctor
  python3 provision/y1.py fetch        (Solar ROM and the flashing tool: downloaded from their own projects)
  python3 provision/y1.py flash        (Y1 switched off, then plug it in)
  python3 provision/y1.py provision --prebuilt prebuilt [--shim] [--voice]

The app in prebuilt/ is signed with a key made for this release; to update a Y1 that has an older release on it, uninstall
the old app first (adb uninstall me.cns.sendspin.android), or build from source with your own key.
TXT
(cd dist && zip -q -r "y1-provision-$V.zip" "y1-provision-$V")
ls -la "dist/y1-provision-$V.zip"
