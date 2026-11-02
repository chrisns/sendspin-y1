#!/bin/sh
# Desktop tests for the pure-Java parts of the app (no Android needed).
set -e
cd "$(dirname "$0")/.."
D=$(mktemp -d)
javac --release 8 -Xlint:-options -d "$D" src/me/cns/sendspin/android/Totp.java src/me/cns/sendspin/android/Bedtime.java src/me/cns/sendspin/android/KeyHold.java src/me/cns/sendspin/android/Chapters.java src/me/cns/sendspin/android/FindMyDerive.java ../src/me/cns/sendspin/Json.java test/BedtimeTest.java test/FindMyDeriveTest.java
java -cp "$D" BedtimeTest
java -cp "$D" FindMyDeriveTest
