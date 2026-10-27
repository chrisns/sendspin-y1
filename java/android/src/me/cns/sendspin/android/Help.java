package me.cns.sendspin.android;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import me.cns.sendspin.Json;

/**
 * The Help video: /sdcard/Help/help.mp4 with chapter names and start times in help.json beside it
 * ({"chapters": [{"title": "Intro", "start": 0}, ...]}). The video is made elsewhere (and can change at any time);
 * the app only plays it and lists its chapters.
 */
final class Help {
    static final class Chapter { String title; double start; }

    static File dir() { return new File(android.os.Environment.getExternalStorageDirectory(), "Help"); }

    static File video() { return new File(dir(), "help.mp4"); }

    static boolean available() { return video().length() > 0; }

    /** The chapters, in order; a single "Help" chapter if there is no (readable) help.json. */
    @SuppressWarnings("unchecked")
    static List<Chapter> chapters() {
        List<Chapter> out = new ArrayList<Chapter>();
        try {
            Object c = Json.obj(Net.readText(new File(dir(), "help.json"))).get("chapters");
            if (c instanceof List) for (Object o : (List<Object>) c) {
                Chapter ch = new Chapter();
                ch.title = String.valueOf(Json.map(o).get("title"));
                Object s = Json.map(o).get("start");
                ch.start = s instanceof Number ? ((Number) s).doubleValue() : 0;
                out.add(ch);
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) { Chapter ch = new Chapter(); ch.title = "Help"; out.add(ch); }
        return out;
    }
}
