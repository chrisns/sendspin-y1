package me.cns.sendspin.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.cns.sendspin.android.MusicLibrary.Track;

/**
 * Artists, albums and genres built from the synced tracks.
 * Albums with several artists, and artists with fewer than two songs, are filed under "Various Artists".
 */
public final class MusicIndex {
    public static final String VARIOUS = "Various Artists";

    public static final class Album {
        public String id, name, artist, date;
        public List<Track> tracks = new ArrayList<Track>();
        public Track coverTrack() { return tracks.isEmpty() ? null : tracks.get(0); }
        public String year() { return date != null && date.length() >= 4 ? date.substring(0, 4) : ""; }
    }

    public static final class Artist {
        public String name;
        public List<Album> albums = new ArrayList<Album>();
        public List<Track> allTracks() {
            List<Track> all = new ArrayList<Track>();
            for (Album a : albums) all.addAll(a.tracks);
            return all;
        }
        public Track coverTrack() { return albums.isEmpty() ? null : albums.get(0).coverTrack(); }
    }

    public final List<Artist> artists = new ArrayList<Artist>();
    public final Map<String, List<Track>> genres = new LinkedHashMap<String, List<Track>>();
    public final List<Track> all;

    private static final Comparator<Track> IN_ALBUM = new Comparator<Track>() {
        public int compare(Track a, Track b) {
            if (a.discNo != b.discNo) return a.discNo < b.discNo ? -1 : 1;
            if (a.trackNo != b.trackNo) return a.trackNo < b.trackNo ? -1 : 1;
            return a.title.compareToIgnoreCase(b.title);
        }
    };

    private static String sortKey(String s) {
        String k = s.toLowerCase();
        return k.startsWith("the ") ? k.substring(4) : k;
    }

    public MusicIndex(List<Track> tracks) {
        all = tracks;
        // 1. group into albums
        Map<String, Album> byId = new LinkedHashMap<String, Album>();
        for (Track t : tracks) {
            // no Apple collection id: same-named albums by different artists (or blank names) must not merge
            String key = t.albumId != null ? t.albumId : t.album + "\u0000" + (t.primaryArtist != null ? t.primaryArtist : t.artist);
            Album a = byId.get(key);
            if (a == null) { a = new Album(); a.id = key; a.name = t.album; byId.put(key, a); }
            a.tracks.add(t);
            if (a.date == null && t.albumDate != null) a.date = t.albumDate;
        }
        // 2. decide each album's artist
        Map<Album, String> owner = new HashMap<Album, String>();
        for (Album a : byId.values()) {
            Collections.sort(a.tracks, IN_ALBUM);
            String aa = null;
            Set<String> primaries = new HashSet<String>();
            for (Track t : a.tracks) {
                if (aa == null && t.albumArtist != null) aa = t.albumArtist;
                String p = t.primaryArtist != null && t.primaryArtist.length() > 0 ? t.primaryArtist : t.artist;
                if (p != null) primaries.add(p);
            }
            boolean various = (aa != null && aa.equalsIgnoreCase(VARIOUS)) || (aa == null && primaries.size() > 1);
            if (aa == null) aa = primaries.isEmpty() ? VARIOUS : primaries.iterator().next();
            owner.put(a, various ? VARIOUS : aa);
        }
        // 3. artists with fewer than two songs go to Various Artists
        Map<String, Integer> count = new HashMap<String, Integer>();
        for (Album a : byId.values()) {
            String o = owner.get(a);
            Integer n = count.get(o);
            count.put(o, (n == null ? 0 : n.intValue()) + a.tracks.size());
        }
        Map<String, Artist> artistMap = new HashMap<String, Artist>();
        for (Album a : byId.values()) {
            String o = owner.get(a);
            if (!o.equals(VARIOUS) && count.get(o).intValue() < 2) o = VARIOUS;
            a.artist = o;
            Artist ar = artistMap.get(o);
            if (ar == null) { ar = new Artist(); ar.name = o; artistMap.put(o, ar); }
            ar.albums.add(a);
        }
        // 4. order: albums newest first by release date (undated ones last), artists alphabetically
        Comparator<Album> byDate = new Comparator<Album>() {
            public int compare(Album x, Album y) {
                String dx = x.date == null ? "" : x.date, dy = y.date == null ? "" : y.date;
                int c = dy.compareTo(dx);
                return c != 0 ? c : x.name.compareToIgnoreCase(y.name);
            }
        };
        for (Artist ar : artistMap.values()) { Collections.sort(ar.albums, byDate); artists.add(ar); }
        Collections.sort(artists, new Comparator<Artist>() {
            public int compare(Artist x, Artist y) { return sortKey(x.name).compareTo(sortKey(y.name)); }
        });
        // 5. genres (three songs or more), tracks in artist / album / track order
        Map<String, List<Track>> g = new HashMap<String, List<Track>>();
        for (Artist ar : artists) for (Album al : ar.albums) for (Track t : al.tracks) {
            for (String name : t.genres) {
                if (name.equalsIgnoreCase("music")) continue;
                List<Track> l = g.get(name);
                if (l == null) { l = new ArrayList<Track>(); g.put(name, l); }
                if (!l.contains(t)) l.add(t);
            }
        }
        List<String> names = new ArrayList<String>();
        for (Map.Entry<String, List<Track>> e : g.entrySet()) if (e.getValue().size() >= 3) names.add(e.getKey());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        for (String n : names) genres.put(n, g.get(n));
    }
}
