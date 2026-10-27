#!/bin/sh
# On-device speech to text for voice search: builds whisper.cpp as a static ARMv7 binary (zig), fetches a model, and
# with "install" puts both in the app's files/stt on the Y1 (root over adb). The app uses it instead of Home
# Assistant's speech-to-text whenever it is there.
#   java/android/native/stt/build.sh            build only
#   java/android/native/stt/build.sh install    build and install
#   MODEL=base.en-q5_1 ...                      a bigger, slower, more accurate model (default tiny.en-q5_1, 31 MB)
# Needs: zig, cmake, git, curl, adb.
set -e
cd "$(dirname "$0")"
V=v1.7.6
MODEL=${MODEL:-tiny.en-q5_1}
APP=me.cns.sendspin.android
ADB="${ADB:-adb} -d"
[ -d whisper.cpp ] || git clone --depth 1 --branch $V https://github.com/ggml-org/whisper.cpp
cat > zig-arm.cmake <<'TC'
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR armv7)
set(CMAKE_C_COMPILER zig cc)
set(CMAKE_CXX_COMPILER zig c++)
set(CMAKE_C_COMPILER_TARGET arm-linux-musleabihf)
set(CMAKE_CXX_COMPILER_TARGET arm-linux-musleabihf)
set(CMAKE_C_FLAGS_INIT "-mcpu=cortex_a7 -mfpu=neon-vfpv4")
set(CMAKE_CXX_FLAGS_INIT "-mcpu=cortex_a7 -mfpu=neon-vfpv4")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-static")
TC
if [ ! -x whisper-cli ]; then
  cmake -S whisper.cpp -B whisper.cpp/build-arm -DCMAKE_TOOLCHAIN_FILE="$PWD/zig-arm.cmake" -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF \
    -DWHISPER_SDL2=OFF >/dev/null
  cmake --build whisper.cpp/build-arm -j8 --target whisper-cli
  cp whisper.cpp/build-arm/bin/whisper-cli .
fi
[ -f ggml-$MODEL.bin ] || curl -fL -o ggml-$MODEL.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$MODEL.bin
ls -la whisper-cli ggml-$MODEL.bin
[ "$1" = install ] || exit 0
$ADB push whisper-cli /data/local/tmp/whisper-cli
$ADB push ggml-$MODEL.bin /data/local/tmp/model.bin
uid=$($ADB shell dumpsys package $APP | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1)
d=/data/data/$APP/files/stt
$ADB shell "su -c 'mkdir -p $d && mv /data/local/tmp/whisper-cli $d/ && mv /data/local/tmp/model.bin $d/ && chown -R $uid:$uid $d && chmod 755 $d $d/whisper-cli && chmod 644 $d/model.bin'"
echo "installed: hold the centre button and speak (the iPod logs how long it took)"
