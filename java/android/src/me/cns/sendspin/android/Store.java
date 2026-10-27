package me.cns.sendspin.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import me.cns.sendspin.Json;

/**
 * Everything the player remembers, in one small JSON file: settings, per-book progress and the
 * cached library. Written atomically (tmp + rename) so a flat battery cannot corrupt it.
 */
public final class Store {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final File file;
    private Map<String, Object> root = new LinkedHashMap<String, Object>();

    public Store(File dir) {
        file = new File(dir, "state.json");
        try {
            if (file.exists()) {
                byte[] b = readAll(file);
                root = Json.obj(new String(b, UTF8));
            }
        } catch (Exception e) {
            root = new LinkedHashMap<String, Object>();
        }
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } finally { in.close(); }
    }

    public synchronized String str(String k, String def) {
        Object v = root.get(k);
        return v instanceof String ? (String) v : def;
    }

    public synchronized long num(String k, long def) {
        Object v = root.get(k);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    public synchronized void put(String k, Object v) { root.put(k, v); save(); }

    /** A named, mutable sub-map. Callers must hold the Store lock (synchronized(store)) while editing. */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> section(String name) {
        Object v = root.get(name);
        if (!(v instanceof Map)) { v = new LinkedHashMap<String, Object>(); root.put(name, v); }
        return (Map<String, Object>) v;
    }

    public synchronized Object raw(String k) { return root.get(k); }

    public synchronized void save() {
        try {
            File tmp = new File(file.getPath() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try { o.write(Json.write(root).getBytes(UTF8)); o.getFD().sync(); } finally { o.close(); }
            if (!tmp.renameTo(file)) throw new IOException("rename failed");
        } catch (IOException e) {
            android.util.Log.w("store", "save failed: " + e);
        }
    }
}
