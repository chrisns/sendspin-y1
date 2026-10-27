# Third-party notices

The code in this repository is MIT licensed (see LICENSE). It bundles or derives from the following, under their own
licences:

| Part | Where | Licence |
|---|---|---|
| Conscrypt 2.5.3 (TLS for Android 4.2) | `java/android/libs/` | Apache License 2.0 |
| Inter typeface | `java/android/assets/fonts/` | SIL Open Font License 1.1 (`Inter-LICENSE.txt`) |
| Let's Encrypt root certificates (ISRG Root X1, X2) | `java/android/assets/certs/` | public CA certificates |
| Time zone grid `tzgrid.bin` | `java/android/assets/` | derived from timezone-boundary-builder data via timezonefinder; the data is under the Open Database License (ODbL) 1.0, so `tzgrid.bin` is too. Rebuild it with `java/android/tools/make_tzgrid.py` |
| IANA time zone database (built into the Android 4.2 zoneinfo files) | `rom/tzdata/legacy/` | public domain |

Not included, and not redistributable here: the Innioasis Y1 stock firmware, the Solar ROM and its scripts, and
MediaTek's Bluetooth firmware and drivers. The ROM scripts expect you to supply those yourself.

Trademarks: Apple Music, Find My and iPod are trademarks of Apple Inc.; Innioasis and Y1 belong to their owner; Sonos,
Music Assistant and Home Assistant belong to theirs. This project is not affiliated with or endorsed by any of them.
