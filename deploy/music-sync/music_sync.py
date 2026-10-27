"""music-sync: keep a folder of 192 kbps stereo MP3s in step with one Music Assistant playlist.

Music Assistant is the only thing that can decrypt Apple Music, so tracks are recorded from MA itself: each worker
registers as a Sendspin player, MA plays one track to it, and the PCM goes straight into ffmpeg. The result is
/data/<id>.mp3 plus a cover jpg and manifest.json, which the file server publishes to the Y1.
Runs inside the Music Assistant image (python, cryptography and ffmpeg are already there).
"""
import hashlib
import json
import os
import queue
import re
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sendspin_min as sm
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives import serialization

MA_HOST = os.environ.get("MA_HOST", "127.0.0.1")
MA_PORT = int(os.environ.get("MA_PORT", "8095"))
SS_PORT = int(os.environ.get("SENDSPIN_PORT", "8927"))
TOKEN = open(os.environ.get("MA_TOKEN_FILE", "/secrets/token")).read().strip()
# recordings are played as this MA user (the child's: Music Assistant then streams from their own Apple Music);
# everything else (library, search, sync) stays with the admin token above
_ptf = os.environ.get("PLAY_TOKEN_FILE", "")
PLAY_TOKEN = open(_ptf).read().strip() if _ptf and os.path.exists(_ptf) else None
PLAYLIST = os.environ.get("PLAYLIST", "FREYAS")
# the provider instance whose playlist of that name to use (the child's own Apple Music); unset: the first of that name
PLAYLIST_PROVIDER = os.environ.get("PLAYLIST_PROVIDER", "")
# or: sync this provider instance's whole library instead of a playlist (the child's own Apple Music: their library IS the player)
# names shown in Music Assistant and on the device; keep in step with the device's Identity (set in Home Assistant)
OWNER_NAME = os.environ.get("OWNER_NAME", "")
DEVICE_NAME = os.environ.get("DEVICE_NAME", (OWNER_NAME + "\u2019s Y1") if OWNER_NAME else "Y1")
LIBRARY_PROVIDER = os.environ.get("LIBRARY_PROVIDER", "")
OUT = os.environ.get("OUT", "/data")
WORKERS = int(os.environ.get("WORKERS", "8"))     # capture players available; how many run at once is learned
BITRATE = os.environ.get("BITRATE", "192k")
INTERVAL = int(os.environ.get("INTERVAL", "300"))


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ------------------------------------------------------------------ Music Assistant API

class MA:
    """MA websocket API; reconnects when the (idle) connection has been dropped."""
    def __init__(self, token=None):
        self.lock = threading.Lock()
        self.n = 0
        self.ws = None
        self.token = token or TOKEN
        self._connect()

    def _connect(self):
        self.ws = sm.WS(MA_HOST, MA_PORT, "/ws")
        self.ws.recv()
        self._send_recv("auth", {"token": self.token})

    def _send_recv(self, command, args):
        self.n += 1
        mid = str(self.n)
        self.ws.send(json.dumps({"message_id": mid, "command": command, "args": args}), binary=False)
        acc = []
        while True:
            _, raw = self.ws.recv()
            r = json.loads(raw)
            if r.get("message_id") == mid:
                if "error_code" in r:
                    raise RuntimeError("%s: %s" % (command, r.get("details")))
                if r.get("partial"):                       # long lists arrive in batches of 500
                    acc.extend(r.get("result") or [])
                    continue
                res = r.get("result")
                return acc + res if acc and isinstance(res, list) else res

    def call(self, command, **args):
        with self.lock:
            try:
                return self._send_recv(command, args)
            except RuntimeError:
                raise
            except Exception:           # dropped connection: reconnect once and retry
                self._connect()
                return self._send_recv(command, args)


# ------------------------------------------------------------------ one capture worker = one Sendspin player

class Worker(sm.Client):
    def __init__(self, index):
        super().__init__(MA_HOST, SS_PORT, name="Music Sync %d" % index)
        self.index = index
        keyfile = os.path.join(OUT, ".state", "worker%d.key" % index)
        os.makedirs(os.path.dirname(keyfile), exist_ok=True)
        if os.path.exists(keyfile):
            self.priv = X25519PrivateKey.from_private_bytes(open(keyfile, "rb").read())
        else:
            raw = self.priv.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw,
                                          serialization.NoEncryption())
            open(keyfile, "wb").write(raw)
        self.client_id = sm.b64u(sm.pub_bytes(self.priv))
        self.sink = None            # callable(bytes) while a track is being recorded
        self.ended = threading.Event()
        self.started = threading.Event()
        self.alive = False
        self.last_data = time.time()

    def serve(self):
        """Connect and pump messages forever (reconnects on failure). Runs in its own thread."""
        while True:
            try:
                self._session()
            except Exception as e:
                log("worker", self.index, "session ended:", repr(e)[:120])
            self.alive = False
            time.sleep(5)

    def _session(self):
        self.offset_us = None
        self.best_rtt = None
        self.connect()
        self.send_json({"type": "client/hello", "payload": {
            "client_id": self.client_id, "name": self.name,
            "device_info": {"product_name": "music-sync", "software_version": "1"},
            "supported_roles": ["player@v1"],
            "player@v1_support": {"supported_formats": [{"codec": "pcm", "channels": 2, "sample_rate": 44100, "bit_depth": 16}],
                                  "buffer_capacity": 256 * 1024 * 1024},
            "supported_pair_methods": {"pairing_psk": {}}, "unpaired_access": {"enabled": True}}})
        self.ws.s.settimeout(5)
        state_sent = False
        while True:
            try:
                mid, body = self.recv_msg()
            except socket.timeout:
                if state_sent:
                    self.send_time()
                continue
            if mid == 0:
                m = json.loads(body)
                t = m.get("type")
                if t == "server/activate":
                    if self.offset_us is None:
                        for _ in range(8):
                            self.send_time()
                            m2, b2 = self.recv_msg()
                            if m2 == 0 and json.loads(b2)["type"] == "server/time":
                                self.on_server_time(json.loads(b2)["payload"])
                    self.send_json({"type": "client/state", "payload": {"available": True, "player": {
                        "volume": 100, "muted": False, "output_delay_ms": 3600000, "required_lead_time_ms": 200,
                        "min_buffer_ms": 300, "supported_commands": ["volume", "mute"]}}})
                    state_sent = True
                    self.alive = True
                elif t == "server/time":
                    self.on_server_time(m["payload"])
                elif t == "stream/start":
                    self.started.set()
                elif t in ("stream/end", "stream/clear"):
                    if self.started.is_set():   # ignore a late end from the previous track
                        self.ended.set()
            elif mid == 4:
                self.last_data = time.time()
                if self.sink:
                    self.sink(body[12:])


def find_player(ma, name):
    for p in ma.call("players/all"):
        if p["display_name"] == name and p["provider"] == "universal_player" and p["available"]:
            return p["player_id"]
    return None


def listeners(ma):
    """How many streams the family is using right now: players (not ours) playing a music track."""
    n = 0
    try:
        for p in ma.call("players/all"):
            if p["display_name"].startswith("Music Sync") or p["playback_state"] != "playing":
                continue
            if (p.get("current_media") or {}).get("media_type") == "track":
                n += 1
    except Exception:
        pass
    return n


def someone_listening(ma):
    try:
        for p in ma.call("players/all"):
            if p["display_name"].startswith("Music Sync") or p["playback_state"] != "playing":
                continue
            cm = p.get("current_media") or {}
            if cm.get("media_type") == "track":
                return True
    except Exception:
        pass
    return False


_play = threading.local()


def play_ma(ma):
    """The connection recordings are started on: as PLAY_TOKEN's user when set (one per worker thread), else ma."""
    if PLAY_TOKEN is None:
        return ma
    if getattr(_play, "ma", None) is None:
        _play.ma = MA(PLAY_TOKEN)
    return _play.ma


def record(ma, worker, player_id, track, dest):
    """Record one track to dest (an .mp3). Returns seconds of audio, raises on failure."""
    meta = ["-metadata", "title=" + track["title"], "-metadata", "artist=" + track["artist"],
            "-metadata", "album=" + track["album"]]
    ff = subprocess.Popen(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "s16le", "-ar", "44100",
                           "-ac", "2", "-i", "pipe:0", "-codec:a", "libmp3lame", "-b:a", BITRATE, "-ac", "2",
                           "-id3v2_version", "3"] + meta + ["-f", "mp3", dest + ".part"],
                          stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    got = [0]

    def sink(b):
        got[0] += len(b)
        try:
            ff.stdin.write(b)
        except BrokenPipeError:
            pass

    worker.ended.clear()
    worker.started.clear()
    worker.last_data = time.time()
    worker.sink = sink
    t0 = time.time()
    record.wall = 0
    try:
        media = resolve_uri(ma, track)
        _rec.media = media
        try:
            try:
                play_ma(ma).call("player_queues/play_media", queue_id=player_id, media=media, option="replace")
            except (OSError, ConnectionError):
                _play.ma = None              # dropped connection: a fresh one next time
                raise
        except Exception as e:
            # a catalogue stand-in MA cannot stream either: rule it out, so the next attempt tries the next-best version
            if media != track["uri"] and ("could not be found" in str(e) or "nothing to play" in str(e)):
                rule_out(track["id"], media)
            raise
        limit = track["duration"] * 3 + 180
        while not worker.ended.is_set():
            if time.time() - t0 > limit:
                raise RuntimeError("timeout")
            if worker.started.is_set() and time.time() - worker.last_data > 60:
                raise RuntimeError("stalled")
            if not worker.started.is_set() and time.time() - t0 > 90:
                raise RuntimeError("never started")
            time.sleep(0.5)
    finally:
        worker.sink = None
        try:
            ff.stdin.close()
        except Exception:
            pass
        ff.wait(timeout=120)
    record.wall = time.time() - t0
    secs = got[0] / (44100 * 4)
    # a catalogue stand-in for a library-only song can be a slightly different edit (chosen within 15 s of it)
    slack = max(16, track["duration"] * 0.08) if media != track["uri"] else max(4, track["duration"] * 0.05)
    if track["duration"] and abs(secs - track["duration"]) > slack:
        os.remove(dest + ".part")
        raise RuntimeError("length %.0fs, expected %ds" % (secs, track["duration"]))
    os.replace(dest + ".part", dest)
    return secs


# ------------------------------------------------------------------ metadata: Apple's public lookup + LRCLIB lyrics

STATE = os.path.join(OUT, ".state")
META = os.path.join(STATE, "meta.json")
meta = {"tracks": {}, "albums": {}, "lyrics_tried": {}}
# One lock for every change to meta (workers, refresher, lyrics and clean loops all write it) and for save_meta,
# which serialises under it: json.dump of a dict another thread grows mid-dump raises. Re-entrant because the
# writers call save_meta while holding it.
_meta_lock = threading.RLock()
UA = {"User-Agent": "y1-music-sync/1.0"}


def load_meta():
    try:
        meta.update(json.load(open(META)))
    except Exception:
        pass
    try:   # tmp files left by a save that died mid-write
        for fn in os.listdir(STATE):
            if fn.startswith("meta.") and fn.endswith(".tmp"):
                os.remove(os.path.join(STATE, fn))
    except OSError:
        pass
    if not meta.get("lyrics_retry_v2"):            # once: before 26 Oct 2026 a busy LRCLIB counted as "no lyrics" for a week
        meta["lyrics_tried"] = {i: t for i, t in meta["lyrics_tried"].items() if os.path.exists(os.path.join(OUT, i + ".lrc"))}
        meta["lyrics_retry_v2"] = True


def save_meta():
    """Atomic: a consistent snapshot to a unique tmp file, then renamed over meta.json (a torn file loads as empty)."""
    with _meta_lock:   # held through the rename too, so an older snapshot can never land after a newer one
        s = json.dumps(meta)
        os.makedirs(STATE, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=STATE, prefix="meta.", suffix=".tmp")
        try:
            with os.fdopen(fd, "w") as fh:
                fh.write(s)
            os.replace(tmp, META)
        except BaseException:
            try:
                os.remove(tmp)
            except OSError:
                pass
            raise


def http_json(url, timeout=30):
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout))


_rec = threading.local()           # per worker: which version record() played (8 workers record at once)


def rule_out(track_id, media):
    """A catalogue stand-in MA could not stream: never pick it again for this song (the next attempt re-resolves)."""
    with _meta_lock:
        meta.setdefault("catalog_bad", {}).setdefault(track_id, []).append(media)
        meta.setdefault("catalog", {}).pop(track_id, None)
        if meta.get("clean", {}).get(track_id) == media:
            meta["clean"].pop(track_id, None)
        save_meta()


def _norm(x):
    import unicodedata
    x = unicodedata.normalize("NFKD", x or "").encode("ascii", "ignore").decode()   # Chéri = Cheri
    return "".join(ch for ch in x.lower() if ch.isalnum())


def find_clean(ma, t):
    """The clean (not explicit) catalogue version of an explicit song: same title and artist, length within 10 s.
    Remembered in meta["clean"] (a URI, or the time no clean version was found: looked for again after a week)."""
    hit = meta.get("clean", {}).get(t["id"])
    if isinstance(hit, str):
        return hit
    if isinstance(hit, (int, float)) and hit > time.time() - 7 * 86400:
        return None
    artist = t.get("primary_artist") or t.get("artist") or ""
    bad = meta.get("catalog_bad", {}).get(t["id"], [])
    found = None
    try:
        r = ma.call("music/search", search_query="%s %s" % (artist, t["title"]), media_types=["track"], limit=15)
        best = None
        for c in (r.get("tracks", []) if isinstance(r, dict) else (r or [])):
            u = str(c.get("uri", ""))
            if not u.startswith("apple_music://track/") or "/i." in u or u in bad or (c.get("metadata") or {}).get("explicit"):
                continue
            if _norm(c.get("name")) != _norm(t["title"]):
                continue
            arts = [_norm(a.get("name")) for a in c.get("artists", [])]
            if _norm(artist) not in arts:
                continue
            gap = abs((c.get("duration") or 0) - (t.get("duration") or 0)) if t.get("duration") else 0
            if gap <= 10 and (best is None or gap < best[0]):
                best = (gap, u)
        found = best[1] if best else None
    except Exception as e:
        log("clean lookup failed for %s - %s: %s" % (artist, t["title"], repr(e)[:80]))
        return None
    with _meta_lock:
        meta.setdefault("clean", {})[t["id"]] = found if found else time.time()
        save_meta()
    return found


def resolve_uri(ma, t):
    """The URI to record. Songs added to an Apple Music library without a catalogue match carry library ids
    (apple_music://track/i.xxxx), which MA cannot stream ('could not be found' / 'nothing to play'): find the catalogue
    version by artist + title, closest in length (within 15 s), and remember it. Failed lookups retry after a day."""
    uri = t["uri"]
    if t.get("explicit"):                       # the clean version wherever Apple has one
        c = find_clean(ma, t)
        if c:
            return c
    if not uri.startswith("apple_music://track/i."):
        return uri
    bad = meta.get("catalog_bad", {}).get(t["id"], [])   # catalogue versions MA could not stream either
    hit = meta.get("catalog", {}).get(t["id"])
    if isinstance(hit, str):
        return hit
    if isinstance(hit, (int, float)) and hit > time.time() - 86400:
        return uri
    artist = t.get("primary_artist") or t.get("artist") or ""
    found = None
    try:
        r = ma.call("music/search", search_query="%s %s" % (artist, t["title"]), media_types=["track"], limit=10)
        items = r.get("tracks", []) if isinstance(r, dict) else (r or [])
        best = None
        for c in items:
            if not str(c.get("uri", "")).startswith("apple_music://track/") or "/i." in str(c.get("uri")) or c.get("uri") in bad:
                continue
            if _norm(c.get("name")) != _norm(t["title"]):
                continue
            arts = [_norm(a.get("name")) for a in c.get("artists", [])]
            if _norm(artist) not in arts and not any(_norm(artist) in a or a in _norm(artist) for a in arts if a):
                continue
            gap = abs((c.get("duration") or 0) - (t.get("duration") or 0)) if t.get("duration") else 0
            if gap <= 15 and (best is None or gap < best[0]):
                best = (gap, c["uri"])
        found = best[1] if best else None
    except Exception as e:
        log("catalogue lookup failed for %s - %s: %s" % (artist, t["title"], repr(e)[:80]))
        return uri
    with _meta_lock:
        meta.setdefault("catalog", {})[t["id"]] = found if found else time.time()
        save_meta()
    log("catalogue %s for %s - %s" % ("match " + found if found else "none", artist, t["title"]))
    return found or uri


def play_uri(t):
    """The version MA can stream (what was recorded: a clean or catalogue stand-in), for favourites on her Sonos."""
    c = meta.get("catalog", {}).get(t["id"])
    return meta.get("recorded_as", {}).get(t["id"]) or (c if isinstance(c, str) else t["uri"])


def apple_id(uri):
    i = uri.rsplit("/", 1)[-1] if uri.startswith("apple_music://track/") else None
    return i if i and i.isdigit() else None   # library-only ids (uploads, purchases) are not in the catalogue


def enrich(tracks):
    """Release date, album artist and album id per track from Apple's lookup (keyless), cached."""
    want = [t for t in tracks if apple_id(t["uri"]) and t["id"] not in meta["tracks"]]
    for i in range(0, len(want), 100):
        batch = want[i:i + 100]
        try:
            r = http_json("https://itunes.apple.com/lookup?country=gb&entity=song&id=" + ",".join(apple_id(t["uri"]) for t in batch))
        except Exception as e:
            log("lookup failed:", e)
            return
        by = {str(x.get("trackId")): x for x in r.get("results", []) if x.get("wrapperType") == "track"}
        with _meta_lock:
            for t in batch:
                x = by.get(apple_id(t["uri"]))
                if x:
                    meta["tracks"][t["id"]] = {"release": x.get("releaseDate"), "collection": str(x.get("collectionId")),
                                              "genre": x.get("primaryGenreName"), "track_no": x.get("trackNumber"),
                                              "disc_no": x.get("discNumber")}
        time.sleep(3)
    with _meta_lock:
        cids = sorted({m["collection"] for m in meta["tracks"].values() if m.get("collection") and m["collection"] not in meta["albums"]})
    for i in range(0, len(cids), 100):
        try:
            r = http_json("https://itunes.apple.com/lookup?country=gb&id=" + ",".join(cids[i:i + 100]))
        except Exception as e:
            log("album lookup failed:", e)
            return
        with _meta_lock:
            for x in r.get("results", []):
                if x.get("wrapperType") == "collection":
                    meta["albums"][str(x["collectionId"])] = {"artist": x.get("artistName"), "date": x.get("releaseDate"),
                                                              "name": x.get("collectionName")}
        time.sleep(3)
    save_meta()


def real_duration(path):
    try:
        out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                             capture_output=True, text=True, timeout=30).stdout.strip()
        return float(out)
    except Exception:
        return None


lyrics_errors = []   # why LRCLIB could not be asked, logged once a pass
MAX_DRIFT = 1.5   # seconds: a lyric file for a different edit of the song would be out of step, so skip it


def fetch_lyrics(t, dur):
    """Time-synced lyrics from LRCLIB, written next to the track as <id>.lrc.
    True if found, False if LRCLIB has none that fit, None if its search could not be asked (busy or offline): only a
    real answer counts as tried, otherwise the song would wait days for nothing."""
    q = {"artist_name": t["primary_artist"] or t["artist"], "track_name": t["title"], "album_name": t["album"], "duration": int(round(dur))}
    try:   # the exact lookup goes out to other sources and is often "busy" (503); search below still works then
        d = http_json("https://lrclib.net/api/get?" + urllib.parse.urlencode(q))
    except Exception:
        d = None
    if d and abs((d.get("duration") or 0) - dur) > MAX_DRIFT:
        d = None
    if not d or not d.get("syncedLyrics"):
        try:
            res = http_json("https://lrclib.net/api/search?" + urllib.parse.urlencode({"artist_name": q["artist_name"], "track_name": t["title"]}))
        except Exception as e:
            lyrics_errors.append(repr(e)[:80])
            return None
        if not isinstance(res, list):
            lyrics_errors.append("search answered " + str(res)[:60])
            return None
        res = [x for x in res if x.get("syncedLyrics") and abs((x.get("duration") or 0) - dur) <= MAX_DRIFT]
        res.sort(key=lambda x: abs((x.get("duration") or 0) - dur))   # the edit whose length matches ours best
        d = res[0] if res else None
    if d and d.get("syncedLyrics"):
        open(os.path.join(OUT, t["id"] + ".lrc.part"), "w").write(d["syncedLyrics"])
        os.replace(os.path.join(OUT, t["id"] + ".lrc.part"), os.path.join(OUT, t["id"] + ".lrc"))
        return True
    return False


def genius_lyrics(ma, t, dur):
    """Fallback when LRCLIB has nothing: Music Assistant's lyrics providers (Genius). Usually plain text, which is
    written as LRC marked [unsynced:1] with the lines spread evenly over the song (the iPod scrolls it gently and does
    not highlight a line). True if found, False if not, None if MA could not be asked."""
    try:
        full = ma.call("music/item_by_uri", uri=t["uri"])
        res = ma.call("metadata/get_track_lyrics", track=full)
    except Exception as e:
        lyrics_errors.append("MA lyrics: " + repr(e)[:60])
        return None
    plain, lrc = (res + [None, None])[:2] if isinstance(res, list) else (None, None)
    if lrc:
        text = lrc
    elif plain:
        lines = [x.strip() for x in plain.splitlines()]
        lines = [x for x in lines if not (x.startswith("[") and x.endswith("]"))]   # [Chorus], [Verse 2]
        while lines and not lines[0]:
            lines.pop(0)
        while lines and not lines[-1]:
            lines.pop()
        if len([x for x in lines if x]) < 3:
            return False
        a, b = dur * 0.06, dur * 0.94
        step = (b - a) / max(1, len(lines))
        out = ["[unsynced:1]"]
        for i, x in enumerate(lines):
            sec = a + i * step
            out.append("[%02d:%05.2f]%s" % (int(sec // 60), sec % 60, x))
        text = "\n".join(out) + "\n"
    else:
        return False
    open(os.path.join(OUT, t["id"] + ".lrc.part"), "w").write(text)
    os.replace(os.path.join(OUT, t["id"] + ".lrc.part"), os.path.join(OUT, t["id"] + ".lrc"))
    return True


# ------------------------------------------------------------------ playlist + manifest

_forced_at = [0.0]
FORCE_EVERY = 15 * 60   # MA serves its cached copy of the playlist until its own library sync (hours): ask Apple again this often


def norm_uri(u):
    """apple_music--<instance>://track/X and apple_music://track/X are the same song: one id for both."""
    return re.sub(r"^apple_music--[A-Za-z0-9]+://", "apple_music://", u)


def track_id(uri):
    """A song's id (its file name on the iPod). Songs that moved to a new URI when the playlist moved to the child's own
    Apple Music (the clean or catalogue version that was already recorded) keep their old id: meta["alias"]."""
    u = norm_uri(uri)
    return meta.get("alias", {}).get(u) or hashlib.sha1(u.encode()).hexdigest()[:16]


def build_alias():
    """Once, before reading a moved playlist: map each recorded version's URI to the id its file already has."""
    if "alias" in meta:
        return
    alias = {}
    try:
        for t in json.load(open(os.path.join(OUT, "manifest.json")))["tracks"]:
            u = norm_uri(t.get("play_uri") or t["uri"])
            if hashlib.sha1(u.encode()).hexdigest()[:16] != t["id"]:
                alias.setdefault(u, t["id"])
    except (OSError, ValueError, KeyError) as e:
        log("alias: no manifest to build from:", e)
        return
    with _meta_lock:
        meta["alias"] = alias
        save_meta()
    log("alias: %d songs keep their ids at their new URIs" % len(alias))


def fetch_library(ma):
    """LIBRARY_PROVIDER's whole library as playlist-style items (uri = that provider's catalogue URI)."""
    force = time.time() - _forced_at[0] >= FORCE_EVERY   # MA only syncs a library daily on its own: ask every 15 min
    if force:
        _forced_at[0] = time.time()
        try:
            ma.call("music/sync", media_types=["track"], providers=[LIBRARY_PROVIDER])
        except Exception as e:
            log("library sync request failed:", e)
    items, off = [], 0
    while True:
        b = ma.call("music/tracks/library_items", limit=500, offset=off, provider=LIBRARY_PROVIDER)
        items += b
        off += 500
        if len(b) < 500:
            break
    out = []
    for t in items:
        m = [x for x in t.get("provider_mappings", []) if x.get("provider_instance") == LIBRARY_PROVIDER]
        if not m:
            continue
        t = dict(t)
        t["uri"] = "apple_music://track/%s" % m[0]["item_id"]
        t["is_playable"] = m[0].get("available", True)
        out.append(t)
    return {"name": (OWNER_NAME + "\u2019s Music") if OWNER_NAME else "Music", "uri": "library://" + LIBRARY_PROVIDER}, out


def adopt_editions(tracks):
    """Apple files a library song under whichever album edition it likes (and changes it), which gives it a new URI:
    the same song (title, artist, length within 10 s) already on the iPod keeps its file and id (meta["alias"])."""
    try:
        old = json.load(open(os.path.join(OUT, "manifest.json")))["tracks"]
    except (OSError, ValueError, KeyError):
        return tracks
    ids = {t["id"] for t in tracks}
    have = {fn[:-4] for fn in os.listdir(OUT) if fn.endswith(".mp3")}
    by_key = {}
    for m in old:
        if m["id"] not in ids:
            by_key.setdefault((_norm(m["title"]), _norm(m.get("primary_artist") or m["artist"])), []).append(m)
    added = 0
    for t in tracks:
        if t["id"] in have:
            continue
        for m in by_key.get((_norm(t["title"]), _norm(t["primary_artist"])), []):
            if m["id"] not in ids and abs((m.get("duration") or 0) - t["duration"]) <= 10:
                with _meta_lock:
                    meta.setdefault("alias", {})[norm_uri(t["uri"])] = m["id"]
                ids.discard(t["id"])
                t["id"] = m["id"]
                ids.add(m["id"])
                added += 1
                break
    if added:
        with _meta_lock:
            save_meta()
        log("editions: %d songs matched to the files already on the iPod" % added)
    # the same RECORDING in the library twice (one ISRC on two album editions, e.g. added again from search): one file,
    # preferring the one already on the iPod. Same title is not enough: Taylor's Versions and Spanish versions differ.
    tracks.sort(key=lambda t: 0 if t["id"] in have else 1)
    out, seen = [], set()
    for t in tracks:
        if t.get("isrc") and t["isrc"] in seen:
            continue
        if t.get("isrc"):
            seen.add(t["isrc"])
        out.append(t)
    if len(out) < len(tracks):
        log("editions: %d duplicate songs left out (another edition is already here)" % (len(tracks) - len(out)))
    return out


REMOVED_PLAYLIST = DEVICE_NAME + ": removed"   # the device's Identity.removedPlaylist()

def removed_songs(ma):
    """Songs taken off the iPod from the iPod itself: their ids and ISRCs. The iPod also removes them from her Apple
    library (Music Assistant, with the local apple_music delete patch); this list keeps them off the iPod meanwhile and
    if that fails. Taken off it again when re-added from search."""
    ids, isrcs = set(), set()
    try:
        for p in ma.call("music/playlists/library_items", search="removed", limit=50):
            if str(p.get("name", "")).replace("\u2019", "'") != REMOVED_PLAYLIST.replace("\u2019", "'"):   # ' or \u2019 alike
                continue
            for t in ma.call("music/playlists/playlist_tracks", item_id=p["item_id"],
                             provider_instance_id_or_domain=p["provider"], force_refresh=True) or []:
                for m in t.get("provider_mappings", []):
                    if str(m.get("provider_domain", "")).startswith("apple_music"):
                        ids.add(track_id("apple_music://track/%s" % m["item_id"]))

                ids.add(track_id(t.get("uri", "")))
                for e in t.get("external_ids") or []:
                    if isinstance(e, (list, tuple)) and len(e) > 1 and e[0] == "isrc":
                        isrcs.add(str(e[1]).upper())
    except Exception as e:
        log("removed list unreadable (keeping everything):", repr(e)[:100])
    return ids, isrcs


def fetch_tracks(ma):
    if LIBRARY_PROVIDER:
        build_alias()
        pl, tracks = fetch_library(ma)
        tracks = adopt_editions(parse_tracks(tracks))
        ids, isrcs = removed_songs(ma)
        if ids or isrcs:
            keep = [t for t in tracks if t["id"] not in ids and not (t.get("isrc") and t["isrc"] in isrcs)]
            if len(keep) < len(tracks):
                log("removed from the iPod: %d songs left out" % (len(tracks) - len(keep)))
            tracks = keep
        return pl, tracks
    pls = ma.call("music/playlists/library_items", limit=500)
    pl = [p for p in pls if p["name"].lower() == PLAYLIST.lower()
          and (not PLAYLIST_PROVIDER or any(m.get("provider_instance") == PLAYLIST_PROVIDER for m in p.get("provider_mappings", [])))]
    if not pl:
        raise RuntimeError("playlist %r not found%s" % (PLAYLIST, " in " + PLAYLIST_PROVIDER if PLAYLIST_PROVIDER else ""))
    if PLAYLIST_PROVIDER:
        build_alias()
    pl = pl[0]
    force = time.time() - _forced_at[0] >= FORCE_EVERY   # songs added in Apple Music show up within 15 minutes
    if force:
        _forced_at[0] = time.time()
    tracks = ma.call("music/playlists/playlist_tracks", item_id=pl["item_id"], provider_instance_id_or_domain=pl["provider"],
                     force_refresh=force)
    return pl, parse_tracks(tracks)


def parse_tracks(tracks):
    out = []
    seen_ids = set()
    for t in tracks:
        # songs MA greys out stay in the list (flagged): dropping them would read as "left the playlist" and delete
        # a good MP3; they are just not recorded until they play again
        arts = t.get("artists") or []
        artist = ", ".join(a["name"] if isinstance(a, dict) else str(a) for a in arts)
        album = t.get("album")
        album = (album.get("name") if isinstance(album, dict) else album) or ""
        imgs = (t.get("metadata") or {}).get("images") or []
        cover = imgs[0].get("proxy_id") if imgs else None
        tid = track_id(t["uri"])
        genres = [g for g in ((t.get("metadata") or {}).get("genres") or []) if g and g.lower() != "music"]
        primary = (arts[0]["name"] if isinstance(arts[0], dict) else str(arts[0])) if arts else ""
        if any(o["id"] == tid for o in out[-400:]) or tid in seen_ids:
            continue            # a playlist can hold the same song hundreds of times; keep the first
        seen_ids.add(tid)
        out.append({"id": tid, "uri": t["uri"], "title": t["name"], "artist": artist, "primary_artist": primary, "album": album,
                    "duration": int(t.get("duration") or 0), "cover_proxy": cover, "genres": genres,
                    "track_no": int(t.get("track_number") or 0), "disc_no": int(t.get("disc_number") or 1),
                    "explicit": bool((t.get("metadata") or {}).get("explicit")), "position": int(t.get("position") or 0),
                    "playable": bool(t.get("is_playable", True)),
                    "isrc": next((str(e[1]).upper() for e in (t.get("external_ids") or []) if isinstance(e, (list, tuple)) and len(e) > 1 and e[0] == "isrc"), None)})
    return out


def write_manifest(pl, tracks, total=None):
    """Each song once, even when the playlist repeats it: the Y1 gets one file per song."""
    items, seen = [], set()
    uniq = []
    for t in tracks:
        if t["id"] in seen:
            continue
        seen.add(t["id"])
        uniq.append(t)
    for t in uniq:
        f = os.path.join(OUT, t["id"] + ".mp3")
        if not os.path.exists(f):
            continue
        mt = meta["tracks"].get(t["id"], {})
        al = meta["albums"].get(mt.get("collection"), {})
        st = os.stat(f)
        # changes whenever the MP3 does (the Y1 re-downloads on a new rev; a clean re-record can be the same size)
        rev = hashlib.sha1(("%s|%d|%d" % (meta.get("recorded_as", {}).get(t["id"]) or t["uri"], st.st_size,
                                          int(st.st_mtime))).encode()).hexdigest()[:12]
        genres = list(t.get("genres") or [])
        if mt.get("genre") and mt["genre"].lower() != "music" and mt["genre"] not in genres:
            genres.insert(0, mt["genre"])
        items.append({"id": t["id"], "title": t["title"], "artist": t["artist"], "primary_artist": t.get("primary_artist"),
                      "album": t["album"], "album_id": mt.get("collection") or "%s|%s" % (t["album"], t.get("primary_artist") or t["artist"]),   # no id: never merge same-named albums
                      "album_artist": al.get("artist"), "album_date": al.get("date") or mt.get("release"),
                      "track_no": t.get("track_no") or mt.get("track_no") or 0, "disc_no": t.get("disc_no") or mt.get("disc_no") or 1,
                      "genres": genres, "duration": t["duration"], "file": t["id"] + ".mp3", "size": st.st_size, "rev": rev,
                      "cover": t["id"] + ".jpg" if os.path.exists(os.path.join(OUT, t["id"] + ".jpg")) else None,
                      "lyrics": t["id"] + ".lrc" if os.path.exists(os.path.join(OUT, t["id"] + ".lrc")) and not t.get("explicit") else None,
                      "play_uri": play_uri(t),
                      "added": int(meta.get("first_seen_v3", {}).get(t["id"], 0)),
                      "clean": bool(t.get("explicit") and isinstance(meta.get("clean", {}).get(t["id"]), str)
                                    and meta.get("recorded_as", {}).get(t["id"]) == meta["clean"][t["id"]]),
                      "uri": t["uri"]})
    # songs only in an Apple Music library with no catalogue version (resolve_uri found none), and songs MA marks not
    # playable: they cannot be fetched, so they are "not available" rather than forever "being prepared"
    have = set(x["id"] for x in items)
    gone = [t for t in uniq if t["id"] not in have and (not t.get("playable", True) or (t["uri"].startswith("apple_music://track/i.")
            and isinstance(meta.get("catalog", {}).get(t["id"]), (int, float))))]
    m = {"updated": int(time.time()), "playlist": {"name": pl["name"], "uri": pl["uri"]},
         "total": len(uniq), "pending": len(uniq) - len(items) - len(gone), "unavailable": len(gone),
         "unavailable_titles": ["%s - %s" % (t["artist"], t["title"]) for t in gone], "tracks": items}
    tmp = os.path.join(OUT, "manifest.json.tmp")
    with open(tmp, "w") as fh:
        json.dump(m, fh)
    os.replace(tmp, os.path.join(OUT, "manifest.json"))


def fetch_cover(t):
    dest = os.path.join(OUT, t["id"] + ".jpg")
    if os.path.exists(dest) or not t["cover_proxy"]:
        return
    try:
        url = "http://%s:%d/imageproxy/%s?size=256&fmt=jpeg" % (MA_HOST, MA_PORT, t["cover_proxy"])
        data = urllib.request.urlopen(url, timeout=30).read()
        open(dest + ".part", "wb").write(data)
        os.replace(dest + ".part", dest)
    except Exception as e:
        log("cover failed", t["title"], e)


def main():
    os.makedirs(OUT, exist_ok=True)
    workers = [Worker(i + 1) for i in range(WORKERS)]
    for w in workers:
        threading.Thread(target=w.serve, daemon=True).start()
    lock = threading.Lock()
    state = {"pl": None, "tracks": [], "wanted": set(), "first": True}
    queued = set()
    failures = {}
    todo = queue.PriorityQueue()     # (priority, seq, track): tracks added after the first pass jump the backlog
    seq = [0]
    guard = {}
    active = [0]
    listen = {"t": 0, "family": 0}
    # Apple allows only so many simultaneous streams per account and will not say how many, so learn it:
    # start at WORKERS, back off to what worked when Apple refuses, and probe upward again every few minutes.
    cap = {"total": WORKERS + 1, "refused_at": 0.0, "probe_at": time.time() + 300}

    def refresher():
        while True:
            try:
                ma = MA()
                pl, tracks = fetch_tracks(ma)
                log("playlist %s: %d tracks (%d not playable)" % (pl["name"], len(tracks), sum(1 for t in tracks if not t["playable"])))
                for t in tracks:
                    fetch_cover(t)
                enrich(tracks)
                wanted = {t["id"] for t in tracks}
                have = {fn.split(".")[0] for fn in os.listdir(OUT) if fn.endswith(".mp3")}
                lost = len(have - wanted)
                # An empty answer is an Apple/MA hiccup (or an unauthenticated provider), never a reason to delete the lot.
                if have and not tracks:
                    log("playlist came back empty: keeping all %d files" % len(have))
                    ma.ws.s.close()
                    time.sleep(INTERVAL)
                    continue
                # Guard: a sharply smaller playlist is far more likely a hiccup (e.g. mid-sync after a playlist edit)
                # than a real edit. Only delete in bulk once the same answer has held for 30 minutes.
                if have and lost > max(20, len(have) // 2):
                    first = guard.setdefault("since", time.time())
                    if time.time() - first < 1800:
                        log("playlist shrank from %d to %d tracks: keeping files until it holds for 30 min" % (len(have), len(tracks)))
                        wanted |= have          # keep everything for now, still record new ones
                else:
                    guard.pop("since", None)
                for fn in os.listdir(OUT):
                    base = fn.split(".")[0]
                    if fn.endswith((".mp3", ".jpg", ".part", ".lrc")) and base not in wanted:
                        os.remove(os.path.join(OUT, fn))
                        log("removed", fn)
                with _meta_lock:
                    fs = meta.get("first_seen_v3")
                    if fs is None:
                        # baseline (26 Oct 2026): the playlist has no "date added" and is ordered by artist, so the songs
                        # known to be new today (SIX, INJI, and the four appended at the end) count as new, the rest as old.
                        # Every song that arrives after this is stamped when it is first seen.
                        fs = meta["first_seen_v3"] = {t["id"]: (time.time() if (t.get("primary_artist") in ("SIX", "INJI")
                                                       or t.get("position", 0) >= 1489) else 0) for t in tracks}
                    for t in tracks:
                        fs.setdefault(t["id"], time.time())
                    save_meta()
                with lock:
                    state["pl"], state["tracks"], state["wanted"] = pl, tracks, wanted
                    prio = 1 if state["first"] else 0
                    new = 0
                    for t in tracks:
                        clean = meta.get("clean", {}).get(t["id"]) if t.get("explicit") else None
                        stale = isinstance(clean, str) and meta.get("recorded_as", {}).get(t["id"]) != clean
                        exists = os.path.exists(os.path.join(OUT, t["id"] + ".mp3"))
                        if t["id"] in queued or (exists and not stale) or (not exists and not t["playable"]):
                            continue
                        if failures.get(t["id"], (0, 0))[1] > time.time():
                            continue
                        queued.add(t["id"])
                        seq[0] += 1
                        todo.put((prio, seq[0], t))
                        new += 1
                    state["first"] = False
                    write_manifest(pl, tracks, len(tracks))
                log("%d queued, %d waiting in total" % (new, todo.qsize()))
                ma.ws.s.close()
            except Exception as e:
                log("refresh error:", repr(e)[:200])
            time.sleep(INTERVAL)

    def worker_loop(w):
        ma = None
        pid = None
        while True:
            try:
                prio, _, t = todo.get(timeout=30)
            except queue.Empty:
                continue
            slot = False
            try:
                if ma is None:
                    ma = MA()
                # leave Apple Music's stream allowance to the family: one fewer recording while anyone is listening
                while not slot:
                    now = time.time()
                    if now - listen["t"] > 20:
                        listen["family"] = listeners(ma)
                        listen["t"] = now
                    with lock:
                        if now > cap["probe_at"] and now - cap["refused_at"] > 300 and cap["total"] < WORKERS + listen["family"]:
                            cap["total"] += 1                      # nothing refused for a while: try one more stream
                            cap["probe_at"] = now + 300
                            log("concurrency: trying %d streams in total" % cap["total"])
                        limit = max(1, min(WORKERS, cap["total"] - listen["family"]))
                        if active[0] < limit:
                            active[0] += 1
                            slot = True
                    if not slot:
                        time.sleep(5)
                with lock:
                    if t["id"] not in state["wanted"]:
                        queued.discard(t["id"])
                        continue            # dropped from the playlist while it waited
                if pid is None:
                    for _ in range(60):
                        pid = find_player(ma, w.name)
                        if pid:
                            break
                        time.sleep(3)
                    if not pid:
                        raise RuntimeError("no player yet")
                    try:   # keep the capture players out of the MA player list and out of Home Assistant
                        ma.call("config/players/save", player_id=pid, values={"hide_in_ui": True, "expose_to_ha": False})
                    except Exception as e:
                        log("could not hide", w.name, e)
                dest = os.path.join(OUT, t["id"] + ".mp3")
                secs = record(ma, w, pid, t, dest)
                with _meta_lock:
                    meta.setdefault("recorded_as", {})[t["id"]] = _rec.media
                    save_meta()
                log("worker %d ok  %s - %s (%.0fs audio in %.0fs, %d KB)%s" % (w.index, t["artist"], t["title"], secs, record.wall,
                    os.path.getsize(dest) // 1024, " [clean version]" if _rec.media == meta.get("clean", {}).get(t["id"]) else ""))
                with lock:
                    queued.discard(t["id"])
                    write_manifest(state["pl"], state["tracks"], len(state["tracks"]))
            except Exception as e:
                if "simultaneous" in str(e):
                    with lock:   # Apple refused: that many streams (ours plus the family's) was one too many
                        cap["total"] = max(1, active[0] + listen["family"] - 1)
                        cap["refused_at"] = time.time()
                        cap["probe_at"] = time.time() + 600
                        seq[0] += 1
                        todo.put((0, seq[0], t))               # not the track's fault: straight back in the queue
                    log("worker %d refused by Apple, limit now %d streams in total" % (w.index, cap["total"]))
                    time.sleep(20)
                    continue
                with lock:
                    queued.discard(t["id"])
                    n = failures.get(t["id"], (0, 0))[0] + 1
                    failures[t["id"]] = (n, time.time() + min(86400, 600 * n * n))
                log("worker %d FAIL %s - %s: %s (attempt %d)" % (w.index, t["artist"], t["title"], e, n))
                try:
                    if ma and pid:
                        ma.call("player_queues/stop", queue_id=pid)
                except Exception:
                    ma = None
                time.sleep(3)
            finally:
                if slot:
                    with lock:
                        active[0] -= 1

    def lyrics_loop():
        lma = None
        while True:
            try:
                with lock:
                    tracks = list(state["tracks"])
                    pl = state["pl"]
                found = 0
                for t in tracks:
                    mp3 = os.path.join(OUT, t["id"] + ".mp3")
                    if os.path.exists(os.path.join(OUT, t["id"] + ".lrc")) or not os.path.exists(mp3):
                        continue
                    if meta["lyrics_tried"].get(t["id"], 0) > time.time() - 2 * 86400:   # LRCLIB gains lyrics daily
                        continue
                    dur = real_duration(mp3) or t["duration"]
                    ok = fetch_lyrics(t, dur)
                    if ok is False and not t.get("explicit"):    # LRCLIB has none: Genius (via MA), plain if not timed
                        try:
                            lma = lma or MA()
                            g_ok = genius_lyrics(lma, t, dur)
                        except Exception as e:
                            lma, g_ok = None, None
                            lyrics_errors.append("MA: " + repr(e)[:60])
                        ok = True if g_ok else ok
                    if ok is None:                  # LRCLIB busy for this song (503s come and go): try it again in 6 hours
                        with _meta_lock:
                            meta["lyrics_tried"][t["id"]] = time.time() - 2 * 86400 + 6 * 3600
                        time.sleep(3)
                        continue
                    with _meta_lock:
                        meta["lyrics_tried"][t["id"]] = time.time()
                    found += 1 if ok else 0
                    time.sleep(1.2)
                if lyrics_errors:
                    log("lyrics: %d lookups could not be made, last: %s" % (len(lyrics_errors), lyrics_errors[-1]))
                    del lyrics_errors[:]
                if found:
                    save_meta()
                    log("lyrics: %d new" % found)
                    with lock:
                        write_manifest(pl, state["tracks"])
            except Exception as e:
                log("lyrics error:", repr(e)[:150])
            time.sleep(120)

    load_meta()
    if not meta.get("lyrics_genius_v1"):          # a second source arrived: look again at songs LRCLIB had nothing for
        meta["lyrics_tried"] = {}
        meta["lyrics_genius_v1"] = True
        save_meta()
    threading.Thread(target=refresher, daemon=True).start()
    threading.Thread(target=lyrics_loop, daemon=True).start()

    def clean_loop():
        """Look up the clean version of explicit songs already synced, so the next pass re-records them clean."""
        cma = None
        while True:
            tracks = []
            try:
                with lock:
                    tracks = list(state["tracks"])
                # never looked up, or none found over a week ago (Apple adds clean versions; a search can miss once):
                # find_clean searches again, and a hit makes the refresher re-record the song clean
                cl = meta.get("clean", {})
                week_ago = time.time() - 7 * 86400
                todo_ids = [t for t in tracks if t.get("explicit") and not isinstance(cl.get(t["id"]), str)
                            and cl.get(t["id"], 0) <= week_ago]
                if todo_ids:
                    cma = cma or MA()
                    found = 0
                    for t in todo_ids:
                        found += 1 if find_clean(cma, t) else 0
                        time.sleep(1.5)
                    log("clean versions: %d of %d explicit songs have one" % (found, len(todo_ids)))
            except Exception as e:
                log("clean lookup error:", repr(e)[:150])
                cma = None
            time.sleep(600 if tracks else 20)       # the playlist is not loaded yet at startup: look again soon

    threading.Thread(target=clean_loop, daemon=True).start()
    ths = [threading.Thread(target=worker_loop, args=(w,), daemon=True) for w in workers]
    for th in ths:
        th.start()
    for th in ths:
        th.join()


if __name__ == "__main__":
    main()
