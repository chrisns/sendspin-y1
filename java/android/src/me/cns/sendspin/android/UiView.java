package me.cns.sendspin.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.View;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The whole interface, drawn by hand: the iPod classic's click-wheel lists with today's Apple finish (Inter type,
 * an animated gradient highlight, slide transitions, artwork-tinted Now Playing, a night theme at bedtime). The wheel
 * moves the highlight (or volume on Now Playing), the centre button selects or plays/pauses, Menu goes back.
 */
public final class UiView extends View {

    static final class Row {
        String text, sub;
        Runnable act;
        boolean header, nav;       // nav: opens another screen (drawn with a chevron)
        Engine.Book book;
        MusicLibrary.Track track;  // artwork (an artist or album card uses one of its songs' covers)
        boolean song;              // the row IS this song: hold centre favourites it, the heart shows
        boolean noThumb;           // a song row drawn without artwork (inside an album)
        Row(String t, String s, Runnable a) { text = t; sub = s; act = a; }
    }

    abstract class Screen {
        String title;
        int sel, top;
        boolean flow;          // draw as a cover flow instead of a list
        float flowPos = -1;
        float scrollY = -1, selY;  // animated list position and highlight
        boolean allowedLocked;     // reachable from the bedtime lock (Wi-Fi)
        private List<Row> cache;
        private long cacheAt;
        abstract List<Row> rows();

        /** Rows are rebuilt at most twice a second: building them is the slow part of a wheel click. */
        List<Row> get() {
            long now = SystemClock.uptimeMillis();
            if (cache == null || now - cacheAt > 2500) { cache = rows(); cacheAt = now; }
            return cache;
        }

        void refresh() { cache = null; }

        /** The rows as drawn: keys act on what is on screen, never on a list rebuilt (reordered) by the key press. */
        List<Row> shown() { return cache != null ? cache : get(); }
    }

    private final Engine eng;
    private final List<Screen> stack = new ArrayList<Screen>();
    private boolean nowPlaying;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Map<String, Bitmap> covers = new HashMap<String, Bitmap>();
    private final Set<String> coverBusy = new HashSet<String>();
    private boolean attached;
    public volatile String lastKey = "";
    private final Clicker clicker;
    private long lastStepAt, lastBackAt;
    private int lastDir, streak;
    public Runnable onRootBack;
    private final Map<String, String> ellipsized = new HashMap<String, String>();
    private final Map<String, Bitmap> fileCovers = new HashMap<String, Bitmap>();

    public UiView(Context c, Engine e) {
        super(c);
        eng = e;
        loadFonts(c);
        setFocusable(true);
        stack.add(home());
        clicker = new Clicker(c);
    }

    private void tick() { clicker.step(eng.clickMode(), eng.clickLevel()); }

    private void clunk() { clicker.press(eng.clickMode(), eng.clickLevel()); }

    // ------------------------------------------------------------------ screens

    private Screen top() { return stack.get(stack.size() - 1); }

    private void push(Screen s) {
        if (!nowPlaying && !stack.isEmpty()) { transFrom = top(); transDir = 1; transAt = SystemClock.uptimeMillis(); }
        stack.add(s);
        nowPlaying = false;
        invalidate();
    }

    private Row nav(Row r) { r.nav = true; return r; }

    private Screen home() {
        return new Screen() {
            { title = eng.id.title(); }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                final Engine.Book cur = eng.current;
                if (cur != null) {
                    long left = Math.max(0, eng.durMsFor(cur) - eng.progressMsOrLive(cur));
                    Row cont = new Row((eng.playing ? "Now playing: " : "Continue: ") + (eng.musicMode ? eng.nowTitle : cur.name),
                        fmtLong(left) + " left · " + eng.targetName, new Runnable() { public void run() {
                            if (!eng.playing || eng.current != cur) {
                                if (cur.kind.equals("audiobook")) eng.play(cur);
                                else if (eng.hasQueue()) eng.resume();   // the queue (also after a restart), where it left off, here or on the speaker
                                else eng.playMusic(cur);
                            }
                            nowPlaying = true;
                        } });
                    cont.book = cur;
                    r.add(cont);
                }
                long tnow = eng.clock.now();
                if (eng.bedtime.inWindow(tnow)) {
                    if (eng.shotMode) { }       // screenshot mode: no unlock line, so the pictures do not show it
                    else if (eng.bedtime.unlocked(tnow)) r.add(emptyRow("Grown-up unlock until " + new java.text.SimpleDateFormat("H:mm", java.util.Locale.UK).format(new java.util.Date(eng.bedtime.unlockUntil))));
                    else r.add(new Row("Bedtime listening: " + eng.bedtime.remaining(tnow) + " min left", "shared with your bedroom speaker", null));
                }
                List<Engine.Book> ip = eng.inProgress();
                if (ip.size() > (cur != null && ip.contains(cur) ? 1 : 0))
                    r.add(nav(new Row("In progress", ip.size() + " books", new Runnable() { public void run() { push(bookList("In progress", eng.inProgress())); } })));
                r.add(nav(new Row("Audiobooks", eng.books.size() + " books", new Runnable() { public void run() { push(authors()); } })));
                r.add(nav(new Row("Music", (eng.musicLib.tracks().isEmpty() ? "playlists and sounds" : eng.musicLib.tracks().size() + " songs \u00b7 artists, genres, shuffle"), new Runnable() { public void run() { push(musicHome()); } })));
                r.add(new Row(eng.storageTitle(), eng.syncSummary(), new Runnable() { public void run() { eng.forceSync(); } }));
                r.add(nav(new Row("Listen on", eng.targetName, new Runnable() { public void run() { push(speakers()); } })));
                r.add(nav(new Row("Settings", "Wi-Fi, Bluetooth, screen, sleep timer, volume limit", new Runnable() { public void run() { push(settings()); } })));
                r.add(nav(new Row("Status", eng.online ? "Connected" : "Offline", new Runnable() { public void run() { push(status()); } })));
                if (solarInstalled())
                    r.add(new Row("Open Solar", "radio, settings and other apps", new Runnable() { public void run() { openSolar(); } }));
                r.add(nav(new Row("Help", Help.available() ? "how to use your iPod" : "not on this iPod yet", new Runnable() { public void run() { push(helpScreen()); } })));
                return r;
            }
        };
    }

    private Screen authors() {
        return new Screen() {
            { title = "Audiobooks"; }
            List<Row> rows() {
                Map<String, List<Engine.Book>> by = new LinkedHashMap<String, List<Engine.Book>>();
                for (Engine.Book b : eng.books) {
                    List<Engine.Book> l = by.get(b.author);
                    if (l == null) { l = new ArrayList<Engine.Book>(); by.put(b.author, l); }
                    l.add(b);
                }
                List<Row> r = new ArrayList<Row>();
                for (final Map.Entry<String, List<Engine.Book>> e : by.entrySet()) {
                    r.add(nav(new Row(e.getKey(), e.getValue().size() + (e.getValue().size() == 1 ? " book" : " books"),
                        new Runnable() { public void run() { push(bookList(e.getKey(), e.getValue())); } })));
                }
                if (r.isEmpty()) r.add(emptyRow(eng.configured() ? "Loading library..." : "Not set up yet"));
                return r;
            }
        };
    }

    private Row emptyRow(String t) {
        Row r = new Row(t, null, null);
        r.header = true;
        return r;
    }

    private Screen bookList(final String listTitle, final List<Engine.Book> list) {
        return new Screen() {
            { title = listTitle; }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                for (final Engine.Book b : list) {
                    long pos = b.kind.equals("audiobook") ? eng.progressMs(b.id) : 0;
                    String sub;
                    Runnable act;
                    if (b.kind.equals("audiobook")) {
                        sub = fmtLong(b.durS * 1000);
                        if (eng.isFinished(b.id)) sub += " · finished";
                        else if (pos > 0 && b.durS > 0) sub += " · " + (pos * 100 / (b.durS * 1000)) + "%";
                        if (eng.isDownloaded(b)) sub += " · offline";
                        act = new Runnable() { public void run() { push(bookPage(b)); } };
                    } else {
                        sub = b.author + " · plays on " + eng.targetName;
                        act = new Runnable() { public void run() { eng.playMusic(b); nowPlaying = true; } };
                    }
                    Row row = new Row(b.name, sub, act);
                    row.book = b;
                    row.nav = b.kind.equals("audiobook");
                    r.add(row);
                }
                if (r.isEmpty()) r.add(emptyRow("Nothing here yet"));
                return r;
            }
        };
    }

    private Screen musicHome() {
        return new Screen() {
            { title = "Music"; }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                final MusicIndex ix = eng.musicIndex();
                if (!ix.all.isEmpty()) {
                    r.add(new Row("Shuffle all music", ix.all.size() + " songs \u00b7 on " + eng.targetName, new Runnable() { public void run() {
                        eng.playTracks(ix.all, 0, true); nowPlaying = true; } }));
                    r.add(nav(new Row("Search", "find songs, albums and artists to stream or add", new Runnable() { public void run() {
                        startSearch(); } })));
                    final List<MusicLibrary.Track> fav = eng.favs.tracks(eng.musicLib);
                    r.add(nav(new Row("Favourites", fav.isEmpty() ? "hold the centre button on a song to add it"
                        : fav.size() + (fav.size() == 1 ? " song" : " songs"), new Runnable() { public void run() {
                        push(trackListScreen("Favourites", new Tracks() { public List<MusicLibrary.Track> get() {
                            return eng.favs.tracks(eng.musicLib); } }, false)); } })));
                    final List<MusicLibrary.Track> fresh = eng.musicLib.newSongs(eng.clock.now());
                    if (!fresh.isEmpty())
                        r.add(nav(new Row("New songs", fresh.size() + (fresh.size() == 1 ? " song" : " songs") + " in the last month",
                            new Runnable() { public void run() { push(trackListScreen("New songs", eng.musicLib.newSongs(eng.clock.now()), false)); } })));
                    r.add(nav(new Row("Artists", ix.artists.size() + (ix.artists.size() == 1 ? " artist" : " artists"), new Runnable() { public void run() { push(artistList()); } })));
                    if (!ix.genres.isEmpty())
                        r.add(nav(new Row("Genres", ix.genres.size() + (ix.genres.size() == 1 ? " genre" : " genres"), new Runnable() { public void run() { push(genreList()); } })));
                }
                for (final String k : new String[] { "Playlists", "Albums", "Radio", "Sounds" }) {
                    final List<Engine.Book> l = eng.musicList(k);
                    if (l.isEmpty()) continue;
                    r.add(nav(new Row(k, l.size() + (l.size() == 1 ? " item" : " items") + (k.equals("Playlists") ? " \u00b7 streamed" : ""),
                        new Runnable() { public void run() { push(bookList(k, eng.musicList(k))); } })));
                }
                if (r.isEmpty()) r.add(emptyRow(eng.online ? "No music in Music Assistant yet" : "Connect to Wi-Fi for music"));
                return r;
            }
        };
    }

    private Screen artistList() {
        return new Screen() {
            { title = "Artists"; }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                for (final MusicIndex.Artist ar : eng.musicIndex().artists) {
                    int n = ar.allTracks().size();
                    Row row = nav(new Row(ar.name, ar.albums.size() + (ar.albums.size() == 1 ? " album" : " albums") + " \u00b7 " + n + " songs",
                        new Runnable() { public void run() { push(artistFlow(ar)); } }));
                    row.track = ar.coverTrack();
                    r.add(row);
                }
                if (r.isEmpty()) r.add(emptyRow("Nothing here yet"));
                return r;
            }
        };
    }

    /** An artist's albums as a cover flow, newest first, with "All songs" as the first card. */
    private Screen artistFlow(final MusicIndex.Artist ar) {
        return new Screen() {
            { title = ar.name; flow = true; }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                final List<MusicLibrary.Track> all = ar.allTracks();
                Row first = nav(new Row("All songs", all.size() + " songs", new Runnable() { public void run() {
                    push(trackListScreen(ar.name, all, false)); } }));
                first.track = ar.coverTrack();
                r.add(first);
                for (final MusicIndex.Album al : ar.albums) {
                    String y = al.year();
                    Row row = nav(new Row(al.name, (y.length() > 0 ? y + " \u00b7 " : "") + al.tracks.size() + (al.tracks.size() == 1 ? " song" : " songs"),
                        new Runnable() { public void run() { push(trackListScreen(al.name, al.tracks, true)); } }));
                    row.track = al.coverTrack();
                    r.add(row);
                }
                return r;
            }
        };
    }

    private Screen genreList() {
        return new Screen() {
            { title = "Genres"; }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                for (final java.util.Map.Entry<String, List<MusicLibrary.Track>> e : eng.musicIndex().genres.entrySet()) {
                    Row row = nav(new Row(e.getKey(), e.getValue().size() + " songs", new Runnable() { public void run() {
                        push(trackListScreen(e.getKey(), e.getValue(), false)); } }));
                    row.track = e.getValue().get(0);
                    r.add(row);
                }
                if (r.isEmpty()) r.add(emptyRow("Nothing here yet"));
                return r;
            }
        };
    }

    /** Tracks in the order given (album order for an album). Picking one plays on from it, in that order. */
    interface Tracks { List<MusicLibrary.Track> get(); }

    private Screen trackListScreen(final String name, final List<MusicLibrary.Track> list, final boolean numbered) {
        return trackListScreen(name, new Tracks() { public List<MusicLibrary.Track> get() { return list; } }, numbered);
    }

    /** A list of songs read afresh on each rebuild (Favourites changes while it is open). */
    private Screen trackListScreen(final String name, final Tracks src, final boolean numbered) {
        return new Screen() {
            { title = name; }
            List<Row> rows() {
                final List<MusicLibrary.Track> list = src.get();
                List<Row> r = new ArrayList<Row>();
                if (list.size() > 1) r.add(new Row("Shuffle", list.size() + " songs \u00b7 on " + eng.targetName, new Runnable() { public void run() {
                    eng.playTracks(list, 0, true); nowPlaying = true; } }));
                for (int i = 0; i < list.size(); i++) {
                    final int at = i;
                    final MusicLibrary.Track t = list.get(i);
                    Row row = new Row(numbered ? t.trackNo + ".  " + t.title : t.title, numbered ? fmtClock(t.durS * 1000) : t.artist,
                        new Runnable() { public void run() { eng.playTracks(list, at, false); nowPlaying = true; } });
                    row.track = t;
                    row.song = true;
                    row.noThumb = numbered;
                    r.add(row);
                }
                return r;
            }
        };
    }

    private Screen trackList() {
        return new Screen() {
            { title = eng.musicLib.playlistName; }
            List<Row> rows() {
                final List<MusicLibrary.Track> ts = eng.musicLib.tracks();
                List<Row> r = new ArrayList<Row>();
                if (!ts.isEmpty()) r.add(new Row("Shuffle all", ts.size() + " tracks · on " + eng.targetName, new Runnable() { public void run() {
                    eng.playTracks(ts, 0, true); nowPlaying = true; } }));
                for (int i = 0; i < ts.size(); i++) {
                    final int at = i;
                    final MusicLibrary.Track t = ts.get(i);
                    Row row = new Row(t.title, t.artist, new Runnable() { public void run() { eng.playTracks(ts, at, false); nowPlaying = true; } });
                    row.track = t;
                    row.song = true;
                    r.add(row);
                }
                if (ts.isEmpty()) r.add(emptyRow(eng.musicLib.serverPending > 0 ? "Music is on its way: syncing on Wi-Fi" : "Nothing here yet"));
                return r;
            }
        };
    }

    // ------------------------------------------------------------------ search: stream from Apple Music, add to the library

    /** A list filled in the background (search results, an album's songs): shows "Searching..." until it arrives. */
    abstract class LoadingScreen extends Screen {
        volatile List<Row> loaded;
        volatile String failed;
        abstract List<Row> load() throws Exception;

        void start() {
            new Thread(new Runnable() { public void run() {
                try { loaded = load(); } catch (Exception e) { failed = String.valueOf(e.getMessage()); }
                post(new Runnable() { public void run() {
                    refresh();
                    List<Row> r = get();
                    if (sel >= r.size() || r.get(sel).header)                     // past the "Songs" heading
                        for (int i = 0; i < r.size(); i++) if (!r.get(i).header) { sel = i; break; }
                    invalidate();
                } });
            } }, "search").start();
        }

        List<Row> rows() {
            List<Row> r = new ArrayList<Row>();
            if (failed != null) r.add(emptyRow("Couldn\u2019t search: " + failed));
            else if (loaded == null) r.add(emptyRow("Searching\u2026"));
            else if (loaded.isEmpty()) r.add(emptyRow("Nothing found"));
            else r.addAll(loaded);
            return r;
        }
    }

    private void startSearch() {
        startEntry("Search Apple Music", new Entry.Done() { public void done(final String q) {
            if (q.trim().length() == 0) return;
            LoadingScreen s = new LoadingScreen() {
                List<Row> load() throws Exception {
                    List<List<Engine.Found>> res = eng.search(q.trim());
                    List<Row> r = new ArrayList<Row>();
                    String[] heads = { "Songs", "Albums", "Artists" };
                    for (int k = 0; k < 3; k++) {
                        if (res.get(k).isEmpty()) continue;
                        r.add(emptyRow(heads[k]));
                        for (Engine.Found f : res.get(k)) r.add(foundRow(f));
                    }
                    return r;
                }
            };
            s.title = q.trim();
            s.start();
            push(s);
        } });
        entry.sets = new String[] { " abcdefghijklmnopqrstuvwxyz", "0123456789", "'&!?-.,()" };
    }

    // ---- voice search: hold the centre button (anywhere but on a song), speak, let go

    private volatile int voiceState;   // 0 off, 1 listening, 2 working out what was said
    private int voiceGen;              // a cancelled search's answer is dropped

    /** Hold centre: start listening (playback pauses); false where voice search does not apply. */
    public boolean startVoice() {
        if (voiceState != 0 || entry != null || lockShowing() || eng.hold.held(SystemClock.uptimeMillis())) return false;
        boolean ok;
        try { ok = eng.startListening(); } catch (RuntimeException e) { ok = false; eng.cancelListening(); }
        if (!ok) { eng.notice("The microphone isn\u2019t working"); return true; }
        voiceState = 1;
        clunk();
        invalidate();
        return true;
    }

    public boolean listening() { return voiceState == 1; }

    /** Centre let go: work out what was said, then search for it. */
    public void stopVoice() {
        if (voiceState != 1) return;
        final byte[] pcm;
        try { pcm = eng.stopListening(); } catch (RuntimeException e) { cancelVoice(); return; }
        if (!Voice.anySpeech(pcm)) { voiceState = 0; eng.notice("Didn\u2019t hear anything: hold the centre button and speak"); invalidate(); return; }
        recognise(pcm);
    }

    /** Whatever voice is doing stops now (the activity paused, or a key-up never came): mic, playback and state recover. */
    public void cancelVoice() {
        if (voiceState == 0) return;
        voiceGen++;
        voiceState = 0;
        eng.cancelListening();
        invalidate();
    }

    private void recognise(final byte[] pcm) {
        voiceState = 2;
        final int gen = ++voiceGen;
        invalidate();
        new Thread(new Runnable() { public void run() {
            String t = null, err = null;
            try { t = eng.transcribe(pcm); } catch (Throwable e) { err = "Voice search failed"; }
            final String text = t, problem = err;
            post(new Runnable() { public void run() {
                if (gen != voiceGen) return;
                voiceState = 0;
                if (problem != null) eng.notice(problem);
                else if (text == null || text.length() == 0) eng.notice("Didn\u2019t catch that: try again");
                else { nowPlaying = false; push(voiceResults(text)); }
                invalidate();
            } });
        } }, "voice-stt").start();
    }

    /** What was said: songs on the iPod first, then (on Wi-Fi) what can be streamed. */
    private Screen voiceResults(final String q) {
        final List<MusicLibrary.Track> local = eng.localSearch(q);
        final boolean stream = eng.canStream();
        LoadingScreen s = new LoadingScreen() {
            List<Row> load() throws Exception {
                List<List<Engine.Found>> res = eng.search(q);
                List<Row> r = new ArrayList<Row>();
                String[] heads = { "Songs to stream", "Albums", "Artists" };
                for (int k = 0; k < 3; k++) {
                    List<Row> part = new ArrayList<Row>();
                    for (Engine.Found f : res.get(k)) if (k != 0 || !eng.onIpod(f)) part.add(foundRow(f));   // on the iPod: listed above
                    if (part.isEmpty()) continue;
                    r.add(emptyRow(heads[k]));
                    r.addAll(part);
                }
                return r;
            }

            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                if (!local.isEmpty()) {
                    r.add(emptyRow("On your iPod"));
                    for (int i = 0; i < local.size(); i++) {
                        final int at = i;
                        MusicLibrary.Track t = local.get(i);
                        Row row = new Row(t.title, t.artist, new Runnable() { public void run() { eng.playTracks(local, at, false); nowPlaying = true; } });
                        row.track = t;
                        row.song = true;
                        r.add(row);
                    }
                }
                if (!stream) r.add(emptyRow(local.isEmpty() ? "Nothing on your iPod: connect to Wi-Fi to search Apple Music" : "Connect to Wi-Fi to find more"));
                else if (failed != null) r.add(emptyRow("Couldn\u2019t search Apple Music: " + failed));
                else if (loaded == null) r.add(emptyRow("Searching Apple Music\u2026"));
                else if (loaded.isEmpty()) { if (local.isEmpty()) r.add(emptyRow("Nothing found")); }
                else r.addAll(loaded);
                return r;
            }
        };
        s.title = "\u201c" + q + "\u201d";
        for (int i = 0; i < s.get().size(); i++) if (!s.get().get(i).header) { s.sel = i; break; }
        if (stream) s.start();
        return s;
    }

    /** "Go ahead, I'm listening...": the voice as a live waveform; a gentle ripple while it is worked out. */
    private boolean drawListening(Canvas c, int w, int h, float u) {
        fill(0xFF0B0B10);
        c.drawRect(0, 0, w, h, p);
        boolean working = voiceState == 2;
        long now = SystemClock.uptimeMillis();
        textCentred(c, working ? "One moment\u2026" : "Go ahead, I\u2019m listening\u2026", w / 2f, h * 0.2f, u * 1.15f, WHITE, SEMI, w - 2 * u);
        float[] lv = eng.voice.levels;
        int n = lv.length, at = eng.voice.levelAt;
        float slot = (w - 2 * u) / n, bw = slot * 0.62f, mid = h * 0.5f, maxH = h * 0.2f, r = bw / 2;
        fill(working ? 0xFF6E6E78 : 0xFF0A84FF);
        for (int i = 0; i < n; i++) {
            float v = working ? 0.12f + 0.1f * (float) (1 + Math.sin(now / 160.0 - i * 0.5))
                : lv[((at - n + i) % n + n) % n];
            float bh = Math.max(bw, v * maxH);
            float x = u + i * slot + (slot - bw) / 2;
            rect.set(x, mid - bh, x + bw, mid + bh);
            c.drawRoundRect(rect, r, r, p);
        }
        textCentred(c, working ? "Working out what you said" : (eng.voice.recording ? "Let go when you\u2019re done" : "That\u2019s long enough: let go"),
            w / 2f, h * 0.84f, u * 0.8f, 0xFF8E8E93, REG, w - 2 * u);
        return true;
    }

    private Row foundRow(final Engine.Found f) {
        final boolean here = eng.onIpod(f);
        String sub = f.kind.equals("artist") ? "artist" : (f.sub == null ? "" : f.sub) + (here ? " \u00b7 on your iPod" : f.inLibrary ? " \u00b7 in your library" : "");
        Row r = new Row(f.name, sub, new Runnable() { public void run() { push(foundPage(f)); } });
        r.nav = true;
        r.book = f.asBook();      // its artwork
        return r;
    }

    /** One song, album or artist: play it, add it to the library, look inside. */
    private Screen foundPage(final Engine.Found f) {
        return new Screen() {
            { title = f.name; }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                if (!f.kind.equals("artist"))
                    r.add(new Row("Play", (f.kind.equals("album") ? "the whole album" : f.sub) + " \u00b7 on " + eng.targetName, new Runnable() { public void run() {
                        eng.playFound(f); nowPlaying = true; } }));
                final MusicLibrary.Track mine = eng.localCopy(f);
                if (mine != null) {
                    r.add(emptyRow("Already on your iPod"));
                    r.add(new Row("Remove from my iPod", "and from your Apple Music library", new Runnable() { public void run() {
                        eng.removeFromIpod(mine); back(); } }));
                }
                else if (!f.kind.equals("artist"))
                    r.add(f.inLibrary && !eng.onIpod(f) ? new Row("Add to my iPod", "it is in your library: sync it here again", new Runnable() { public void run() {
                            eng.addToLibrary(f); refresh(); } })
                        : new Row("Add to my library", "it then syncs to the iPod for listening anywhere", new Runnable() { public void run() {
                            eng.addToLibrary(f); refresh(); } }));
                if (f.kind.equals("album"))
                    r.add(nav(new Row("Songs", null, new Runnable() { public void run() { push(children(f, "tracks", "Songs")); } })));
                if (f.kind.equals("artist")) {
                    r.add(nav(new Row("Top songs", null, new Runnable() { public void run() { push(children(f, "tracks", "Top songs")); } })));
                    r.add(nav(new Row("Albums", null, new Runnable() { public void run() { push(children(f, "albums", "Albums")); } })));
                }
                return r;
            }
        };
    }

    private Screen children(final Engine.Found f, final String what, String name) {
        LoadingScreen s = new LoadingScreen() {
            List<Row> load() throws Exception {
                List<Row> r = new ArrayList<Row>();
                for (Engine.Found c : eng.children(f, what)) r.add(foundRow(c));
                return r;
            }
        };
        s.title = name;
        s.start();
        return s;
    }

    // ------------------------------------------------------------------ settings (what Solar used to provide)

    private static int nextOf(int[] steps, int cur) {
        for (int s : steps) if (s > cur) return s;
        return steps[0];
    }

    /** The Help video's chapters: pick where to start; it carries on through the rest. */
    private Screen helpScreen() {
        return new Screen() {
            { title = "Help"; }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                if (!Help.available()) { r.add(emptyRow("The Help video isn\u2019t on this iPod yet")); return r; }
                List<Help.Chapter> cs = Help.chapters();
                for (int i = 0; i < cs.size(); i++) {
                    final int at = i;
                    r.add(new Row(cs.get(i).title, cs.size() > 1 ? "chapter " + (i + 1) + " of " + cs.size() : null, new Runnable() { public void run() { playHelp(at, false); } }));
                }
                return r;
            }
        };
    }

    /** After a power-on while armed (MainActivity, a few seconds after it starts, once the media system is up). */
    public void playHelpAfterBoot() { playHelp(0, true, true); }

    private void playHelp(int chapter, boolean loud) { playHelp(chapter, loud, false); }

    private void playHelp(int chapter, boolean loud, boolean gift) {
        android.content.Intent i = new android.content.Intent(getContext(), HelpActivity.class);
        i.putExtra("chapter", chapter);
        i.putExtra("loud", loud);
        i.putExtra("gift", gift);
        getContext().startActivity(i);
    }

    private Screen settings() {
        return new Screen() {
            { title = "Settings"; }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                final Sys sys = eng.sys;
                String ssid = sys.connectedSsid();
                r.add(nav(new Row("Wi-Fi", !sys.wifiOn() ? "off" : (ssid != null ? "connected to " + ssid : "not connected"),
                    new Runnable() { public void run() { sys.scan(); push(wifiScreen()); } })));
                r.add(nav(new Row("Bluetooth headphones", btSummary(), new Runnable() { public void run() { sys.bindA2dp(); push(btScreen()); } })));
                final int br = sys.brightnessPercent();
                if (Help.available() || eng.giftArmed())
                    r.add(new Row(eng.giftArmed() ? "Armed for gifting" : "Arm for gifting",
                        eng.giftArmed() ? "switches off; the Help video plays when you turn it on. Select to cancel" : "switches it off; the Help video plays, loud, when you turn it on", grownUp(new Runnable() { public void run() {
                        eng.setGiftArmed(!eng.giftArmed());
                        if (!eng.giftArmed()) eng.notice("Not armed"); } })));
                r.add(new Row("Find My beacon: " + (eng.findMyEnabled() ? eng.beacon.status : "off"), "passing iPhones report where it is", grownUp(new Runnable() { public void run() {
                    eng.setFindMyEnabled(!eng.findMyEnabled()); } })));
                r.add(new Row("Brightness: " + br + "%", "select to change", new Runnable() { public void run() {
                    int v = nextOf(new int[] { 20, 40, 60, 80, 100 }, br + 1); sys.setBrightnessPercent(v); applyBrightness(v); } }));
                final int to = sys.screenTimeoutSec();
                r.add(new Row("Screen off after: " + (to >= 60 ? (to / 60) + " min" : to + " s"), "select to change", new Runnable() { public void run() {
                    sys.setScreenTimeoutSec(nextOf(new int[] { 15, 30, 60, 120, 300 }, to)); } }));
                final int hi = eng.holdIdleS();
                r.add(new Row("Hold after: " + (hi == 0 ? "never" : hi >= 60 ? (hi / 60) + " min" : hi + " s"),
                    "buttons stop until one is pressed 3 times quickly", grownUp(new Runnable() { public void run() {
                    eng.setHoldIdleS(nextOf(KeyHold.IDLE_CHOICES_S, hi)); } })));
                final int hw = eng.holdWindowMs();
                r.add(new Row("Unlock: 3 presses in " + String.format(java.util.Locale.UK, "%.2f", hw / 1000.0).replaceAll("0$", "") + " s",
                    "how quickly the same button must be pressed", grownUp(new Runnable() { public void run() {
                    eng.setHoldWindowMs(nextOf(KeyHold.WINDOW_CHOICES_MS, hw)); } })));
                long left = eng.sleepRemainingMs();
                r.add(new Row("Sleep timer: " + (left == 0 ? "off" : ((left + 59999) / 60000) + " min left"), "pauses playback; select to change",
                    new Runnable() { public void run() {
                        long l = eng.sleepRemainingMs() / 60000;
                        int[] steps = { 15, 30, 45, 60, 90 };
                        int next = 0;
                        for (int s : steps) if (s > l + 1) { next = s; break; }
                        eng.setSleepTimerMin(next); } }));
                final int lim = eng.volumeLimit();
                r.add(new Row("Volume limit: " + (lim >= 100 ? "off" : lim + "%"), "the most the volume can go up to", grownUp(new Runnable() { public void run() {
                    int[] steps = { 50, 60, 70, 80, 100 };
                    eng.setVolumeLimit(nextOf(steps, lim)); } })));
                String[] names = { "off", "sound", "vibration", "sound + vibration" };
                r.add(new Row("Wheel click: " + names[eng.clickMode() & 3], "select to change", new Runnable() { public void run() {
                    eng.setClickMode((eng.clickMode() + 1) & 3); tick(); } }));
                String[] lv = { "soft", "medium", "loud" };
                r.add(new Row("Click loudness: " + lv[eng.clickLevel()], "select to change", new Runnable() { public void run() {
                    eng.setClickLevel((eng.clickLevel() + 1) % 3); tick(); } }));
                final long tn = eng.clock.now();
                r.add(new Row("Bedtime: " + (eng.bedtime.unlocked(tn) && !eng.shotMode ? "unlocked" : eng.bedtime.used(tn) + " of " + eng.bedtime.capMin + " min"),
                    Bedtime.hhmm(eng.bedtime.startMin) + " to " + Bedtime.hhmm(eng.bedtime.endMin) + (eng.bedtime.unlocked(tn) && !eng.shotMode ? " \u00b7 select to lock again" : " \u00b7 set in Home Assistant"),
                    new Runnable() { public void run() { if (eng.bedtime.unlocked(eng.clock.now())) { eng.relock(); eng.notice("Bedtime limit back on"); } } }));
                r.add(nav(new Row("Power", "sleep, power off, restart (or hold Menu)", new Runnable() { public void run() { openPowerMenu(); } })));
                return r;
            }
        };
    }

    private void applyBrightness(int p) {
        try {
            android.app.Activity a = (android.app.Activity) getContext();
            android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            lp.screenBrightness = p / 100f;
            a.getWindow().setAttributes(lp);
        } catch (RuntimeException ignored) { }
    }

    private Screen wifiScreen() {
        return new Screen() {
            { title = "Wi-Fi"; allowedLocked = true; }
            List<Row> rows() {
                final Sys sys = eng.sys;
                List<Row> r = new ArrayList<Row>();
                // switching it on, or joining a network, is fine for anyone; switching it off or forgetting one needs a grown-up
                Runnable off = grownUp(new Runnable() { public void run() { sys.setWifiOn(false); } });
                r.add(new Row("Wi-Fi: " + (sys.wifiOn() ? "on" : "off"), sys.wifiOn() ? "select to switch off (grown-up code)" : "select to switch on",
                    sys.wifiOn() ? off : new Runnable() { public void run() { sys.setWifiOn(true); } }));
                if (!sys.wifiOn()) return r;
                r.add(new Row("Search again", null, new Runnable() { public void run() { sys.scan(); } }));
                for (final Sys.Net n : sys.networks()) {
                    String bars = "▂▄▆█".substring(0, Math.max(1, Math.min(4, n.level)));
                    String sub = bars + "  " + (n.connected ? "connected" : n.saved ? "saved" : n.secure ? "needs a password" : "open");
                    r.add(nav(new Row(n.ssid, sub, new Runnable() { public void run() {
                        if (n.connected) { push(networkPage(n)); return; }
                        if (n.saved) { sys.join(n.ssid, null); eng.notice("Joining " + n.ssid); return; }
                        // bedtime still counts away from home (offline, and through Nabu Casa), so no grown-up needed
                        if (!n.secure) { sys.join(n.ssid, null); eng.notice("Joining " + n.ssid); return; }
                        startEntry("Password for " + n.ssid, new Entry.Done() { public void done(String pw) {
                            boolean ok = sys.join(n.ssid, pw);
                            eng.notice(ok ? "Joining " + n.ssid : "Couldn't join " + n.ssid); } });
                    } })));
                }
                return r;
            }
        };
    }

    private Screen networkPage(final Sys.Net n) {
        return new Screen() {
            { title = n.ssid; allowedLocked = true; }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                r.add(new Row("Forget this network", "grown-up code; the password is needed again", grownUp(new Runnable() { public void run() {
                    eng.sys.forget(n.ssid); back(); } })));
                return r;
            }
        };
    }

    // Bluetooth: discovered devices arrive by broadcast
    private final List<android.bluetooth.BluetoothDevice> btFound = new ArrayList<android.bluetooth.BluetoothDevice>();
    private boolean btReceiver;

    private String btSummary() {
        Sys sys = eng.sys;
        if (!sys.btOn()) return "off";
        for (Sys.Dev d : sys.devices(btFound)) if (d.connected) return "connected to " + d.name;
        return "on, not connected";
    }

    private Screen btScreen() {
        if (!btReceiver) {
            btReceiver = true;
            getContext().registerReceiver(new android.content.BroadcastReceiver() {
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    android.bluetooth.BluetoothDevice d = i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
                    if (d != null && !btFound.contains(d)) btFound.add(d);
                }
            }, new android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_FOUND));
        }
        return new Screen() {
            { title = "Bluetooth"; }
            List<Row> rows() {
                final Sys sys = eng.sys;
                List<Row> r = new ArrayList<Row>();
                r.add(new Row("Bluetooth: " + (sys.btOn() ? "on" : "off"), "select to switch", new Runnable() { public void run() {
                    final boolean on = !sys.btOn();
                    eng.runAsync(new Runnable() { public void run() {
                        if (on && !eng.releaseRadio()) { eng.notice("Radio busy, try again in a moment"); return; }
                        sys.setBtOn(on); } }); } }));
                if (!sys.btOn()) return r;
                r.add(new Row("Search for headphones", "put them in pairing mode first", new Runnable() { public void run() {
                    btFound.clear(); sys.discover(); eng.notice("Searching..."); } }));
                for (final Sys.Dev d : sys.devices(btFound)) {
                    r.add(new Row(d.name, d.connected ? "connected" : d.bonded ? "paired" : "found", new Runnable() { public void run() {
                        if (d.connected) sys.disconnect(d.device); else { sys.connect(d.device); eng.notice("Connecting to " + d.name); } } }));
                }
                return r;
            }
        };
    }

    // ------------------------------------------------------------------ text entry with the wheel

    static final class Entry {
        interface Done { void done(String text); }
        static final String[] SETS = { "abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "0123456789", " !@#$%&*?-_.+=/:;'\"(),<>[]{}~^|\\`\u00a3\u20ac" };
        String title, text = "";
        String[] sets = SETS;
        int maxLen;                // > 0: a passcode of that many digits, shown as dots, done on the last one
        int set, ch;
        Done done;
        String chars() { return sets[set]; }
    }

    private Entry entry;

    /**
     * A parental setting: runs straight away while a grown-up session is open (10 minutes after the code), otherwise
     * asks for the grown-up code first (the same 5-digit code from 1Password; it does not lift the bedtime limit).
     */
    private Runnable grownUp(final Runnable action) {
        return new Runnable() { public void run() {
            if (SystemClock.elapsedRealtime() < eng.grownUpUntil) { action.run(); return; }
            startEntry("Grown-up code", new Entry.Done() { public void done(final String code) {
                new Thread(new Runnable() { public void run() {   // a reset clock can take seconds to check
                    final boolean ok = eng.grownUp(code);
                    post(new Runnable() { public void run() {
                        if (ok) action.run();
                        else if (eng.bedtime.codeLockedOut(eng.clock.now())) eng.notice("Too many tries. Wait a minute");
                        else eng.notice("That code didn\u2019t work");
                        invalidate();
                    } });
                } }).start();
            } });
            entry.sets = new String[] { "0123456789" };
            entry.maxLen = Totp.DIGITS;
        } };
    }

    /** The grown-up code (5 digits, from the shared 1Password item): lifts the bedtime limit for 8 hours. */
    private void startCodeEntry() {
        startEntry("Grown-up code", new Entry.Done() { public void done(final String code) {
            eng.notice("Checking\u2026");
            new Thread(new Runnable() { public void run() {   // a clock that was reset can take seconds to check
                if (eng.unlock(code)) eng.notice("Unlocked until " + new java.text.SimpleDateFormat("H:mm", java.util.Locale.UK).format(new java.util.Date(eng.bedtime.unlockUntil)));
                else if (eng.bedtime.codeLockedOut(eng.clock.now())) eng.notice("Too many tries. Wait a minute");
                else eng.notice("That code didn\u2019t work. Try the next one");
            } }).start();
        } });
        entry.sets = new String[] { "0123456789" };
        entry.maxLen = Totp.DIGITS;
    }

    private void startEntry(String title, Entry.Done done) {
        entry = new Entry();
        entry.title = title;
        entry.done = done;
        invalidate();
    }

    private boolean entryKey(int code) {
        Entry e = entry;
        boolean up = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_PLAY;
        boolean down = code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_PAUSE;
        if (eng.wheelReversed()) { boolean t = up; up = down; down = t; }
        int n = e.chars().length();
        if (up) { e.ch = (e.ch + n - 1) % n; tick(); }
        else if (down) { e.ch = (e.ch + 1) % n; tick(); }
        else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            e.text += e.chars().charAt(e.ch);
            clunk();
            if (e.maxLen > 0 && e.text.length() >= e.maxLen) { entry = null; e.done.done(e.text); }
        }
        else if (code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { if (e.text.length() > 0) e.text = e.text.substring(0, e.text.length() - 1); tick(); }
        else if (code == KeyEvent.KEYCODE_MEDIA_NEXT) { e.set = (e.set + 1) % e.sets.length; e.ch = 0; tick(); }
        else if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (e.maxLen > 0) return true;   // a code is sent when its last digit is in: Play must not spend a try on part of one
            entry = null; clunk(); e.done.done(e.text);
        }
        else if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) { entry = null; }
        else return false;
        invalidate();
        return true;
    }

    private Screen bookPage(final Engine.Book b) {
        return new Screen() {
            { title = b.name; }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                long pos = eng.isFinished(b.id) ? 0 : eng.progressMs(b.id);
                r.add(new Row(pos > 30000 ? "Resume at " + fmtClock(pos) : "Play", "on " + eng.targetName, new Runnable() { public void run() {
                    eng.play(b); nowPlaying = true; } }));
                if (pos > 30000 || eng.isFinished(b.id))
                    r.add(new Row("Start again", "from the beginning", new Runnable() { public void run() {
                        eng.restart(b); nowPlaying = true; } }));
                long[] d;
                synchronized (eng.downloading) { d = eng.downloading.get(b.id); }
                if (d != null) {
                    r.add(new Row("Downloading " + (d[1] > 0 ? d[0] * 100 / d[1] : 0) + "%", "select to cancel", new Runnable() { public void run() { eng.cancelDownload(b); } }));
                } else if (eng.isDownloaded(b)) {
                    r.add(new Row("Remove download", "frees space; the place is kept", new Runnable() { public void run() { eng.deleteDownload(b); } }));
                } else {
                    r.add(new Row("Download", "to listen without Wi-Fi", new Runnable() { public void run() { eng.download(b); } }));
                }
                final Chapters ch = eng.chapters(b);
                if (ch != null) r.add(nav(new Row("Chapters", ch.size() + " chapters", new Runnable() { public void run() { push(chapterList(b, ch)); } })));
                r.add(nav(new Row("Listen on", eng.targetName, new Runnable() { public void run() { push(speakers()); } })));
                return r;
            }
        };
    }

    /** Every chapter of a book: select one to play from its start (the one playing now is marked). */
    private Screen chapterList(final Engine.Book b, final Chapters ch) {
        return new Screen() {
            { title = "Chapters"; long pos = eng.progressMsOrLive(b); sel = ch.at(pos); }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                boolean here = eng.current != null && eng.current.id.equals(b.id);
                int now = ch.at(eng.progressMsOrLive(b));
                for (int i = 0; i < ch.size(); i++) {
                    final long at = ch.startMs.get(i);
                    r.add(new Row(ch.names.get(i), (i == now ? (here && eng.playing ? "playing \u00b7 " : "you are here \u00b7 ") : "") + fmtClock(at),
                        new Runnable() { public void run() {
                            // seek only when this book is playing now; otherwise (after a restart, or paused) play from there
                            if (eng.playing && eng.current != null && eng.current.id.equals(b.id)) eng.seekBook(at);
                            else eng.playFrom(b, at);
                            nowPlaying = true;
                        } }));
                }
                return r;
            }
        };
    }

    private Screen speakers() {
        return new Screen() {
            { title = "Listen on"; }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                r.add(new Row(eng.id.device(), eng.targetId.length() == 0 ? "selected" : "its own speaker or headphones", new Runnable() { public void run() {
                    eng.setTarget("", eng.id.device()); back(); } }));
                for (final Engine.Speaker s : eng.speakers) {
                    r.add(new Row(s.name, s.id.equals(eng.targetId) ? "selected" : (s.playing ? "playing something" : "speaker"), new Runnable() { public void run() {
                        eng.setTarget(s.id, s.name); back(); } }));
                }
                if (eng.speakers.isEmpty()) r.add(emptyRow(eng.online ? "No speakers found" : "Connect to Wi-Fi to see speakers"));
                return r;
            }
        };
    }

    private Screen status() {
        return new Screen() {
            { title = "Status"; }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                r.add(new Row("Sync now", eng.online ? "connected to Music Assistant" : "offline", new Runnable() { public void run() { eng.syncNow(); } }));
                r.add(new Row("Music on the iPod: " + eng.musicLib.tracks().size() + " of " + eng.musicLib.serverTotal,
                    eng.musicLib.status.length() > 0 ? eng.musicLib.status : (eng.musicLib.bytes() >> 20) + " MB", null));
                String[] names = { "off", "sound", "vibration", "sound + vibration" };
                r.add(new Row("Wheel click: " + names[eng.clickMode() & 3], "select to change", new Runnable() { public void run() {
                    int m = (eng.clickMode() + 1) & 3; eng.setClickMode(m); tick(); } }));
                r.add(new Row("Keep everything offline: " + (eng.keepAll() ? "on" : "off"),
                    eng.offlineSummary() + (eng.keepAll() ? " · fills in on Wi-Fi" : ""), new Runnable() { public void run() { eng.setKeepAll(!eng.keepAll()); } }));
                r.add(new Row("iPod as a speaker: " + (eng.speakerAlways() ? "always on" : (eng.speakerRunning() ? "on for now" : "off")),
                    "shows up in Music Assistant; uses battery", new Runnable() { public void run() { eng.setSpeakerAlways(!eng.speakerAlways()); } }));
                String[] lv = { "soft", "medium", "loud" };
                r.add(new Row("Click loudness: " + lv[eng.clickLevel()], "select to change", new Runnable() { public void run() {
                    eng.setClickLevel((eng.clickLevel() + 1) % 3); tick(); } }));
                r.add(new Row("Lyrics timing on speakers: " + String.format("%+.2f s", eng.lyricOffsetMs() / 1000.0), "select to nudge; the iPod itself is exact", new Runnable() { public void run() {
                    int v = eng.lyricOffsetMs() + 250; if (v > 1000) v = -1000; eng.setLyricOffsetMs(v); } }));
                r.add(new Row("Wheel direction: " + (eng.wheelReversed() ? "reversed" : "normal"),
                    "turn clockwise: highlight should go down", new Runnable() { public void run() { eng.setWheelReversed(!eng.wheelReversed()); } }));
                r.add(new Row("Time: " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.UK).format(new java.util.Date(eng.clock.now())), "from " + eng.clock.source, null));
                r.add(new Row("Timezone: " + eng.geo.zone(), eng.geo.source.length() > 0 ? "from " + eng.geo.source : "default", null));
                r.add(new Row("Home Assistant: " + (eng.ha.configured() ? "paired" : "not paired"), eng.bedtimeStatus, null));
                String pk = eng.ha.publicKey();   // what to paste into the Y1 Player integration
                r.add(new Row("Player key: " + pk.substring(0, 22), pk.substring(22), null));
                r.add(new Row("Last key: " + lastKey, "wheel / buttons test", null));
                for (String l : eng.logLines()) { Row x = new Row(l, null, null); x.header = true; r.add(x); }
                return r;
            }
        };
    }

    private void back() {
        if (nowPlaying) { nowPlaying = false; invalidate(); return; }
        if (stack.size() > 1) { transFrom = stack.remove(stack.size() - 1); transDir = -1; transAt = SystemClock.uptimeMillis(); invalidate(); return; }
        invalidate();   // already at the top: Back does nothing, Open Solar is the deliberate way out
    }

    /**
     * Hold centre: add the highlighted song (or the one playing, on Now Playing) to Favourites, or take it off.
     * False when there is no song here, so the press acts as a normal select on release.
     */
    public boolean toggleFavourite() {
        if (entry != null || lockShowing()) return false;
        MusicLibrary.Track t = null;
        if (nowPlaying) t = eng.playingTrack();          // null for an audiobook
        else {
            List<Row> rows = top().shown();
            if (top().sel >= 0 && top().sel < rows.size() && rows.get(top().sel).song) t = rows.get(top().sel).track;
        }
        if (t == null) return false;
        boolean on = eng.favs.toggle(t);
        eng.notice(on ? "Added to Favourites" : "Taken off Favourites");
        clunk();
        top().refresh();
        invalidate();
        return true;
    }

    /** Hold Play: jump to Now Playing from anywhere in the menus (not over the bedtime lock or a code entry). */
    public void showNowPlaying() {
        if (eng.current == null || entry != null || lockShowing()) return;
        if (!nowPlaying) { nowPlaying = true; clunk(); }
        invalidate();
    }

    public void openPowerMenu() {
        nowPlaying = false;
        push(new Screen() {
            { title = "Power"; }
            List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                r.add(new Row("Hold", "buttons stop until one is pressed 3 times quickly", new Runnable() { public void run() {
                    back(); eng.hold.engage(); invalidate(); } }));
                r.add(nav(new Row("Listen on", eng.targetName, new Runnable() { public void run() { back(); push(speakers()); } })));
                r.add(new Row("Sleep", "screen off; playing carries on", new Runnable() { public void run() { back(); Root.sleep(); } }));
                r.add(new Row("Power off", "switches the iPod fully off", new Runnable() { public void run() {
                    eng.pause(); Root.powerOff(); } }));
                r.add(new Row("Restart", null, new Runnable() { public void run() { eng.pause(); Root.restart(); } }));
                r.add(new Row("Cancel", null, new Runnable() { public void run() { back(); } }));
                return r;
            }
        });
    }

    private boolean solarInstalled() {
        try {
            return getContext().getPackageManager().getApplicationInfo("com.solar.launcher", 0).enabled;
        } catch (Exception e) { return false; }
    }

    private void openSolar() {
        try {
            android.content.Intent i = new android.content.Intent();
            i.setComponent(new android.content.ComponentName("com.solar.launcher", "com.solar.launcher.MainActivity"));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (RuntimeException e) { eng.notice("Solar is not installed"); }
    }

    // ------------------------------------------------------------------ keys

    // ------------------------------------------------------------------ hold

    private int holdSwallowUp = -1;   // the key whose press was taken by the hold: its release must not act either

    private static boolean wheelTurn(int code, KeyEvent e) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT
            || code == KeyEvent.KEYCODE_DPAD_RIGHT
            || ((code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE) && MediaButtonReceiver.fromWheel(e));
    }

    /**
     * Every key comes here first. Not on hold: it counts as activity and goes on to onKey. On hold: headphone buttons
     * still work, wheel turns only wake the screen, and any other button counts towards the three quick presses that
     * unlock (none of them acts). True when the key was dealt with here.
     */
    public boolean holdKey(int code, KeyEvent e, boolean down) {
        if (code >= 990 && code != 994) return false;                       // the app's own test and design hooks
        long now = SystemClock.uptimeMillis();
        if (code == 994) { eng.hold.engage(); invalidate(); return true; }   // test hook: hold now
        if (!down) {
            if (code == holdSwallowUp) { holdSwallowUp = -1; return true; }
            return false;
        }
        if (!eng.hold.held(now)) { eng.hold.activity(now); return false; }
        if (MediaButtonReceiver.fromHeadphones(e)) return false;              // like a real iPod's remote
        invalidate();
        if (wheelTurn(code, e) || (e != null && e.getRepeatCount() > 0)) return true;
        holdSwallowUp = code;
        int left = eng.hold.press(code, now);
        if (left == 0) clunk(); else tick();
        return true;
    }

    /** A long press of a key the hold took (or while on hold) does nothing. */
    public boolean holdSwallows(int code) { return code == holdSwallowUp || eng.hold.held(SystemClock.uptimeMillis()); }

    private static String buttonName(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: return "the centre button";
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_BACK: return "Menu";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_HEADSETHOOK: return "Play";
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return "Next";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_REWIND: return "Previous";
            default: return "that button";
        }
    }

    public boolean onKey(int code, KeyEvent ev) {
        lastKey = KeyEvent.keyCodeToString(code);
        if (code == 987) { eng.testArm(); return true; }          // test hook: arm without switching off
        if (code == 989 || code == 988) { eng.shotMode = code == 989; top().refresh(); invalidate(); return true; }   // test hooks: screenshot mode on (989) / off (988), see Engine.shotMode
        if (code == 990) {      // test hook only: recognise /sdcard/voice-test.pcm (16 kHz mono 16-bit) as if spoken
            try { recognise(Net.readBytes(new java.io.File("/sdcard/voice-test.pcm"))); } catch (Exception e) { eng.notice("no test audio"); }
            return true;
        }
        if (voiceState == 2) {              // working out what was said: Back cancels, the rest waits
            if (code == KeyEvent.KEYCODE_BACK) { voiceGen++; voiceState = 0; Voice.cancelLocal(); invalidate(); }
            return true;
        }
        if (code == 999) { startEntry("Password test", new Entry.Done() { public void done(String t) { eng.notice("typed: " + t); } }); return true; }  // test hook only
        if (code == 997) {      // design preview: Now Playing for the highlighted book, without starting it
            List<Row> rs = top().get();
            if (top().sel < rs.size() && rs.get(top().sel).book != null) { eng.current = rs.get(top().sel).book; nowPlaying = true; }
            invalidate();
            return true;
        }
        if (code == 996) { previewLock = !previewLock; invalidate(); return true; }   // design preview: bedtime lock
        if (code == 995) { forceDark = !forceDark; invalidate(); return true; }       // design preview: night theme
        if (code == 992) {   // test hook: pretend to be away from home Wi-Fi; off again forgets any test fix
            eng.testAway = !eng.testAway;
            if (!eng.testAway) eng.clock.position(0, 0, 0, 0);
            eng.notice(eng.testAway ? "Test: away from home" : "Test: home again");
            return true;
        }
        if (code == 993) {   // test hook: a pretend GPS fix (Trafalgar Square) to check Home Assistant's map
            eng.clock.position(51.50800, -0.12810, 12f, eng.clock.now());
            eng.notice("Test GPS fix set");
            return true;
        }
        if (entry != null) return entryKey(code);
        boolean wheel = MediaButtonReceiver.fromWheel(ev);   // MEDIA_PLAY/PAUSE: the wheel turning, or headphone buttons
        boolean up = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT || (wheel && code == KeyEvent.KEYCODE_MEDIA_PLAY);
        boolean down = code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT || (wheel && code == KeyEvent.KEYCODE_MEDIA_PAUSE);
        boolean headPlay = !wheel && code == KeyEvent.KEYCODE_MEDIA_PLAY, headPause = !wheel && code == KeyEvent.KEYCODE_MEDIA_PAUSE;
        if (eng.wheelReversed()) { boolean t = up; up = down; down = t; }
        boolean center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        boolean playPause = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_HEADSETHOOK;
        if (lockShowing()) {     // bedtime: only the grown-up code and Wi-Fi
            if (up) { lockSel = 0; tick(); }
            else if (down) { lockSel = 1; tick(); }
            else if (center) { clunk(); if (lockSel == 0) startCodeEntry(); else { eng.sys.scan(); push(wifiScreen()); } }
            else if (playPause) eng.togglePlay();
            else if (headPause) eng.pause();
            invalidate();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) { back(); return true; }
        if (playPause) { eng.togglePlay(); invalidate(); return true; }
        if (headPlay) { eng.resume(); invalidate(); return true; }
        if (headPause) { eng.pause(); invalidate(); return true; }
        // Next/Previous: the next chapter (audiobooks with chapters) or song; fast forward/rewind (and holding them): 30 s
        if (code == KeyEvent.KEYCODE_MEDIA_NEXT) { eng.next(); invalidate(); return true; }
        if (code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { eng.previous(); invalidate(); return true; }
        if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) { eng.skip(30); invalidate(); return true; }
        if (code == KeyEvent.KEYCODE_MEDIA_REWIND) { eng.skip(-30); invalidate(); return true; }
        if (code == KeyEvent.KEYCODE_VOLUME_UP) { eng.volume(1); return true; }
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) { eng.volume(-1); return true; }
        if (nowPlaying) {
            // clockwise = louder
            if (down) { eng.volume(1); tick(); volumeShownAt = SystemClock.uptimeMillis(); }
            else if (up) { eng.volume(-1); tick(); volumeShownAt = SystemClock.uptimeMillis(); }
            else if (center) { clunk(); eng.togglePlay(); }
            else return false;
            invalidate();
            return true;
        }
        Screen s = top();
        List<Row> rows = s.shown();
        if (up || down) {
            long now = SystemClock.uptimeMillis();
            int dir = up ? -1 : 1;
            streak = (dir == lastDir && now - lastStepAt < 180) ? streak + 1 : 0; // a quick spin speeds up
            lastDir = dir;
            lastStepAt = now;
            int step = rows.size() > 60 && streak >= 8 ? 1 + Math.min((streak - 8) / 4, 2) : 1;
            int to = Math.max(0, Math.min(rows.size() - 1, s.sel + dir * step));
            if (to != s.sel) { s.sel = to; tick(); }
        } else if (center && s.sel < rows.size() && rows.get(s.sel).act != null) {
            clunk();
            rows.get(s.sel).act.run();
            s.refresh();
        } else return false;
        invalidate();
        return true;
    }

    // ------------------------------------------------------------------ drawing: the iPod classic's layout with today's Apple finish

    private static final class Theme {
        final int bg, bg2, label, label2, sep, chev, bar, accent, selTop, selBot, toast;
        Theme(int bg, int bg2, int label, int label2, int sep, int chev, int bar, int accent, int selTop, int selBot, int toast) {
            this.bg = bg; this.bg2 = bg2; this.label = label; this.label2 = label2; this.sep = sep; this.chev = chev;
            this.bar = bar; this.accent = accent; this.selTop = selTop; this.selBot = selBot; this.toast = toast;
        }
    }

    private static final Theme LIGHT = new Theme(0xFFFFFFFF, 0xFFF2F2F7, 0xFF111114, 0xFF8A8A8E, 0xFFE4E4E9, 0xFFC4C4C9,
        0xFFF7F7F9, 0xFF007AFF, 0xFF4BA3FF, 0xFF0A6CF0, 0xEE1C1C1E);
    /** Inside the bedtime window: dark, like Night Shift for a bedroom. */
    private static final Theme DARK = new Theme(0xFF000000, 0xFF1C1C1E, 0xFFF5F5F7, 0xFF8E8E93, 0xFF26262A, 0xFF48484C,
        0xFF101012, 0xFF0A84FF, 0xFF3393FF, 0xFF0060DF, 0xEE3A3A3C);
    private static final int LIGHTW = 0, REG = 1, MED = 2, SEMI = 3, BOLD = 4;
    private static final int WHITE = 0xFFFFFFFF;

    private Theme th = LIGHT;
    private Typeface[] faces;
    private final android.graphics.Path path = new android.graphics.Path();
    private final android.graphics.Matrix mtx = new android.graphics.Matrix();

    private void loadFonts(Context c) {
        String[] names = { "Light", "Regular", "Medium", "SemiBold", "Bold" };
        faces = new Typeface[names.length];
        for (int i = 0; i < names.length; i++) {
            try { faces[i] = Typeface.createFromAsset(c.getAssets(), "fonts/Inter-" + names[i] + ".ttf"); }
            catch (RuntimeException e) { faces[i] = i >= SEMI ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT; }
        }
    }

    @Override
    protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; postInvalidateDelayed(500); }

    @Override
    protected void onDetachedFromWindow() { attached = false; super.onDetachedFromWindow(); }

    /** Stop the redraw loop while the screen is off or another app is in front (battery). */
    @Override
    protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        boolean vis = v == View.VISIBLE;
        if (vis && !attached) { attached = true; invalidate(); }
        else if (!vis) attached = false;
    }

    private static String fmtLong(long ms) {
        long m = ms / 60000;
        return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
    }

    static String fmtClock(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    private final Set<String> decoding = new HashSet<String>();

    /** A cover from a file; decoded on a worker thread (decoding on the UI thread is what made the wheel stutter). */
    private Bitmap fileBitmap(final File f) {
        if (f == null) return null;
        final String key = f.getPath();
        Bitmap bm = fileCovers.get(key);
        if (bm != null) return bm;
        boolean start;
        synchronized (decoding) { start = decoding.add(key); }
        if (start) {
            eng.runAsync(new Runnable() { public void run() {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap d = BitmapFactory.decodeFile(key, o);
                post(new Runnable() { public void run() {
                    if (d != null) {
                        if (fileCovers.size() > 80) fileCovers.clear();
                        fileCovers.put(key, d);
                    }
                    synchronized (decoding) { decoding.remove(key); }
                    invalidate();
                } });
            } });
        }
        return null;
    }

    private Bitmap cover(final Engine.Book b) {
        if (b != null && b.coverLocal != null) return fileBitmap(eng.coverFile(b));
        if (b == null || b.cover == null) return null;
        File f = eng.coverFile(b);
        if (f != null) return fileBitmap(f);
        if (coverBusy.add(b.cover)) {
            eng.runAsync(new Runnable() { public void run() { eng.fetchCover(b); coverBusy.remove(b.cover); } });
        }
        return null;
    }

    // ---- scaled copies: drawing a big cover scaled every frame is what made frames slow

    private final LinkedHashMap<String, Bitmap> scaled = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 70; }
    };

    private Bitmap scaledCopy(Bitmap bm, int w, int h) {
        if (bm == null || w <= 0 || h <= 0) return null;
        String k = System.identityHashCode(bm) + ":" + w + "x" + h;
        Bitmap s = scaled.get(k);
        if (s == null) { s = Bitmap.createScaledBitmap(bm, w, h, true); scaled.put(k, s); }
        return s;
    }

    /** A soft, colour-rich backdrop from the artwork: shrink to a few pixels, then let bilinear filtering spread it. */
    private Bitmap blurOf(Bitmap bm) {
        if (bm == null) return null;
        Bitmap tiny = scaledCopy(bm, 6, 6);
        return scaledCopy(tiny, 64, 48);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float u = Math.min(w, h) / 20f;
        long t0 = SystemClock.uptimeMillis();
        th = forceDark || eng.bedtime.inWindow(eng.clock.now()) ? DARK : LIGHT;
        p.setShader(null);
        boolean anim;
        if (voiceState != 0) anim = drawListening(c, w, h, u);
        else if (eng.hold.held(SystemClock.uptimeMillis())) {
            if (eng.playing && eng.current != null) { anim = drawNow(c, w, h, u); holdPrompt(c, w, h, u, 0xE61C1E24); }
            else anim = drawHold(c, w, h, u);
        }
        else if (entry != null) anim = drawEntry(c, w, h, u);
        else if (lockShowing()) anim = drawLock(c, w, h, u);
        else if (nowPlaying) anim = drawNow(c, w, h, u);
        else anim = drawScreens(c, w, h, u);
        long took = SystemClock.uptimeMillis() - t0;
        if (took > 40) android.util.Log.i("ab", "slow draw " + took + " ms");
        if (attached) postInvalidateDelayed(voiceState == 2 ? 100 : anim ? 16 : (nowPlaying || lockShowing() || entry != null || eng.hold.held(t0) ? 250 : 600));
    }

    // ---- text

    private void face(int weight, float size) {
        p.setTypeface(faces[weight]);
        p.setTextSize(size);
        p.setFakeBoldText(false);
    }

    private float measure(String t, float size, int weight) {
        face(weight, size);
        return p.measureText(t);
    }

    private String fit(String t, float size, int weight, float maxW) {
        if (t == null) return "";
        if (maxW <= 0) return t;
        String key = size + "|" + weight + "|" + (int) maxW + "|" + t;
        String e = ellipsized.get(key);
        if (e == null) {
            face(weight, size);
            e = android.text.TextUtils.ellipsize(t, new android.text.TextPaint(p), maxW, android.text.TextUtils.TruncateAt.END).toString();
            if (ellipsized.size() > 400) ellipsized.clear();
            ellipsized.put(key, e);
        }
        return e;
    }

    private void text(Canvas c, String t, float x, float y, float size, int color, int weight, float maxW) {
        face(weight, size);
        p.setShader(null);
        p.setColor(color);
        c.drawText(maxW > 0 ? fit(t, size, weight, maxW) : t, x, y, p);
    }

    private void textCentred(Canvas c, String t, float cx, float y, float size, int color, int weight, float maxW) {
        String s = fit(t, size, weight, maxW);
        text(c, s, cx - measure(s, size, weight) / 2f, y, size, color, weight, 0);
    }

    /** Up to two lines, broken at a space, the second ellipsized. Returns the lines used. */
    private int wrap2(Canvas c, String t, float x, float y, float size, float lead, int color, int weight, float maxW) {
        if (measure(t, size, weight) <= maxW) { text(c, t, x, y, size, color, weight, 0); return 1; }
        int cut = t.length();
        while (cut > 0) {
            cut = t.lastIndexOf(' ', cut - 1);
            if (cut <= 0) break;
            if (measure(t.substring(0, cut), size, weight) <= maxW) break;
        }
        if (cut <= 0) { text(c, t, x, y, size, color, weight, maxW); return 1; }
        text(c, t.substring(0, cut), x, y, size, color, weight, 0);
        text(c, t.substring(cut + 1), x, y + lead, size, color, weight, maxW);
        return 2;
    }

    private static int alpha(int color, float a) { return (Math.round(((color >>> 24) & 0xff) * a) << 24) | (color & 0xffffff); }

    private static float ease(float t) { t = Math.max(0, Math.min(1, t)); float k = 1 - t; return 1 - k * k * k; }

    // ---- glyphs, drawn rather than taken from a font so they are crisp at any size

    private void stroke(int color, float width) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
    }

    private void fill(int color) { p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color); }

    private void chevron(Canvas c, float x, float cy, float u, int color) {
        stroke(color, u * 0.13f);
        path.reset();
        path.moveTo(x - u * 0.3f, cy - u * 0.42f);
        path.lineTo(x, cy);
        path.lineTo(x - u * 0.3f, cy + u * 0.42f);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void playGlyph(Canvas c, float cx, float cy, float r, int color) {
        fill(color);
        path.reset();
        path.moveTo(cx - r * 0.7f, cy - r);
        path.lineTo(cx + r * 0.95f, cy);
        path.lineTo(cx - r * 0.7f, cy + r);
        path.close();
        c.drawPath(path, p);
    }

    private void pauseGlyph(Canvas c, float cx, float cy, float r, int color) {
        fill(color);
        rect.set(cx - r * 0.8f, cy - r, cx - r * 0.2f, cy + r);
        c.drawRoundRect(rect, r * 0.15f, r * 0.15f, p);
        rect.set(cx + r * 0.2f, cy - r, cx + r * 0.8f, cy + r);
        c.drawRoundRect(rect, r * 0.15f, r * 0.15f, p);
    }

    private void battery(Canvas c, float right, float cy, float u, int color) {
        int bl = eng.batteryLevel;
        if (bl < 0) return;
        float bw = u * 1.6f, bh = u * 0.78f, x = right - bw - u * 0.12f;
        stroke(alpha(color, 0.45f), u * 0.075f);
        rect.set(x, cy - bh / 2, x + bw, cy + bh / 2);
        c.drawRoundRect(rect, u * 0.22f, u * 0.22f, p);
        fill(alpha(color, 0.45f));
        rect.set(x + bw + u * 0.06f, cy - bh * 0.18f, x + bw + u * 0.16f, cy + bh * 0.18f);
        c.drawRoundRect(rect, u * 0.05f, u * 0.05f, p);
        int col = eng.charging ? 0xFF34C759 : (bl <= 20 ? 0xFFFF3B30 : color);
        fill(col);
        float in = u * 0.13f;
        rect.set(x + in, cy - bh / 2 + in, x + in + (bw - 2 * in) * Math.max(0.08f, bl / 100f), cy + bh / 2 - in);
        c.drawRoundRect(rect, u * 0.1f, u * 0.1f, p);
        if (eng.charging) {   // bolt, punched out in the bar colour
            fill(th.bar == 0 ? 0xFF000000 : (color == WHITE ? 0xFF000000 : th.bar));
            float bx = x + bw / 2, s = bh * 0.42f;
            path.reset();
            path.moveTo(bx + s * 0.15f, cy - s);
            path.lineTo(bx - s * 0.45f, cy + s * 0.12f);
            path.lineTo(bx - s * 0.02f, cy + s * 0.12f);
            path.lineTo(bx - s * 0.15f, cy + s);
            path.lineTo(bx + s * 0.45f, cy - s * 0.12f);
            path.lineTo(bx + s * 0.02f, cy - s * 0.12f);
            path.close();
            c.drawPath(path, p);
        }
    }

    private void wifiGlyph(Canvas c, float cx, float bottom, float s, int color) {
        fill(color);
        c.drawCircle(cx, bottom - s * 0.12f, s * 0.13f, p);
        stroke(color, s * 0.17f);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 1; i <= 2; i++) {
            float r = s * (0.36f + 0.34f * i);
            rect.set(cx - r, bottom - s * 0.12f - r, cx + r, bottom - s * 0.12f + r);
            c.drawArc(rect, 225, 90, false, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void speakerGlyph(Canvas c, float x, float cy, float s, int color, int waves) {
        fill(color);
        path.reset();
        path.moveTo(x, cy - s * 0.28f);
        path.lineTo(x + s * 0.3f, cy - s * 0.28f);
        path.lineTo(x + s * 0.7f, cy - s * 0.62f);
        path.lineTo(x + s * 0.7f, cy + s * 0.62f);
        path.lineTo(x + s * 0.3f, cy + s * 0.28f);
        path.lineTo(x, cy + s * 0.28f);
        path.close();
        c.drawPath(path, p);
        stroke(color, s * 0.12f);
        for (int i = 1; i <= waves; i++) {
            float r = s * 0.35f * i;
            rect.set(x + s * 0.65f - r, cy - r, x + s * 0.65f + r, cy + r);
            c.drawArc(rect, -45, 90, false, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void moonGlyph(Canvas c, float cx, float cy, float r, int color, int cutColor) {
        fill(color);
        c.drawCircle(cx, cy, r, p);
        fill(cutColor);
        c.drawCircle(cx + r * 0.45f, cy - r * 0.3f, r * 0.85f, p);
    }

    private void noteGlyph(Canvas c, float cx, float cy, float s, int color) {
        fill(color);
        c.drawCircle(cx - s * 0.18f, cy + s * 0.3f, s * 0.2f, p);
        rect.set(cx, cy - s * 0.42f, cx + s * 0.08f, cy + s * 0.3f);
        c.drawRect(rect, p);
        path.reset();
        path.moveTo(cx + s * 0.04f, cy - s * 0.42f);
        path.lineTo(cx + s * 0.38f, cy - s * 0.25f);
        path.lineTo(cx + s * 0.38f, cy - s * 0.1f);
        path.lineTo(cx + s * 0.04f, cy - s * 0.27f);
        path.close();
        c.drawPath(path, p);
    }

    /** Artwork with rounded corners (a shader: clipPath is not hardware accelerated on Android 4.2). */
    private void roundedImage(Canvas c, Bitmap bm, float x, float y, float size, float radius) {
        rect.set(x, y, x + size, y + size);
        Bitmap s = scaledCopy(bm, Math.round(size), Math.round(size));
        if (s == null) {
            fill(th.bg2);
            c.drawRoundRect(rect, radius, radius, p);
            noteGlyph(c, x + size / 2, y + size / 2, size * 0.5f, th.label2);
            return;
        }
        android.graphics.BitmapShader sh = new android.graphics.BitmapShader(s, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
        mtx.reset();
        mtx.setTranslate(x, y);
        sh.setLocalMatrix(mtx);
        p.setStyle(Paint.Style.FILL);
        p.setColor(WHITE);
        p.setShader(sh);
        c.drawRoundRect(rect, radius, radius, p);
        p.setShader(null);
    }

    private void shadow(Canvas c, float x, float y, float size, float radius, float u) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        for (int i = 4; i >= 1; i--) {
            p.setColor(alpha(0xFF000000, 0.07f));
            float g = u * 0.16f * i;
            rect.set(x - g * 0.5f, y - g * 0.2f + u * 0.25f, x + size + g * 0.5f, y + size + g + u * 0.25f);
            c.drawRoundRect(rect, radius + g, radius + g, p);
        }
    }

    private void selection(Canvas c, float l, float t, float r, float b, float u) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new android.graphics.LinearGradient(0, t, 0, b, th.selTop, th.selBot, android.graphics.Shader.TileMode.CLAMP));
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, u * 0.5f, u * 0.5f, p);
        p.setShader(null);
    }

    // ---- chrome

    private float hdrH(float u) { return u * 2.5f; }

    private float footH(float u) { return eng.current != null ? u * 3.0f : 0; }

    private void headerBg(Canvas c, int w, float u) {
        fill(th.bar);
        c.drawRect(0, 0, w, hdrH(u), p);
        fill(th.sep);
        c.drawRect(0, hdrH(u) - 1, w, hdrH(u), p);
    }

    private void headerTitle(Canvas c, int w, float u, String title, int color) {
        textCentred(c, title, w / 2f, hdrH(u) / 2 + u * 0.36f, u * 0.98f, color, SEMI, w - u * 9);
    }

    /** Play state on the left (as on the iPod classic), Wi-Fi and battery on the right. */
    private void headerGlyphs(Canvas c, int w, float u, int color) {
        float cy = hdrH(u) / 2;
        if (eng.current != null) {
            if (eng.playing) playGlyph(c, u * 1.1f, cy, u * 0.36f, color); else pauseGlyph(c, u * 1.1f, cy, u * 0.36f, color);
        }
        battery(c, w - u * 0.7f, cy, u, color);
        if (eng.online) wifiGlyph(c, w - u * 3.3f, cy + u * 0.38f, u * 0.62f, color);
    }

    private void miniPlayer(Canvas c, int w, int h, float u) {
        Engine.Book cur = eng.current;
        if (cur == null) return;
        float fh = footH(u), y = h - fh;
        fill(th.bar);
        c.drawRect(0, y, w, h, p);
        fill(th.sep);
        c.drawRect(0, y, w, y + 1, p);
        long dur = Math.max(1, eng.durMsFor(cur)), pos = eng.progressMsOrLive(cur);
        fill(th.accent);
        c.drawRect(0, y, w * Math.min(1f, (float) pos / dur), y + u * 0.12f, p);
        float cs = u * 2.05f, cx = u * 0.75f, cy = y + (fh - cs) / 2 + u * 0.06f;
        roundedImage(c, cover(cur), cx, cy, cs, u * 0.25f);
        float tx = cx + cs + u * 0.65f, maxW = w - tx - u * 2.6f;
        boolean music = eng.musicMode || eng.localMusic;
        text(c, music ? eng.nowTitle : cur.name, tx, y + fh * 0.45f, u * 0.9f, th.label, MED, maxW);
        text(c, (music ? eng.nowSub + " · " : "") + fmtClock(pos) + " · " + eng.targetName, tx, y + fh * 0.76f, u * 0.72f, th.label2, REG, maxW);
        if (eng.playing) pauseGlyph(c, w - u * 1.4f, y + fh / 2 + u * 0.06f, u * 0.48f, th.label);
        else playGlyph(c, w - u * 1.4f, y + fh / 2 + u * 0.06f, u * 0.5f, th.label);
    }

    private void toast(Canvas c, int w, float bottom, float u, int bg) {
        String m = eng.msg();
        if (m.length() == 0) return;
        float s = u * 0.78f;
        String t = fit(m, s, MED, w - u * 4);
        float tw = measure(t, s, MED), bw = tw + u * 2.0f, bh = u * 2.0f, y = bottom - bh - u * 0.6f;
        fill(bg);
        rect.set(w / 2f - bw / 2, y, w / 2f + bw / 2, y + bh);
        c.drawRoundRect(rect, bh / 2, bh / 2, p);
        text(c, t, w / 2f - tw / 2, y + bh / 2 + s * 0.36f, s, WHITE, MED, 0);
    }

    // ---- lists, with slide transitions between screens

    private Screen transFrom;
    private int transDir;
    private long transAt;
    private static final long TRANS_MS = 280;

    private boolean drawScreens(Canvas c, int w, int h, float u) {
        long el = SystemClock.uptimeMillis() - transAt;
        boolean moving;
        if (transFrom != null && el < TRANS_MS) {
            float k = ease(el / (float) TRANS_MS);
            Screen under = transDir > 0 ? transFrom : top(), over = transDir > 0 ? top() : transFrom;
            float overX = transDir > 0 ? w * (1 - k) : w * k, underX = transDir > 0 ? -w * 0.3f * k : -w * 0.3f * (1 - k);
            c.save(); c.translate(underX, 0); drawLayer(c, under, w, h, u); c.restore();
            fill(alpha(0xFF000000, 0.12f * (1 - overX / w)));               // the screen underneath dims a touch
            c.drawRect(0, 0, w, h, p);
            c.save(); c.translate(overX, 0); drawLayer(c, over, w, h, u); c.restore();
            moving = true;
        } else {
            transFrom = null;
            moving = drawLayer(c, top(), w, h, u);
        }
        headerGlyphs(c, w, u, top().flow ? WHITE : th.label);
        miniPlayer(c, w, h, u);
        toast(c, w, h - footH(u), u, th.toast);
        return moving;
    }

    private boolean drawLayer(Canvas c, Screen s, int w, int h, float u) {
        if (s.flow) return drawFlow(c, s, w, h, u);
        fill(th.bg);
        c.drawRect(0, 0, w, h, p);
        headerBg(c, w, u);
        headerTitle(c, w, u, s.title, th.label);
        return drawListBody(c, s, w, h, u);
    }

    private float rowH(float u) { return u * 2.8f; }

    private boolean drawListBody(Canvas c, Screen s, int w, int h, float u) {
        List<Row> rows = s.get();
        s.sel = Math.max(0, Math.min(s.sel, rows.size() - 1));
        float top = hdrH(u), bottom = h - footH(u), rh = rowH(u), area = bottom - top;
        int visible = Math.max(1, (int) (area / rh));
        if (s.sel < s.top) s.top = s.sel;
        if (s.sel >= s.top + visible) s.top = s.sel - visible + 1;
        float wantScroll = s.top * rh, wantSel = s.sel * rh;
        if (s.scrollY < 0) { s.scrollY = wantScroll; s.selY = wantSel; }
        boolean moving = false;
        float ds = wantScroll - s.scrollY, dy = wantSel - s.selY;
        if (Math.abs(ds) > 0.6f) { s.scrollY += ds * 0.4f; moving = true; } else s.scrollY = wantScroll;
        if (Math.abs(dy) > 0.6f) { s.selY += dy * 0.45f; moving = true; } else s.selY = wantSel;
        c.save();
        c.clipRect(0, top, w, bottom);
        Row selRow = rows.isEmpty() ? null : rows.get(s.sel);
        boolean showSel = selRow != null && !selRow.header;
        if (showSel) {
            float hy = top + s.selY - s.scrollY;
            selection(c, u * 0.45f, hy + u * 0.16f, w - u * 0.45f, hy + rh - u * 0.16f, u);
        }
        int first = Math.max(0, (int) (s.scrollY / rh)), last = Math.min(rows.size() - 1, (int) ((s.scrollY + area) / rh) + 1);
        for (int i = first; i <= last; i++) {
            float y = top + i * rh - s.scrollY;
            boolean sel = showSel && i == s.sel && Math.abs(dy) < rh * 0.5f;
            boolean sep = i < rows.size() - 1 && !(showSel && (i == s.sel || i + 1 == s.sel));
            drawRow(c, rows.get(i), y, w, rh, u, sel, sep);
        }
        if (rows.size() > visible) {    // a slim iOS-style scroll indicator
            float bh = Math.max(u * 1.2f, area * visible / rows.size());
            float by = top + (area - bh) * (s.scrollY / Math.max(1f, (rows.size() - visible) * rh));
            fill(alpha(th.label2, 0.55f));
            rect.set(w - u * 0.32f, by + u * 0.2f, w - u * 0.14f, by + bh - u * 0.2f);
            c.drawRoundRect(rect, u * 0.1f, u * 0.1f, p);
        }
        c.restore();
        return moving;
    }

    private void drawRow(Canvas c, Row r, float y, int w, float rh, float u, boolean sel, boolean sep) {
        int fg = sel ? WHITE : th.label, fg2 = sel ? 0xD9FFFFFF : th.label2;
        float x = u * 1.15f, right = w - u * 1.1f;
        if (r.header) {        // empty states and log lines: quiet
            text(c, r.text, x, y + rh * 0.5f + u * 0.28f, u * 0.78f, th.label2, REG, w - 2.3f * u);
            return;
        }
        boolean thumb = r.book != null || (r.track != null && !r.noThumb);
        if (thumb) {
            float cs = rh - u * 0.75f;
            roundedImage(c, r.book != null ? cover(r.book) : fileBitmap(eng.musicLib.coverFile(r.track)), x - u * 0.2f, y + (rh - cs) / 2, cs, u * 0.28f);
            x += cs + u * 0.45f;
        }
        if (r.nav) { chevron(c, right, y + rh / 2, u, sel ? WHITE : th.chev); right -= u * 1.05f; }
        if (r.song && eng.favs.has(r.track)) { heart(c, right - u * 0.35f, y + rh / 2, u * 0.62f, sel ? WHITE : 0xFFFF375F); right -= u * 1.0f; }
        String label = r.text, value = null, sub = r.sub;
        if (sub != null && sub.startsWith("select to")) sub = null;   // the highlight already says it can be selected
        int ci = label.indexOf(": ");
        if (!thumb && ci > 0 && ci < 30) { value = label.substring(ci + 2); label = label.substring(0, ci); }
        float ts = u * 0.98f, ss = u * 0.74f;
        float lineY = sub != null ? y + rh * 0.45f : y + rh * 0.5f + ts * 0.36f;
        if (value != null) {
            float vs = u * 0.9f;
            String v = fit(value, vs, REG, (right - x) * 0.5f);
            float vw = measure(v, vs, REG);
            text(c, v, right - vw, lineY, vs, sel ? 0xE6FFFFFF : th.label2, REG, 0);
            text(c, label, x, lineY, ts, fg, MED, right - vw - u * 0.8f - x);
        } else {
            text(c, label, x, lineY, ts, fg, MED, right - x);
        }
        if (sub != null) text(c, sub, x, y + rh * 0.77f, ss, fg2, REG, (r.nav ? right : w - u * 1.1f) - x);
        if (sep) { fill(th.sep); c.drawRect(x, y + rh - 1, w, y + rh, p); }
    }

    private final android.graphics.Path heartPath = new android.graphics.Path();

    /** A filled heart of width size centred on cx, cy. */
    private void heart(Canvas c, float cx, float cy, float size, int color) {
        float s = size / 2;
        android.graphics.Path h = heartPath;
        h.reset();
        h.moveTo(cx, cy + s * 0.9f);
        h.cubicTo(cx - s * 1.25f, cy + s * 0.05f, cx - s * 1.0f, cy - s * 1.05f, cx, cy - s * 0.4f);
        h.cubicTo(cx + s * 1.0f, cy - s * 1.05f, cx + s * 1.25f, cy + s * 0.05f, cx, cy + s * 0.9f);
        h.close();
        fill(color);
        c.drawPath(h, p);
    }

    // ---- cover flow (always on black, as Apple's was)

    private boolean drawFlow(Canvas c, Screen s, int w, int h, float u) {
        fill(0xFF000000);
        c.drawRect(0, 0, w, h, p);
        headerTitle(c, w, u, s.title, WHITE);
        List<Row> rows = s.get();
        s.sel = Math.max(0, Math.min(s.sel, rows.size() - 1));
        if (s.flowPos < 0) s.flowPos = s.sel;
        float diff = s.sel - s.flowPos;
        boolean moving = Math.abs(diff) > 0.01f;
        s.flowPos = moving ? s.flowPos + diff * 0.32f : s.sel;
        float areaTop = hdrH(u) + u * 0.4f, areaH = h - areaTop - u * 4.6f - footH(u) * 0.0f;
        float big = Math.min(areaH * 0.66f, w * 0.4f);
        float baseY = areaTop + areaH * 0.5f + big * 0.5f - u * 0.3f;
        int centre = Math.round(s.flowPos);
        for (int k = 3; k >= 0; k--) {
            for (int side = -1; side <= 1; side += 2) {
                if (k == 0 && side == 1) continue;
                int idx = centre + side * k;
                if (idx < 0 || idx >= rows.size()) continue;
                drawCard(c, rows.get(idx), idx - s.flowPos, w, baseY, big, u);
            }
        }
        Row cur = rows.get(s.sel);
        float ty = h - footH(u) - u * 2.2f;
        textCentred(c, cur.text, w / 2f, ty, u * 1.08f, WHITE, SEMI, w - 2 * u);
        if (cur.sub != null) textCentred(c, cur.sub, w / 2f, ty + u * 1.25f, u * 0.78f, 0xFF9A9AA0, REG, w - 2 * u);
        return moving;
    }

    private void drawCard(Canvas c, Row r, float d, int w, float baseY, float big, float u) {
        float ad = Math.abs(d);
        float size = big / (1f + 0.5f * Math.min(ad, 3f));
        float x = ad < 1f ? w / 2f + d * big * 0.62f : w / 2f + Math.signum(d) * (big * 0.62f + (ad - 1f) * big * 0.26f);
        float squeeze = ad < 1f ? 1f - 0.32f * ad : 0.68f;
        float cw = size * squeeze, ch = size;
        int a = (int) (255 - Math.min(150f, ad * 75f));
        Bitmap bm = r.track != null ? fileBitmap(eng.musicLib.coverFile(r.track)) : null;
        Bitmap sc = scaledCopy(bm, Math.max(1, Math.round(cw)), Math.max(1, Math.round(ch)));
        p.setShader(null);
        p.setAlpha(255);
        if (sc != null) {
            p.setAlpha(a);
            c.drawBitmap(sc, x - cw / 2, baseY - ch, p);
            c.save();                                     // reflection, fading into the black
            c.scale(1f, -1f, 0, baseY);
            p.setAlpha(a / 4);
            c.drawBitmap(sc, x - cw / 2, baseY - ch, p);
            c.restore();
            p.setAlpha(255);
            p.setShader(new android.graphics.LinearGradient(0, baseY, 0, baseY + ch * 0.4f, 0x66000000, 0xFF000000, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(x - cw / 2 - 1, baseY, x + cw / 2 + 1, baseY + ch + 1, p);
            p.setShader(null);
        } else {
            fill(alpha(0xFF2C2C2E, a / 255f));
            rect.set(x - cw / 2, baseY - ch, x + cw / 2, baseY);
            c.drawRect(rect, p);
            noteGlyph(c, x, baseY - ch / 2, ch * 0.4f, 0xFF636366);
        }
        p.setAlpha(255);
    }

    // ---- Now Playing: Apple Music style, on a backdrop made from the artwork

    private long volumeShownAt;
    private float lyricPos = -1;

    private void backdrop(Canvas c, int w, int h, Bitmap bm) {
        Bitmap b = blurOf(bm);
        p.setShader(null);
        if (b != null) {
            p.setFilterBitmap(true);
            rect.set(-w * 0.1f, -h * 0.1f, w * 1.1f, h * 1.1f);
            c.drawBitmap(b, null, rect, p);
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0x73000000, 0xB3000000, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        } else {
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFF3A3A3C, 0xFF0B0B0C, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }
    }

    private boolean drawNow(Canvas c, int w, int h, float u) {
        Engine.Book b = eng.current;
        Bitmap bm = b == null ? null : cover(b);
        backdrop(c, w, h, bm);
        headerTitle(c, w, u, "Now Playing", WHITE);
        headerGlyphs(c, w, u, WHITE);
        if (b == null) return false;
        Lrc lrc = eng.lyrics();
        boolean moving = lrc != null ? drawLyrics(c, w, h, u, b, bm, lrc) : drawNowCard(c, w, h, u, b, bm);
        float by = h - u * 3.2f;
        if (eng.hold.held(SystemClock.uptimeMillis())) { }    // the unlock prompt sits here instead
        else if (SystemClock.uptimeMillis() - volumeShownAt < 1600) drawVolume(c, w, by, u);
        else drawScrubber(c, w, by, u, b);
        toast(c, w, by - u * 0.4f, u, 0xCC000000);
        return moving;
    }

    private boolean drawNowCard(Canvas c, int w, int h, float u, Engine.Book b, Bitmap bm) {
        float top = hdrH(u) + u * 0.3f;
        float cs = Math.min(h - top - u * 4.4f, w * 0.44f), x = u * 1.3f;
        shadow(c, x, top, cs, u * 0.5f, u);
        roundedImage(c, bm, x, top, cs, u * 0.5f);
        float tx = x + cs + u * 1.2f, maxW = w - tx - u * 1.1f, y = top + cs * 0.3f;
        boolean music = eng.musicMode || eng.localMusic;
        int lines = wrap2(c, music ? eng.nowTitle : b.name, tx, y, u * 1.22f, u * 1.45f, WHITE, SEMI, maxW);
        y += lines * u * 1.45f + u * 0.15f;
        text(c, music ? eng.nowSub : b.author, tx, y, u * 0.92f, 0xCCFFFFFF, REG, maxW);
        String chap = music ? null : eng.chapterLabel();
        if (chap != null) { y += u * 1.25f; text(c, chap, tx, y, u * 0.8f, 0xB3FFFFFF, MED, maxW); }
        if (music && eng.favs.has(eng.playingTrack())) { y += u * 1.25f; heart(c, tx + u * 0.35f, y - u * 0.3f, u * 0.7f, 0xFFFF375F);
            text(c, "Favourite", tx + u * 1.0f, y, u * 0.78f, 0xB3FFFFFF, MED, maxW - u); }
        y += u * 1.9f;
        speakerGlyph(c, tx, y - u * 0.3f, u * 0.7f, 0x99FFFFFF, 1);
        text(c, eng.targetName, tx + u * 1.15f, y, u * 0.78f, 0x99FFFFFF, MED, maxW - u * 1.15f);
        return false;
    }

    private void drawScrubber(Canvas c, int w, float by, float u, Engine.Book b) {
        long dur = Math.max(1, eng.durMsFor(b)), pos = eng.progressMsOrLive(b);
        float l = u * 1.3f, r = w - u * 1.3f, th2 = u * 0.2f;
        fill(0x4DFFFFFF);
        rect.set(l, by, r, by + th2);
        c.drawRoundRect(rect, th2 / 2, th2 / 2, p);
        fill(0xE6FFFFFF);
        rect.set(l, by, l + (r - l) * Math.min(1f, (float) pos / dur), by + th2);
        c.drawRoundRect(rect, th2 / 2, th2 / 2, p);
        text(c, fmtClock(pos), l, by + u * 1.35f, u * 0.72f, 0x99FFFFFF, MED, 0);
        String left = "-" + fmtClock(Math.max(0, dur - pos));
        text(c, left, r - measure(left, u * 0.72f, MED), by + u * 1.35f, u * 0.72f, 0x99FFFFFF, MED, 0);
    }

    /** Turning the wheel on Now Playing shows the volume, as the iPod did. */
    private void drawVolume(Canvas c, int w, float by, float u) {
        int vol = eng.targetId.length() == 0 ? eng.localVolumePercent() : eng.remoteVolume;
        if (vol < 0) vol = 0;
        float l = u * 3.0f, r = w - u * 3.0f, th2 = u * 0.32f, cy = by + th2 / 2;
        speakerGlyph(c, u * 1.2f, cy, u * 0.8f, 0xCCFFFFFF, 0);
        speakerGlyph(c, w - u * 2.2f, cy, u * 0.8f, 0xCCFFFFFF, 2);
        fill(0x4DFFFFFF);
        rect.set(l, by, r, by + th2);
        c.drawRoundRect(rect, th2 / 2, th2 / 2, p);
        fill(WHITE);
        rect.set(l, by, l + (r - l) * vol / 100f, by + th2);
        c.drawRoundRect(rect, th2 / 2, th2 / 2, p);
        textCentred(c, vol + "%", w / 2f, by + u * 1.45f, u * 0.72f, 0x99FFFFFF, MED, 0);
    }

    /** Synced lyrics: the sung line large and bright, the rest dimmed, gliding as the song moves on. */
    private boolean drawLyrics(Canvas c, int w, int h, float u, Engine.Book b, Bitmap bm, Lrc lrc) {
        float top = hdrH(u) + u * 0.2f, cs = u * 2.6f;
        roundedImage(c, bm, u * 1.2f, top, cs, u * 0.3f);
        boolean music = eng.musicMode || eng.localMusic;
        float tx = u * 1.2f + cs + u * 0.7f;
        text(c, music ? eng.nowTitle : b.name, tx, top + u * 1.1f, u * 0.9f, WHITE, SEMI, w - tx - u);
        text(c, music ? eng.nowSub : b.author, tx, top + u * 2.1f, u * 0.75f, 0xB3FFFFFF, REG, w - tx - u);
        long pos = eng.livePosMs(), shift = eng.localMusic ? 0 : eng.lyricOffsetMs();
        int cur = lrc.indexAt(pos + shift + 100);   // a hair early: the line is read just before it is heard
        if (lyricPos < 0 || Math.abs(lyricPos - cur) > 6) lyricPos = cur;
        float d = cur - lyricPos;
        boolean moving = Math.abs(d) > 0.01f;
        lyricPos = moving ? lyricPos + d * 0.18f : cur;
        float areaTop = top + cs + u * 0.6f, areaBot = h - u * 3.9f, mid = (areaTop + areaBot) / 2f + u * 0.4f, gap = u * 1.9f;
        c.save();
        c.clipRect(0, areaTop, w, areaBot);
        for (int i = Math.max(0, cur - 3); i <= Math.min(lrc.text.length - 1, cur + 3); i++) {
            String line = lrc.text[i].length() == 0 ? "• • •" : lrc.text[i];
            float off = i - lyricPos;
            boolean now = i == cur && !lrc.unsynced;      // untimed lyrics: no line claims to be the one sung
            float size = now ? u * 1.3f : u * 1.02f;
            while (size > u * 0.85f && measure(line, size, now ? BOLD : SEMI) > w - 2.4f * u) size -= u * 0.05f;
            int col = now ? WHITE : lrc.unsynced ? alpha(WHITE, Math.max(0.3f, 0.85f - 0.16f * Math.abs(off)))
                : alpha(WHITE, Math.max(0.12f, 0.42f - 0.1f * Math.abs(off)));
            text(c, line, u * 1.2f, mid + off * gap, size, col, now ? BOLD : SEMI, w - 2.4f * u);
        }
        c.restore();
        return moving;
    }

    // ---- text entry with the wheel: a picker drum, as on iOS

    private boolean drawEntry(Canvas c, int w, int h, float u) {
        Entry e = entry;
        fill(th.bg);
        c.drawRect(0, 0, w, h, p);
        headerBg(c, w, u);
        headerTitle(c, w, u, e.title, th.label);
        headerGlyphs(c, w, u, th.label);
        float fy = hdrH(u) + u * 0.9f, fh = u * 2.5f;
        if (e.maxLen > 0) {      // passcode: a row of dots, as on an iPhone
            float r = u * 0.42f, gap = u * 1.5f, x0 = w / 2f - gap * (e.maxLen - 1) / 2f, cy0 = fy + fh / 2;
            for (int i = 0; i < e.maxLen; i++) {
                if (i < e.text.length()) { fill(th.label); c.drawCircle(x0 + i * gap, cy0, r, p); }
                else { stroke(th.label, u * 0.1f); c.drawCircle(x0 + i * gap, cy0, r, p); p.setStyle(Paint.Style.FILL); }
            }
            textCentred(c, "From the shared 1Password item", w / 2f, fy + fh + u * 0.9f, u * 0.72f, th.label2, REG, w - 2 * u);
        } else {
        fill(th.bg2);
        rect.set(u, fy, w - u, fy + fh);
        c.drawRoundRect(rect, u * 0.6f, u * 0.6f, p);
        float ts = u * 1.15f, tw = measure(e.text, ts, MED), tx = Math.min(u * 1.8f, w - u * 2.2f - tw);
        c.save();
        c.clipRect(u * 1.2f, fy, w - u * 1.2f, fy + fh);
        text(c, e.text, tx, fy + fh / 2 + ts * 0.36f, ts, th.label, MED, 0);
        if ((SystemClock.uptimeMillis() / 530) % 2 == 0) {
            fill(th.accent);
            rect.set(tx + tw + u * 0.12f, fy + u * 0.55f, tx + tw + u * 0.24f, fy + fh - u * 0.55f);
            c.drawRoundRect(rect, u * 0.06f, u * 0.06f, p);
        }
        c.restore();
        }
        String cs = e.chars();
        float cy = fy + fh + u * 4.0f, step = u * 2.4f;
        fill(th.accent);
        rect.set(w / 2f - u * 1.35f, cy - u * 1.55f, w / 2f + u * 1.35f, cy + u * 1.15f);
        c.drawRoundRect(rect, u * 0.7f, u * 0.7f, p);
        for (int k = -5; k <= 5; k++) {
            int i = ((e.ch + k) % cs.length() + cs.length()) % cs.length();
            String s = String.valueOf(cs.charAt(i));
            if (s.equals(" ")) s = "space";
            boolean mid = k == 0;
            float size = mid ? u * 1.6f : u * 1.15f * (1f - 0.06f * Math.abs(k));
            int col = mid ? WHITE : alpha(th.label, Math.max(0.12f, 1f - 0.2f * Math.abs(k)));
            textCentred(c, s, w / 2f + k * step, cy + size * 0.36f - u * 0.2f, mid && s.length() > 1 ? u * 0.8f : size, col, mid ? SEMI : REG, 0);
        }
        String[] setNames = { "abc", "ABC", "123", "#+=" };
        List<String[]> chips = new ArrayList<String[]>();
        chips.add(new String[] { "Centre", "add" });
        chips.add(new String[] { "◀◀", "delete" });
        if (e.sets.length > 1) chips.add(new String[] { "▶▶", setNames[(e.set + 1) % e.sets.length] });
        if (e.maxLen == 0) chips.add(new String[] { "Play", "done" });
        chips.add(new String[] { "Menu", "cancel" });
        float cyc = h - u * 1.6f, gapc = u * 0.45f, total = 0, ks = u * 0.66f;
        float[] widths = new float[chips.size()];
        for (int i = 0; i < chips.size(); i++) { widths[i] = measure(chips.get(i)[0] + " ", ks, SEMI) + measure(chips.get(i)[1], ks, REG) + u * 1.2f; total += widths[i] + gapc; }
        float x = (w - total + gapc) / 2f;
        for (int i = 0; i < chips.size(); i++) {
            fill(th.bg2);
            rect.set(x, cyc - u * 0.75f, x + widths[i], cyc + u * 0.75f);
            c.drawRoundRect(rect, u * 0.75f, u * 0.75f, p);
            float kx = x + u * 0.6f;
            text(c, chips.get(i)[0] + " ", kx, cyc + ks * 0.36f, ks, th.label, SEMI, 0);
            text(c, chips.get(i)[1], kx + measure(chips.get(i)[0] + " ", ks, SEMI), cyc + ks * 0.36f, ks, th.label2, REG, 0);
            x += widths[i] + gapc;
        }
        return false;
    }

    // ---- bedtime lock: a calm night sky

    private int lockSel;
    private Bitmap moon;

    /** The crescent, drawn once into a transparent bitmap with the bite cleared out (no colour has to match the sky). */
    private Bitmap crescent(int r) {
        if (moon != null && moon.getWidth() == 2 * r) return moon;
        moon = Bitmap.createBitmap(2 * r, 2 * r, Bitmap.Config.ARGB_8888);
        Canvas mc = new Canvas(moon);
        Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);
        mp.setColor(0xFFFFF1CF);
        mc.drawCircle(r, r, r, mp);
        mp.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        mc.drawCircle(r + r * 0.5f, r - r * 0.32f, r * 0.86f, mp);
        return moon;
    }

    private boolean previewLock, forceDark;

    private boolean lockShowing() { return entry == null && !top().allowedLocked && (previewLock || eng.bedtimeLocked()); }

    /** On hold: the time, whose iPod it is and how to return it, and how to unlock (the same button 3 times quickly). */
    private boolean drawHold(Canvas c, int w, int h, float u) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFF101114, 0xFF22252C, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        battery(c, w - u * 0.7f, hdrH(u) / 2, u, 0xCCFFFFFF);
        if (eng.online) wifiGlyph(c, w - u * 3.3f, hdrH(u) / 2 + u * 0.38f, u * 0.62f, 0xCCFFFFFF);
        padlock(c, u * 1.5f, hdrH(u) / 2, u * 0.75f, 0xCCFFFFFF);
        long now = SystemClock.uptimeMillis();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("H:mm", java.util.Locale.UK);
        float y = h * 0.34f;
        text(c, f.format(new java.util.Date(eng.clock.now())), u * 1.4f, y, u * 3.2f, WHITE, LIGHTW, 0);
        text(c, eng.id.device(), u * 1.5f, y + u * 1.8f, u * 1.15f, WHITE, SEMI, 0);
        String[] found = eng.id.lostContact();      // set in Home Assistant (Identity), e.g. "If found, call ...|or email ..."
        for (int i = 0; i < found.length && i < 3; i++)
            text(c, found[i], u * 1.5f, y + u * (3.0f + i), u * 0.78f, 0xB3FFFFFF, REG, w - 3 * u);
        holdPrompt(c, w, h, u, 0);
        return false;
    }

    /** How to unlock: a pill with one dot per press (filled as they come) and which button, how many more. bg 0 = subtle. */
    private void holdPrompt(Canvas c, int w, int h, float u, int bg) {
        long now = SystemClock.uptimeMillis();
        int left = eng.hold.remaining(now);
        String prompt = left == 0 ? "Press any button 3 times to unlock"
            : "Press " + buttonName(eng.hold.counting(now)) + " " + left + " more time" + (left == 1 ? "" : "s");
        float py = h - u * 3.4f;
        rect.set(u * 1.4f, py, w - u * 1.4f, py + u * 2.2f);
        if (bg != 0) { fill(bg); c.drawRoundRect(rect, u * 1.1f, u * 1.1f, p); }   // readable over the artwork
        fill(left == 0 ? 0x1FFFFFFF : 0x40FFFFFF);
        c.drawRoundRect(rect, u * 1.1f, u * 1.1f, p);
        padlock(c, u * 2.5f, py + u * 1.1f, u * 0.6f, 0xCCFFFFFF);
        for (int i = 0; i < KeyHold.PRESSES; i++) {
            fill(i < KeyHold.PRESSES - left && left > 0 ? WHITE : 0x59FFFFFF);
            c.drawCircle(u * 3.6f + i * u * 0.7f, py + u * 1.1f, u * 0.2f, p);
        }
        text(c, prompt, u * 5.6f, py + u * 1.1f + u * 0.3f, u * 0.8f, WHITE, MED, w - u * 7.4f);
    }

    /** A small padlock: shackle and body. */
    private void padlock(Canvas c, float cx, float cy, float s, int color) {
        p.setShader(null);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.16f);
        rect.set(cx - s * 0.32f, cy - s * 0.62f, cx + s * 0.32f, cy + s * 0.05f);
        c.drawArc(rect, 180, 180, false, p);
        c.drawLine(cx - s * 0.32f, cy - s * 0.28f, cx - s * 0.32f, cy, p);
        c.drawLine(cx + s * 0.32f, cy - s * 0.28f, cx + s * 0.32f, cy, p);
        p.setStyle(Paint.Style.FILL);
        rect.set(cx - s * 0.48f, cy - s * 0.05f, cx + s * 0.48f, cy + s * 0.6f);
        c.drawRoundRect(rect, s * 0.12f, s * 0.12f, p);
    }

    private boolean drawLock(Canvas c, int w, int h, float u) {
        int skyTop = 0xFF070B1E, skyBot = 0xFF1A1E3D;
        android.graphics.LinearGradient sky = new android.graphics.LinearGradient(0, 0, 0, h, skyTop, skyBot, android.graphics.Shader.TileMode.CLAMP);
        p.setStyle(Paint.Style.FILL);
        p.setShader(sky);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        long t = SystemClock.uptimeMillis();
        java.util.Random rnd = new java.util.Random(7);       // same stars every time, gently twinkling
        for (int i = 0; i < 46; i++) {
            float sx = rnd.nextFloat() * w, sy = rnd.nextFloat() * h * 0.72f, sr = u * (0.05f + rnd.nextFloat() * 0.09f);
            float tw = 0.45f + 0.55f * (float) Math.abs(Math.sin(t / 1400.0 + i * 1.7));
            if (sx < w * 0.68f && sy > h * 0.14f && sy < h * 0.64f) continue;        // keep the words clear
            if (Math.hypot(sx - w * 0.78f, sy - h * 0.2f) < u * 3.4f) continue;        // and the moon
            if (sy < hdrH(u) && sx > w - u * 5f) continue;                             // and the status icons
            fill(alpha(0xFFE9EDFF, tw));
            c.drawCircle(sx, sy, sr, p);
        }
        float mx = w * 0.78f, my = h * 0.2f, mr = u * 1.5f;
        c.drawBitmap(crescent(Math.round(mr)), mx - mr, my - mr, null);
        p.setShader(new android.graphics.RadialGradient(mx, my, mr * 2.6f, 0x26FFF4D6, 0x00FFF4D6, android.graphics.Shader.TileMode.CLAMP));
        c.drawCircle(mx, my, mr * 2.6f, p);                   // soft glow over both, so no edge shows
        p.setShader(null);
        battery(c, w - u * 0.7f, hdrH(u) / 2, u, 0xCCFFFFFF);
        if (eng.online) wifiGlyph(c, w - u * 3.3f, hdrH(u) / 2 + u * 0.38f, u * 0.62f, 0xCCFFFFFF);
        long now = eng.clock.now();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("H:mm", java.util.Locale.UK);
        text(c, f.format(new java.util.Date(now)), u * 1.4f, h * 0.36f, u * 3.4f, WHITE, LIGHTW, 0);
        text(c, "Goodnight" + eng.id.commaName(), u * 1.5f, h * 0.36f + u * 1.9f, u * 1.15f, WHITE, SEMI, 0);
        text(c, "Stories and music are back at " + f.format(new java.util.Date(eng.bedtime.windowEnd(now))), u * 1.5f,
            h * 0.36f + u * 3.1f, u * 0.78f, 0x99FFFFFF, REG, w - 3 * u);
        String[] opts = { "Grown-up code", "Wi-Fi" };
        float oy = h - u * 3.6f, ow = (w - u * 3.4f) / 2f;
        for (int i = 0; i < 2; i++) {
            float ox = u * 1.4f + i * (ow + u * 0.6f);
            fill(lockSel == i ? 0x33FFFFFF : 0x14FFFFFF);
            rect.set(ox, oy, ox + ow, oy + u * 2.2f);
            c.drawRoundRect(rect, u * 1.1f, u * 1.1f, p);
            textCentred(c, opts[i], ox + ow / 2, oy + u * 1.1f + u * 0.3f, u * 0.85f, lockSel == i ? WHITE : 0xB3FFFFFF, MED, ow - u);
        }
        toast(c, w, oy - u * 0.2f, u, 0xCC000000);
        return false;
    }
}
