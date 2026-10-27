# Third-party notices

The code in this repository is MIT licensed (see LICENSE). It bundles or derives from the following, under their own
licences:

| Part | Where | Licence |
|---|---|---|
| Conscrypt 2.5.3 (TLS for Android 4.2): `conscrypt-classes.jar` and `libconscrypt_jni.so`, both also inside the APK | `java/android/libs/` | Apache License 2.0, with its NOTICE (`CONSCRYPT-LICENSE.txt`) |
| BoringSSL, statically linked into `libconscrypt_jni.so` | `java/android/libs/` | see `BORINGSSL-LICENSE.txt` (Apache 2.0 for current code; older files carry OpenSSL and ISC terms) |
| whisper.cpp v1.7.6 and ggml (static in the `y1-stt` binary of release bundles; built by `java/android/native/stt/build.sh`) | `licenses/whisper.cpp-LICENSE.txt` | MIT, Copyright (c) 2023-2024 The ggml authors |
| Whisper `tiny.en` model, `ggml-tiny.en-q5_1.bin` (downloaded by `stt/build.sh`, not stored in git; copied to the player) | `licenses/whisper-model-LICENSE.txt` | MIT, Copyright (c) 2022 OpenAI |
| Inter typeface | `java/android/assets/fonts/` | SIL Open Font License 1.1 (`Inter-LICENSE.txt`) |
| Let's Encrypt root certificates (ISRG Root X1, X2, and the cross-signed YR root `root-yr-cross.pem`) and Amazon Root CA 1 | `java/android/assets/certs/` | public CA certificates |
| Time zone grid `tzgrid.bin` | `java/android/assets/` | derived from [timezone-boundary-builder](https://github.com/evansiroky/timezone-boundary-builder) data (c) Evan Siroky and OpenStreetMap contributors, read through [timezonefinder](https://github.com/jannikmi/timezonefinder); the data is under the [Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/), so `tzgrid.bin` is too. Rebuild it with `java/android/tools/make_tzgrid.py` |
| IANA time zone database (built into the Android 4.2 zoneinfo files) | `rom/tzdata/legacy/` | public domain |

Fetched at provisioning time and not redistributed here: mtkclient (the MediaTek flashing client inside Innioasis
Updater, GPLv3, by B. Kerler), which `provision/y1.py fetch` downloads from its own project.

Services the player contacts at run time (nothing from them is bundled): LRCLIB (synced lyrics; plain lyrics from Genius arrive through Music Assistant) and
ip-api.com (approximate location from the IP address, used for the time zone).

Not included, and not redistributable here: the Innioasis Y1 stock firmware, the Solar ROM and its scripts, and
MediaTek's Bluetooth firmware and drivers. The ROM scripts expect you to supply those yourself.

Trademarks: Apple Music, Find My and iPod are trademarks of Apple Inc.; Innioasis and Y1 belong to their owner; Sonos,
Music Assistant and Home Assistant belong to theirs. This project is not affiliated with or endorsed by any of them.
