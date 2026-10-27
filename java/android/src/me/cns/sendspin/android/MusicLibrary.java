package me.cns.sendspin.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import me.cns.sendspin.Json;

/**
 * The synced music: a folder of MP3s that the music-sync service on the server keeps in step with the owner's library.
 * sync() fetches manifest.json, deletes tracks that left the playlist and downloads new ones (and covers).
 * What is on the Y1 is always readable offline: the manifest is kept next to the files.
 */
public final class MusicLibrary {
    public static final class Track {
        public String id, title, artist, album, file, cover, uri, primaryArtist, albumId, albumArtist, albumDate, lyrics;
        public String playUri;      // the version Music Assistant can stream (a clean or catalogue stand-in), for favourites
        public long durS, size;
        public long addedS;
        public boolean sizeExact;
        public String rev;          // changes whenever the server's recording changes (a clean re-record), null on old manifests   // size came from the server (not estimated), so a mismatch means the file changed         // when it first arrived in the playlist (epoch s, 0 = before "New songs" existed)
        public int trackNo, discNo;
        public List<String> genres = new ArrayList<String>();
    }


    private final File dir;
    private final AtomicBoolean running = new AtomicBoolean();
    private long shrinkSince;
    private volatile List<Track> tracks = new ArrayList<Track>();   // only tracks whose file is present
    public volatile int serverTotal, serverPending, serverUnavailable;   // unavailable: library-only songs Apple has no copy of
    public volatile String playlistName = "", playlistUri = "";
    public volatile String status = "";
    public volatile long missingBytes;   // listed on the server, not yet on the Y1
    public volatile long lastSyncAt;

    public MusicLibrary(File dir) {
        this.dir = dir;
        dir.mkdirs();
        loadLocal();
    }

    public List<Track> tracks() {
        // the card can mount after the app starts at boot: read the saved library once it is there
        if (!loaded && tracks.isEmpty() && android.os.Environment.MEDIA_MOUNTED.equals(android.os.Environment.getExternalStorageState())) loadLocal();
        return tracks;
    }

    /** Every song in the playlist as the server last listed it, present here or not (favourites keep absent ones). */
    public List<Track> listed() { return all; }

    private volatile List<Track> all = new ArrayList<Track>();

    /** Songs that arrived in the last 30 days, newest first. */
    public List<Track> newSongs(long nowMs) {
        List<Track> out = new ArrayList<Track>();
        long since = nowMs / 1000 - 30 * 86400L;
        for (Track t : tracks) if (t.addedS > since) out.add(t);
        java.util.Collections.sort(out, new java.util.Comparator<Track>() {
            public int compare(Track a, Track b) { return a.addedS == b.addedS ? 0 : (a.addedS > b.addedS ? -1 : 1); }
        });
        return out;
    }

    public File mp3(Track t) { return new File(dir, t.file); }

    public File coverFile(Track t) {
        if (t.cover == null) return null;
        File f = new File(dir, t.cover);
        return f.exists() ? f : null;
    }

    public long bytes() {
        long n = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".mp3")) n += f.length();
        return n;
    }

    public Track byUri(String uri) {
        if (uri == null) return null;
        for (Track t : tracks) if (uri.equals(t.uri) || uri.equals(t.playUri)) return t;   // speakers play playUri
        return null;
    }

    private static String norm(String x) {
        String n = java.text.Normalizer.normalize(x == null ? "" : x, java.text.Normalizer.Form.NFKD).toLowerCase();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) { char c = n.charAt(i); if (Character.isLetterOrDigit(c) && c < 128) b.append(c); }
        return b.toString();
    }

    /**
     * The synced copy of what a speaker is playing: same title AND an artist that matches (a cover version of the same
     * name must not lend its artwork or lyrics). artists is the stream's artist text ("Nirvana", "A, B & C").
     */
    public Track byTitleArtist(String title, String artists) {
        if (title == null) return null;
        String t = norm(title), a = norm(artists);
        if (a.length() == 0) return null;
        for (Track x : tracks) {
            if (!norm(x.title).equals(t)) continue;
            String xa = norm(x.primaryArtist != null ? x.primaryArtist : x.artist);
            if (xa.length() > 0 && (a.contains(xa) || xa.contains(a))) return x;
        }
        return null;
    }

    public Track byTitle(String title, String artist) {
        if (title == null) return null;
        for (Track t : tracks) if (title.equals(t.title) && (artist == null || artist.equals(t.artist))) return t;
        return null;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    public volatile int version;   // bumped whenever the set of tracks on the Y1 changes

    public File lyricsFile(Track t) {
        if (t == null || t.lyrics == null) return null;
        File f = new File(dir, t.lyrics);
        return f.exists() ? f : null;
    }

    private volatile boolean loaded;

    /** The library as last synced, readable with no network at all (also after a late SD card mount: see tracks()). */
    private void loadLocal() {
        try {
            File m = new File(dir, "manifest.json");
            if (!m.exists()) return;
            publishPresent(parse(Net.readText(m)));
            loaded = true;
            android.util.Log.i("ab", "music: " + tracks.size() + " songs on the iPod (saved library, no network needed)");
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("unchecked")
    private List<Track> parse(String json) {
        Map<String, Object> m = Json.obj(json);
        Map<String, Object> pl = Json.map(m.get("playlist"));
        playlistName = String.valueOf(pl.get("name"));
        playlistUri = String.valueOf(pl.get("uri"));
        serverTotal = ((Number) m.get("total")).intValue();
        serverPending = ((Number) m.get("pending")).intValue();
        serverUnavailable = m.get("unavailable") instanceof Number ? ((Number) m.get("unavailable")).intValue() : 0;
        List<Track> all = new ArrayList<Track>();
        for (Object o : (List<Object>) m.get("tracks")) {
            Map<String, Object> t = Json.map(o);
            Track x = new Track();
            x.id = String.valueOf(t.get("id"));
            x.title = String.valueOf(t.get("title"));
            x.artist = String.valueOf(t.get("artist"));
            x.album = String.valueOf(t.get("album"));
            x.file = String.valueOf(t.get("file"));
            x.cover = t.get("cover") == null ? null : String.valueOf(t.get("cover"));
            x.uri = String.valueOf(t.get("uri"));
            x.primaryArtist = str(t.get("primary_artist"));
            x.albumId = str(t.get("album_id"));
            x.albumArtist = str(t.get("album_artist"));
            x.albumDate = str(t.get("album_date"));
            x.lyrics = str(t.get("lyrics"));
            x.playUri = str(t.get("play_uri"));
            x.rev = str(t.get("rev"));
            x.addedS = t.get("added") instanceof Number ? ((Number) t.get("added")).longValue() : 0;
            x.trackNo = t.get("track_no") instanceof Number ? ((Number) t.get("track_no")).intValue() : 0;
            x.discNo = t.get("disc_no") instanceof Number ? ((Number) t.get("disc_no")).intValue() : 1;
            if (t.get("genres") instanceof List) for (Object g : (List<Object>) t.get("genres")) x.genres.add(String.valueOf(g));
            x.durS = t.get("duration") instanceof Number ? ((Number) t.get("duration")).longValue() : 0;
            x.sizeExact = t.get("size") instanceof Number;
            x.size = x.sizeExact ? ((Number) t.get("size")).longValue() : x.durS * 24000L;
            all.add(x);
        }
        return all;
    }

    private void publishPresent(List<Track> all) {
        this.all = all;
        List<Track> have = new ArrayList<Track>();
        Set<String> seen = new HashSet<String>();
        long missing = 0;
        for (Track t : all) {
            if (mp3(t).exists()) have.add(t);
            else if (seen.add(t.file)) missing += t.size;   // a track repeated in the playlist is fetched once
        }
        tracks = have;
        version++;
        missingBytes = missing;
    }

    /** One pass: manifest, prune, download what is missing. Safe to call often; overlapping calls are ignored. */
    public void sync(String base, Net.Gate gate) throws IOException {
        if (!running.compareAndSet(false, true)) return;
        try {
            if (status.startsWith("Storage")) status = "";
            String text = Net.getText(base + "/manifest.json");
            List<Track> all = parse(text);
            Set<String> wanted = new HashSet<String>();
            for (Track t : all) {
                wanted.add(t.file); wanted.add(partName(t));
                if (t.cover != null) wanted.add(t.cover);
                if (t.lyrics != null) wanted.add(t.lyrics);
            }
            wanted.add("manifest.json");
            wanted.add(REVS);
            File[] fs = dir.listFiles();
            int local = 0, lost = 0;
            if (fs != null) for (File f : fs) if (f.getName().endsWith(".mp3")) { local++; if (!wanted.contains(f.getName())) lost++; }
            // an empty or sharply smaller manifest is far more likely a server hiccup than a real edit: wait it out.
            // Songs still in the playlist but missing from the manifest (not recorded on the server) are never a reason
            // to delete: that is the server losing files, however long it lasts.
            boolean suspicious = local > 0 && (all.isEmpty() || lost > Math.max(20, local / 2));
            if (suspicious) {
                if (shrinkSince == 0) shrinkSince = System.currentTimeMillis();
                boolean serverLost = serverTotal - all.size() >= lost;
                if (serverLost || System.currentTimeMillis() - shrinkSince < 30 * 60000L) {
                    android.util.Log.i("ab", "music: manifest shrank (" + lost + " of " + local + " would go); keeping files for now");
                    return;
                }
            } else shrinkSince = 0;
            // prune first, so a full card still frees the space of songs that left the playlist (and stale .part files)
            if (fs != null) for (File f : fs) {
                String n = f.getName();
                if (!wanted.contains(n) && !n.endsWith(".tmp")) f.delete();
            }
            Net.writeText(new File(dir, "manifest.json"), text);   // only now: never save a manifest the guard rejected
            Map<String, Object> revs = loadRevs();
            boolean revsChanged = false;
            publishPresent(all);
            int fetched = 0;
            for (Track t : all) {
                if (t.cover != null && !new File(dir, t.cover).exists()) tryFetch(base + "/" + t.cover, new File(dir, t.cover), -1, null);
                if (t.lyrics != null && !new File(dir, t.lyrics).exists()) tryFetch(base + "/" + t.lyrics, new File(dir, t.lyrics), -1, null);
                File f = mp3(t);
                Object had = revs.get(t.file);
                if (f.exists() && t.rev != null && had == null && (!t.sizeExact || f.length() == t.size)) {
                    revs.put(t.file, t.rev);    // first sight of revisions: the file here is the current one
                    revsChanged = true;
                    had = t.rev;
                }
                // missing, or replaced on the server (a clean version re-recorded under the same name): fetch it
                boolean stale = f.exists() && ((t.sizeExact && f.length() != t.size) || (t.rev != null && !t.rev.equals(had)));
                if (!f.exists() || stale) {
                    if (!gate.ok()) break;
                    if (dir.getUsableSpace() < (t.sizeExact ? t.size : 10L << 20) + (200L << 20)) {
                        status = "Storage full: " + (all.size() - tracks.size()) + " songs not on the iPod";
                        android.util.Log.i("ab", "music: storage full, stopping downloads");
                        break;
                    }
                    status = "Syncing music " + tracks.size() + " of " + all.size();
                    if (tryFetch(base + "/" + t.file, f, t.sizeExact ? t.size : -1, partName(t))) {
                        fetched++;
                        if (t.rev != null) { revs.put(t.file, t.rev); revsChanged = true; }
                        publishPresent(all);   // each finished track is playable straight away
                    }
                }
            }
            if (revsChanged) saveRevs(revs);
            publishPresent(all);
            if (status.startsWith("Syncing")) status = "";
            lastSyncAt = System.currentTimeMillis();
            if (fetched > 0) android.util.Log.i("ab", "music: fetched " + fetched + ", " + tracks.size() + " of " + all.size() + " on the Y1");
        } finally {
            running.set(false);
        }
    }

    // ---- which server revision each MP3 here is (music/revs.json), so a same-size re-record is still noticed

    private static final String REVS = "revs.json";

    private Map<String, Object> loadRevs() {
        try { return Json.obj(Net.readText(new File(dir, REVS))); } catch (Exception e) { return new java.util.HashMap<String, Object>(); }
    }

    private void saveRevs(Map<String, Object> revs) {
        try { Net.writeText(new File(dir, REVS), Json.write(revs)); } catch (IOException e) { android.util.Log.i("ab", "music: revs not saved: " + e); }
    }

    /** A partial download is named after the revision it belongs to, so a replaced file is never spliced onto it. */
    private static String partName(Track t) { return t.file + "." + (t.rev != null ? t.rev : "0") + ".part"; }

    /** Fetch to a .part file and move it into place; size >= 0 is checked (a short or spliced file is thrown away). */
    private boolean tryFetch(String url, File dest, long size, String part) {
        File p = new File(dir, part != null ? part : dest.getName() + ".part");
        try {
            Net.download(url, p);
            if (size >= 0 && p.length() != size) {
                android.util.Log.i("ab", "music fetch " + dest.getName() + ": " + p.length() + " bytes, expected " + size + ": discarded");
                p.delete();
                return false;
            }
            return p.renameTo(dest);
        } catch (IOException e) {
            android.util.Log.i("ab", "music fetch failed " + dest.getName() + ": " + e.getMessage());
            return false;
        }
    }
}
