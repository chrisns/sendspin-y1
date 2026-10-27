#!/usr/bin/env python3
"""Build assets/tzgrid.bin: an offline lat/lon -> timezone map for the iPod (GPS fix -> local time, no internet).

0.25 degree grid from timezonefinder's boundary data, run-length coded per row. Each cell is sampled 3 x 3 (centre,
corners, edge midpoints), not just at its centre, which put Monte Gordo and Valenca (Portugal) in Madrid's cells and
Ceuta in Casablanca's. Sea samples (timezonefinder's nautical Etc/GMT zones: fixed offsets, no DST) are ignored next
to land. A cell whose land samples keep different clocks (UTC offsets over 2026-2027) is flagged mixed, and Geo then
leaves the zone to GeoIP. Open sea within 2 cells (about 50 km) of land takes the land's zone when it is one clock;
further out it stays Etc/*, which Geo treats as no answer.
Format (big endian):
  'TZG2', u16 names, then per name: u8 len, utf8 name, s16 standard offset in minutes (fallback when the
  Android 4.2 tz database does not know a newer name); u16 rows, u16 cols, then per row: u16 runs,
  runs x (u16 len, u16 name | 0x8000 when the cells are mixed).
Usage: make_tzgrid.py ../assets/tzgrid.bin   (needs timezonefinder and numpy)
"""
import struct, sys, zoneinfo, datetime
from collections import Counter
import numpy as np
from timezonefinder import TimezoneFinder

STEP = 0.25
COAST = 2                                    # cells of open sea that take the nearby land's zone
tf = TimezoneFinder(in_memory=True)
rows, cols = int(180 / STEP), int(360 / STEP)
names, index = [], {}

def zid(tz):
    if tz not in index:
        index[tz] = len(names); names.append(tz)
    return index[tz]

# the sample lattice: every half step, so neighbouring cells share their corners and edges
pts = np.zeros((2 * rows + 1, 2 * cols + 1), np.int32)
for k in range(2 * rows + 1):
    lat = 90 - k * STEP / 2
    for j in range(2 * cols + 1):
        pts[k, j] = zid(tf.timezone_at(lng=-180 + j * STEP / 2, lat=lat) or "Etc/UTC")
    if k % 120 == 0: print("lattice row", k, file=sys.stderr)
sea = np.array([n.startswith("Etc/") for n in names])

_clock = {}
def clock(name):
    """UTC offsets hour by hour through 2026-2027: two zones with the same list keep the same local time."""
    if name not in _clock:
        try:
            z, t0 = zoneinfo.ZoneInfo(name), datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
            _clock[name] = tuple(int((t0 + datetime.timedelta(hours=h)).astimezone(z).utcoffset().total_seconds()) for h in range(2 * 8760))
        except Exception:
            _clock[name] = name
    return _clock[name]

def one_clock(ids):
    return len({clock(names[i]) for i in ids}) == 1

samples = np.stack([pts[dr:dr + 2 * rows:2, dc:dc + 2 * cols:2] for dr in range(3) for dc in range(3)])   # (9, rows, cols)
centre = samples[4]
cell = centre.copy()
mixed = np.zeros((rows, cols), bool)
open_sea = np.zeros((rows, cols), bool)
uniform = (samples == centre).all(0)
open_sea[uniform] = sea[centre[uniform]]
for r, c in zip(*np.nonzero(~uniform)):
    land = [int(i) for i in samples[:, r, c] if not sea[i]]
    if not land:
        open_sea[r, c] = True
        continue
    if sea[centre[r, c]]: cell[r, c] = Counter(land).most_common(1)[0][0]
    mixed[r, c] = not one_clock(set(land))

# open sea near land: the land's zone when every land cell around keeps one clock, else mixed (no answer either way)
land_id = np.pad(np.where(open_sea, -1, cell), ((COAST, COAST), (0, 0)), constant_values=-1)
near = np.zeros((rows, cols), bool)
for dr in range(-COAST, COAST + 1):
    for dc in range(-COAST, COAST + 1):
        near |= np.roll(land_id, -dc, axis=1)[COAST + dr:COAST + dr + rows] >= 0
for r, c in zip(*np.nonzero(open_sea & near)):
    zones, any_mixed = [], False
    for rr in range(max(0, r - COAST), min(rows, r + COAST + 1)):
        for dc in range(-COAST, COAST + 1):
            cc = (c + dc) % cols
            if not open_sea[rr, cc]:
                zones.append(int(cell[rr, cc])); any_mixed |= bool(mixed[rr, cc])
    cell[r, c] = Counter(zones).most_common(1)[0][0]
    mixed[r, c] = any_mixed or not one_clock(set(zones))
print("mixed cells:", int(mixed.sum()), file=sys.stderr)

def std_offset(name):
    try:
        z = zoneinfo.ZoneInfo(name)
        jan = datetime.datetime(2026, 1, 15, tzinfo=z).utcoffset(); jul = datetime.datetime(2026, 7, 15, tzinfo=z).utcoffset()
        return int(min(jan, jul).total_seconds() // 60)
    except Exception:
        return 0

# only the names still in use (a sea zone can vanish under the coast fill)
code = cell | (mixed.astype(np.int32) << 15)
used = sorted(int(i) for i in np.unique(cell))
remap = {old: new for new, old in enumerate(used)}
b = bytearray(b"TZG2") + struct.pack(">H", len(used))
for old in used:
    e = names[old].encode(); b += struct.pack(">B", len(e)) + e + struct.pack(">h", std_offset(names[old]))
b += struct.pack(">HH", rows, cols)
for r in range(rows):
    runs, prev, n = [], None, 0
    for v in code[r].tolist():
        if v == prev: n += 1
        else:
            if prev is not None: runs.append((n, prev))
            prev, n = v, 1
    runs.append((n, prev))
    b += struct.pack(">H", len(runs))
    for n, v in runs: b += struct.pack(">HH", n, remap[v & 0x7fff] | (v & 0x8000))
open(sys.argv[1], "wb").write(b)
print(len(used), "zones,", len(b), "bytes", file=sys.stderr)
