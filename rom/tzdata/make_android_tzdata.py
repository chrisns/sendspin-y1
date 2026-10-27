#!/usr/bin/env python3
"""Build current Android 4.x time zone files from the Mac's IANA zoneinfo (2026e today).

Why: the Y1's database is from ~2012, so places that changed their rules since (Turkey dropped DST in 2016,
Mexico 2022, Brazil 2019, ...) would show the wrong local time, and bedtime would start at the wrong hour.
  make_android_tzdata.py [--legacy] [DIR]   the Android 4.0-4.2 trio (zoneinfo.dat/.idx/.version) the Y1 reads, into
                                            DIR (default: legacy/ next to this script); then install_zoneinfo.sh
  make_android_tzdata.py --single FILE      the Android 4.3-4.4 single file instead. The Y1 (4.2.2) ignores it.
--single format (libcore ZoneInfoDB, Android 4.3-4.4): 12-byte "tzdataNNNNx\\0", int32 index_offset, data_offset,
zonetab_offset; index of (40-byte name, int32 offset into data, int32 length, int32 raw UTC offset s) sorted by
name; the TZif files back to back (Android reads their v1 block, so source files must be 'fat'); then zone.tab.
"""
import os, struct, sys

SRC = "/usr/share/zoneinfo"

def raw_offset(tzif):
    # standard (non-DST) offset in effect at the last transition, from the v1 block
    isutc, isstd, leap, timecnt, typecnt, charcnt = struct.unpack(">6l", tzif[20:44])
    o = 44 + timecnt * 4
    types = tzif[o:o + timecnt]; o += timecnt
    infos = [struct.unpack(">lBB", tzif[o + 6 * i:o + 6 * i + 6]) for i in range(typecnt)]
    for t in reversed(types):
        off, dst, _ = infos[t]
        if not dst: return off
    for off, dst, _ in infos:
        if not dst: return off
    return infos[0][0] if infos else 0

def main(mode, out):
    version = open(os.path.join(SRC, "+VERSION")).read().strip()
    zones = []
    for root, dirs, files in os.walk(SRC):
        rel = os.path.relpath(root, SRC)
        if rel.split(os.sep)[0] in ("posix", "right"): continue
        for f in files:
            name = f if rel == "." else os.path.join(rel, f)
            p = os.path.join(root, f)
            d = open(p, "rb").read()
            if d[:4] != b"TZif" or len(name) >= 40: continue
            zones.append((name, d))
    zones.sort(key=lambda z: z[0].encode())
    if mode == "legacy":
        return legacy(version, zones, out)
    index, data = bytearray(), bytearray()
    for name, d in zones:
        index += name.encode().ljust(40, b"\0") + struct.pack(">lll", len(data), len(d), raw_offset(d))
        data += d
    zonetab = open(os.path.join(SRC, "zone.tab"), "rb").read() if os.path.exists(os.path.join(SRC, "zone.tab")) else b""
    header_len = 12 + 12
    idx_off = header_len; data_off = idx_off + len(index); tab_off = data_off + len(data)
    blob = ("tzdata" + version).encode().ljust(11, b"\0")[:11] + b"\0" + struct.pack(">lll", idx_off, data_off, tab_off) + index + data + zonetab
    open(out, "wb").write(blob)
    print("tzdata%s: %d zones, %d bytes" % (version, len(zones), len(blob)))

def legacy(version, zones, outdir):
    """Android 4.0-4.2 (the Y1): zoneinfo.idx (40-byte name, int32 offset, length, raw offset in MS), zoneinfo.dat
    (the TZif files back to back) and zoneinfo.version ("2026e\\n"). bionic and libcore both read these."""
    os.makedirs(outdir, exist_ok=True)
    index, data = bytearray(), bytearray()
    for name, d in zones:
        index += name.encode().ljust(40, b"\0") + struct.pack(">lll", len(data), len(d), raw_offset(d) * 1000)
        data += d
    open(os.path.join(outdir, "zoneinfo.idx"), "wb").write(index)
    open(os.path.join(outdir, "zoneinfo.dat"), "wb").write(data)
    open(os.path.join(outdir, "zoneinfo.version"), "w").write(version + "\n")
    print("zoneinfo %s: %d zones, %d bytes" % (version, len(zones), len(data)))

args = sys.argv[1:]
if args[:1] == ["--single"]:
    main("single", args[1] if len(args) > 1 else "tzdata")
else:
    if args[:1] == ["--legacy"]: args = args[1:]
    main("legacy", args[0] if args else os.path.join(os.path.dirname(os.path.abspath(__file__)), "legacy"))
