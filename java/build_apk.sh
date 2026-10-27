#!/bin/sh
# Build a debug-signed APK targeting Android 4.2.2 (API 17). Needs the Android SDK
# (platform android-17, build-tools 34.0.0) and a JDK 17.
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-17/android.jar"
rm -rf build && mkdir -p build/classes
# core client (everything except the desktop harness) + android front end
CORE=$(ls src/me/cns/sendspin/*.java | grep -v -e Main.java -e CryptoSelfTest.java)
TLSJAR=android/libs/conscrypt-classes.jar   # Conscrypt 2.5.3 (Maven Central): TLS 1.2 GCM / 1.3 for Home Assistant
javac --release 8 -Xlint:-options -cp "$JAR:$TLSJAR" -d build/classes $CORE android/src/me/cns/sendspin/android/*.java
"$BT/d8" --min-api 17 --lib "$JAR" --output build $(find build/classes -name '*.class') "$TLSJAR"
"$BT/aapt2" link -o build/base.apk --manifest android/AndroidManifest.xml -I "$JAR" \
  --min-sdk-version 17 --target-sdk-version 17
mkdir -p build/assets build/lib/armeabi-v7a
cp android/native/fmhci build/assets/   # Find My radio helper, built from native/fmhci.c
cp -R android/assets/. build/assets/     # Let's Encrypt roots, offline timezone map
cp android/libs/armeabi-v7a/libconscrypt_jni.so build/lib/armeabi-v7a/
(cd build && zip -q -r base.apk classes.dex assets lib)
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias dbg -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=debug" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out build/sendspin-y1.apk build/aligned.apk
ls -la build/sendspin-y1.apk
