package me.cns.sendspin.android;

import java.util.ArrayList;
import java.util.List;

/**
 * Time-synced lyrics in LRC form: "[mm:ss.xx] words". "[unsynced:1]" marks plain lyrics (Genius) whose lines the server
 * spread evenly over the song: shown scrolling, with no line picked out as the one being sung.
 */
public final class Lrc {
    public final long[] ms;
    public final String[] text;
    public final boolean unsynced;

    private Lrc(long[] ms, String[] text, boolean unsynced) { this.ms = ms; this.text = text; this.unsynced = unsynced; }

    public static Lrc parse(String s) {
        List<Long> t = new ArrayList<Long>();
        List<String> x = new ArrayList<String>();
        boolean unsynced = false;
        for (String line : s.split("\n")) {
            if (line.trim().startsWith("[unsynced:")) { unsynced = true; continue; }
            line = line.trim();
            int close = line.indexOf(']');
            if (!line.startsWith("[") || close < 0) continue;
            String stamp = line.substring(1, close);
            int colon = stamp.indexOf(':');
            if (colon < 0) continue; // [ar:..] style tags
            try {
                long min = Long.parseLong(stamp.substring(0, colon));
                double sec = Double.parseDouble(stamp.substring(colon + 1));
                t.add(Long.valueOf(min * 60000 + (long) (sec * 1000)));
                x.add(line.substring(close + 1).trim());
            } catch (NumberFormatException e) { /* a metadata tag, not a lyric */ }
        }
        long[] a = new long[t.size()];
        for (int i = 0; i < a.length; i++) a[i] = t.get(i).longValue();
        return new Lrc(a, x.toArray(new String[x.size()]), unsynced);
    }

    public boolean empty() { return ms.length == 0; }

    /** Index of the line being sung at position p, or -1 before the first line. */
    public int indexAt(long p) {
        int lo = 0, hi = ms.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (ms[mid] <= p) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return ans;
    }
}
