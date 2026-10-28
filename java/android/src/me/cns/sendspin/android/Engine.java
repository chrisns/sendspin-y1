package me.cns.sendspin.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.PowerManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import me.cns.sendspin.Json;

/**
 * The brain of the Y1 audiobook player. Owns the library, the offline cache, local playback,
 * remote control of a Music Assistant speaker, and progress sync. The UI only reads its
 * volatile state and calls its commands, so playback and sync carry on with the screen off.
 *
 * Progress model: every book has {pos, ts, synced, full}. pos is where the listener is (ms); synced is
 * the last value known to equal Music Assistant's. pos != synced means the Y1 is ahead of the
 * server and must push; otherwise the server's value wins when the library is refreshed.
 */
public final class Engine {
    public static final class Book {
        public String id, name, author, path, cover, uri;
        public String kind = "audiobook";
        public File coverLocal;   // cover already on the Y1 (synced music)
        public long durS;
    }

    public static final class Speaker {
        public String id, name;
        public boolean playing;
    }

    private static Engine instance;
    private static final String TAG = "ab";

    public static synchronized Engine get(Context c) {
        if (instance == null) instance = new Engine(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Store store;
    private final File legacyDir, oldCovers;
    private volatile File sdDir; // /sdcard/Audiobooks once the card is mounted
    private final Object dirLock = new Object();   // not the Engine monitor: drawing asks for it, workers hold that one across network calls
    public final MusicLibrary musicLib;
    public final Sys sys;
    public final FindMyBeacon beacon;
    private long beaconAt;
    // ---- bedtime: shared listening budget with Home Assistant, enforced offline too
    public final Bedtime bedtime = new Bedtime();
    public final KeyHold hold = new KeyHold();   // buttons on hold after a quiet spell (Settings)
    public TrustedClock clock;
    public Geo geo;
    public HaClient ha;
    private long playedMs, lastCountAt, haAt, ntpAt, fadeAt;
    private boolean wasOnWifi;
    private final java.util.concurrent.atomic.AtomicBoolean haBusy = new java.util.concurrent.atomic.AtomicBoolean();
    public volatile String bedtimeStatus = "";
    private final java.util.concurrent.atomic.AtomicBoolean beaconBusy = new java.util.concurrent.atomic.AtomicBoolean();
    public volatile int batteryLevel = -1;
    public volatile boolean charging;
    private long batteryAt;
    private final ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(8);
    private final AudioManager audio;
    private final PowerManager.WakeLock netLock;
    private MaClient ma;
    public Favourites favs;
    public Identity id;
    public Finder finder;
    private String host, token, gwPrefix;
    private int port, gwPort;

    // ---- state read by the UI
    public volatile List<Book> books = new ArrayList<Book>();
    public volatile boolean online;
    private volatile String message = "";
    private volatile long messageAt;

    public void notice(String m) { say(m); }

    private void say(String m) { message = m; messageAt = System.currentTimeMillis(); }

    /** The current notice, or "" once it is stale (offline notices stay while true). */
    public String msg() {
        return (message.startsWith("Offline") || System.currentTimeMillis() - messageAt < 8000) ? message : "";
    }
    public volatile Book current;
    public volatile boolean playing;
    public volatile long posMs, durMs;
    public volatile String targetId = "";      // "" = play on this Y1
    public volatile String targetName = "";
    public volatile int remoteVolume = -1;
    public volatile List<Speaker> speakers = new ArrayList<Speaker>();
    public final Map<String, long[]> downloading = new LinkedHashMap<String, long[]>(); // id -> {done, total}
    private final LinkedList<String> log = new LinkedList<String>();

    // ---- local playback
    private MediaPlayer mp;
    private boolean mpReady, wantPlay;
    private long resumeAtMs, musicSavedAt;   // where restored music picks up; when its place was last saved
    private long lastSave;
    private boolean resumeOnFocus;
    private final java.util.Set<String> cancelDl = new java.util.HashSet<String>();

    private Engine(Context a) {
        app = a;
        store = new Store(a.getFilesDir());
        geo = new Geo(a, store);
        clock = new TrustedClock(a, store);
        clock.loadPosition();
        clock.geo = geo;
        ha = new HaClient(a, clock);
        synchronized (store) { bedtime.load(store.section("bedtime")); }
        hold.idleS = holdIdleS();
        hold.windowMs = holdWindowMs();
        // books live on the big SD storage (the internal partition is only ~1 GB). At boot the card may not be mounted yet,
        // so it is resolved lazily (dlDir()) and nothing downloads until it is there.
        legacyDir = new File(a.getFilesDir(), "books");
        legacyDir.mkdirs();
        oldCovers = new File(a.getFilesDir(), "covers");
        oldCovers.mkdirs();                         // used until the card is mounted (and if it never is)
        sys = new Sys(a);
        beacon = new FindMyBeacon(a);
        musicLib = new MusicLibrary(new File(android.os.Environment.getExternalStorageDirectory(), "Music"));
        id = new Identity(store);
        favs = new Favourites(store, id);
        finder = new Finder(a);
        audio = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        netLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiobooks-net");
        netLock.setReferenceCounted(false);
        loadConfig();
        loadBooksCache();
        targetId = store.str("targetId", "");
        targetName = targetId.length() == 0 ? id.device() : store.str("targetName", targetId);
        a.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { if (playing && targetId.length() == 0) pause(); }
        }, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        // with the screen off nothing wakes the CPU for tick(): an alarm every 2 minutes keeps it awake for a few
        // seconds, enough for Find (checked at once) and the bedtime and Home Assistant work to run
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ab:tick");
        wakeLock.setReferenceCounted(false);
        remoteLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ab:remote-play");
        remoteLock.setReferenceCounted(false);
        a.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { wakeLock.acquire(8000); findAt = 0; }
        }, new IntentFilter(WAKE_ACTION));
        android.app.AlarmManager am = (android.app.AlarmManager) a.getSystemService(Context.ALARM_SERVICE);
        am.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 120000L,
            120000L, android.app.PendingIntent.getBroadcast(a, 0, new Intent(WAKE_ACTION), 0));
        a.registerReceiver(new BroadcastReceiver() {   // the grown-up session ends when the iPod is put down
            public void onReceive(Context c, Intent i) { grownUpUntil = 0; }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        pool.scheduleWithFixedDelay(new Runnable() { public void run() { tick(); } }, 1, 1, TimeUnit.SECONDS);
        pool.scheduleWithFixedDelay(new Runnable() { public void run() { syncLoop(); } }, 2, 30, TimeUnit.SECONDS);
        restoreLast();
        if (speakerAlways()) pool.execute(new Runnable() { public void run() { startSpeaker(); } });   // "always on" survives a restart
    }

    private File dlDir() {
        File d = sdDir;
        if (d != null) return d;
        synchronized (dirLock) {
            if (sdDir != null) return sdDir;
            if (!android.os.Environment.MEDIA_MOUNTED.equals(android.os.Environment.getExternalStorageState())) return legacyDir;
            File sd = new File(android.os.Environment.getExternalStorageDirectory(), "Audiobooks");
            sd.mkdirs();
            if (!sd.isDirectory() || !sd.canWrite()) return legacyDir;
            new File(sd, ".covers").mkdirs();
            sdDir = sd;
            migrateInternalMedia(oldCovers);
            return sd;
        }
    }

    private boolean sdReady() { return dlDir() != legacyDir; }

    private File coverDir() { return sdReady() ? new File(dlDir(), ".covers") : oldCovers; }

    /** Media must not sit on the small internal partition: move any books, part files and covers to the SD card. */
    private void migrateInternalMedia(final File oldCovers) {
        pool.execute(new Runnable() { public void run() {
            moveAll(legacyDir, sdDir, new String[] { ".m4b", ".part" });
            moveAll(oldCovers, new File(sdDir, ".covers"), new String[] { ".jpg", ".tmp" });
        } });
    }

    private void moveAll(File from, File to, String[] suffixes) {
        File[] fs = from.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            boolean match = false;
            for (String s : suffixes) if (f.getName().endsWith(s)) match = true;
            if (!match) continue;
            File dst = new File(to, f.getName());
            try {
                if (dst.exists()) { f.delete(); continue; }
                if (!f.renameTo(dst)) { // different filesystem: copy, verify size, then remove the original
                    File tmp = new File(to, f.getName() + ".moving");
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                    try {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    } finally { in.close(); out.close(); }
                    if (tmp.length() != f.length() || !tmp.renameTo(dst)) { tmp.delete(); continue; }
                    f.delete();
                }
                log("moved " + f.getName() + " to SD card");
            } catch (IOException ex) {
                log("move failed: " + f.getName() + ": " + ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ config

    private void loadConfig() {
        host = store.str("host", "");
        token = store.str("token", "");
        gwPrefix = store.str("gwPrefix", "");
        port = (int) store.num("port", 8095);
        gwPort = (int) store.num("gwPort", 18099);
        ma = (host.length() > 0 && token.length() > 0) ? new MaClient(host, port, token) : null;
    }

    public synchronized void configure(String h, int p, String t, String prefix, int gp) {
        store.put("host", h); store.put("port", Long.valueOf(p)); store.put("token", t);
        store.put("gwPrefix", prefix); store.put("gwPort", Long.valueOf(gp));
        if (ma != null) ma.close();
        loadConfig();
        log("configured for " + h);
        pool.execute(new Runnable() { public void run() { syncLoop(); } });
    }

    public boolean configured() { return ma != null; }

    public void log(String s) {
        android.util.Log.i(TAG, s);
        synchronized (log) { log.add(s); while (log.size() > 40) log.removeFirst(); }
    }

    public List<String> logLines() { synchronized (log) { return new ArrayList<String>(log); } }

    // ------------------------------------------------------------------ library + progress

    @SuppressWarnings("unchecked")
    private void loadBooksCache() {
        Object o = store.raw("library");
        if (!(o instanceof List)) return;
        List<Book> out = new ArrayList<Book>();
        for (Object e : (List<Object>) o) {
            Map<String, Object> m = Json.map(e);
            Book b = new Book();
            b.id = (String) m.get("id"); b.name = (String) m.get("name"); b.author = (String) m.get("author");
            b.path = (String) m.get("path"); b.cover = (String) m.get("cover");
            b.durS = ((Number) m.get("dur")).longValue();
            out.add(b);
        }
        books = sorted(out);
    }

    private static List<Book> sorted(List<Book> in) {
        Collections.sort(in, new Comparator<Book>() {
            public int compare(Book a, Book b) {
                int c = a.author.compareToIgnoreCase(b.author);
                if (c != 0) return c;
                return a.path.compareToIgnoreCase(b.path); // series folders sort in reading order
            }
        });
        return in;
    }

    public Book book(String id) {
        for (Book b : books) if (b.id.equals(id)) return b;
        return null;
    }

    @SuppressWarnings("unchecked")
    private void refreshLibrary() throws IOException {
        Object r = ma.call("music/audiobooks/library_items", MaClient.args("limit", Long.valueOf(1000)), 30000);
        List<Book> out = new ArrayList<Book>();
        List<Object> cache = new ArrayList<Object>();
        for (Object e : (List<Object>) r) {
            Map<String, Object> m = Json.map(e);
            Book b = new Book();
            b.id = String.valueOf(m.get("item_id"));
            b.name = String.valueOf(m.get("name"));
            List<Object> au = (List<Object>) m.get("authors");
            b.author = (au != null && au.size() > 0) ? String.valueOf(au.get(0)) : "Unknown";
            Object dur = m.get("duration");
            b.durS = dur instanceof Number ? ((Number) dur).longValue() : 0;
            List<Object> pm = (List<Object>) m.get("provider_mappings");
            b.path = pm != null && pm.size() > 0 ? String.valueOf(Json.map(pm.get(0)).get("item_id")) : b.name;
            Map<String, Object> md = m.get("metadata") instanceof Map ? Json.map(m.get("metadata")) : null;
            List<Object> imgs = md != null && md.get("images") instanceof List ? (List<Object>) md.get("images") : null;
            if (imgs != null && imgs.size() > 0) {
                Object pid = Json.map(imgs.get(0)).get("proxy_id");
                if (pid != null) b.cover = String.valueOf(pid);
            }
            out.add(b);
            Map<String, Object> c = new LinkedHashMap<String, Object>();
            c.put("id", b.id); c.put("name", b.name); c.put("author", b.author); c.put("path", b.path);
            c.put("cover", b.cover); c.put("dur", Long.valueOf(b.durS));
            cache.add(c);
            mergeRemote(b, m);
        }
        store.put("library", cache);
        books = sorted(out);
        log("library: " + out.size() + " books");
    }

    /** Server progress arrives: adopt it unless the Y1 is ahead (unsynced) or is playing this book right now. */
    private void mergeRemote(Book b, Map<String, Object> m) {
        long remote = m.get("resume_position_ms") instanceof Number ? ((Number) m.get("resume_position_ms")).longValue() : 0;
        boolean full = Boolean.TRUE.equals(m.get("fully_played"));
        synchronized (store) {
            Map<String, Object> prog = store.section("progress");
            Map<String, Object> p = prog.get(b.id) instanceof Map ? Json.map(prog.get(b.id)) : null;
            boolean playingThis = current != null && current.id.equals(b.id) && playing;
            if (p == null) {
                if (remote == 0 && !full) return;
                p = newProgress(remote, remote, full);
                prog.put(b.id, p);
            } else if (num(p, "pos") == num(p, "synced") && !playingThis) {
                p.put("pos", Long.valueOf(remote)); p.put("synced", Long.valueOf(remote));
                p.put("full", Boolean.valueOf(full));
            }
        }
    }

    private static Map<String, Object> newProgress(long pos, long synced, boolean full) {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("pos", Long.valueOf(pos)); p.put("synced", Long.valueOf(synced));
        p.put("ts", Long.valueOf(0)); p.put("full", Boolean.valueOf(full));
        return p;
    }

    private static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    public long progressMs(String id) {
        synchronized (store) {
            Object p = store.section("progress").get(id);
            return p instanceof Map ? num(Json.map(p), "pos") : 0;
        }
    }

    public boolean isFinished(String id) {
        synchronized (store) {
            Object p = store.section("progress").get(id);
            return p instanceof Map && Boolean.TRUE.equals(Json.map(p).get("full"));
        }
    }

    private long lastPlayedTs(String id) {
        synchronized (store) {
            Object p = store.section("progress").get(id);
            return p instanceof Map ? num(Json.map(p), "ts") : 0;
        }
    }

    /** Books with progress that are not finished, most recently listened to on this Y1 first. */
    public List<Book> inProgress() {
        List<Book> out = new ArrayList<Book>();
        for (Book b : books) if (progressMs(b.id) > 30000 && !isFinished(b.id)) out.add(b);
        Collections.sort(out, new Comparator<Book>() {
            public int compare(Book a, Book b) { return Long.valueOf(lastPlayedTs(b.id)).compareTo(Long.valueOf(lastPlayedTs(a.id))); }
        });
        return out;
    }

    private void setProgress(Book b, long pos, boolean full) {
        if (b.kind.equals("audiobook") && b.durS > 0 && pos > b.durS * 1000 + 5000) return; // a stale position from somewhere else
        synchronized (store) {
            Map<String, Object> prog = store.section("progress");
            Map<String, Object> p = prog.get(b.id) instanceof Map ? Json.map(prog.get(b.id)) : newProgress(0, 0, false);
            p.put("pos", Long.valueOf(pos)); p.put("ts", Long.valueOf(System.currentTimeMillis()));
            p.put("full", Boolean.valueOf(full));
            prog.put(b.id, p);
        }
        store.save();
    }

    /** Push every unsynced position to Music Assistant. */
    @SuppressWarnings("unchecked")
    private void pushProgress() throws IOException {
        List<String[]> todo = new ArrayList<String[]>();
        synchronized (store) {
            for (Map.Entry<String, Object> e : store.section("progress").entrySet()) {
                Map<String, Object> p = Json.map(e.getValue());
                if (num(p, "pos") != num(p, "synced")) {
                    todo.add(new String[] { e.getKey(), String.valueOf(num(p, "pos")), String.valueOf(p.get("full")) });
                }
            }
        }
        for (String[] t : todo) {
            Book b = book(t[0]);
            if (b == null) continue;
            long pos = Long.parseLong(t[1]);
            Map<String, Object> item = MaClient.args("item_id", b.id, "provider", "library", "name", b.name,
                "media_type", "audiobook");
            ma.call("music/mark_played", MaClient.args("media_item", item, "fully_played", Boolean.valueOf("true".equals(t[2])),
                "seconds_played", Long.valueOf(pos / 1000), "is_playing", Boolean.FALSE, "user_initiated", Boolean.FALSE), 15000);
            synchronized (store) {
                Map<String, Object> p = Json.map(store.section("progress").get(t[0]));
                if (num(p, "pos") == pos) p.put("synced", Long.valueOf(pos)); // unchanged while we pushed
            }
            log("synced " + b.name + " at " + (pos / 60000) + " min");
        }
        if (!todo.isEmpty()) store.save();
    }

    private final Object syncLock = new Object();

    private void syncLoop() {
        synchronized (syncLock) { syncLoopLocked(); }
    }

    private void syncLoopLocked() {
        if (ma == null) return;
        try {
            pushProgress();
            refreshLibrary();
            online = true;
            refreshSpeakers();
            refreshMusic();
            resumeDownloads();
            autoNext();
            syncMusic();
            favs.sync(ma, musicLib);
            cacheChapters();
            if (message.startsWith("Offline")) say("");
            lastSyncAt = System.currentTimeMillis();
        } catch (IOException e) {
            online = false;
            log("sync: " + e.getMessage());
        } catch (RuntimeException e) {
            log("sync error: " + e);
        }
    }

    /** Called by the UI when it comes to the front, so the library and progress are fresh. */
    private final java.util.concurrent.atomic.AtomicBoolean syncQueued = new java.util.concurrent.atomic.AtomicBoolean();

    /** One sync soon. Requests while one is already waiting are folded in, so screen wakes cannot pile up threads. */
    public void syncNow() {
        if (!syncQueued.compareAndSet(false, true)) return;
        pool.execute(new Runnable() { public void run() { syncQueued.set(false); syncLoop(); } });
    }

    @SuppressWarnings("unchecked")
    private void refreshSpeakers() throws IOException {
        Object r = ma.call("players/all", null, 15000);
        List<Speaker> out = new ArrayList<Speaker>();
        for (Object e : (List<Object>) r) {
            Map<String, Object> m = Json.map(e);
            String prov = String.valueOf(m.get("provider"));
            if (!Boolean.TRUE.equals(m.get("available"))) continue;
            if (!(prov.equals("sonos") || prov.equals("airplay"))) continue;
            Speaker s = new Speaker();
            s.id = String.valueOf(m.get("player_id"));
            s.name = String.valueOf(m.get("display_name"));
            s.playing = "playing".equals(m.get("playback_state"));
            out.add(s);
        }
        Collections.sort(out, new Comparator<Speaker>() {
            public int compare(Speaker a, Speaker b) { return a.name.compareToIgnoreCase(b.name); }
        });
        speakers = out;
    }

    // ------------------------------------------------------------------ offline cache

    private File fileFor(Book b) {
        File f = new File(dlDir(), b.id + ".m4b"), g = new File(legacyDir, b.id + ".m4b");
        return !f.exists() && g.exists() ? g : f;
    }

    public boolean isDownloaded(Book b) { return fileFor(b).exists(); }

    public long downloadedBytes() {
        long n = 0;
        for (File d : new File[] { dlDir(), legacyDir }) {
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) if (f.getName().endsWith(".m4b")) n += f.length();
        }
        return n;
    }

    public List<Book> downloadedBooks() {
        List<Book> out = new ArrayList<Book>();
        for (Book b : books) if (isDownloaded(b)) out.add(b);
        return out;
    }

    private String urlFor(Book b) throws IOException {
        StringBuilder sb = new StringBuilder("http://" + host + ":" + gwPort + "/" + gwPrefix);
        for (String seg : b.path.split("/")) sb.append('/').append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
        return sb.toString();
    }

    /** The user asked for this book: download it, and forget any earlier "don't auto-download" choice. */
    public void download(Book b) {
        setNoAuto(b, false);
        startDownload(b);
    }

    private void startDownload(final Book b) {
        if (!sdReady()) { say("Storage card not ready yet"); return; }
        synchronized (downloading) {
            if (downloading.containsKey(b.id) || isDownloaded(b)) return;
            downloading.put(b.id, new long[] { 0, 0 });
            cancelDl.remove(b.id);
        }
        Intent i = new Intent(app, PlayService.class);
        app.startService(i);
        pool.execute(new Runnable() { public void run() { doDownload(b); } });
    }

    // ---- keep everything offline: the owner's whole profile, not just what she picked

    // ---- sync status for the home screen

    public volatile long lastSyncAt;
    private final long[] sampleT = new long[90], sampleB = new long[90];
    private int sampleN;

    private void sampleThroughput() {
        long now = System.currentTimeMillis();
        sampleT[sampleN % 90] = now;
        sampleB[sampleN % 90] = Net.bytes.get();
        sampleN++;
    }

    /** Bytes per second over the last minute or so. */
    private double rate() {
        if (sampleN < 5) return 0;
        int newest = (sampleN - 1) % 90, oldest = sampleN >= 90 ? sampleN % 90 : 0;
        long dt = sampleT[newest] - sampleT[oldest];
        return dt < 5000 ? 0 : (sampleB[newest] - sampleB[oldest]) * 1000.0 / dt;
    }

    private long audiobookBytesMissing() {
        long n = 0;
        for (Book b : books) if (!isDownloaded(b) && !noAuto(b)) n += b.durS * 13750L;
        return n;
    }

    public boolean syncing() {
        synchronized (downloading) { if (!downloading.isEmpty()) return true; }
        return musicLib.status.length() > 0;
    }

    /** Storage line for the home screen: "43% full". */
    public String storageTitle() {
        try {
            android.os.StatFs s = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
            long total = (long) s.getBlockCount() * s.getBlockSize(), free = (long) s.getAvailableBlocks() * s.getBlockSize();
            return "Storage " + (total == 0 ? 0 : (total - free) * 100 / total) + "% full";
        } catch (RuntimeException e) { return "Storage"; }
    }

    /** Second line: when we last synced, or how long this sync has left. */
    public String syncSummary() {
        long remaining = audiobookBytesMissing() * (keepAll() ? 1 : 0) + musicLib.missingBytes;
        if (!online) return "Offline" + (lastSyncAt > 0 ? " · last synced " + ago(lastSyncAt) : "");
        if (syncing()) {
            double r = rate();
            String left = r > 50000 ? "about " + duration((long) (remaining / r)) + " left" : "working out the time left";
            return "Syncing · " + left + " · " + (remaining >> 20) + " MB to go";
        }
        int still = musicLib.serverPending;
        String s = (lastSyncAt > 0 ? "Up to date · synced " + ago(lastSyncAt) : "Not synced yet");
        if (still > 0) s += " · " + still + " tracks being prepared";
        int gone = musicLib.serverUnavailable;
        if (gone > 0) s += " · " + gone + (gone == 1 ? " song" : " songs") + " not in Apple Music";
        return s;
    }

    public void forceSync() {
        say("Syncing now...");
        pool.execute(new Runnable() { public void run() { syncLoop(); } });
    }

    private static String ago(long t) {
        long s = (System.currentTimeMillis() - t) / 1000;
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " days ago";
    }

    private static String duration(long s) {
        if (s < 90) return "a minute";
        if (s < 5400) return (s / 60) + " min";
        return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
    }

    public boolean keepAll() { return store.num("keepAll", 1) == 1; }

    public void setKeepAll(boolean on) {
        store.put("keepAll", Long.valueOf(on ? 1 : 0));
        touched("keep_offline");
        if (on) pool.execute(new Runnable() { public void run() { autoNext(); } });
    }

    @SuppressWarnings("unchecked")
    private boolean noAuto(Book b) {
        synchronized (store) { return store.section("noAuto").containsKey(b.id); }
    }

    private void setNoAuto(Book b, boolean on) {
        synchronized (store) {
            Map<String, Object> m = store.section("noAuto");
            if (on) m.put(b.id, Boolean.TRUE); else m.remove(b.id);
        }
        store.save();
    }

    private boolean onWifi() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo n = cm.getActiveNetworkInfo();
        return n != null && n.isConnected() && n.getType() == android.net.ConnectivityManager.TYPE_WIFI;
    }

    /** Big downloads only when plugged in or the battery is comfortable. */
    private boolean batteryOk() {
        Intent b = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return true;
        int plugged = b.getIntExtra("plugged", 0), level = b.getIntExtra("level", 100), scale = b.getIntExtra("scale", 100);
        return plugged != 0 || level * 100 / Math.max(1, scale) >= 40;
    }

    /** Start the next missing book, one at a time: in-progress first, finished ones last. */
    private synchronized void autoNext() {
        if (!keepAll() || ma == null || !sdReady()) return;
        if (!online || !onWifi() || !batteryOk()) { log("auto-download waiting: online=" + online + " wifi=" + onWifi() + " battery=" + batteryOk()); return; }
        synchronized (downloading) { if (!downloading.isEmpty()) return; }
        Book pick = null;
        int bestRank = 9;
        for (Book b : books) {
            if (isDownloaded(b) || noAuto(b)) continue;
            int rank = progressMs(b.id) > 30000 && !isFinished(b.id) ? 0 : (isFinished(b.id) ? 2 : 1);
            if (rank < bestRank) { bestRank = rank; pick = b; }
        }
        if (pick == null) { log("auto-download: nothing left"); return; }
        long need = pick.durS * 14000L + (64L << 20); // ~110 kbps plus headroom
        log("auto-download check: dir=" + dlDir() + " free=" + (dlDir().getUsableSpace() >> 20) + " MB need=" + (need >> 20) + " MB");
        if (dlDir().getUsableSpace() < need + (1L << 30)) { say("Storage nearly full: not downloading more"); return; }
        log("auto-download " + pick.name);
        startDownload(pick);
    }

    /** "n of m offline" for the Status screen. */
    public String offlineSummary() {
        int n = 0;
        for (Book b : books) if (isDownloaded(b)) n++;
        return n + " of " + books.size() + " books offline";
    }

    /** A download cut short by a Wi-Fi drop leaves a .part file; carry on from where it stopped. */
    private void resumeDownloads() {
        File[] fs = dlDir().listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (!f.getName().endsWith(".part")) continue;
            Book b = book(f.getName().substring(0, f.getName().length() - 5));
            if (b != null) startDownload(b);
        }
    }

    public void cancelDownload(Book b) {
        setNoAuto(b, true);
        synchronized (downloading) { cancelDl.add(b.id); }
    }

    private void doDownload(Book b) {
        File part = new File(dlDir(), b.id + ".part");
        netLock.acquire();
        try {
            long have = part.exists() ? part.length() : 0;
            HttpURLConnection c = (HttpURLConnection) new URL(urlFor(b)).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            if (code == 200) have = 0;
            long total = have + c.getContentLength();
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(part, have > 0);
            try {
                byte[] buf = new byte[65536];
                long done = have;
                int n;
                long lastLog = 0;
                while ((n = in.read(buf)) > 0) {
                    synchronized (downloading) { if (cancelDl.contains(b.id)) throw new IOException("cancelled"); }
                    out.write(buf, 0, n);
                    done += n;
                    long[] pr;
                    synchronized (downloading) { pr = downloading.get(b.id); }
                    if (pr != null) { pr[0] = done; pr[1] = total; }
                    Net.bytes.addAndGet(n);
                    if (done - lastLog > 8 << 20) { lastLog = done; log("download " + b.name + " " + (done >> 20) + "/" + (total >> 20) + " MB"); }
                }
            } finally { out.close(); in.close(); }
            if (!part.renameTo(fileFor(b))) throw new IOException("rename failed");
            log("downloaded " + b.name);
        } catch (IOException e) {
            log("download failed: " + e.getMessage());
            say("Download failed: " + e.getMessage());
            if ("cancelled".equals(e.getMessage())) part.delete();
        } finally {
            synchronized (downloading) { downloading.remove(b.id); cancelDl.remove(b.id); }
            netLock.release();
            maybeStopService();
            pool.execute(new Runnable() { public void run() { autoNext(); } });
        }
    }

    public void deleteDownload(Book b) {
        if (current != null && current.id.equals(b.id) && playing && targetId.length() == 0) return;
        setNoAuto(b, true); // the listener removed it on purpose, so don't fetch it again behind her back
        new File(dlDir(), b.id + ".m4b").delete();
        new File(legacyDir, b.id + ".m4b").delete();
        new File(dlDir(), b.id + ".part").delete();
    }

    // ------------------------------------------------------------------ playback

    private void maybeStopService() { /* the service always runs: it is what keeps the app alive */ }

    /** Duration to show for a book: the live one for the current book, else the library's. */
    public long durMsFor(Book b) { return (current != null && current.id.equals(b.id) && durMs > 0) ? durMs : b.durS * 1000; }

    /** Live position for the current book, saved position otherwise. */
    public long progressMsOrLive(Book b) { return (current != null && current.id.equals(b.id)) ? livePosMs() : progressMs(b.id); }

    /** Play a book from the beginning. */
    public void restart(final Book b) {
        if (bedtimeBlocks()) return;
        pool.execute(new Runnable() { public void run() {
            try { startOn(b, targetId, 0); } catch (Exception e) { fail(e); }
        } });
    }

    /** Play a book from ms (a chapter's start). */
    public void playFrom(final Book b, final long ms) {
        if (bedtimeBlocks()) return;
        pool.execute(new Runnable() { public void run() {
            try { startOn(b, targetId, ms); } catch (Exception e) { fail(e); }
        } });
    }

    public String nowText() { return current == null ? "" : current.name; }

    /** Start (or resume) a book on the chosen target; the saved position is honoured. */
    public void play(final Book b) {
        if (bedtimeBlocks()) return;
        pool.execute(new Runnable() { public void run() {
            try { startOn(b, targetId, progressMsFor(b)); } catch (Exception e) { fail(e); }
        } });
    }

    private long progressMsFor(Book b) {
        if (isFinished(b.id)) return 0;
        return progressMs(b.id);
    }

    private void fail(Exception e) {
        log("play failed: " + e);
        say("Can't play: " + e.getMessage());
    }

    private synchronized void startOn(Book b, String target, long startMs) throws IOException {
        localMusic = false;
        leaveMusic();
        stopLocal(false);
        current = b;
        durMs = b.durS * 1000;
        posMs = startMs;
        if (target.length() == 0) {
            startLocal(b, startMs);
        } else {
            setProgress(b, startMs, false);
            pushProgress(); // so the speaker resumes exactly where this Y1 is
            ma.call("player_queues/play_media", MaClient.args("queue_id", target, "media", "library://audiobook/" + b.id,
                "option", "replace"), 20000);
            sleep(2500);
            seekRemoteIfNeeded(target, startMs);
            playing = true;
        }
        if (store != null) { store.put("lastBook", b.id); rememberLast("audiobook"); }
    }

    private void seekRemoteIfNeeded(String queue, long wantMs) throws IOException {
        for (int i = 0; i < 3; i++) {
            Map<String, Object> q = Json.map(ma.call("player_queues/get", MaClient.args("queue_id", queue), 10000));
            double el = q.get("elapsed_time") instanceof Number ? ((Number) q.get("elapsed_time")).doubleValue() : 0;
            if (Math.abs(el * 1000 - wantMs) < 8000) return;
            ma.call("player_queues/seek", MaClient.args("queue_id", queue, "position", Long.valueOf(wantMs / 1000)), 10000);
            sleep(1500);
        }
    }

    /** After a music handover, put the speaker at the same second (MA starts a track at 0:00). Under 3 s: leave it. */
    private void seekMusicTo(String queue, long ms) {
        if (ms < 3000) return;
        try {
            for (int i = 0; i < 10; i++) {    // wait until the speaker is really playing the track, then seek
                Map<String, Object> q = Json.map(ma.call("player_queues/get", MaClient.args("queue_id", queue), 10000));
                if ("playing".equals(String.valueOf(q.get("state")))) break;
                sleep(500);
            }
            seekRemoteIfNeeded(queue, ms);
            posMs = ms;
        } catch (IOException e) { log("music handover seek: " + e.getMessage()); }
    }

    /** How far into the current item a speaker's queue is, in ms (0 if unknown). */
    private long queueElapsedMs(String queue) {
        try {
            Map<String, Object> q = Json.map(ma.call("player_queues/get", MaClient.args("queue_id", queue), 10000));
            return q.get("elapsed_time") instanceof Number ? (long) (((Number) q.get("elapsed_time")).doubleValue() * 1000) : 0;
        } catch (IOException e) { return 0; }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    private void startLocal(Book b, final long startMs) throws IOException {
        String src;
        if (isDownloaded(b)) src = fileFor(b).getPath();
        else if (ma != null && online) src = urlFor(b);
        else throw new IOException("Not downloaded and offline");
        MediaPlayer p = new MediaPlayer();
        p.setAudioStreamType(AudioManager.STREAM_MUSIC);
        p.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK);
        p.setDataSource(src);
        mpReady = false;
        wantPlay = true;
        mp = p;
        final Book bk = b;
        p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer m) {
                mpReady = true;
                if (mp != m) return;
                if (startMs > 0) m.seekTo((int) startMs);
                durMs = m.getDuration() > 0 ? m.getDuration() : durMs;
                if (wantPlay) { requestFocus(); m.start(); playing = true; }
            }
        });
        p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer m) {
                setProgress(bk, durMs, true);
                playing = false;
                wantPlay = false;
                maybeStopService();
                syncNow();
            }
        });
        p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer m, int what, int extra) {
                log("player error " + what + "/" + extra);
                say("Playback error " + what);
                if (mp == m) {   // drop the dead player, so Play starts afresh at the saved place
                    try { if (mpReady) setProgress(bk, m.getCurrentPosition(), false); } catch (RuntimeException ignored) { }
                    mp = null;
                    mpReady = false;
                    try { m.release(); } catch (RuntimeException ignored) { }
                }
                playing = false;
                wantPlay = false;
                return true;
            }
        });
        p.prepareAsync();
        app.startService(new Intent(app, PlayService.class));
        log("local play " + b.name + " from " + (startMs / 1000) + " s via " + (isDownloaded(b) ? "file" : "stream"));
    }

    private void stopLocal(boolean keepCurrent) {
        MediaPlayer p = mp;
        if (p != null) {
            try {
                if (mpReady) { posMs = p.getCurrentPosition(); }
                if (current != null && current.kind.equals("audiobook")) setProgress(current, posMs, false);
                p.release();
            } catch (RuntimeException ignored) { }
            mp = null;
        }
        mpReady = false;
        wantPlay = false;
        if (!keepCurrent) playing = false;
        audio.abandonAudioFocus(focus);
        maybeStopService();
    }

    private final AudioManager.OnAudioFocusChangeListener focus = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS) pause();
            else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { resumeOnFocus = playing; pause(); }
            else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeOnFocus) { resumeOnFocus = false; togglePlay(); }
        }
    };

    private void requestFocus() {
        audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        audio.registerMediaButtonEventReceiver(new android.content.ComponentName(app, MediaButtonReceiver.class));
    }

    /**
     * Pause, and only pause: never the play/pause toggle, so a repeated pause (bedtime, sleep timer, headphones, the
     * watchdog, Find) can never start playback again.
     */
    public void pause() {
        if (!playing) return;
        pool.execute(new Runnable() { public void run() {
            try {
                if (current == null) { playing = false; return; }
                String q = queueId();
                if (q.length() == 0) {
                    MediaPlayer p = mp;
                    if (p != null && mpReady && p.isPlaying()) {
                        p.pause();
                        if (current.kind.equals("audiobook")) { setProgress(current, p.getCurrentPosition(), false); syncNow(); }
                        else if (localMusic) store.put("lastPos", Long.valueOf(p.getCurrentPosition()));
                    }
                    wantPlay = false;
                } else {
                    ma.call("player_queues/pause", MaClient.args("queue_id", q), 10000);
                }
                playing = false;
            } catch (Exception e) { fail(e); }
        } });
    }

    public void resume() { if (!playing) togglePlay(); }

    /** True when audio is produced by this Y1 itself: a local file, or its own Sendspin speaker. */
    private boolean ownAudio() {
        String q = queueId();
        return q.length() == 0 || q.equals(speakerQueue);
    }

    /** Queue the transport commands go to. "" means local file playback (audiobooks on this Y1). */
    private String queueId() {
        if (musicMode) return targetId.length() > 0 ? targetId : speakerQueue;
        return targetId;
    }

    public void togglePlay() {
        if (!playing && bedtimeBlocks()) return;
        pool.execute(new Runnable() { public void run() {
            try {
                if (current == null) return;
                String q = queueId();
                if (q.length() == 0) {
                    MediaPlayer p = mp;
                    if (p == null) {        // nothing loaded yet (after a restart only restoreLast ran): start it, as Continue does
                        if (current.kind.equals("audiobook")) startOn(current, q, progressMsFor(current));
                        else if (hasQueue()) synchronized (Engine.this) { localMusic = true; resumeAtMs = posMs; startTrackLocal(mqi); }
                        else playMusic(current);
                        return;
                    }
                    if (!mpReady) return;   // still loading
                    if (p.isPlaying()) {
                        p.pause(); playing = false; wantPlay = false;
                        if (current.kind.equals("audiobook")) { setProgress(current, p.getCurrentPosition(), false); syncNow(); }
                        else if (localMusic) store.put("lastPos", Long.valueOf(p.getCurrentPosition()));
                    }
                    else { requestFocus(); p.start(); playing = true; wantPlay = true; }
                } else if (!playing && !musicMode) {
                    // idle speaker: its queue may be stale or on another source, so start afresh: a book at its saved place,
                    // music from the current track of the iPod's queue (never as a book: that is "can't play")
                    if (current.kind.equals("audiobook")) startOn(current, q, progressMsFor(current));
                    else if (hasQueue()) synchronized (Engine.this) {
                        long at = posMs;
                        startRemoteList(mq, Math.max(0, Math.min(mq.size() - 1, mqi)), false);
                        seekMusicTo(q, at);
                    }
                    else playMusic(current);
                } else {
                    ma.call("player_queues/play_pause", MaClient.args("queue_id", q), 10000);
                    playing = !playing;
                }
            } catch (Exception e) { fail(e); }
        } });
    }

    // ---- audiobook chapters (from Music Assistant, cached in files/chapters/<id>.json so they work offline)

    private final Map<String, Chapters> chapterCache = new HashMap<String, Chapters>();
    private final Set<String> chapterTried = new HashSet<String>();

    /** The book's chapters, or null (none, or not fetched yet: a fetch starts when online). */
    public Chapters chapters(final Book b) {
        if (b == null || !"audiobook".equals(b.kind)) return null;
        synchronized (chapterCache) {
            if (chapterCache.containsKey(b.id)) return chapterCache.get(b.id);
            File f = chapterFile(b);
            if (f.exists()) {
                Chapters c = null;
                try { c = Chapters.from(Json.parse(Net.readText(f))); } catch (Exception ignored) { }
                chapterCache.put(b.id, c);
                return c;
            }
            if (ma == null || !online || !chapterTried.add(b.id)) return null;
        }
        pool.execute(new Runnable() { public void run() { fetchChapters(b); } });
        return null;
    }

    private File chapterFile(Book b) {
        File d = new File(app.getFilesDir(), "chapters");
        d.mkdirs();
        return new File(d, b.id.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }

    @SuppressWarnings("unchecked")
    private void fetchChapters(Book b) {
        try {
            Map<String, Object> m = Json.map(ma.call("music/audiobooks/get_audiobook", MaClient.args("item_id", b.id,
                "provider_instance_id_or_domain", "library"), 20000));
            Object ch = m.get("metadata") instanceof Map ? Json.map(m.get("metadata")).get("chapters") : null;
            String json = Json.write(ch instanceof List ? ch : new ArrayList<Object>());
            FileOutputStream o = new FileOutputStream(chapterFile(b));
            try { o.write(json.getBytes("UTF-8")); } finally { o.close(); }
            synchronized (chapterCache) { chapterCache.put(b.id, Chapters.from(ch)); }
        } catch (Exception e) {
            synchronized (chapterCache) { chapterTried.remove(b.id); }
        }
    }

    /** Fetch chapters for every downloaded book that has none cached yet (from the sync loop, for offline use). */
    private void cacheChapters() {
        List<Book> bs;
        synchronized (this) { bs = new ArrayList<Book>(books); }
        for (Book b : bs) if (isDownloaded(b) && !chapterFile(b).exists()) fetchChapters(b);
    }

    /** The chapter label for Now Playing ("Chapter 7 of 22"), or null. */
    public String chapterLabel() {
        Book b = current;
        if (b == null || musicMode) return null;
        Chapters c = chapters(b);
        return c == null ? null : c.label(livePosMs());
    }

    /** Next: the next chapter in a book with chapters, else the next song, else 30 s on. */
    public void next() { if (!playing && bedtimeBlocks()) return; chapterJump(true); }

    /** Previous: the start of this chapter (or the one before), else the previous song, else 30 s back. */
    public void previous() { if (!playing && bedtimeBlocks()) return; chapterJump(false); }

    private void chapterJump(boolean fwd) {
        Book b = current;
        Chapters c = (b != null && !musicMode) ? chapters(b) : null;
        if (c == null) { skip(fwd ? 30 : -30); return; }
        long pos = livePosMs();
        long to = fwd ? c.next(pos) : c.previous(pos);
        if (to < 0) return;
        seekBook(to);
    }

    /** Put the audiobook at ms, here or on the speaker. */
    public void seekBook(final long ms) {
        pool.execute(new Runnable() { public void run() {
            try {
                if (current == null || musicMode) return;
                if (targetId.length() == 0) {
                    MediaPlayer p = mp;
                    if (p == null || !mpReady) return;
                    int to = (int) Math.max(0, Math.min(p.getDuration() - 1000, ms));
                    p.seekTo(to);
                    posMs = to;
                    setProgress(current, to, false);
                } else {
                    ma.call("player_queues/seek", MaClient.args("queue_id", targetId, "position", Long.valueOf(ms / 1000)), 10000);
                    posMs = ms;
                    posStamp = System.currentTimeMillis();
                }
            } catch (Exception e) { fail(e); }
        } });
    }

    /** Skip forwards or back, in seconds (audiobooks). */
    public void skip(final int seconds) {
        if (!playing && bedtimeBlocks()) return;
        pool.execute(new Runnable() { public void run() {
            try {
                if (current == null) return;
                if (localMusic) { if (seconds > 0) nextTrack(); else prevTrack(); return; }
                if (musicMode) { track(seconds > 0); return; }
                if (targetId.length() == 0) {
                    MediaPlayer p = mp;
                    if (p == null || !mpReady) return;
                    int to = Math.max(0, Math.min(p.getDuration() - 1000, p.getCurrentPosition() + seconds * 1000));
                    p.seekTo(to);
                    posMs = to;
                    setProgress(current, to, false);
                } else {
                    ma.call("player_queues/skip", MaClient.args("queue_id", targetId, "seconds", Long.valueOf(seconds)), 10000);
                }
            } catch (Exception e) { fail(e); }
        } });
    }

    /** Next or previous track (music). */
    private void track(boolean next) throws IOException {
        String q = queueId();
        if (q.length() > 0) ma.call(next ? "player_queues/next" : "player_queues/previous", MaClient.args("queue_id", q), 10000);
    }

    /** Volume nudge: positive/negative steps. */
    public void volume(final int steps) {
        if (ownAudio()) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, steps > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            enforceVolumeLimit();
            return;
        }
        pool.execute(new Runnable() { public void run() {
            try {
                int v = Math.max(0, Math.min(Math.min(100, volumeLimit()), (remoteVolume < 0 ? 30 : remoteVolume) + steps * 4));
                remoteVolume = v;
                ma.call("players/cmd/volume_set", MaClient.args("player_id", targetId, "volume_level", Long.valueOf(v)), 10000);
            } catch (Exception e) { fail(e); }
        } });
    }

    public int localVolumePercent() {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max == 0 ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max;
    }

    /** Choose where audio comes out. If something is playing it moves there, carrying the place with it. */
    public void setTarget(final String id, final String name) {
        pool.execute(new Runnable() { public void run() {
            try { moveTo(id, name); } catch (Exception e) { fail(e); }
        } });
    }

    private synchronized void moveTo(String id, String name) throws IOException {
        String old = targetId;
        Book b = current;
        long pos = posMs;
        boolean was = playing;
        if (localMusic) { // synced music playing from files: carry on from the same track on a speaker
            MusicLibrary.Track t = curTrack;
            targetId = id;
            targetName = name;
            store.put("targetId", id);
            store.put("targetName", name);
            remoteVolume = -1;
            if (id.length() > 0) {
                boolean wasPlaying = playing;
                MediaPlayer p = mp;
                long at = p != null && mpReady ? p.getCurrentPosition() : posMs;   // where the song is now
                stopLocal(false);
                localMusic = false;
                posMs = at;
                store.put("lastPos", Long.valueOf(at));
                if (t != null && wasPlaying) {
                    startRemoteList(mq, Math.max(0, Math.min(mq.size() - 1, mqi)), false);
                    seekMusicTo(id, at);       // the speaker carries on mid-song, not from the start
                }
            }
            return;
        }
        if (musicMode) { // music: the queue itself moves, MA keeps the track and place
            String oldQ = queueId();
            if (id.length() == 0) { // back to this Y1: play from the synced file when we have it
                MusicLibrary.Track t = queueTrack(oldQ);
                if (t != null) {
                    long el = queueElapsedMs(oldQ);   // where the speaker had got to in the song
                    try { ma.call("player_queues/pause", MaClient.args("queue_id", oldQ), 8000); } catch (IOException ignored) { }
                    resumeAtMs = el;
                    targetId = "";
                    targetName = name;
                    store.put("targetId", "");
                    store.put("targetName", name);
                    musicMode = false;
                    localMusic = true;
                    mq = new ArrayList<MusicLibrary.Track>(musicLib.tracks());
                    int at = 0;
                    for (int k = 0; k < mq.size(); k++) if (mq.get(k).id.equals(t.id)) at = k;
                    if (was && !bedtimeBlocks()) { startTrackLocal(at); return; }
                    // it was paused (or bedtime is over): set the song up here, paused at the same place
                    mqi = at;
                    curTrack = t;
                    current = trackBook(t);
                    nowTitle = t.title;
                    nowSub = t.artist;
                    durMs = t.durS * 1000;
                    posMs = el;
                    resumeAtMs = 0;
                    playing = false;
                    saveQueue();
                    store.put("lastPos", Long.valueOf(el));
                    return;
                }
            }
            targetId = id;
            targetName = name;
            store.put("targetId", id);
            store.put("targetName", name);
            remoteVolume = -1;
            if (id.equals(old)) return;
            String newQ = id.length() > 0 ? id : ensureSpeaker();
            if (b != null && !oldQ.equals(newQ)) {   // paused music moves too, still paused, so Play carries on with it
                ma.call("player_queues/transfer", MaClient.args("source_queue_id", oldQ, "target_queue_id", newQ, "auto_play", Boolean.valueOf(was)), 20000);
            }
            return;
        }
        if (b != null && old.length() > 0 && !id.equals(old)) { // leaving a speaker: take its place
            try {
                Map<String, Object> q = Json.map(ma.call("player_queues/get", MaClient.args("queue_id", old), 10000));
                Object ci = q.get("current_item");
                String qn = ci instanceof Map && Json.map(ci).get("name") != null ? String.valueOf(Json.map(ci)).length() > 0 ? String.valueOf(Json.map(ci).get("name")) : null : null;
                // only take the speaker's place if it really is playing this book: an idle queue holds some older item
                if (qn != null && qn.equals(b.name) && q.get("elapsed_time") instanceof Number) {
                    long el = (long) (((Number) q.get("elapsed_time")).doubleValue() * 1000);
                    if (el >= 0 && el <= b.durS * 1000) pos = el;
                }
                ma.call("player_queues/pause", MaClient.args("queue_id", old), 10000);
            } catch (IOException e) { log("handoff read: " + e.getMessage()); }
        } else if (b != null && old.length() == 0 && mp != null && mpReady) {
            pos = mp.getCurrentPosition();
        }
        targetId = id;
        targetName = name;
        store.put("targetId", id);
        store.put("targetName", name);
        remoteVolume = -1;
        if (b != null && was && !id.equals(old)) {
            startOn(b, id, pos);
        } else if (b != null && !id.equals(old)) {
            stopLocal(true);
            current = b; posMs = pos; playing = false;
            setProgress(b, pos, false);
        }
    }

    // ------------------------------------------------------------------ synced music, played from files on the Y1

    public volatile boolean localMusic;
    public volatile boolean shuffle;
    private List<MusicLibrary.Track> mq = new ArrayList<MusicLibrary.Track>();
    private int mqi;
    private MusicLibrary.Track curTrack;

    private void syncMusic() {
        if (host.length() == 0 || gwPrefix.length() == 0 || !onWifi()) return;
        final String base = "http://" + host + ":" + gwPort + "/" + gwPrefix + "/music";
        pool.execute(new Runnable() { public void run() {
            try {
                musicLib.sync(base, new Net.Gate() { public boolean ok() { return onWifi() && batteryOk(); } });
            } catch (IOException e) { log("music sync: " + e.getMessage()); }
        } });
    }

    private Book trackBook(MusicLibrary.Track t) {
        Book b = new Book();
        b.kind = "track";
        b.id = t.id;
        b.name = t.title;
        b.author = t.artist;
        b.uri = t.uri;
        b.path = t.title;
        b.durS = t.durS;
        b.coverLocal = musicLib.coverFile(t);
        return b;
    }

    /** Play a list of synced tracks from index. On this Y1 they play from files; on a speaker MA plays the playlist from that track. */
    public void playTracks(final List<MusicLibrary.Track> list, final int index, final boolean shuf) {
        if (bedtimeBlocks()) return;
        pool.execute(new Runnable() { public void run() {
            try { startTracks(list, index, shuf); } catch (Exception e) { fail(e); }
        } });
    }

    private synchronized void startTracks(List<MusicLibrary.Track> list, int index, boolean shuf) throws IOException {
        if (list.isEmpty()) throw new IOException("No music on the iPod yet");
        shuffle = shuf;
        MusicLibrary.Track first = list.get(index);
        if (targetId.length() == 0) {
            if (musicMode) leaveMusic();
            stopLocal(false);
            mq = new ArrayList<MusicLibrary.Track>(list);
            if (shuf) {
                mq.remove(index);
                Collections.shuffle(mq);
                mq.add(0, first);
                mqi = 0;
            } else mqi = index;
            localMusic = true;
            musicMode = false;
            saveQueue();
            startTrackLocal(mqi);
        } else {
            startRemoteList(list, index, shuf);
        }
    }

    /** The URI a speaker should play for a synced song: the version recorded (clean or catalogue), else the original. */
    private static String speakerUri(MusicLibrary.Track t) { return t.playUri != null ? t.playUri : t.uri; }

    /** The queue as it is now (mq, mqi), for restoreLast: track ids, the index, and the current track's id. */
    private void saveQueue() {
        StringBuilder ids = new StringBuilder();
        for (MusicLibrary.Track x : mq) { if (ids.length() > 0) ids.append(','); ids.append(x.id); }
        store.put("lastQueue", ids.toString());
        store.put("lastIdx", Long.valueOf(mqi));
        if (mqi >= 0 && mqi < mq.size()) store.put("lastTrack", mq.get(mqi).id);
    }

    /** Play these tracks, in this order, on the chosen speaker, starting at index (at most 500 around it). */
    private void startRemoteList(List<MusicLibrary.Track> list, int index, boolean shuf) throws IOException {
        MusicLibrary.Track first = list.get(index);
        stopLocal(false);
        localMusic = false;
        musicMode = true;
        current = trackBook(first);
        nowTitle = first.title;
        nowSub = first.artist;
        durMs = first.durS * 1000;
        posMs = 0;
        int from = Math.max(0, Math.min(index - 250, list.size() - 500));
        List<MusicLibrary.Track> sent = new ArrayList<MusicLibrary.Track>(list.subList(from, Math.min(list.size(), from + 500)));
        List<Object> uris = new ArrayList<Object>();
        for (MusicLibrary.Track t : sent) uris.add(speakerUri(t));
        ma.call("player_queues/play_media", MaClient.args("queue_id", targetId, "media", uris, "option", "replace",
            "start_item", speakerUri(first), "shuffle", Boolean.valueOf(shuf)), 30000);
        playing = true;
        mq = sent;                       // remembered, so a restart offers this music again
        mqi = index - from;
        saveQueue();
        store.put("lastPos", Long.valueOf(0));
        rememberLast("music");
        log("list from " + first.title + " (" + uris.size() + " tracks) on " + targetName);
    }

    /** The whole library on the speaker from this song (as recorded: clean versions, never the playlist's originals). */
    private void startRemoteTracks(MusicLibrary.Track first, boolean shuf) throws IOException {
        List<MusicLibrary.Track> all = new ArrayList<MusicLibrary.Track>(musicLib.tracks());
        if (shuf) { all.remove(first); Collections.shuffle(all); all.add(0, first); }
        int i = all.indexOf(first);
        startRemoteList(all, Math.max(0, i), shuf);
    }

    private int trackErrors;   // failed songs in a row: a queue whose files are all gone must not loop for ever

    private void startTrackLocal(int i) throws IOException {
        int n = mq.size();
        for (int k = 0; k < n && !musicLib.mp3(mq.get(i)).exists(); k++) i = (i + 1) % n;   // skip songs no longer here
        if (n == 0 || !musicLib.mp3(mq.get(i)).exists() || trackErrors >= n) {
            MediaPlayer old = mp;
            mp = null;
            if (old != null) { try { old.release(); } catch (RuntimeException ignored) { } }
            playing = false;
            wantPlay = false;
            trackErrors = 0;
            say("These songs aren\u2019t on the iPod any more");
            log("music: nothing playable left in the queue, stopped");
            return;
        }
        final MusicLibrary.Track t = mq.get(i);
        mqi = i;
        curTrack = t;
        current = trackBook(t);
        nowTitle = t.title;
        nowSub = t.artist;
        durMs = t.durS * 1000;
        final long seekTo = resumeAtMs;
        resumeAtMs = 0;
        posMs = seekTo;
        rememberLast("music");
        saveQueue();
        store.put("lastPos", Long.valueOf(seekTo));
        MediaPlayer old = mp;
        mp = null;
        if (old != null) { try { old.release(); } catch (RuntimeException ignored) { } }
        MediaPlayer p = new MediaPlayer();
        p.setAudioStreamType(AudioManager.STREAM_MUSIC);
        p.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK);
        p.setDataSource(musicLib.mp3(t).getPath());
        mpReady = false;
        wantPlay = !bedtime.locked(clock.now());   // Next during the bedtime lock moves along without playing
        if (!wantPlay) playing = false;
        mp = p;
        p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer m) {
                if (mp != m) return;
                mpReady = true;
                trackErrors = 0;
                if (m.getDuration() > 0) durMs = m.getDuration();
                if (seekTo > 0 && seekTo < m.getDuration()) m.seekTo((int) seekTo);
                if (wantPlay) { requestFocus(); m.start(); playing = true; }
            }
        });
        p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer m) { if (mp == m && localMusic) nextTrack(); }
        });
        p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer m, int what, int extra) {
                log("track error " + what + "/" + extra + " on " + t.title);
                trackErrors++;
                if (mp == m && localMusic) nextTrack();
                return true;
            }
        });
        p.prepareAsync();
        app.startService(new Intent(app, PlayService.class));
        log("music " + t.artist + " - " + t.title);
    }

    public void nextTrack() {
        pool.execute(new Runnable() { public void run() {
            synchronized (Engine.this) {
                if (!localMusic || mq.isEmpty()) return;
                try { startTrackLocal((mqi + 1) % mq.size()); } catch (IOException e) { fail(e); }
            }
        } });
    }

    public void prevTrack() {
        pool.execute(new Runnable() { public void run() {
            synchronized (Engine.this) {
                if (!localMusic || mq.isEmpty()) return;
                MediaPlayer p = mp;
                try {
                    if (p != null && mpReady && p.getCurrentPosition() > 3000) p.seekTo(0);
                    else startTrackLocal((mqi + mq.size() - 1) % mq.size());
                } catch (IOException e) { fail(e); }
            }
        } });
    }

    private static String stripArtist(String n) {
        if (n == null) return null;
        int i = n.indexOf(" - ");
        return i > 0 ? n.substring(i + 3) : n;
    }

    private volatile MusicIndex index;
    private volatile int indexVersion = -1;
    private final Object indexLock = new Object();

    /** Artists / albums / genres of what is on the Y1 now (rebuilt when the track set changes). Own lock: see dirLock. */
    public MusicIndex musicIndex() {
        int v = musicLib.version;
        MusicIndex ix = index;
        if (ix != null && v == indexVersion) return ix;
        synchronized (indexLock) {
            if (index == null || v != indexVersion) {
                List<MusicLibrary.Track> ts = musicLib.tracks();
                index = new MusicIndex(ts);
                indexVersion = musicLib.version;
            }
            return index;
        }
    }

    private Lrc lrc;
    private String lrcId = "";

    public MusicLibrary.Track playingTrack() {
        if (localMusic) return curTrack;
        if (musicMode && nowTitle.length() > 0) return musicLib.byTitleArtist(nowTitle, nowSub);
        return null;
    }

    /** Synced lyrics for the track playing now, or null. */
    public Lrc lyrics() {
        MusicLibrary.Track t = playingTrack();
        File f = musicLib.lyricsFile(t);
        if (f == null) { lrcId = ""; lrc = null; return null; }
        if (!t.id.equals(lrcId)) {
            try { Lrc l = Lrc.parse(Net.readText(f)); lrc = l.empty() ? null : l; } catch (IOException e) { lrc = null; }
            lrcId = t.id;
        }
        return lrc;
    }

    private volatile long posStamp;

    /** Position now, in ms: exact for local playback, the last poll plus elapsed time for a speaker. */
    public long livePosMs() {
        MediaPlayer p = mp;
        if (p != null && mpReady && (localMusic || (!musicMode && targetId.length() == 0))) {
            try { return p.getCurrentPosition(); } catch (RuntimeException ignored) { }
        }
        return playing && posStamp > 0 ? posMs + (System.currentTimeMillis() - posStamp) : posMs;
    }

    private MusicLibrary.Track queueTrack(String queue) {
        try {
            Map<String, Object> q = Json.map(ma.call("player_queues/get", MaClient.args("queue_id", queue), 10000));
            Object ci = q.get("current_item");
            if (!(ci instanceof Map)) return null;
            Map<String, Object> cm = Json.map(ci);
            Object mi = cm.get("media_item");
            if (mi instanceof Map) {
                Map<String, Object> mm = Json.map(mi);
                MusicLibrary.Track t = musicLib.byUri(String.valueOf(mm.get("uri")));
                if (t != null) return t;
                // the queue labels items "Artist - Title"; the media item itself carries the plain title
                if (mm.get("name") != null) {
                    StringBuilder arts = new StringBuilder();
                    if (mm.get("artists") instanceof List) for (Object a : (List<Object>) mm.get("artists"))
                        arts.append(a instanceof Map ? Json.map(a).get("name") : a).append(' ');
                    t = musicLib.byTitleArtist(String.valueOf(mm.get("name")), arts.toString());
                    if (t != null) return t;
                }
            }
            // "Artist - Title": both halves must match (a cover of the same name is a different song)
            String qn = cm.get("name") == null ? null : String.valueOf(cm.get("name"));
            int sep = qn == null ? -1 : qn.indexOf(" - ");
            return sep > 0 ? musicLib.byTitleArtist(qn.substring(sep + 3), qn.substring(0, sep)) : null;
        } catch (IOException e) { return null; }
    }

    // ------------------------------------------------------------------ music (anything that is not an audiobook)

    public volatile boolean musicMode;
    public volatile String nowTitle = "", nowSub = "";
    public final Map<String, List<Book>> music = new LinkedHashMap<String, List<Book>>(); // "Playlists" -> items
    private volatile String speakerQueue = "";
    private volatile boolean speakerWanted;
    private volatile long speakerUsedAt;
    private volatile me.cns.sendspin.SendspinClient speakerClient;
    private PowerManager.WakeLock speakerLock;
    private android.net.wifi.WifiManager.WifiLock wifiLockHolder;

    /** Wheel click feel: bit 1 = tick sound, bit 2 = vibration. Default both. */
    public int clickMode() { return (int) store.num("clickMode", 3); }

    public void setClickMode(int m) { store.put("clickMode", Long.valueOf(m)); touched("wheel_click"); }

    /** 0 soft (default), 1 medium, 2 loud. */
    public int clickLevel() { return (int) store.num("clickLevel", 0); }

    public void setClickLevel(int l) { store.put("clickLevel", Long.valueOf(l)); touched("click_loudness"); }

    /** Speakers play a little behind what Music Assistant reports; this nudges the lyrics to match (ms, -1000..+1000). */
    public int lyricOffsetMs() { return (int) store.num("lyricOff", 0); }

    public void setLyricOffsetMs(int v) { store.put("lyricOff", Long.valueOf(v)); }

    public boolean wheelReversed() { return store.num("wheelRev", 0) == 1; }

    /** Hold: quiet seconds before the buttons stop (0 = never), and how fast the three unlocking presses must be. */
    public int holdIdleS() { return (int) store.num("holdIdle", 60); }

    public void setHoldIdleS(int s) { store.put("holdIdle", Long.valueOf(s)); hold.idleS = s; touched("hold_after"); }

    public int holdWindowMs() { return (int) store.num("holdWin", 1000); }

    public void setHoldWindowMs(int ms) { store.put("holdWin", Long.valueOf(ms)); hold.windowMs = ms; touched("unlock_speed"); }

    public void setWheelReversed(boolean on) { store.put("wheelRev", Long.valueOf(on ? 1 : 0)); touched("wheel_reversed"); }

    public boolean speakerAlways() { return store.num("speakerAlways", 0) == 1; }

    public boolean speakerRunning() { return speakerWanted; }

    public void setSpeakerAlways(boolean on) {
        store.put("speakerAlways", Long.valueOf(on ? 1 : 0));
        touched("speaker_always_on");
        if (on) startSpeaker(); else if (!(musicMode && playing && ownAudio())) stopSpeaker();
    }

    @SuppressWarnings("unchecked")
    private void refreshMusic() throws IOException {
        String[][] kinds = { { "Playlists", "playlist", "music/playlists/library_items" }, { "Albums", "album", "music/albums/library_items" },
            { "Radio", "radio", "music/radios/library_items" } };
        for (String[] k : kinds) {
            List<Book> out = new ArrayList<Book>();
            Object r = ma.call(k[2], MaClient.args("limit", Long.valueOf(300)), 30000);
            for (Object e : (List<Object>) r) {
                Map<String, Object> m = Json.map(e);
                Book b = new Book();
                b.kind = k[1];
                b.uri = String.valueOf(m.get("uri"));
                b.id = b.uri;
                b.name = String.valueOf(m.get("name"));
                Object ar = m.get("artists");
                if (ar instanceof List && ((List<Object>) ar).size() > 0) {
                    Object f = ((List<Object>) ar).get(0);
                    b.author = f instanceof Map ? String.valueOf(Json.map(f).get("name")) : String.valueOf(f);
                } else b.author = m.get("owner") != null ? String.valueOf(m.get("owner")) : k[0].substring(0, k[0].length() - 1);
                b.path = b.name;
                Map<String, Object> md = m.get("metadata") instanceof Map ? Json.map(m.get("metadata")) : null;
                List<Object> imgs = md != null && md.get("images") instanceof List ? (List<Object>) md.get("images") : null;
                if (imgs != null && imgs.size() > 0 && Json.map(imgs.get(0)).get("proxy_id") != null) b.cover = String.valueOf(Json.map(imgs.get(0)).get("proxy_id"));
                out.add(b);
            }
            // the sync playlist is plumbing, not something the listener should see
            String hidden = musicLib.playlistName;
            for (int i = out.size() - 1; i >= 0; i--) if (hidden.length() > 0 && out.get(i).name.equalsIgnoreCase(hidden)) out.remove(i);
            Collections.sort(out, new Comparator<Book>() {
                public int compare(Book a, Book b) { return a.name.compareToIgnoreCase(b.name); }
            });
            synchronized (music) { music.put(k[0], out); }
        }
        // ambient sounds (white noise, ocean waves...) are a browse folder rather than a library list
        List<Book> sounds = new ArrayList<Book>();
        try {
            Object r = ma.call("music/browse", MaClient.args("path", "ambient_sounds://"), 20000);
            for (Object e : (List<Object>) r) {
                Map<String, Object> m = Json.map(e);
                if ("..".equals(m.get("name")) || m.get("uri") == null) continue;
                Book b = new Book();
                b.kind = "sound";
                b.uri = String.valueOf(m.get("uri"));
                b.id = b.uri;
                b.name = String.valueOf(m.get("name"));
                b.author = "Ambient sound";
                b.path = b.name;
                sounds.add(b);
            }
        } catch (IOException ignored) { }
        synchronized (music) { music.put("Sounds", sounds); }
    }

    public List<Book> musicList(String kind) {
        synchronized (music) { List<Book> l = music.get(kind); return l == null ? new ArrayList<Book>() : new ArrayList<Book>(l); }
    }

    /** Play a playlist, album or radio station on the chosen target. For the device itself the Y1 becomes an MA speaker first. */
    public void playMusic(final Book m) {
        if (bedtimeBlocks()) return;
        pool.execute(new Runnable() { public void run() {
            try { startMusic(m); } catch (Exception e) { fail(e); }
        } });
    }

    private synchronized void startMusic(Book m) throws IOException {
        stopLocal(false); // an audiobook in progress is saved and paused
        String q = targetId.length() > 0 ? targetId : null;
        if (q == null) { say("Starting the iPod speaker..."); q = ensureSpeaker(); }
        musicMode = true;
        localMusic = false;      // the local song queue is not what plays now (Next, the watchdog and lyrics follow this)
        current = m;
        nowTitle = m.name;
        nowSub = m.author;
        durMs = 0;
        posMs = 0;
        ma.call("player_queues/play_media", MaClient.args("queue_id", q, "media", m.uri, "option", "replace"), 20000);
        if (m.uri != null && m.uri.equals(resumeStreamUri) && resumeStreamIndex > 0) {   // carry on where it was
            try {
                ma.call("player_queues/play_index", MaClient.args("queue_id", q, "index", Long.valueOf(resumeStreamIndex),
                    "seek_position", Long.valueOf(resumeStreamMs / 1000)), 20000);
            } catch (IOException e) { log("music: could not go back to the place: " + e.getMessage()); }
        }
        resumeStreamUri = null;
        playing = true;
        store.put("lastStream", Json.write(HaClient.args("id", m.id, "name", m.name, "author", m.author, "uri", m.uri, "kind", m.kind, "cover", m.cover)));
        rememberLast("stream");
        log("music " + m.name + " on " + (q.equals(speakerQueue) ? "this Y1" : targetName));
    }

    /** Leave music mode (an audiobook is about to start): pause whatever music is running. */
    private void leaveMusic() {
        if (!musicMode) return;
        String q = queueId();
        musicMode = false;
        if (q.length() > 0) try { ma.call("player_queues/pause", MaClient.args("queue_id", q), 8000); } catch (IOException ignored) { }
    }

    private void startSpeaker() {
        synchronized (this) {
            if (speakerWanted || host.length() == 0) { speakerUsedAt = System.currentTimeMillis(); return; }
            speakerWanted = true;
            speakerUsedAt = System.currentTimeMillis();
            speakerGen++;
        }
        final int gen = speakerGen;
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        speakerLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sendspin");
        speakerLock.acquire();
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        wifiLockHolder = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL, "sendspin");
        wifiLockHolder.acquire();
        Thread t = new Thread(new Runnable() { public void run() { speakerLoop(gen); } }, "sendspin");
        t.setDaemon(true);
        t.start();
    }

    private volatile int speakerGen;   // a stop then a quick start must not leave two loops sharing one client id

    private void speakerLoop(int gen) {
        while (speakerWanted && gen == speakerGen) {
            try {
                long origin = System.nanoTime();
                me.cns.sendspin.SendspinClient.Log lg = new me.cns.sendspin.SendspinClient.Log() {
                    public void log(String l) { android.util.Log.i("sendspin", l); }
                };
                AudioTrackSink sink = new AudioTrackSink(origin, lg);
                me.cns.sendspin.SendspinClient c = new me.cns.sendspin.SendspinClient(host, 8927, id.device(),
                    new File(app.getFilesDir(), "client.key"), sink, lg, origin);
                sink.attach(c);
                speakerClient = c;
                c.run(0);
            } catch (Throwable t) {
                log("speaker: " + t);
            }
            if (speakerWanted && gen == speakerGen) sleep(5000);
        }
    }

    // where a paused stream on the iPod's own speaker had got to when the idle speaker was switched off: Play restarts
    // the stream (its queue went with the speaker), so it goes back to that song and second
    private String resumeStreamUri;
    private long resumeStreamIndex = -1, resumeStreamMs;

    private void rememberStreamPlace() {
        if (!musicMode || current == null || speakerQueue.length() == 0 || ma == null) return;
        try {
            Map<String, Object> q = Json.map(ma.call("player_queues/get", MaClient.args("queue_id", speakerQueue), 8000));
            if (q.get("current_index") instanceof Number) {
                resumeStreamUri = current.uri;
                resumeStreamIndex = ((Number) q.get("current_index")).longValue();
                resumeStreamMs = q.get("elapsed_time") instanceof Number ? (long) (((Number) q.get("elapsed_time")).doubleValue() * 1000) : 0;
            }
        } catch (IOException e) { log("speaker: place not kept: " + e.getMessage()); }
    }

    private void stopSpeaker() {
        speakerWanted = false;
        speakerQueue = "";
        me.cns.sendspin.SendspinClient c = speakerClient;
        if (c != null) c.stop = true;
        if (speakerLock != null && speakerLock.isHeld()) speakerLock.release();
        if (wifiLockHolder != null && wifiLockHolder.isHeld()) wifiLockHolder.release();
        log("speaker off");
    }

    /** Start the Y1's own Sendspin speaker if needed and return its Music Assistant queue id. */
    @SuppressWarnings("unchecked")
    private String ensureSpeaker() throws IOException {
        startSpeaker();
        for (int i = 0; i < 40; i++) {
            Object r = ma.call("players/all", null, 15000);
            for (Object e : (List<Object>) r) {
                Map<String, Object> m = Json.map(e);
                if ((id.device().equals(m.get("display_name")) || "Innioasis Y1".equals(m.get("display_name"))) && Boolean.TRUE.equals(m.get("available"))
                    && "universal_player".equals(String.valueOf(m.get("provider")))) {
                    speakerQueue = String.valueOf(m.get("player_id"));
                    return speakerQueue;
                }
                if (i % 10 == 0 && String.valueOf(m.get("display_name")).contains("iPod"))
                    log("speaker: waiting, MA has " + m.get("player_id") + " " + m.get("display_name") + " available=" + m.get("available") + " provider=" + m.get("provider"));
            }
            if (i % 10 == 0) log("speaker: waiting (" + ((List<Object>) r).size() + " players listed)");
            sleep(2000);
        }
        throw new IOException("iPod speaker did not appear in Music Assistant");
    }

    // ------------------------------------------------------------------ ticking

    // ---- sleep timer and volume limit (both for the listener)

    private volatile long sleepAt;

    public void setSleepTimerMin(int min) { sleepAt = min <= 0 ? 0 : System.currentTimeMillis() + min * 60000L; }

    public long sleepRemainingMs() { return sleepAt == 0 ? 0 : Math.max(0, sleepAt - System.currentTimeMillis()); }

    /** Highest music volume allowed, in percent; 100 = no limit. */
    public int volumeLimit() { return (int) store.num("volLimit", 100); }

    public void setVolumeLimit(int p) { store.put("volLimit", Long.valueOf(p)); touched("volume_limit"); enforceVolumeLimit(); }

    private void enforceVolumeLimit() {
        int lim = volumeLimit();
        if (lim >= 100 || HelpActivity.open) return;     // the Help video of a gifted iPod plays full volume
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cap = Math.max(1, max * lim / 100);
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) > cap) audio.setStreamVolume(AudioManager.STREAM_MUSIC, cap, 0);
    }

    // ---- Find My beacon: runs whenever Android's Bluetooth is off (the radio can only serve one of them)

    public boolean findMyEnabled() { return store.num("findmy", 1) == 1; }

    public void setFindMyEnabled(boolean on) {
        store.put("findmy", Long.valueOf(on ? 1 : 0));
        touched("find_my");
        pool.execute(new Runnable() { public void run() { beaconTick(); } });
    }

    public void reloadBeacon() {
        pool.execute(new Runnable() { public void run() { beacon.stop(); beaconTick(); } });
    }

    /** Before Android's Bluetooth is switched on, hand the radio back; false if it could not be freed. */
    private volatile long handingToBtUntil;   // the UI is switching Bluetooth on: leave the radio alone meanwhile

    public boolean releaseRadio() {
        handingToBtUntil = android.os.SystemClock.elapsedRealtime() + 30000;
        synchronized (beaconLock) { return beacon.releaseAndWait(); }
    }

    /** Bluetooth is off and meant to stay off (adapter OFF, and not persisted on, so nothing is about to enable it). */
    private boolean btSettledOff() {
        try {
            android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (a == null || a.getState() != android.bluetooth.BluetoothAdapter.STATE_OFF) return false;
            return android.provider.Settings.Global.getInt(app.getContentResolver(), "bluetooth_on", 0) == 0;
        } catch (RuntimeException e) { return false; }
    }

    private int beaconFailures;

    private final Object beaconLock = new Object();
    private String beaconLogged = "";

    private void beaconTick() {
        synchronized (beaconLock) { beaconTickLocked(); }
    }

    private void beaconTickLocked() {
        try {
            // Runs alongside Wi-Fi since the beacon stopped sending the command that crashed the radio firmware
            // (FindMyBeacon). With Android's Bluetooth on it steps aside (the radio is mtkbt's) and the ROM's driver
            // shim carries the beacon instead.
            // only once Bluetooth has settled off: at boot Android turns a persisted-on Bluetooth on some seconds after
            // we start, and a helper holding the radio then would stop mtkbt opening it (Bluetooth stays dead)
            boolean want = findMyEnabled() && beacon.hasKeys() && !sys.btOn() && btSettledOff() && beaconFailures < 3
                && android.os.SystemClock.elapsedRealtime() > handingToBtUntil;
            if (findMyEnabled() && beacon.hasKeys() && sys.btOn() && FindMyBeacon.shimInstalled()) {
                beacon.writeShimConfig();           // the ROM's Bluetooth driver shim keeps the beacon going alongside the headphones
                if (!beacon.running()) { beacon.status = "on, with Bluetooth"; return; }
            } else if (!findMyEnabled() || !beacon.hasKeys()) beacon.clearShimConfig();
            if (want && !beacon.running()) {
                beacon.start();
                if (beacon.needsInit) { beacon.needsInit = false; beaconFailures++; }
                if (!beacon.status.equals(beaconLogged)) { beaconLogged = beacon.status; log("find my beacon: " + beacon.status); }
            } else if (!want && beacon.running()) beacon.releaseAndWait();
            else if (want) beacon.tick();
            if (!want && !beacon.running())
                beacon.status = !findMyEnabled() ? "off" : !beacon.hasKeys() ? "no keys yet" : beaconFailures >= 3 ? "radio would not start" : "paused while Bluetooth is on";
        } catch (RuntimeException ex) { log("beacon: " + ex); }
    }

    // ---- self-healing audio. Seen once (Oct 2026): Android's Bluetooth audio hung, the song sat at 0:00 while the
    // player said playing, and a reconnect hung too; only a reboot cleared it (restarting Bluetooth alone breaks A2DP on
    // this chip until a reboot). So: stuck 8 s -> restart the player where it was; still stuck 15 s later on Bluetooth
    // -> reboot (at most once in 6 hours; what was playing comes back after it). Never loops.

    private long wdPos = -1, wdSince, wdGood, wdMoveStart, wdRestartAt;
    private int wdStage;
    private boolean wdMoving;
    public volatile String watchdogLast = "";

    private void audioWatchdog() {
        // files only: a streamed audiobook can sit buffering on weak Wi-Fi, which is not a hang
        boolean own = playing && current != null && (localMusic || (!musicMode && targetId.length() == 0
            && "audiobook".equals(current.kind) && isDownloaded(current)));
        long el = android.os.SystemClock.elapsedRealtime();
        if (!own) {
            // our own restart pauses for a moment: that must not reset the stage, or it would restart for ever
            if (wdRestartAt == 0 || el - wdRestartAt > 25000) { wdPos = -1; wdStage = 0; wdRestartAt = 0; }
            return;
        }
        long pos = -2;
        MediaPlayer p = mp;
        if (p != null && mpReady) try { pos = p.getCurrentPosition(); } catch (RuntimeException ignored) { }
        if (pos >= 0 && pos != wdPos) {                  // moving: healthy once it has kept going for 30 s
            if (!wdMoving) { wdMoving = true; wdMoveStart = el; }
            if (el - wdMoveStart > 30000) wdStage = 0;
            wdGood = pos;
            wdPos = pos;
            wdSince = el;
            return;
        }
        wdMoving = false;
        if (pos != wdPos) { wdPos = pos; wdSince = el; return; }   // just went not-ready (-2): time it from now
        long stuck = el - wdSince;
        if (wdStage == 0 && stuck > (pos == -2 ? 15000 : 8000)) {
            wdStage = 1;
            wdSince = el;
            wdRestartAt = el;
            final long at = Math.max(wdGood, posMs);
            watchdogLast = "player restarted";
            log("watchdog: audio stuck at " + at / 1000 + " s (" + (sys.a2dpConnected() ? "Bluetooth" : "speaker") + "): restarting the player");
            pool.execute(new Runnable() { public void run() {
                try {
                    synchronized (Engine.this) {
                        if (localMusic) { resumeAtMs = at; startTrackLocal(mqi); }
                        else if (current != null) startOn(current, "", at);
                    }
                } catch (Exception e) { log("watchdog: restart failed: " + e); }
            } });
        } else if (wdStage == 1 && stuck > 15000) {
            wdStage = 2;
            long last = store.num("watchdogRebootAt", 0), now = clock.now();
            if (sys.a2dpConnected() && now - last > 6 * 3600000L) {
                store.put("watchdogRebootAt", Long.valueOf(now));
                watchdogLast = "rebooted for Bluetooth audio";
                log("watchdog: still stuck on Bluetooth: rebooting");
                say("Fixing Bluetooth audio: restarting");
                saveMusicPlace();
                if (current != null && !musicMode && !localMusic) setProgress(current, Math.max(wdGood, posMs), false);   // not "finished"
                pool.schedule(new Runnable() { public void run() { Root.restart(); } }, 3, TimeUnit.SECONDS);
            } else {
                watchdogLast = "gave up";
                log("watchdog: still stuck: paused");
                say("Can\u2019t play right now: try again");
                pause();
            }
        }
    }

    // ---- "Find my Y1" (the Find it button in Home Assistant): in every sync's reply, synced every 30 s on Wi-Fi

    private volatile long findAt;
    private static final String WAKE_ACTION = "me.cns.ab.WAKE";
    private PowerManager.WakeLock wakeLock, remoteLock;

    /** The reply's find request (ms, Home Assistant's clock): ring once for a fresh press. */
    private void handleFind(Map<String, Object> r) {
        String v = r.get("find") == null ? "" : String.valueOf(r.get("find"));
        String seen = store.str("findSeen", "");
        if (v.length() == 0 || v.equals(seen)) return;
        store.put("findSeen", v);
        try {
            long at = Long.parseLong(v), now = r.get("now") instanceof Number ? ((Number) r.get("now")).longValue() : clock.now();
            if (now - at > 10 * 60000L) return;                           // an old press (the iPod was off): ignore
        } catch (NumberFormatException e) {
            return;
        }
        log("find: ringing (pressed in Home Assistant)");
        if (playing && ownAudio()) pause();
        say("Here I am! Press any button");
        finder.ring(60);
    }

    private void tick() {
        saveMusicPlace();
        sampleThroughput();
        try { bedtimeTick(android.os.SystemClock.elapsedRealtime()); } catch (RuntimeException ex) { log("bedtime: " + ex); }
        long tnow = System.currentTimeMillis();
        if (tnow - batteryAt > 30000) {
            batteryAt = tnow;
            int[] b = sys.battery();
            batteryLevel = b[0];
            charging = b[1] == 1;
        }
        // every 10 s (key rotation, hanging up on connections); never queue a second tick behind a slow one
        if (tnow - beaconAt > 10000 && beaconBusy.compareAndSet(false, true)) {
            beaconAt = tnow;
            pool.execute(new Runnable() { public void run() { try { beaconTick(); } finally { beaconBusy.set(false); } } });
        }
        if (sleepAt != 0 && tnow >= sleepAt) { sleepAt = 0; if (playing) pause(); log("sleep timer: paused"); }
        try { audioWatchdog(); } catch (RuntimeException ex) { log("watchdog: " + ex); }
        // playing on a speaker that counts towards bedtime: stay awake, so the minutes are counted and the limit holds
        // with the screen off (the iPod's own playback already holds the player's wake lock)
        boolean holdAwake = playing && !ownAudio() && countsNow();
        if (holdAwake && !remoteLock.isHeld()) remoteLock.acquire();
        else if (!holdAwake && remoteLock.isHeld()) remoteLock.release();
        if (tnow - findAt > 30000 && ha.configured() && haFailures == 0 && onWifi() && haBusy.compareAndSet(false, true)) {
            findAt = tnow;
            pool.execute(new Runnable() { public void run() { try { bedtimeSync(); } finally { haBusy.set(false); } } });
        }
        enforceVolumeLimit();
        try {
            String q = queueId();
            if (speakerWanted && !speakerAlways() && !(musicMode && playing && ownAudio())
                && System.currentTimeMillis() - speakerUsedAt > 600000) {   // idle speaker drains the battery
                rememberStreamPlace();
                stopSpeaker();
            }
            if (musicMode && playing && ownAudio()) speakerUsedAt = System.currentTimeMillis();
            if (q.length() == 0) {
                MediaPlayer p = mp;
                if (p != null && mpReady) {
                    posMs = p.getCurrentPosition();
                    playing = p.isPlaying();
                    long now = System.currentTimeMillis();
                    if (current != null && current.kind.equals("audiobook") && playing && now - lastSave > 5000) {
                        lastSave = now;
                        setProgress(current, posMs, false);
                    }
                }
            } else if (ma != null && current != null) {
                Map<String, Object> qm = Json.map(ma.call("player_queues/get", MaClient.args("queue_id", q), 8000));
                String st = String.valueOf(qm.get("state"));
                Object ci = qm.get("current_item");
                String qname = ci instanceof Map && Json.map(ci).get("name") != null ? String.valueOf(Json.map(ci).get("name")) : null;
                // someone else's audio on that speaker (a parent's music) is not ours: it must not count against her
                // budget, show as her book, or be paused by bedtime or her Play button
                boolean ours = musicMode || (qname != null && qname.equals(current.name));
                playing = "playing".equals(st) && ours;
                // an idle queue reports a stale elapsed time, so only trust it while playing
                if (playing && qm.get("elapsed_time") instanceof Number) { posMs = (long) (((Number) qm.get("elapsed_time")).doubleValue() * 1000); posStamp = System.currentTimeMillis(); }
                if (ci instanceof Map) {
                    Map<String, Object> cm = Json.map(ci);
                    Object d = cm.get("duration");
                    // an idle or foreign queue must not rewrite our length: only a queue playing our own item counts
                    boolean mine = ours;
                    if (playing && mine && d instanceof Number) durMs = ((Number) d).longValue() * 1000;
                    if (musicMode) {
                        Object mi0 = cm.get("media_item");
                        String plain = mi0 instanceof Map && Json.map(mi0).get("name") != null ? String.valueOf(Json.map(mi0).get("name")) : stripArtist(qname);
                        nowTitle = plain == null ? nowTitle : plain;
                        Object mi = cm.get("media_item");
                        if (mi instanceof Map) {
                            Object ars = Json.map(mi).get("artists");
                            if (ars instanceof List && ((List<?>) ars).size() > 0) {
                                Object f = ((List<?>) ars).get(0);
                                nowSub = f instanceof Map ? String.valueOf(Json.map(f).get("name")) : String.valueOf(f);
                            }
                        }
                        // the synced copy's art only when it is this song by this artist (else the stream's own art)
                        MusicLibrary.Track lt = musicLib.byTitleArtist(nowTitle, nowSub);
                        if (current != null) current.coverLocal = lt != null ? musicLib.coverFile(lt) : null;
                    }
                }
                online = true;
                long now = System.currentTimeMillis();
                // MA owns the place while a speaker plays our book; mirror it so the Y1 knows too
                if (!musicMode && playing && now - lastSave > 20000 && qname != null && qname.equals(current.name)) {
                    lastSave = now;
                    setProgress(current, posMs, false);
                    synchronized (store) { Json.map(store.section("progress").get(current.id)).put("synced", Long.valueOf(posMs)); }
                }
                if (!ownAudio()) {
                    Object pl = ma.call("players/get", MaClient.args("player_id", q), 8000);
                    if (pl instanceof Map && Json.map(pl).get("volume_level") instanceof Number)
                        remoteVolume = ((Number) Json.map(pl).get("volume_level")).intValue();
                }
            }
        } catch (IOException e) {
            if (queueId().length() > 0) { online = false; say("Offline: speaker out of reach"); }
        } catch (RuntimeException e) {
            log("tick: " + e);
        }
    }

    // ------------------------------------------------------------------ bedtime

    private void saveBedtime() { store.put("bedtime", bedtime.save()); }

    /** True (and says so kindly) when playback may not start because the bedtime budget is spent. */
    public boolean bedtimeBlocks() {
        if (!bedtime.locked(clock.now())) return false;
        say("It\u2019s bedtime. Sleep well" + id.commaName());
        return true;
    }

    public boolean bedtimeLocked() { return bedtime.locked(clock.now()); }

    /** Counts toward the shared budget: anything we play, except on speakers Home Assistant already counts. */
    private boolean countsNow() {
        if (!playing) return false;
        if (ownAudio()) return true;
        String n = targetName.toLowerCase(java.util.Locale.UK);
        for (String c : id.countedByHa()) if (c.length() > 0 && n.contains(c.toLowerCase(java.util.Locale.UK))) return false;
        return true;
    }

    private void bedtimeTick(long el) {
        long now = clock.now();
        if (lastCountAt != 0 && !shotMode && countsNow()) playedMs += Math.min(5000, el - lastCountAt);
        lastCountAt = el;
        if (playedMs >= 60000) { playedMs -= 60000; bedtime.playedMinute(now); saveBedtime(); }
        if (playing && bedtime.locked(now)) {
            MediaPlayer m = mp;
            if (fadeAt == 0 && m != null && mpReady && queueId().length() == 0) fadeAt = el;   // local: a gentle fade
            float left = fadeAt == 0 ? 0 : 1f - (el - fadeAt) / 8000f;
            if (left > 0.02f && m != null) {
                try { m.setVolume(left * left, left * left); } catch (RuntimeException ignored) { }
            } else {
                pause();
                fadeAt = 0;
                if (m != null) try { m.setVolume(1f, 1f); } catch (RuntimeException ignored) { }
                say("It\u2019s bedtime. Sleep well" + id.commaName());
                log("bedtime: budget used, paused");
            }
        } else if (fadeAt != 0) {           // unlocked mid-fade
            fadeAt = 0;
            MediaPlayer m = mp;
            if (m != null) try { m.setVolume(1f, 1f); } catch (RuntimeException ignored) { }
        }
        clock.persist();
        long every = bedtime.inWindow(now) ? 60000 : 300000;
        // something changed: tell HA now (only while syncs work: a failing one must not be retried every 5 s)
        if (ha.configured() && haFailures == 0 && !sensorSig.equals(sensorSignature(now)) && el - haAt > 5000) haAt = 0;
        // after a failure (Home Assistant restarting, say) try again soon, then less often: 30 s, 1, 2, 4, 8, then 10 min
        if (haFailures > 0) every = Math.min(10 * 60000L, 30000L << Math.min(5, haFailures - 1));
        boolean wifi = onWifi(), joined = wifi && !wasOnWifi;   // just got Wi-Fi (after boot, or home again): sync now
        wasOnWifi = wifi;
        if ((haAt == 0 || joined || el - haAt > every) && haBusy.compareAndSet(false, true)) {
            haAt = el;
            pool.execute(new Runnable() { public void run() { try { bedtimeSync(); } finally { haBusy.set(false); } } });
        }
    }

    private static int intOf(Object o, int d) {
        try { return (int) Math.round(Double.parseDouble(String.valueOf(o))); } catch (RuntimeException e) { return d; }
    }

    /** Time, timezone and the shared counter, whenever there is Wi-Fi. */
    private void bedtimeSync() {
        if (shotMode) return;                           // screenshot mode: nothing goes to (or comes from) Home Assistant
        ha.maybeReload();
        long el = android.os.SystemClock.elapsedRealtime();
        boolean wifi = onWifi();
        if (wifi && (ntpAt == 0 || el - ntpAt > 3600000L)) ntpAt = clock.syncNtp() ? el : el - 3300000L;   // retry in 5 min
        if (wifi) geo.maybeGeoIp();
        clock.maybeGps(wifi);
        if (!wifi) { bedtimeStatus = "offline: counting here, sends later"; return; }
        if (!ha.configured()) { bedtimeStatus = ha.removed ? "removed from Home Assistant: register it again" : "Home Assistant not set up"; return; }
        try {
            long now = clock.now();
            Map<String, Object> rep = new HashMap<String, Object>();
            rep.put("sensors", sensorValues(now));
            rep.put("settings", settingsReport());
            final Map<String, Object> loc = locationReport();
            if (loc != null) rep.put("location", loc);
            int n = (int) store.num("playedN", 0);
            long seq = store.num("playedSeq", 1);
            // the integration adds our minutes to the shared counter itself, so minutes counted there at the same
            // moment (her Sonos) are never overwritten. The batch {seq, n} is frozen (kept in the store) and resent
            // unchanged until the integration confirms it, so a resend after a lost reply is recognised and not
            // counted twice, and a batch it dropped is never mistaken for counted
            if (n > 0 && bedtime.localUnsent == 0) {      // the night rolled over since it was frozen: those minutes belong to last night
                n = 0;
                store.put("playedN", Long.valueOf(0));
                store.put("playedSeq", Long.valueOf(++seq));   // it may have been counted: a higher seq is accepted either way
            }
            if (n == 0 && bedtime.localUnsent > 0 && bedtime.inWindow(now)) {
                n = Math.min(bedtime.localUnsent, 60);
                store.put("playedN", Long.valueOf(n));
            }
            if (n > 0) rep.put("played", HaClient.args("minutes", Integer.valueOf(n), "seq", Long.valueOf(seq)));
            long unl = unlockPending;
            if (unl > 0) rep.put("unlocked_until", Long.valueOf(unl));
            Map<String, Object> r = ha.sync(rep);
            boolean counted = n > 0;
            if (n > 0 && r.get("played_seq") instanceof Number) {
                long hs = ((Number) r.get("played_seq")).longValue();    // where the integration's own seq has got to
                if (hs > seq) {                                          // ours was behind (data cleared): it dropped this batch
                    counted = false;
                    store.put("playedSeq", Long.valueOf(hs + 1));
                    log("played: seq was behind (" + seq + " against " + hs + "), resending");
                }
            }
            if (counted) { store.put("playedSeq", Long.valueOf(seq + 1)); store.put("playedN", Long.valueOf(0)); }
            if (unl > 0 && unlockPending == unl) unlockPending = 0;
            if (loc != null) locationSent();
            applySettings(r.get("settings"));
            handleFind(r);
            Map<String, Object> b = r.get("bedtime") instanceof Map ? Json.map(r.get("bedtime")) : new HashMap<String, Object>();
            int ws = Bedtime.parseHhmm(String.valueOf(b.get("start")), bedtime.startMin);
            int we = Bedtime.parseHhmm(String.valueOf(b.get("end")), bedtime.endMin);
            int cap = intOf(b.get("cap"), -1);
            int used = intOf(b.get("used"), -1);
            if (used < 0 || cap <= 0) {    // a linked counter is unavailable: keep counting here, never fail open
                saveBedtime();
                bedtimeStatus = "Home Assistant: bedtime counter unavailable";
                haFailures = 0;
                return;
            }
            Object nightObj = b.get("night");
            String night = nightObj == null ? null : String.valueOf(nightObj).trim();
            if (night != null && !night.matches("\\d{4}-\\d{2}-\\d{2}|none")) night = null;
            if (counted) bedtime.sent(n, used);     // its counter already includes them
            bedtime.applyServer(ws, we, cap, used, now, night);
            saveBedtime();
            sensorSig = sensorSignature(now);
            bedtimeStatus = "in step with Home Assistant";
            haFailures = 0;
        } catch (IOException ex) {
            haFailures++;
            bedtimeStatus = "Home Assistant: " + ex.getMessage();
        }
    }

    // ---- the iPod as a device in Home Assistant: its sensors (registered on first use)

    private String sensorSig = "";
    private volatile int haFailures;   // failed syncs in a row: backs off
    /** A grown-up unlock (until, ms) Home Assistant has not been told about yet. */
    private volatile long unlockPending;

    /** What the iPod reports to its device in Home Assistant (the integration's sensors). */
    private Map<String, Object> sensorValues(long now) {
        Map<String, Object> m = new HashMap<String, Object>();
        Book b = current;
        boolean book = b != null && "audiobook".equals(b.kind);
        String title = b == null ? "nothing" : book ? b.name : (nowTitle.length() > 0 ? nowTitle : b.name);
        String who = b == null ? "" : book ? (b.author == null ? "" : b.author) : nowSub;
        m.put("battery", Integer.valueOf(Math.max(0, batteryLevel)));
        m.put("charging", Boolean.valueOf(charging));
        m.put("now_playing", title);
        m.put("now_playing_attributes", HaClient.args("artist", who, "kind", b == null ? "" : book ? "audiobook" : "music"));
        m.put("playing", Boolean.valueOf(playing));
        m.put("listen_on", targetName);
        m.put("bedtime_left", Integer.valueOf(bedtime.inWindow(now) ? bedtime.remaining(now) : bedtime.capMin));
        m.put("bedtime_locked", Boolean.valueOf(bedtime.locked(now)));
        m.put("on_hold", Boolean.valueOf(hold.held(android.os.SystemClock.uptimeMillis())));
        m.put("find_my_beacon", findMyEnabled() ? beacon.status : "off");
        String ssid = sys.connectedSsid();
        m.put("wifi_network", ssid == null ? "not connected" : ssid);
        if (clock.fixAtUtc != 0) {
            java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'+00:00'", java.util.Locale.US);
            iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            m.put("last_gps_fix", iso.format(new java.util.Date(clock.fixAtUtc)));
        }
        return m;
    }

    // ---- settings that can be changed in Home Assistant as well as here: newest change wins

    /** Identity settings (Identity store keys). */
    private static final String[][] IDENTITY = { { "owner_name", "idOwner" }, { "device_name", "idDevice" },
        { "lost_contact", "idContact" }, { "home_wifi", "idHomeWifi" }, { "counted_speakers", "idCountedSpeakers" } };
    private static final String[] SETTINGS = { "volume_limit", "hold_after", "unlock_speed", "wheel_click", "click_loudness",
        "wheel_reversed", "find_my", "speaker_always_on", "keep_offline", "voice_local_only", "gift_armed" };

    /**
     * Armed for gifting: arming switches the iPod off; the next time it is turned on it plays the Help video at full volume,
     * then disarms itself. So a freshly set-up iPod can be handed over and explain itself the first time it is switched on.
     */
    public boolean giftArmed() { return store.num("giftArmed", 0) == 1; }

    public void setGiftArmed(boolean on) {
        if (on && !Help.available()) { touched("gift_armed"); return; }   // nothing to play: stays unarmed, and says so
        boolean was = giftArmed();
        store.put("giftArmed", Long.valueOf(on ? 1 : 0));
        touched("gift_armed");
        if (on && !was) {
            say("Armed: switching off. Turn me on to start the Help video");
            log("gift: armed, powering off");
            pause();
            pool.schedule(new Runnable() { public void run() { if (giftArmed()) Root.powerOff(); } }, 4, TimeUnit.SECONDS);
        }
    }

    /** BootReceiver: a real power-on while armed (not a restart of the app): remember to play the video once it is up. */
    public void giftBooted() { if (giftArmed() && Help.available()) store.put("giftBoot", Long.valueOf(1)); }

    /** MainActivity, once up: true once after a power-on while armed. */
    public boolean takeGiftBoot() {
        if (store.num("giftBoot", 0) != 1) return false;
        store.put("giftBoot", Long.valueOf(0));
        return giftArmed() && Help.available();
    }

    /** Voice search never leaves the iPod (slower; needs the local model). */
    public boolean voiceLocalOnly() { return store.num("voiceLocal", 0) == 1; }

    public void setVoiceLocalOnly(boolean on) { store.put("voiceLocal", Long.valueOf(on ? 1 : 0)); touched("voice_local_only"); }
    private volatile boolean applyingHa;

    /** A setting changed here on the iPod (not one arriving from Home Assistant): it is now the newest. */
    private void touched(String key) {
        if (applyingHa) return;
        synchronized (store) { store.section("settingTs").put(key, Long.valueOf(clock.now())); }
        store.save();
        haAt = 0;                          // tell Home Assistant soon
    }

    private long settingTs(String key) {
        synchronized (store) {
            Object v = store.section("settingTs").get(key);
            return v instanceof Number ? ((Number) v).longValue() : 0;
        }
    }

    private Object settingValue(String k) {
        if (k.equals("volume_limit")) return Integer.valueOf(volumeLimit());
        if (k.equals("hold_after")) return String.valueOf(holdIdleS());
        if (k.equals("unlock_speed")) return String.valueOf(holdWindowMs());
        if (k.equals("wheel_click")) return String.valueOf(clickMode() & 3);
        if (k.equals("click_loudness")) return String.valueOf(clickLevel());
        if (k.equals("wheel_reversed")) return Boolean.valueOf(wheelReversed());
        if (k.equals("find_my")) return Boolean.valueOf(findMyEnabled());
        if (k.equals("speaker_always_on")) return Boolean.valueOf(speakerAlways());
        if (k.equals("keep_offline")) return Boolean.valueOf(keepAll());
        if (k.equals("voice_local_only")) return Boolean.valueOf(voiceLocalOnly());
        if (k.equals("gift_armed")) return Boolean.valueOf(giftArmed());
        for (String[] i : IDENTITY) if (i[0].equals(k)) return id.raw(i[1]);
        return null;
    }

    /** Every setting with when it last changed here (0: never, so Home Assistant's value wins, or it adopts ours). */
    private Map<String, Object> settingsReport() {
        Map<String, Object> m = new HashMap<String, Object>();
        for (String k : SETTINGS) m.put(k, java.util.Arrays.asList(settingValue(k), Long.valueOf(settingTs(k))));
        for (String[] i : IDENTITY) m.put(i[0], java.util.Arrays.asList(settingValue(i[0]), Long.valueOf(settingTs(i[0]))));
        return m;
    }

    private static boolean boolOf(Object o) { return Boolean.TRUE.equals(o) || "true".equals(String.valueOf(o)) || "1".equals(String.valueOf(o)); }

    /** Settings from Home Assistant: each one changed there after it last changed here is applied. */
    private void applySettings(Object o) {
        if (!(o instanceof Map)) return;
        Map<String, Object> m = Json.map(o);
        applyingHa = true;
        try {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!(e.getValue() instanceof List) || ((List<?>) e.getValue()).size() != 2) continue;
                String k = e.getKey();
                Object v = ((List<?>) e.getValue()).get(0), tsO = ((List<?>) e.getValue()).get(1);
                long ts = tsO instanceof Number ? ((Number) tsO).longValue() : 0;
                if (ts == 0 || ts <= settingTs(k) || ts > clock.now() + 600000L) continue;   // nothing, old, or stamped in the future
                Object cur = settingValue(k);
                if (cur == null) continue;      // not a setting this version knows
                boolean same = String.valueOf(cur).equals(String.valueOf(v))
                    || (cur instanceof Boolean && ((Boolean) cur).booleanValue() == boolOf(v));
                if (!same) {
                    log("settings: " + k + " set in Home Assistant");
                    applySetting(k, v);
                }
                synchronized (store) { store.section("settingTs").put(k, Long.valueOf(ts)); }
            }
            store.save();
        } finally {
            applyingHa = false;
        }
    }

    private void applySetting(String k, Object v) {
        if (k.equals("volume_limit")) setVolumeLimit(Math.max(10, Math.min(100, intOf(v, 100))));
        else if (k.equals("hold_after")) setHoldIdleS(Math.max(0, Math.min(3600, intOf(v, 60))));
        else if (k.equals("unlock_speed")) setHoldWindowMs(Math.max(250, Math.min(5000, intOf(v, 1000))));
        else if (k.equals("wheel_click")) setClickMode(intOf(v, 3) & 3);
        else if (k.equals("click_loudness")) setClickLevel(Math.max(0, Math.min(2, intOf(v, 0))));
        else if (k.equals("wheel_reversed")) setWheelReversed(boolOf(v));
        else if (k.equals("find_my")) setFindMyEnabled(boolOf(v));
        else if (k.equals("speaker_always_on")) setSpeakerAlways(boolOf(v));
        else if (k.equals("keep_offline")) setKeepAll(boolOf(v));
        else if (k.equals("voice_local_only")) setVoiceLocalOnly(boolOf(v));
        else if (k.equals("gift_armed")) setGiftArmed(boolOf(v));
        else for (String[] i : IDENTITY) {
            if (!i[0].equals(k)) continue;
            if (id.update(i[1], v) && k.equals("device_name") && targetId.length() == 0) targetName = id.device();
        }
    }

    private long fixSent;            // utc of the GPS fix last sent
    private String zoneSent = "";    // "home" / "not_home" last sent, when there was no new fix

    /** Home Wi-Fi networks: on one of these the iPod is home (no GPS needed). */
    public volatile boolean testAway;   // test hook 992: behave as if away from home Wi-Fi

    private boolean onHomeWifi() {
        if (testAway) return false;
        String ssid = sys.connectedSsid();
        if (ssid == null) return false;
        for (String h : id.homeWifi()) if (ssid.equals(h)) return true;
        return false;
    }

    private long pendingFix;         // what locationReport() offered, committed by locationSent()
    private String pendingZone;

    /**
     * The device tracker: a new GPS fix goes up as coordinates (even hours later, once there is Wi-Fi again: it is the
     * last place it was seen); otherwise home Wi-Fi means "home" and other Wi-Fi (a phone's hotspot) "not_home".
     * Null when nothing changed.
     */
    private Map<String, Object> locationReport() {
        if (fixSent == 0) fixSent = store.num("fixSent", 0);
        pendingFix = 0;
        pendingZone = null;
        boolean home = onHomeWifi();
        if (home) ha.preferHome();
        // a fix older than the iPod coming home (or than 6 hours) is not where it is now: never send it later as current
        if (home && clock.fixAtUtc != fixSent) { fixSent = clock.fixAtUtc; store.put("fixSent", Long.valueOf(fixSent)); }
        boolean fresh = clock.now() - clock.fixAtUtc < 6 * 3600000L;
        if (clock.fixAtUtc != 0 && clock.fixAtUtc != fixSent && !home && fresh) {
            pendingFix = clock.fixAtUtc;
            return HaClient.args("gps", java.util.Arrays.asList(Double.valueOf(clock.fixLat), Double.valueOf(clock.fixLon)),
                "accuracy", Integer.valueOf(Math.max(1, Math.round(clock.fixAccuracy))));
        }
        String zone = home ? "home" : "not_home";
        if (zone.equals(zoneSent)) return null;
        if (zone.equals("not_home") && fixSent == clock.fixAtUtc && clock.fixAtUtc != 0 && zoneSent.length() == 0) return null;   // keep showing the last fix
        pendingZone = zone;
        return HaClient.args("zone", zone);
    }

    private void locationSent() {
        if (pendingFix != 0) { fixSent = pendingFix; store.put("fixSent", Long.valueOf(fixSent)); zoneSent = ""; }
        if (pendingZone != null) zoneSent = pendingZone;
    }

    /** What changes often enough to report straight away (play, pause, track, hold, bedtime lock). */
    private String sensorSignature(long now) {
        Book b = current;
        return playing + "|" + (b == null ? "" : b.id) + "|" + nowTitle + "|" + hold.held(android.os.SystemClock.uptimeMillis()) + "|" + bedtime.locked(now) + "|" + charging + "|" + clock.fixAtUtc;
    }

    /** Until when (uptime ms) parental settings are open after the grown-up code. */
    public volatile long grownUpUntil;

    /** The grown-up code for parental settings: opens them for 10 minutes; does not lift the bedtime limit. */
    public boolean grownUp(String code) {
        if (code == null || code.length() != Totp.DIGITS) return false;   // part of a code: not a try
        ha.maybeReload();
        long now = clock.now();
        if (!bedtime.checkCode(ha.totpKey, code, now, clock.codeSlack())) {
            log("parental settings: wrong or used code (" + bedtime.wrongInRow + " in a row)");
            saveBedtime();
            return false;
        }
        if (bedtime.resyncedUtc != 0) clock.fix(bedtime.resyncedUtc, "grown-up code");
        saveBedtime();
        grownUpUntil = android.os.SystemClock.elapsedRealtime() + 10 * 60000L;   // counts through sleep (uptime does not)
        log("parental settings opened with the grown-up code");
        return true;
    }

    /** The grown-up code: lifts the limit for 8 hours, once per code, and tells the parents. */
    public boolean unlock(String code) {
        if (code == null || code.length() != Totp.DIGITS) return false;   // part of a code: not a try
        ha.maybeReload();
        final long now = clock.now();
        if (!bedtime.tryUnlock(ha.totpKey, code, now, clock.codeSlack())) {
            log("bedtime: wrong or used code (" + bedtime.wrongInRow + " in a row)");
            saveBedtime();
            return false;
        }
        if (bedtime.resyncedUtc != 0) {
            log("bedtime: two codes put the clock right, it was " + (now - bedtime.resyncedUtc) / 60000 + " min out");
            clock.fix(bedtime.resyncedUtc, "grown-up code");
        }
        saveBedtime();
        log("bedtime: unlocked with the grown-up code until " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(new java.util.Date(bedtime.unlockUntil)));
        unlockPending = bedtime.unlockUntil;   // goes with the next sync (straight away on Wi-Fi)
        haAt = 0;
        return true;
    }

    public void relock() { bedtime.unlockUntil = 0; saveBedtime(); }

    /**
     * Remember what was last played, for restoreLast: "audiobook" (lastBook), "music" (the local queue: lastQueue,
     * lastIdx, lastPos) or "stream" (a playlist or radio through Music Assistant: lastStream). Logged with the caller,
     * so a wrong restore can be traced.
     */
    private void rememberLast(String kind) {
        String was = store.str("lastKind", "");
        store.put("lastKind", kind);
        if (!kind.equals(was)) {
            StackTraceElement[] st = new Throwable().getStackTrace();
            log("last played: " + kind + " (was " + (was.length() == 0 ? "nothing" : was) + ", from " + (st.length > 2 ? st[2].getMethodName() : "?") + ")");
        }
    }

    /** On start, show what was playing last (paused), book or music, so the listener is one click from carrying on. */
    private void restoreLast() {
        String kind = store.str("lastKind", "audiobook");
        if (kind.equals("stream") && restoreStream()) return;
        boolean music = kind.equals("music");
        if (music && restoreMusic()) return;
        // music was last but the library is still loading (it takes a second or so): show nothing rather than the book,
        // so a stray Play (headphones reconnecting) cannot start the book meanwhile; the book only if the music is gone
        if (music && musicLib.tracks().isEmpty()) { restoreMusicLater(120); return; }
        restoreBook();
    }

    private void restoreBook() {
        String id = store.str("lastBook", "");
        Book b = id.length() > 0 ? book(id) : null;
        if (b != null) { current = b; durMs = b.durS * 1000; posMs = progressMs(b.id); }
    }

    /** The playlist or radio last streamed through Music Assistant, ready for Continue (playMusic). */
    private boolean restoreStream() {
        try {
            Map<String, Object> m = Json.obj(store.str("lastStream", ""));
            Book b = new Book();
            b.id = String.valueOf(m.get("id"));
            b.name = String.valueOf(m.get("name"));
            b.author = m.get("author") == null ? "" : String.valueOf(m.get("author"));
            b.uri = String.valueOf(m.get("uri"));
            b.kind = String.valueOf(m.get("kind"));
            b.cover = m.get("cover") == null ? null : String.valueOf(m.get("cover"));
            if (b.uri.equals("null")) return false;
            current = b;
            nowTitle = b.name;
            nowSub = b.author;
            log("restore: carrying on with " + b.name + " (streamed)");
            return true;
        } catch (RuntimeException e) { return false; }
    }

    /** The local music queue as it was, on the same track and place. False if its songs are no longer here. */
    private synchronized boolean restoreMusic() {
        java.util.Map<String, MusicLibrary.Track> byId = new java.util.HashMap<String, MusicLibrary.Track>();
        for (MusicLibrary.Track t : musicLib.tracks()) byId.put(t.id, t);
        List<MusicLibrary.Track> q = new ArrayList<MusicLibrary.Track>();
        String[] ids = store.str("lastQueue", "").split(",");
        int last = (int) store.num("lastIdx", 0), i = -1;
        String lastTrack = store.str("lastTrack", "");
        boolean same = true;
        for (int k = 0; k < ids.length; k++) {
            MusicLibrary.Track t = byId.get(ids[k]);
            if (t == null) continue;
            // the song that was playing, or (if it is gone) the next one still here, from its start
            if (i < 0 && (lastTrack.length() > 0 ? ids[k].equals(lastTrack) : k == last)) i = q.size();
            else if (i < 0 && k > last) { i = q.size(); same = false; }
            q.add(t);
        }
        if (q.isEmpty() || i < 0 || i >= q.size()) return false;
        mq = q;
        mqi = i;
        curTrack = q.get(i);
        current = trackBook(curTrack);
        nowTitle = curTrack.title;
        nowSub = curTrack.artist;
        durMs = curTrack.durS * 1000;
        posMs = same ? store.num("lastPos", 0) : 0;
        localMusic = targetId.length() == 0;   // on a speaker, Play hands the queue to it (togglePlay)
        musicMode = false;
        return true;
    }

    /** A music queue the iPod knows (playing, paused or restored), whose current track is showing. */
    public boolean hasQueue() { Book b = current; return b != null && "track".equals(b.kind) && !mq.isEmpty(); }

    /** Once the music library has loaded, restore the music that was playing (unless the listener has started something). */
    private void restoreMusicLater(final int tries) {
        pool.schedule(new Runnable() { public void run() {
            if (playing || current != null) return;   // the listener started something meanwhile
            if (!musicLib.tracks().isEmpty()) {
                boolean ok = restoreMusic();
                log("restore: carrying on with the music " + (ok ? "as it was" : "(its songs are gone, showing the last book)"));
                if (!ok) restoreBook();
                return;
            }
            if (tries > 1) restoreMusicLater(tries - 1); else restoreBook();
        } }, 1, TimeUnit.SECONDS);
    }

    /** Every 15 s while local music plays, remember the place (a flat battery or restart then loses little). */
    private void saveMusicPlace() {
        MediaPlayer p = mp;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!localMusic || !playing || p == null || !mpReady || now - musicSavedAt < 15000) return;
        musicSavedAt = now;
        try { store.put("lastPos", Long.valueOf(p.getCurrentPosition())); } catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------ covers

    public File coverFile(Book b) {
        if (b.coverLocal != null) return b.coverLocal.exists() ? b.coverLocal : null;
        if (b.cover == null) return null;
        File f = new File(coverDir(), b.cover + ".jpg");
        return f.exists() ? f : null;
    }

    /** Fetch a cover into the cache (blocking; the UI calls this from a worker). */
    public void fetchCover(Book b) {
        if (b.cover == null || host.length() == 0 || new File(coverDir(), b.cover + ".jpg").exists()) return;
        File tmp = new File(coverDir(), b.cover + ".tmp");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/imageproxy/" + b.cover + "?size=256&fmt=jpeg").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() != 200) return;
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { out.close(); in.close(); }
            tmp.renameTo(new File(coverDir(), b.cover + ".jpg"));
        } catch (IOException e) {
            tmp.delete();
        }
    }

    public void runAsync(Runnable r) { pool.execute(r); }

    // ---- search Apple Music (as the owner's Music Assistant user, so only their account), stream a find, add it to her library (then it syncs here)

    /** A search result: a song, album or artist from Music Assistant. */
    public static final class Found {
        public String kind, name, title, sub, uri, cover, itemId, provider, addUri;
        public long durS;
        public boolean inLibrary;

        Book asBook() {
            Book b = new Book();
            b.id = uri; b.name = name; b.author = sub == null ? "" : sub; b.uri = uri; b.cover = cover; b.kind = "stream";
            b.durS = durS;
            return b;
        }
    }

    @SuppressWarnings("unchecked")
    private static Found found(Map<String, Object> m, String kind) {
        Map<String, Object> md = m.get("metadata") instanceof Map ? Json.map(m.get("metadata")) : null;
        if (md != null && Boolean.TRUE.equals(md.get("explicit"))) return null;   // no explicit songs or albums
        Found f = new Found();
        f.kind = kind;
        f.name = String.valueOf(m.get("name"));
        f.title = f.name;
        String ver = m.get("version") == null ? "" : String.valueOf(m.get("version"));
        if (ver.length() > 0 && !kind.equals("artist")) f.name += " (" + ver + ")";
        f.uri = String.valueOf(m.get("uri"));
        f.itemId = String.valueOf(m.get("item_id"));
        f.provider = String.valueOf(m.get("provider"));
        f.durS = m.get("duration") instanceof Number ? ((Number) m.get("duration")).longValue() : 0;
        List<Object> arts = m.get("artists") instanceof List ? (List<Object>) m.get("artists") : null;
        if (arts != null && !arts.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (Object a : arts) { if (b.length() > 0) b.append(", "); b.append(Json.map(a).get("name")); }
            f.sub = b.toString();
        }
        List<Object> imgs = md != null && md.get("images") instanceof List ? (List<Object>) md.get("images") : null;
        if (imgs != null && !imgs.isEmpty() && Json.map(imgs.get(0)).get("proxy_id") != null) f.cover = String.valueOf(Json.map(imgs.get(0)).get("proxy_id"));
        // adding to the library has to name her Apple Music account itself (a plain apple_music:// URI may pick another)
        if (m.get("provider_mappings") instanceof List) for (Object o : (List<Object>) m.get("provider_mappings")) {
            Map<String, Object> pm = Json.map(o);
            String inst = String.valueOf(pm.get("provider_instance"));
            if (Boolean.TRUE.equals(pm.get("in_library")) && inst.startsWith("apple_music")) f.inLibrary = true;
            if (f.addUri == null && inst.startsWith("apple_music")) f.addUri = inst + "://" + kind + "/" + pm.get("item_id");
        }
        return f;
    }

    @SuppressWarnings("unchecked")
    private static List<Found> founds(Object list, String kind) {
        List<Found> out = new ArrayList<Found>();
        if (list instanceof List) for (Object o : (List<Object>) list) {
            Found f = found(Json.map(o), kind);
            if (f != null) out.add(f);
        }
        return out;
    }

    private static String norm(String x) {
        String n = java.text.Normalizer.normalize(x == null ? "" : x, java.text.Normalizer.Form.NFKD).toLowerCase();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) { char c = n.charAt(i); if (Character.isLetterOrDigit(c) && c < 128) b.append(c); }
        return b.toString();
    }

    /** A song already synced here (any album edition): same title and artist. */
    public boolean onIpod(Found f) { return localCopy(f) != null; }

    public MusicLibrary.Track localCopy(Found f) {
        if (!"track".equals(f.kind)) return null;
        String t = norm(f.title), a = norm(f.sub);
        for (MusicLibrary.Track x : musicLib.tracks()) {
            if (!norm(x.title).equals(t)) continue;
            String xa = norm(x.primaryArtist != null ? x.primaryArtist : x.artist);
            if (xa.length() > 0 && a.contains(xa)) return x;
        }
        return null;
    }

    // ---- taking a song off the iPod. Apple's API cannot delete from a library, so the song goes on a Music Assistant
    // playlist the music server reads as "not wanted": it deletes the recording and the next sync removes it here.



    @SuppressWarnings("unchecked")
    private String removedPlaylist(boolean create) throws IOException {
        String id = store.str("removedPlaylist", "");
        if (id.length() > 0) return id;
        Object r = ma.call("music/playlists/library_items", MaClient.args("search", "removed", "limit", Long.valueOf(50)), 30000);
        if (r instanceof List) for (Object o : (List<Object>) r) {
            Map<String, Object> p = Json.map(o);
            if (this.id.removedPlaylist().replace('\u2019', '\'').equals(String.valueOf(p.get("name")).replace('\u2019', '\''))) id = String.valueOf(p.get("item_id"));
        }
        if (id.length() == 0 && create) id = String.valueOf(Json.map(ma.call("music/playlists/create_playlist", MaClient.args("name", this.id.removedPlaylist()), 30000)).get("item_id"));
        if (id.length() > 0) store.put("removedPlaylist", id);
        return id;
    }

    /** Take a synced song off the iPod (and stop the server keeping it). */
    public void removeFromIpod(final MusicLibrary.Track t) {
        if (ma == null || !online) { say("Connect to Wi-Fi first"); return; }
        say("Removing " + t.title + "...");
        pool.execute(new Runnable() { public void run() {
            try {
                String pid = removedPlaylist(true);
                ma.call("music/playlists/add_playlist_tracks", MaClient.args("db_playlist_id", pid,
                    "uris", java.util.Arrays.asList(t.playUri != null ? t.playUri : t.uri)), 30000);
                // and out of her Apple Music library (Music Assistant's remove, made to work for Apple by the
                // local patch, deletes only from accounts this user can see)
                String lib = libraryTrackId(t);
                if (lib != null) ma.call("music/library/remove_item", MaClient.args("media_type", "track", "library_item_id", lib), 60000);
                else log("library: " + t.title + " not found in Music Assistant's library, hidden only");
                favs.forget(t);
                say("Removed: gone from your iPod after the next sync");
                log("library: removed " + t.artist + " - " + t.title + " from the iPod");
            } catch (IOException e) {
                say("Couldn\u2019t remove " + t.title);
                log("library: remove failed for " + t.title + ": " + e.getMessage());
            }
        } });
    }

    /** Music Assistant's library id for a synced song: the library track mapped to its Apple Music catalogue id. */
    @SuppressWarnings("unchecked")
    private String libraryTrackId(MusicLibrary.Track t) throws IOException {
        String u = t.playUri != null ? t.playUri : t.uri;
        String cid = u.substring(u.lastIndexOf('/') + 1);
        Object r = ma.call("music/tracks/library_items", MaClient.args("search", t.title, "limit", Long.valueOf(50)), 30000);
        if (r instanceof List) for (Object o : (List<Object>) r) {
            Map<String, Object> it = Json.map(o);
            if (it.get("provider_mappings") instanceof List) for (Object pm : (List<Object>) it.get("provider_mappings")) {
                Map<String, Object> m = Json.map(pm);
                if (String.valueOf(m.get("provider_instance")).startsWith("apple_music") && cid.equals(String.valueOf(m.get("item_id"))))
                    return String.valueOf(it.get("item_id"));
            }
        }
        return null;
    }

    /** Re-adding a song that was taken off: off the removed list again, so it syncs back. */
    @SuppressWarnings("unchecked")
    private void unremove(Found f) {
        try {
            String pid = removedPlaylist(false);
            if (pid.length() == 0) return;
            Object r = ma.call("music/playlists/playlist_tracks", MaClient.args("item_id", pid,
                "provider_instance_id_or_domain", "library", "force_refresh", Boolean.TRUE), 30000);
            List<Long> pos = new ArrayList<Long>();
            String t = norm(f.title), a = norm(f.sub);
            if (r instanceof List) for (Object o : (List<Object>) r) {
                Map<String, Object> it = Json.map(o);
                StringBuilder arts = new StringBuilder();
                if (it.get("artists") instanceof List) for (Object x : (List<Object>) it.get("artists")) arts.append(Json.map(x).get("name"));
                if (norm(String.valueOf(it.get("name"))).equals(t) && (a.contains(norm(arts.toString())) || norm(arts.toString()).contains(a))
                    && it.get("position") instanceof Number) pos.add(((Number) it.get("position")).longValue());
            }
            if (!pos.isEmpty()) ma.call("music/playlists/remove_playlist_tracks", MaClient.args("db_playlist_id", pid, "positions_to_remove", pos), 30000);
        } catch (IOException e) { log("library: could not clear the removed mark for " + f.name + ": " + e.getMessage()); }
    }

    /** Songs, albums, artists for a query (blocking: call off the UI thread). */
    public List<List<Found>> search(String q) throws IOException {
        if (ma == null || !online) throw new IOException("Connect to Wi-Fi to search");
        Map<String, Object> r = Json.map(ma.call("music/search", MaClient.args("search_query", q,
            "media_types", java.util.Arrays.asList("track", "album", "artist"), "limit", Long.valueOf(15)), 20000));
        List<List<Found>> out = new ArrayList<List<Found>>();
        out.add(founds(r.get("tracks"), "track"));
        out.add(founds(r.get("albums"), "album"));
        out.add(founds(r.get("artists"), "artist"));
        return out;
    }

    /** An album's songs, or an artist's albums or top songs (blocking). */
    public List<Found> children(Found f, String what) throws IOException {
        if (ma == null) throw new IOException("Not connected");
        String cmd = what.equals("albums") ? "music/artists/artist_albums" : what.equals("tracks") && f.kind.equals("artist")
            ? "music/artists/artist_tracks" : "music/albums/album_tracks";
        Object r = ma.call(cmd, MaClient.args("item_id", f.itemId, "provider_instance_id_or_domain", f.provider), 20000);
        return founds(r, what.equals("albums") ? "album" : "track");
    }

    /** Stream it on the chosen speaker (or the iPod's own), like a playlist. */
    public void playFound(Found f) { playMusic(f.asBook()); }

    /** Add it to the owner's Apple Music library; the music server then records it and it arrives here on the next sync. */
    public void addToLibrary(final Found f) {
        if (ma == null) { say("Connect to Wi-Fi first"); return; }
        say("Adding " + f.name + "...");
        pool.execute(new Runnable() { public void run() {
            try {
                ma.call("music/library/add_item", MaClient.args("item", f.addUri != null ? f.addUri : f.uri), 120000);
                unremove(f);
                f.inLibrary = true;
                say("Added: on your iPod soon");
                log("library: added " + f.kind + " " + f.name);
            } catch (IOException e) {
                say("Couldn\u2019t add " + f.name);
                log("library: add failed for " + f.name + ": " + e.getMessage());
            }
        } });
    }

    // ---- voice search: hold the centre button and say what to find

    public final Voice voice = new Voice();

    /**
     * Screenshot mode (test hook 989, off after every restart): no Home Assistant sync, no bedtime minutes counted, and
     * no "grown-up unlock" lines on screen, so a run of docs/screenshots/capture.py changes nothing outside the iPod
     * and the pictures do not show the unlock.
     */
    public volatile boolean shotMode;
    private volatile boolean voicePaused;   // we paused playback to listen: resume afterwards

    /** Start listening (pausing whatever plays); false if the microphone could not start. */
    public boolean startListening() {
        voicePaused = false;
        if (playing) { pause(); voicePaused = true; }
        if (voice.start()) return true;
        if (voicePaused) { voicePaused = false; resume(); }
        return false;
    }

    /** Stop listening; what was said, as recorded audio (resumes playback that listening paused). */
    public byte[] stopListening() {
        byte[] pcm = voice.stop();
        if (voicePaused) { voicePaused = false; resume(); }
        return pcm;
    }

    /** Abandon a search: the microphone is released and paused playback resumes, whatever state it was in. */
    public void cancelListening() {
        try { stopListening(); } catch (RuntimeException e) { log("voice: cancel failed: " + e); }
        voicePaused = false;
        Voice.cancelLocal();
    }

    /** Speech to text (blocking): on the iPod if a local model is installed, else Home Assistant's speech-to-text. */
    public String transcribe(byte[] pcm) throws IOException {
        long t0 = android.os.SystemClock.elapsedRealtime();
        pcm = Voice.trim(pcm);
        File stt = new File(app.getFilesDir(), "stt");
        // Home Assistant is quicker (about 2 s against 5 or more here) whenever it can be reached, unless the voice
        // must stay on the iPod (the "Voice on the iPod only" setting); with no Wi-Fi the local model is the only way
        boolean cloud = ha.configured() && onWifi() && !voiceLocalOnly();
        if (!cloud || !Voice.hasLocal(stt)) {
            String t;
            t = Voice.local(stt, pcm);
            if (t != null) {
                log("voice: \"" + t + "\" on the iPod in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
                return t;
            }
            if (voiceLocalOnly()) throw new IOException(Voice.hasLocal(stt) ? "Couldn\u2019t understand that" : "Voice search isn\u2019t set up on this iPod");
        }
        if (!ha.configured() || !onWifi()) throw new IOException("Voice search needs Wi-Fi");
        String t;
        Map<String, Object> r = ha.sync(HaClient.args("transcribe", android.util.Base64.encodeToString(pcm, android.util.Base64.NO_WRAP),
            "rate", Integer.valueOf(Voice.RATE)));
        Object tr = r.get("transcript");
        t = tr == null ? "" : String.valueOf(tr).replaceAll("[.!?]+$", "").trim();
        log("voice: \"" + t + "\" from Home Assistant in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
        return t;
    }

    private static List<String> words(String x) {
        String n = java.text.Normalizer.normalize(x == null ? "" : x, java.text.Normalizer.Form.NFKD).toLowerCase(java.util.Locale.UK);
        List<String> out = new ArrayList<String>();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i <= n.length(); i++) {
            char c = i < n.length() ? n.charAt(i) : ' ';
            if (c == '\'') continue;                       // "don't" = "dont"
            if (Character.isLetterOrDigit(c) && c < 128) b.append(c);
            else if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            else if (b.length() > 0) { out.add(b.toString()); b.setLength(0); }
        }
        return out;
    }

    /** One spoken word against one written word: the same, or one letter out for longer words. */
    private static boolean near(String a, String b) {
        if (a.equals(b)) return true;
        if (Math.min(a.length(), b.length()) < 4 || Math.abs(a.length() - b.length()) > 1) return false;
        int i = 0, j = 0, diff = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; continue; }
            if (++diff > 1) return false;
            if (a.length() > b.length()) i++; else if (b.length() > a.length()) j++; else { i++; j++; }
        }
        return diff + (a.length() - i) + (b.length() - j) <= 1;
    }

    /** How well the words match: the share of spoken words found (title counts most), 0 for none. */
    private static double score(List<String> q, MusicLibrary.Track t) {
        List<String> title = words(t.title), artist = words(t.artist), album = words(t.album);
        double got = 0;
        int inTitle = 0;
        for (String w : q) {
            boolean ti = false, ot = false;
            for (String x : title) if (near(w, x)) { ti = true; break; }
            if (!ti) { for (String x : artist) if (near(w, x)) { ot = true; break; } }
            if (!ti && !ot) { for (String x : album) if (near(w, x)) { ot = true; break; } }
            if (ti) { got += 1; inTitle++; } else if (ot) got += 0.8;
        }
        if (q.isEmpty()) return 0;
        double s = got / q.size();
        if (inTitle == title.size() && inTitle == q.size()) s += 0.5;       // exactly the title
        else if (inTitle > 0 && inTitle == title.size()) s += 0.25;         // the whole title (plus the artist, say)
        return s;
    }

    /** Songs on the iPod for spoken (or typed) words, best first. */
    public List<MusicLibrary.Track> localSearch(String q) {
        List<String> qw = words(q);
        List<MusicLibrary.Track> all = musicLib.tracks();
        final Map<MusicLibrary.Track, Double> sc = new HashMap<MusicLibrary.Track, Double>();
        for (MusicLibrary.Track t : all) {
            double v = score(qw, t);
            if (v >= 0.6) sc.put(t, Double.valueOf(v));
        }
        List<MusicLibrary.Track> out = new ArrayList<MusicLibrary.Track>(sc.keySet());
        java.util.Collections.sort(out, new java.util.Comparator<MusicLibrary.Track>() {
            public int compare(MusicLibrary.Track a, MusicLibrary.Track b) { return sc.get(b).compareTo(sc.get(a)); }
        });
        return out.size() > 30 ? out.subList(0, 30) : out;
    }

    /** Whether streaming results can be fetched now. */
    public boolean canStream() { return ma != null && online && onWifi(); }
}
