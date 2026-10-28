#!/bin/sh
# Conscrypt (TLS 1.2 with AES-GCM for Android 4.2) from Maven Central, checked against a pinned sha256, into
# android/libs: conscrypt-classes.jar and armeabi-v7a/libconscrypt_jni.so. Called by java/build_apk.sh, which holds the version
# (Renovate updates it there). 2.5.3 is the last release that runs on Android 4.2: 2.6.0 and later need Android 5.
#   java/android/libs/fetch.sh VERSION
set -e
V="${1:?usage: fetch.sh VERSION}"
cd "$(dirname "$0")"
if [ -f conscrypt-classes.jar ] && [ -f armeabi-v7a/libconscrypt_jni.so ] && [ "$(cat .version 2>/dev/null)" = "$V" ]; then exit 0; fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
U="https://repo1.maven.org/maven2/org/conscrypt/conscrypt-android/$V/conscrypt-android-$V.aar"
curl -fsSL -o "$T/c.aar" "$U"
got=$(shasum -a 256 "$T/c.aar" 2>/dev/null | cut -c1-64 || sha256sum "$T/c.aar" | cut -c1-64)
# Central publishes only sha1 and md5 for this artifact, so the version Renovate is allowed to propose (2.5.x) is pinned by sha256
case "$V" in
  2.5.3) [ "$got" = 551ae4e301c571760d1791e647db6ed1dcb10d34dcae7aa12b67f220f2ce98d1 ] || { echo "Conscrypt $V: sha256 $got is not the pinned one" >&2; exit 1; } ;;
  *) want=$(curl -fsSL "$U.sha1" | cut -c1-40); sha1=$(shasum -a 1 "$T/c.aar" | cut -c1-40)
     [ -n "$want" ] && [ "$want" = "$sha1" ] || { echo "Conscrypt $V: sha1 does not match Maven Central's; add its sha256 to fetch.sh" >&2; exit 1; }
     echo "Conscrypt $V: only Central's sha1 checked (add a sha256 pin)" >&2 ;;
esac
unzip -q -o "$T/c.aar" classes.jar jni/armeabi-v7a/libconscrypt_jni.so -d "$T/x"
mkdir -p armeabi-v7a
cp "$T/x/classes.jar" conscrypt-classes.jar
cp "$T/x/jni/armeabi-v7a/libconscrypt_jni.so" armeabi-v7a/libconscrypt_jni.so
echo "$V" > .version
echo "Conscrypt $V fetched"
