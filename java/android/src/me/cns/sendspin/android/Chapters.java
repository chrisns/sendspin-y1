package me.cns.sendspin.android;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.cns.sendspin.Json;

/**
 * An audiobook's chapters, from Music Assistant's metadata (name, start and end in seconds), kept on the iPod so they
 * work offline. Next goes to the next chapter's start; Previous goes back to the start of this chapter, or to the one
 * before when within the first few seconds (as an iPod does).
 *
 * Pure Java on purpose: it is unit-tested on the desktop (test/BedtimeTest.java).
 */
public final class Chapters {
    static final long RESTART_MS = 4000;   // Previous within this of a chapter start goes to the chapter before

    public final List<String> names = new ArrayList<String>();
    public final List<Long> startMs = new ArrayList<Long>();

    /** From the "chapters" list of an audiobook's metadata; null when it has fewer than two. */
    @SuppressWarnings("unchecked")
    public static Chapters from(Object list) {
        if (!(list instanceof List)) return null;
        Chapters c = new Chapters();
        for (Object o : (List<Object>) list) {
            Map<String, Object> m = Json.map(o);
            Object st = m.get("start");
            if (!(st instanceof Number)) continue;
            c.names.add(m.get("name") == null ? "Chapter " + (c.names.size() + 1) : String.valueOf(m.get("name")));
            c.startMs.add(Long.valueOf(Math.round(((Number) st).doubleValue() * 1000)));
        }
        return c.size() < 2 ? null : c;
    }

    public int size() { return startMs.size(); }

    /** The chapter playing at posMs (0-based). */
    public int at(long posMs) {
        int i = 0;
        while (i + 1 < startMs.size() && startMs.get(i + 1) <= posMs) i++;
        return i;
    }

    /** Where Next goes, or -1 in the last chapter. */
    public long next(long posMs) {
        int i = at(posMs);
        return i + 1 < size() ? startMs.get(i + 1) : -1;
    }

    /** Where Previous goes. */
    public long previous(long posMs) {
        int i = at(posMs);
        if (posMs - startMs.get(i) > RESTART_MS || i == 0) return startMs.get(i);
        return startMs.get(i - 1);
    }

    /** "Chapter 7 of 22". */
    public String label(long posMs) {
        int i = at(posMs);
        return "Chapter " + (i + 1) + " of " + size();
    }

    public String name(long posMs) { return names.get(at(posMs)); }
}
