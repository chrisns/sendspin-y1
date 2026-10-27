package me.cns.sendspin.android;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.cns.sendspin.Json;

/**
 * Favourite songs: hold the centre button on a song to add or remove it. Kept on the device (works offline, newest
 * first) and mirrored to a Music Assistant playlist ("<owner>'s Favourites", Identity) so speakers can play it too.
 * The iPod is the source of truth: each sync makes the playlist match it.
 */
public final class Favourites {

    private final Store store;
    private final Identity id;
    private final LinkedHashSet<String> ids = new LinkedHashSet<String>();   // track ids, oldest first
    private volatile boolean dirty;
    private long gen;                          // bumped on every change, so a change made mid-sync is not lost
    public volatile String lastError = "";

    public Favourites(Store s, Identity identity) {
        store = s;
        id = identity;
        for (String id : s.str("favs", "").split(",")) if (id.length() > 0) ids.add(id);
        dirty = s.num("favsDirty", 0) != 0;
    }

    public synchronized boolean has(MusicLibrary.Track t) { return t != null && ids.contains(t.id); }

    /** Add or remove; true when it is now a favourite. */
    public synchronized boolean toggle(MusicLibrary.Track t) {
        boolean now = !ids.remove(t.id);
        if (now) ids.add(t.id);
        save(true);
        return now;
    }

    /** The favourites present on the iPod, newest first. */
    public synchronized List<MusicLibrary.Track> tracks(MusicLibrary lib) {
        List<MusicLibrary.Track> out = new ArrayList<MusicLibrary.Track>();
        List<String> order = new ArrayList<String>(ids);
        for (int i = order.size() - 1; i >= 0; i--) {
            for (MusicLibrary.Track t : lib.tracks()) if (t.id.equals(order.get(i))) { out.add(t); break; }
        }
        return out;
    }

    public synchronized int size() { return ids.size(); }

    /** A song taken off the iPod is no favourite any more. */
    public synchronized void forget(MusicLibrary.Track t) { if (ids.remove(t.id)) save(true); }

    private void save(boolean changed) {
        StringBuilder b = new StringBuilder();
        for (String id : ids) { if (b.length() > 0) b.append(','); b.append(id); }
        store.put("favs", b.toString());
        if (changed) { gen++; dirty = true; store.put("favsDirty", Long.valueOf(1)); }
    }

    public boolean needsSync() { return dirty; }

    /** What the playlist should hold now, as one string (a clean or catalogue swap changes it without a toggle). */
    private String signature(Set<String> want) {
        StringBuilder b = new StringBuilder();
        for (String u : want) b.append(u).append('\n');
        return Integer.toHexString(b.toString().hashCode());
    }

    /**
     * Make the Music Assistant playlist match (creating it the first time), after a change here or whenever the
     * versions to list change. Blocking: call off the UI thread.
     */
    @SuppressWarnings("unchecked")
    public void sync(MaClient ma, MusicLibrary lib) {
        if (ma == null) return;
        List<MusicLibrary.Track> listed = lib.listed();
        if (listed.isEmpty()) return;          // library not loaded yet: an empty "want" would wipe the playlist
        Set<String> want = new LinkedHashSet<String>();
        long at;
        synchronized (this) {
            at = gen;
            // from the whole playlist listing, not just files present here, so a song still downloading stays put
            for (MusicLibrary.Track t : listed) if (ids.contains(t.id)) want.add(t.playUri != null ? t.playUri : t.uri);
        }
        String sig = signature(want);
        if (!dirty && sig.equals(store.str("favsSig", ""))) return;
        try {
            String pid = playlistId(ma);
            Object r = ma.call("music/playlists/playlist_tracks", MaClient.args("item_id", pid,
                "provider_instance_id_or_domain", "library", "force_refresh", Boolean.TRUE), 30000);
            List<Long> remove = new ArrayList<Long>();
            Set<String> have = new HashSet<String>();
            if (r instanceof List) for (Object o : (List<Object>) r) {
                Map<String, Object> it = Json.map(o);
                Set<String> uris = uris(it);
                String match = null;
                for (String u : uris) if (want.contains(u)) { match = u; break; }
                if (match == null || have.contains(match)) {
                    if (it.get("position") instanceof Number) remove.add(((Number) it.get("position")).longValue());
                } else have.add(match);
            }
            if (!remove.isEmpty())
                ma.call("music/playlists/remove_playlist_tracks", MaClient.args("db_playlist_id", pid, "positions_to_remove", remove), 30000);
            List<String> add = new ArrayList<String>();
            for (String u : want) if (!have.contains(u)) add.add(u);
            if (!add.isEmpty()) ma.call("music/playlists/add_playlist_tracks", MaClient.args("db_playlist_id", pid, "uris", add), 30000);
            synchronized (this) {
                if (gen == at) { dirty = false; store.put("favsDirty", Long.valueOf(0)); }   // else: changed meanwhile, go again
            }
            store.put("favsSig", sig);
            lastError = "";
            if (!add.isEmpty() || !remove.isEmpty())
                android.util.Log.i("ab", "favourites synced: " + want.size() + " (+" + add.size() + " -" + remove.size() + ")");
        } catch (IOException e) {
            lastError = String.valueOf(e.getMessage());
            android.util.Log.i("ab", "favourites sync failed: " + e.getMessage());
        }
    }

    /** Every URI a playlist entry answers to: its own and each provider's (library://... and apple_music://...). */
    @SuppressWarnings("unchecked")
    private static Set<String> uris(Map<String, Object> it) {
        Set<String> s = new HashSet<String>();
        if (it.get("uri") != null) s.add(String.valueOf(it.get("uri")));
        if (it.get("provider_mappings") instanceof List) for (Object o : (List<Object>) it.get("provider_mappings")) {
            Map<String, Object> m = Json.map(o);
            if (m.get("provider_domain") != null && m.get("item_id") != null)
                s.add(m.get("provider_domain") + "://track/" + m.get("item_id"));
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private String playlistId(MaClient ma) throws IOException {
        String id = store.str("favPlaylist", "");
        if (id.length() > 0) {
            try {   // deleted or renamed in Music Assistant since: find or make it again
                Map<String, Object> p = Json.map(ma.call("music/playlists/get_playlist", MaClient.args("item_id", id,
                    "provider_instance_id_or_domain", "library"), 15000));
                if (this.id.favouritesPlaylist().replace('\u2019', '\'').equals(String.valueOf(p.get("name")).replace('\u2019', '\''))) return id;
            } catch (IOException e) {
                if (!String.valueOf(e.getMessage()).toLowerCase().contains("not found")) throw e;
            }
            id = "";
        }
        Object r = ma.call("music/playlists/library_items", MaClient.args("search", "Favourites", "limit", Long.valueOf(50)), 30000);
        if (r instanceof List) for (Object o : (List<Object>) r) {
            Map<String, Object> p = Json.map(o);
            if (this.id.favouritesPlaylist().replace('\u2019', '\'').equals(String.valueOf(p.get("name")).replace('\u2019', '\''))) id = String.valueOf(p.get("item_id"));
        }
        if (id.length() == 0) {
            Map<String, Object> p = Json.map(ma.call("music/playlists/create_playlist", MaClient.args("name", this.id.favouritesPlaylist()), 30000));
            id = String.valueOf(p.get("item_id"));
        }
        store.put("favPlaylist", id);
        return id;
    }
}
