#!/usr/bin/env python3
"""Screenshots of everything the player does, for the docs and the Help video script.

  docs/screenshots/capture.py [--only NAME,NAME] [--raw DIR] [--result-down N]

Safety: it plays music while it works (silently), so it refuses to run unless the music volume of every output is 0.
Needs: the Y1 on USB (adb -d, root), the app running, Pillow, macOS `say` + afconvert (for the voice search clip; that
step is skipped without them). The Home Assistant page is captured separately (see .claude/skills/y1-docs/SKILL.md).

How it drives the device: the app's test hook (`su -c am broadcast -a me.cns.ab.KEY --ei code N`) presses keys inside the
app (UP 19, DOWN 20, CENTER 23, BACK 4, MENU 82; 994 = hold now, 995 = dark theme toggle, 996 = lock preview, 997 =
Now Playing preview of the highlighted row without starting it, 990 = recognise /sdcard/voice-test.pcm). Screens come
from `screencap` over adb.

What it will not do: press anything that changes a setting, favourites or the library, or the grown-up code. It only
looks. It leaves the Y1 as it found it: screen asleep and on hold if it was, nothing playing.

Raw captures stay in a temp directory (never in the repo); the PNGs written to docs/screenshots/ have the regions in
BLUR painted over (phone number and email on the lock screen, Wi-Fi network names), so they are safe to publish.
manifest.json lists file, title and caption for each.
"""
import argparse, json, os, shutil, subprocess, sys, tempfile, time

try:
    from PIL import Image, ImageChops, ImageFilter, ImageStat
except ImportError:
    sys.exit("needs Pillow: pip3 install pillow")

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = os.environ.get("ADB") or shutil.which("adb") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
APP = "me.cns.sendspin.android"
UP, DOWN, CENTER, BACK, MENU = 19, 20, 23, 4, 82
HOLD, DARK, LOCKPREVIEW, PREVIEW, VOICETEST, SHOTMODE, SHOTMODE_OFF = 994, 995, 996, 997, 990, 989, 988

# Regions (x0, y0, x1, y1 on the 480x360 screen) painted over before a screenshot is saved.
CONTACT = (20, 158, 280, 205)       # the "if found" phone number and email on the lock screen
SSID_ROWS = [(20, 150, 400, 174), (20, 200, 400, 224), (20, 250, 400, 274)]   # the network names in the Wi-Fi list
SSID_SUB = (20, 70, 420, 92)        # "connected to <network>" under the Wi-Fi row in Settings

# The order the screenshots come in the docs: NN in NN-name.png is the position here, so a partial run (--only) keeps
# every file's number and only replaces the files it takes. manifest.json is merged the same way.
ORDER = ["menu", "menu-more", "now-playing", "in-progress", "book-now-playing", "authors", "books", "book-page", "chapters",
         "music", "music-more", "favourites", "new-songs", "artists", "artist", "artist-songs", "album-tracks", "search", "voice-listening", "voice-thinking",
         "voice-results", "voice-result-page", "listen-on", "settings", "settings-more", "wifi", "bluetooth", "status", "help",
         "help-video", "hold", "bedtime-dark", "bedtime-lock", "ha-device-top", "ha-device-config", "ha-device-config-more"]
RESULT_DOWN = 0     # --result-down N
UNLOCKED = False    # a grown-up has unlocked bedtime: the main menu has an extra line ("Grown-up unlock until ...") under Continue
# Where things are, as DOWN presses (the wheel) from the top of a list. Change them when the library changes.
ARTIST_DOWN = 13    # Music > Artists: down to Taylor Swift (the demo artist: lots of albums, every song has lyrics)
AUTHOR_DOWN = 6     # Audiobooks: down to J.K. Rowling (the demo author: Harry Potter)
BOOK_DOWN = 3       # her books: down to The Goblet of Fire
# On a book's page the rows are: Play (or Resume at.. and Start again, once it has been listened to), Remove download,
# Chapters, Listen on. A press of CENTER on "Remove download" REMOVES THE BOOK from the iPod, so the count to Chapters
# must match the page as it is right now: look at book-page.png after a run, and after changing a book's progress.
CHAPTERS_DOWN = 2
MUSIC = (DOWN, DOWN, DOWN, CENTER)                              # main menu > Music
ARTISTS = MUSIC + (DOWN,) * 4 + (CENTER,)                       # > Artists
SHOTS = []          # filled by shot()


def sh(*a, out=False, check=True):
    r = subprocess.run([ADB, "-d"] + list(a), capture_output=True, text=True)
    if check and r.returncode: raise RuntimeError("adb %s: %s" % (" ".join(a), r.stderr.strip()))
    return r.stdout if out else None


def su(cmd, out=False):
    return sh("shell", "su -c '%s'" % cmd, out=out)


QUIET = "settings put system volume_music_speaker 0; settings put system volume_music_headset 0; settings put system volume_music_headphone 0; settings put system volume_music_bt_a2dp 0"


def loud_outputs():
    """Output devices whose music volume is not 0. A screenshot run must never be able to make a noise: refuse to start
    unless every output (speaker, headphones, Bluetooth) is at 0. Pressing the volume keys only changes the output in use,
    and the iPod can route to another one, so set them all and reboot (see QUIET and the README of this folder)."""
    line = [l for l in sh("shell", "dumpsys audio", out=True).splitlines() if "Current:" in l][0]
    loud = []
    for part in line.split("Current:")[1].split(","):
        k, _, v = part.strip().partition(":")
        if k.strip() in ("2", "4", "8", "80", "800", "1000", "2000", "4000") and v.strip() not in ("", "0"):
            loud.append(k.strip())
    return [d for d in loud if d in ("2", "4", "8", "80")]      # speaker, wired headset, headphone, Bluetooth A2DP


def playing_now():
    """True if the iPod is producing sound (silently here): an active track on the audio mixer."""
    out = sh("shell", "su -c 'dumpsys media.audio_flinger'", out=True).splitlines()
    for i, l in enumerate(out):
        if l.strip().endswith("active tracks"):
            rows = []
            for r in out[i + 2:]:
                if not r.strip(): break
                rows.append(r)
            return any(r.split()[0].isdigit() for r in rows if r.split())
    return False


def pause_if_playing():
    if playing_now():
        key(85, gap=0.8)


def nav(path):
    """The main menu's first move down from Continue takes one more press while bedtime is unlocked (that extra line)."""
    path = tuple(path)
    return ((DOWN,) + path) if UNLOCKED and path and path[0] == DOWN else path


def awake():
    return "Awake" in sh("shell", "dumpsys power | grep mWakefulness", out=True)


def key(*codes, gap=0.45):
    for c in codes:
        su("am broadcast -a me.cns.ab.KEY --ei code %d >/dev/null" % c)
        time.sleep(gap)


def ensure_awake():
    """Wake the screen if it slept and lift the button hold (three quick presses of one button)."""
    if not awake():
        su("input keyevent 26"); time.sleep(2)
    su("for i in 1 2 3; do am broadcast -a me.cns.ab.KEY --ei code 82 >/dev/null & done; wait")
    time.sleep(0.8)


def grab(raw, name):
    su("screencap -p /data/local/tmp/s.png; chmod 644 /data/local/tmp/s.png")
    dst = os.path.join(raw, name + ".png")
    sh("pull", "/data/local/tmp/s.png", dst)
    return dst


def blur(im, boxes):
    for b in boxes:
        reg = im.crop(b)
        small = reg.resize((max(1, reg.width // 12), max(1, reg.height // 12)), Image.BILINEAR)   # pixelate...
        im.paste(small.resize(reg.size, Image.NEAREST).filter(ImageFilter.GaussianBlur(6)), b)   # ...then blur
    return im


def shot(raw, name, title, caption, boxes=(), wait=0.9):
    time.sleep(wait)
    if not awake(): ensure_awake()
    src = grab(raw, name)
    im = blur(Image.open(src).convert("RGB"), boxes)
    fn = "%02d-%s.png" % (ORDER.index(name) + 1, name)
    for old in os.listdir(HERE):                     # a renumbered earlier file of this screenshot
        if old.endswith("-%s.png" % name) and old != fn: os.remove(os.path.join(HERE, old))
    im.save(os.path.join(HERE, fn))
    SHOTS.append({"file": fn, "name": name, "title": title, "caption": caption})
    print("  %s" % fn, flush=True)


STATE = "/data/data/%s/files/state.json" % APP
KEEP = ("targetId", "targetName", "lastKind", "lastBook", "lastTrack", "lastQueue", "lastIdx", "lastPos", "lastStream", "progress")


def read_state():
    su("cp %s /data/local/tmp/state.json; chmod 644 /data/local/tmp/state.json" % STATE)
    p = os.path.join(tempfile.gettempdir(), "y1-state.json")
    sh("pull", "/data/local/tmp/state.json", p)
    su("rm -f /data/local/tmp/state.json")
    return json.load(open(p))


def bedtime_guard(st):
    """Refuse to run in (or within 20 minutes of) the bedtime window: the app locks itself then, and the locked screen only
    takes the grown-up code, which this tool must never use."""
    b = st.get("bedtime", {})
    start, end = b.get("start"), b.get("end", 6 * 60 + 30)
    if start is None: return
    hhmm = sh("shell", "date +%H:%M", out=True).strip().split(":")
    now = int(hhmm[0]) * 60 + int(hhmm[1])
    ahead = (now + 20) % 1440
    inside = lambda t: (start <= t or t < end) if start > end else (start <= t < end)
    if inside(now) or inside(ahead):
        sys.exit("it is %s: too close to bedtime (%02d:%02d to %02d:%02d); run this after %02d:%02d or before %02d:%02d. "
                 "(`--only bedtime-live` takes the lock screen itself.)" % (":".join(hhmm), start // 60, start % 60, end // 60, end % 60,
                                                                           end // 60, end % 60, start // 60, (start - 20) % 60))


def restore_state(before):
    """Put back what a screenshot run can disturb (what Continue shows, where it plays, book progress). The app rewrites
    its state when it stops and restarts itself, so write the file and kill it in one go: it respawns with this state."""
    now = read_state()
    if all(now.get(k) == before.get(k) for k in KEEP): return
    merged = dict(now)
    for k in KEEP:
        if k in before: merged[k] = before[k]
        else: merged.pop(k, None)
    p = os.path.join(tempfile.gettempdir(), "y1-state-restore.json")
    json.dump(merged, open(p, "w"))
    sh("push", p, "/data/local/tmp/state.new")
    su("cat /data/local/tmp/state.new > %s; kill -9 $(pidof %s); rm -f /data/local/tmp/state.new" % (STATE, APP))   # in place: same owner and mode
    print("  (restored what Continue shows, where it plays, and book progress)")


def at_root(raw, ref):
    """True when the top bar looks like the main menu's (same title pixels)."""
    p = grab(raw, "_probe")
    a = Image.open(p).convert("L").crop((90, 0, 380, 44))
    b = Image.open(ref).convert("L").crop((90, 0, 380, 44))
    return ImageStat.Stat(ImageChops.difference(a, b)).mean[0] < 6


def reset(raw, ref):
    """Back out to the main menu with the first row highlighted."""
    ensure_awake()
    for _ in range(10):
        if at_root(raw, ref): break
        key(BACK, gap=0.6)
    key(*([UP] * 10), gap=0.22)
    time.sleep(0.5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", help="comma separated screenshot names to take")
    ap.add_argument("--ignore-bedtime", action="store_true", help="a grown-up has unlocked bedtime with the code: go ahead (the screens come out in the dark theme)")
    ap.add_argument("--raw", help="where raw (unblurred) captures go; default a temp directory")
    ap.add_argument("--result-down", type=int, default=0, help="DOWN presses from the first voice result to a streamed one (see voice())")
    a = ap.parse_args()
    global RESULT_DOWN
    RESULT_DOWN = a.result_down
    global UNLOCKED
    UNLOCKED = False        # screenshot mode (hook 989, below) hides the unlock line, so the menu rows are where they always are
    raw = a.raw or tempfile.mkdtemp(prefix="y1raw-")
    os.makedirs(raw, exist_ok=True)
    only = set(a.only.split(",")) if a.only else None
    started_asleep = not awake()
    su("svc power stayon true")                  # injected keys do not count as activity: the screen would sleep mid-run
    time.sleep(15)                               # let Wi-Fi and the music server connect: the first screens showed "Offline" otherwise
    if only and "bedtime-live" in only:      # it really is bedtime and the iPod is on its lock screen: take it as it is
        if started_asleep: su("input keyevent 26"); time.sleep(2)
        shot(raw, "bedtime-lock", "Bedtime", "When tonight's listening is used up, it says goodnight. Only a grown-up's code (or Wi-Fi settings) works.", wait=0.3)
        return finish(raw, a, started_asleep, None, leave=False)
    before_state = read_state()
    if not a.ignore_bedtime: bedtime_guard(before_state)
    ensure_awake()                               # (keys are ignored while the buttons are on hold, so this comes first)
    key(SHOTMODE, gap=0.5)                       # screenshot mode: no Home Assistant sync or minutes counted, no unlock lines (app restart = off)
    pause_if_playing()
    loud = loud_outputs()
    if loud:
        sys.exit("music volume is not 0 on output(s) %s: this tool starts playback while it works. Silence everything first:\n"
                 "  adb -d shell \"su -c '%s; reboot'\"\n(note the old values: settings get system volume_music_speaker etc., "
                 "and put them back, then reboot, when you are done)" % (", ".join(loud), QUIET))
    key(BACK, gap=0.5)
    # the reference for "at the main menu": reached by backing out until the title bar stops changing
    for _ in range(10): key(BACK, gap=0.5)
    key(*([UP] * 10), gap=0.2)
    ref = grab(raw, "_root")

    def want(n): return only is None or n in only

    def screen(name, title, caption, path, boxes=(), after=(), extra=None, wait=1.0):
        """Reset to the main menu, press `path`, screenshot; `extra` runs first, `after` presses are for screens past it."""
        if not want(name): return
        # A press can be lost. The remedy is never to press again where we are (on a book page that would start the
        # book playing): back out to the main menu and walk the whole path again.
        for attempt in range(3):
            reset(raw, ref)
            if not path: break
            path = nav(path)
            key(*path[:-1], gap=0.6)
            before = Image.open(grab(raw, "_before")).convert("L")
            key(path[-1], gap=0.6)
            time.sleep(1.0)
            now = Image.open(grab(raw, "_after")).convert("L")
            if path[-1] != CENTER or ImageStat.Stat(ImageChops.difference(before, now)).mean[0] > 2: break   # only a CENTER must change the screen
            print("  (%s: the last press was lost, starting this one again)" % name)
        else:
            sys.exit("%s: could not get there; stopping rather than guess" % name)
        if extra: extra()
        shot(raw, name, title, caption, boxes, wait)

    M = lambda *k: k

    def prepare():
        """Continue should be a song (Taylor Swift), not whatever was playing: shuffle her songs, silently, then pause."""
        if only is not None and "prepare" not in only: return
        reset(raw, ref)
        key(*nav(ARTISTS), *([DOWN] * ARTIST_DOWN), CENTER, CENTER, gap=0.7)   # artist page > All songs (a list of songs)
        key(CENTER, gap=1.5)                                               # the first row is "Shuffle all"
        time.sleep(2.5)
        key(85, gap=0.6)                                                   # play/pause: paused, the song stays as Continue
        reset(raw, ref)
    prepare()
    # ---- the main menu
    screen("menu", "The main menu", "Continue where you stopped, your audiobooks, your music, speakers, settings, help.", M())
    screen("menu-more", "The rest of the menu", "Scroll down for Listen on, Settings, Status and Help.", M(*([DOWN] * 8)))
    # ---- now playing (a preview of the highlighted row: nothing starts playing)
    screen("now-playing", "Now playing", "The cover, the title, who is singing, and where it is playing. Turn the wheel for volume.", M(PREVIEW), wait=1.4)
    # ---- audiobooks
    screen("in-progress", "In progress", "Books you have started, with how far through each one you are.", M(DOWN, CENTER))
    # (book-now-playing is taken at the end: it really plays the book for a moment, silently, and the Continue row is reset after)
    screen("authors", "Audiobooks by author", "Your audiobooks, grouped by author.", M(DOWN, DOWN, CENTER, *([DOWN] * AUTHOR_DOWN)))
    screen("books", "One author's books", "Each book shows its length and whether it is saved on the iPod to play offline.", M(DOWN, DOWN, CENTER, *([DOWN] * AUTHOR_DOWN), CENTER, *([DOWN] * BOOK_DOWN)))
    screen("book-page", "A book", "Play it, remove the download, jump to a chapter, or choose where it plays.", M(DOWN, DOWN, CENTER, *([DOWN] * AUTHOR_DOWN), CENTER, *([DOWN] * BOOK_DOWN), CENTER))
    screen("chapters", "Chapters", "Every chapter, with the one you are in marked.", M(DOWN, DOWN, CENTER, *([DOWN] * AUTHOR_DOWN), CENTER, *([DOWN] * BOOK_DOWN), CENTER, *([DOWN] * CHAPTERS_DOWN), CENTER))
    # ---- music
    screen("music", "Music", "Shuffle everything, search, favourites, new songs, artists and more.", M(DOWN, DOWN, DOWN, CENTER))
    screen("music-more", "More ways into your music", "Artists, genres, albums and playlists.", M(DOWN, DOWN, DOWN, CENTER, *([DOWN] * 8)))
    screen("favourites", "Favourites", "Hold the middle button on a song to add it here. Hold it again to take it off.", M(DOWN, DOWN, DOWN, CENTER, DOWN, DOWN, CENTER))
    screen("new-songs", "New songs", "Songs added in the last month, newest first.", M(DOWN, DOWN, DOWN, CENTER, DOWN, DOWN, DOWN, CENTER))
    screen("artists", "Artists", "Every artist in your music.", M(*ARTISTS, *([DOWN] * ARTIST_DOWN)))
    screen("artist", "An artist", "Artist, album, song: the albums are a row of covers, with All songs first. Turn the wheel to move along them.", M(*ARTISTS, *([DOWN] * ARTIST_DOWN), CENTER), wait=1.4)
    screen("artist-songs", "All of an artist's songs", "Choose All songs, then Shuffle to hear everything by them in a surprise order.", M(*ARTISTS, *([DOWN] * ARTIST_DOWN), CENTER, CENTER))
    screen("album-tracks", "An album", "Choose an album to see its songs, and Shuffle to mix just that album.", M(*ARTISTS, *([DOWN] * ARTIST_DOWN), CENTER, DOWN, CENTER))
    screen("search", "Search", "Spell a song, album or artist with the wheel; it looks on the iPod and in Apple Music.", M(DOWN, DOWN, DOWN, CENTER, DOWN, CENTER))
    # ---- speakers, settings, status, help
    screen("listen-on", "Listen on", "Move what you are listening to to any speaker in the house, and back, from where you stopped.", M(*([DOWN] * 5), CENTER))
    screen("settings", "Settings", "Wi-Fi, Bluetooth headphones, armed for gifting, Find My, brightness, and more as you scroll.", M(*([DOWN] * 6), CENTER), boxes=[SSID_SUB])
    screen("settings-more", "More settings", "Hold after, Unlock, Volume limit and a few others ask for the grown-up code when you select them.", M(*([DOWN] * 6), CENTER, *([DOWN] * 9)))
    screen("wifi", "Wi-Fi", "Pick a network and type its password with the wheel. Joining needs no code; forgetting one does.",
           M(*([DOWN] * 6), CENTER, CENTER), boxes=SSID_ROWS, wait=2.5)
    screen("bluetooth", "Bluetooth headphones", "Pair headphones and the sound moves to them.", M(*([DOWN] * 6), CENTER, DOWN, CENTER), wait=1.5)
    screen("status", "Status", "Whether it is connected, and what is still to sync.", M(*([DOWN] * 7), CENTER))
    screen("help", "Help", "The last row of the menu: a video that explains the iPod, in chapters.", M(*([DOWN] * 8), CENTER))
    # ---- the Help video (paused a moment after it starts, so it is quiet)
    if want("help-video"):
        reset(raw, ref)
        su("am start -n %s/.HelpActivity --ei chapter 1" % APP)
        time.sleep(1.8); key(CENTER, gap=0.3)
        shot(raw, "help-video", "The Help video", "It plays chapter after chapter; the wheel or Next and Previous jump between them, the middle button pauses.", wait=0.6)
        key(BACK, gap=0.8)
    # ---- the lock screens
    pause_if_playing()
    if want("hold"):
        reset(raw, ref); key(HOLD, gap=1.0)
        shot(raw, "hold", "On hold", "Like the hold switch on an iPod: press the same button three times quickly to wake it up.", [CONTACT])
        ensure_awake()
    if want("bedtime-dark"):
        pause_if_playing(); reset(raw, ref); key(DARK, gap=1.0)
        shot(raw, "bedtime-dark", "Bedtime colours", "In the bedtime window the screen goes dark, like Night Shift.")
        key(DARK, gap=0.8)
    if want("bedtime-lock"):
        reset(raw, ref); key(LOCKPREVIEW, gap=1.0)
        shot(raw, "bedtime-lock", "Bedtime", "When tonight's listening is used up, it says goodnight. Only a grown-up's code (or Wi-Fi settings) works.")
        key(LOCKPREVIEW, gap=0.8)
    # ---- voice search
    voice(raw, ref, want)
    # ---- an audiobook playing (silently): the real Now Playing of a book; then Continue goes back to a song
    if want("book-now-playing"):
        reset(raw, ref)
        key(*nav((DOWN, DOWN, CENTER)), *([DOWN] * AUTHOR_DOWN), CENTER, *([DOWN] * BOOK_DOWN), CENTER, gap=0.6)
        key(CENTER, gap=0.6)                                     # Play (the first row): it opens Now Playing
        shot(raw, "book-now-playing", "An audiobook", "An audiobook shows its chapter. Press play, and it carries on from where you stopped.", wait=2.5)
        key(85, gap=0.6)                                         # pause
        if only is None or "prepare" in only: prepare()
    # prepare() made Continue a song on purpose: that is the state to leave (restoring would bring back the old Continue)
    finish(raw, a, started_asleep, ref, before_state=None if only is None or "prepare" in only else before_state)


def finish(raw, a, started_asleep, ref, leave=True, before_state=None):
    # ---- leave it as found
    if leave:
        reset(raw, ref)
    if before_state: restore_state(before_state)
    pause_if_playing()                           # whatever started during the run (silently) is stopped
    key(SHOTMODE_OFF, gap=0.5)                   # back to normal: syncing and counting again
    su("svc power stayon false")
    if started_asleep:
        key(HOLD, gap=0.8)
        su("input keyevent 26")
    mf = os.path.join(HERE, "manifest.json")
    have = {m["name"]: m for m in (json.load(open(mf)) if os.path.exists(mf) else [])}
    have.update({m["name"]: m for m in SHOTS})
    for m in have.values(): m["file"] = "%02d-%s.png" % (ORDER.index(m["name"]) + 1, m["name"])
    with open(mf, "w") as f:
        json.dump(sorted(have.values(), key=lambda m: ORDER.index(m["name"])), f, indent=1)
    shutil.rmtree(raw, ignore_errors=True) if not a.raw else None
    print("done: %d screenshots in %s" % (len(SHOTS), HERE))


def voice(raw, ref, want):
    names = ("voice-listening", "voice-thinking", "voice-results", "voice-result-page")
    if not any(want(n) for n in names): return
    clip = os.path.join(raw, "give.pcm")
    try:
        subprocess.run(["say", "-v", "Daniel", "-o", os.path.join(raw, "g.aiff"), "give you better"], check=True)
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", os.path.join(raw, "g.aiff"), os.path.join(raw, "g.wav")], check=True)
        open(clip, "wb").write(open(os.path.join(raw, "g.wav"), "rb").read()[44:])
        sh("push", clip, "/data/local/tmp/voice-test.pcm", out=True)
        su("cp /data/local/tmp/voice-test.pcm /sdcard/voice-test.pcm")
    except (OSError, subprocess.CalledProcessError):
        print("  (no `say`/afconvert: skipping the voice results)"); clip = None
    if want("voice-listening"):
        reset(raw, ref); key(*nav((DOWN, DOWN)), gap=0.4)      # on Audiobooks: a row where holding the middle button listens
        # a real hold of the centre key (mtk-kpd scancode 232), as a finger would
        su("sendevent /dev/input/event0 1 232 1; sendevent /dev/input/event0 0 0 0")
        shot(raw, "voice-listening", "Listening", "Hold the middle button and say what you want. It pauses the music, and the wave moves as you speak.", wait=1.6)
        su("sendevent /dev/input/event0 1 232 0; sendevent /dev/input/event0 0 0 0")
        time.sleep(2.5)
    if clip and any(want(n) for n in names[1:]):
        reset(raw, ref); key(*nav((DOWN, DOWN)), gap=0.4)
        key(VOICETEST, gap=0.1)
        shot(raw, "voice-thinking", "Working out what you said", "Let go and it works out the words, on the iPod itself or through Home Assistant.", wait=0.9)
        for _ in range(40):                              # until the results screen is up
            time.sleep(1.0)
            if not at_root(raw, ref): break
        time.sleep(7)                                    # the songs on the iPod come first, Apple Music a moment later
        shot(raw, "voice-results", "What it found", "Songs on the iPod come first; below them, songs it can stream if it is on Wi-Fi.", wait=0.2)
        # Pressing the middle button on a result under "On your iPod" PLAYS it, so open a result only when told which row is
        # a streamed one (look at voice-results.png, count the DOWN presses from the highlight): --result-down N
        if RESULT_DOWN and want("voice-result-page"):
            key(*([DOWN] * RESULT_DOWN), gap=0.5)
            key(CENTER, gap=1.5)
            shot(raw, "voice-result-page", "One result", "Play it, or add it to the library so it syncs to the iPod to listen to anywhere.", wait=0.8)
            key(BACK, gap=0.7)
        key(BACK, gap=0.7)


if __name__ == "__main__":
    main()
