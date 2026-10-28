package me.cns.sendspin.android;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import me.cns.sendspin.Json;

/**
 * The Help video: /sdcard/Help/help.mp4 with chapter names and start times in help.json beside it
 * ({"chapters": [{"title": "Intro", "start": 0}, ...]}). The video is made elsewhere (and can change at any time);
 * the app only plays it and lists its chapters.
 *
 * The app keeps a copy in its own storage (mirror()): at power-on the memory card can take a while to mount, and the gift
 * video must not wait for it. The copy is readable by the media server (it opens the file itself).
 */
final class Help {
    static final class Chapter { String title; double start; }

    private static volatile File internal;

    static void init(android.content.Context c) { internal = new File(c.getFilesDir(), "help"); }

    static File dir() { return new File(android.os.Environment.getExternalStorageDirectory(), "Help"); }

    /** Where to play from: the app's own copy when it has one, else the card. */
    private static File home() {
        File i = internal;
        return i != null && new File(i, "help.mp4").length() > 0 ? i : dir();
    }

    static File video() { return new File(home(), "help.mp4"); }

    static boolean available() { return video().length() > 0; }

    /** Copy the card's Help video and chapters into the app's storage when they differ (a real change, or the first time). */
    static void mirror() {
        File i = internal, src = dir();
        if (i == null || new File(src, "help.mp4").length() == 0) { android.util.Log.i("ab", "help mirror: nothing to copy (" + i + ", " + new File(src, "help.mp4").length() + ")"); return; }
        try {
            File v = new File(src, "help.mp4"), j = new File(src, "help.json"), dv = new File(i, "help.mp4"), dj = new File(i, "help.json");
            boolean same = dv.length() == v.length() && Math.abs(dv.lastModified() - v.lastModified()) < 2000 && dj.length() == j.length();
            if (same) return;
            if (i.getParentFile().getUsableSpace() < v.length() + 100L * 1024 * 1024) { android.util.Log.i("ab", "help mirror: not enough room"); return; }   // never fill the phone's storage
            i.mkdirs();
            i.setExecutable(true, false);
            copy(j, dj);
            copy(v, dv);
            dv.setLastModified(v.lastModified());
            android.util.Log.i("ab", "help mirror: copied " + dv.length() + " bytes");
        } catch (java.io.IOException e) { android.util.Log.i("ab", "help mirror failed: " + e); }
    }

    private static void copy(File from, File to) throws java.io.IOException {
        if (!from.exists()) { to.delete(); return; }
        File tmp = new File(to.getPath() + ".tmp");
        java.io.InputStream in = new java.io.FileInputStream(from);
        java.io.OutputStream out = new java.io.FileOutputStream(tmp);
        try {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally { in.close(); out.close(); }
        tmp.setReadable(true, false);
        if (!tmp.renameTo(to)) throw new java.io.IOException("rename failed");
    }

    /** The chapters, in order; a single "Help" chapter if there is no (readable) help.json. */
    @SuppressWarnings("unchecked")
    static List<Chapter> chapters() {
        List<Chapter> out = new ArrayList<Chapter>();
        try {
            Object c = Json.obj(Net.readText(new File(home(), "help.json"))).get("chapters");
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
