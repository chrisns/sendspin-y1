#!/bin/sh
# On-device speech to text for voice search: builds whisper.cpp's libraries for ARMv7 (zig), links y1-stt.cpp (our
# small front end: one short segment, stops at the first sentence or repeat) as a static binary, fetches a model, and
# with "install" puts both in the app's files/stt on the Y1 (root over adb). The app uses it when there is no Wi-Fi,
# or always when "Voice on the iPod only" is on. tiny.en takes 3 to 5 s for a song name on the Y1's one core.
#   java/android/native/stt/build.sh            build only
#   java/android/native/stt/build.sh install    build and install
#   MODEL=base.en-q5_1 ...                      a bigger, slower, more accurate model (default tiny.en-q5_1, 31 MB)
# Needs: zig, cmake, git, curl, adb.
set -e
cd "$(dirname "$0")"
V=v1.7.6
VCOMMIT=a8d002cfd879315632a579e73f0148d06959de36      # what v1.7.6 pointed at when this was proven (a tag can be moved)
MODEL=${MODEL:-tiny.en-q5_1}
APP=me.cns.sendspin.android
ADB="${ADB:-adb} -d"
# a release bundle brings y1-stt already built: only build when zig is here (or there is no y1-stt)
if command -v zig >/dev/null 2>&1 || [ ! -x y1-stt ]; then
  [ -d whisper.cpp ] || git clone --depth 1 --branch $V https://github.com/ggml-org/whisper.cpp
  [ "$(git -C whisper.cpp rev-parse HEAD)" = "$VCOMMIT" ] || { echo "whisper.cpp is not at the pinned commit $VCOMMIT: delete whisper.cpp and run again" >&2; exit 1; }
  # zig as the compiler and archiver, through one-word wrappers (CMake mangles "zig cc", and macOS's own ar/ranlib
  # cannot index ARM ELF objects, which shows up as every whisper_* symbol undefined at the final link)
  mkdir -p zigbin
  for t in cc c++ ar ranlib; do printf '#!/bin/sh\nexec zig %s "$@"\n' $t > zigbin/zig-$t; chmod +x zigbin/zig-$t; done
  Z="$PWD/zigbin"
  cat > zig-arm.cmake <<TC
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR armv7)
set(CMAKE_C_COMPILER $Z/zig-cc)
set(CMAKE_CXX_COMPILER $Z/zig-c++)
set(CMAKE_AR $Z/zig-ar CACHE FILEPATH "")
set(CMAKE_RANLIB $Z/zig-ranlib CACHE FILEPATH "")
set(CMAKE_C_COMPILER_TARGET arm-linux-musleabihf)
set(CMAKE_CXX_COMPILER_TARGET arm-linux-musleabihf)
set(CMAKE_C_FLAGS_INIT "-mcpu=cortex_a7 -mfpu=neon-vfpv4")
set(CMAKE_CXX_FLAGS_INIT "-mcpu=cortex_a7 -mfpu=neon-vfpv4")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-static")
TC
  if [ ! -f whisper.cpp/build-arm/src/libwhisper.a ]; then
    rm -rf whisper.cpp/build-arm
    cmake -S whisper.cpp -B whisper.cpp/build-arm -DCMAKE_TOOLCHAIN_FILE="$PWD/zig-arm.cmake" -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF \
      -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_SDL2=OFF >/dev/null
    cmake --build whisper.cpp/build-arm -j8 --target whisper
  fi
  W=whisper.cpp; B=$W/build-arm
  zig c++ -target arm-linux-musleabihf -mcpu=cortex_a7 -mfpu=neon-vfpv4 -O2 -static -s -std=c++17 -I$W/include -I$W/ggml/include \
    y1-stt.cpp $B/src/libwhisper.a $B/ggml/src/libggml.a $B/ggml/src/libggml-cpu.a $B/ggml/src/libggml-base.a -o y1-stt
fi
# the model is checked against its sha256 (known for the default; another MODEL is used unchecked, with a warning), and
# downloaded to a .part file first so an interrupted download is never mistaken for the model
case $MODEL in tiny.en-q5_1) WANT=c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b ;; *) WANT= ;; esac
sha() { if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1"; else sha256sum "$1"; fi | cut -d' ' -f1; }
if [ -n "$WANT" ] && [ -f ggml-$MODEL.bin ] && [ "$(sha ggml-$MODEL.bin)" != "$WANT" ]; then rm -f ggml-$MODEL.bin; fi
if [ ! -f ggml-$MODEL.bin ]; then
  curl -fL -o ggml-$MODEL.bin.part https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$MODEL.bin
  if [ -n "$WANT" ] && [ "$(sha ggml-$MODEL.bin.part)" != "$WANT" ]; then rm -f ggml-$MODEL.bin.part; echo "the model does not match its pinned sha256: not using it" >&2; exit 1; fi
  [ -n "$WANT" ] || echo "warning: no pinned sha256 for model $MODEL" >&2
  mv ggml-$MODEL.bin.part ggml-$MODEL.bin
fi
ls -la y1-stt ggml-$MODEL.bin
[ "$1" = install ] || exit 0
$ADB push y1-stt /data/local/tmp/y1-stt
$ADB push ggml-$MODEL.bin /data/local/tmp/model.bin
uid=$($ADB shell dumpsys package $APP | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1)
d=/data/data/$APP/files/stt
$ADB shell "su -c 'mkdir -p $d && rm -f $d/whisper-cli* && mv /data/local/tmp/y1-stt $d/ && mv /data/local/tmp/model.bin $d/ && chown $uid:$uid $d $d/y1-stt $d/model.bin && chmod 755 $d $d/y1-stt && chmod 644 $d/model.bin'"
echo "installed: hold the centre button and speak (the iPod logs how long it took)"
