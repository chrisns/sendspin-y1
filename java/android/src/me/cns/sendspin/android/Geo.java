package me.cns.sendspin.android;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.TimeZone;
import me.cns.sendspin.Json;

/**
 * Keeps the iPod on local time wherever it is, so bedtime is 19:45 local on holiday too. Sources, best first:
 * a GPS fix looked up in the offline map (assets/tzgrid.bin, tools/make_tzgrid.py), then GeoIP on Wi-Fi.
 */
public final class Geo {
    private final Context app;
    private final Store store;
    private byte[] grid;
    public volatile String source = "";
    private volatile long geoIpAt;

    public Geo(Context c, Store s) {
        app = c;
        store = s;
        String saved = s.str("tz", "");
        if (saved.length() > 0) setZone(saved, s.num("tzStd", 0), s.str("tzSrc", "saved"), false);
    }

    public String zone() { return TimeZone.getDefault().getID(); }

    /**
     * From a GPS fix. No answer at sea (the map's nautical Etc/* zones are fixed offsets with no DST: mid-Channel in
     * summer is an hour behind England) or in a border cell whose zones keep different clocks (Monte Gordo is in
     * Portugal, its cell reaches Spain): the zone stays, the fix does not hold GeoIP off, and GeoIP asks at the next
     * Wi-Fi. The next fix on land, away from a border, decides otherwise.
     */
    public void fromLocation(double lat, double lon) {
        String[] z = lookup(lat, lon);
        if (z == null || z[0].startsWith("Etc/") || z[2].length() > 0) {
            geoIpAt = 0;
            return;
        }
        setZone(z[0], Long.parseLong(z[1]), "GPS", true);
        store.put("tzGpsAt", Long.valueOf(System.currentTimeMillis()));
    }

    /** On Wi-Fi, every 6 hours, unless GPS placed us recently. */
    public void maybeGeoIp() {
        long now = System.currentTimeMillis();
        if (now - geoIpAt < 6 * 3600000L || now - store.num("tzGpsAt", 0) < 12 * 3600000L) return;
        geoIpAt = now;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://ip-api.com/json/?fields=status,timezone,offset").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try {
                byte[] b = new byte[2048];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            } finally { in.close(); }
            java.util.Map<String, Object> m = Json.obj(new String(o.toByteArray(), "UTF-8"));
            if ("success".equals(m.get("status")) && m.get("timezone") != null) {
                long off = m.get("offset") instanceof Number ? ((Number) m.get("offset")).longValue() / 60 : 0;
                setZone(String.valueOf(m.get("timezone")), off, "GeoIP", true);
            }
        } catch (Exception ignored) { }
    }

    private synchronized void setZone(String id, long stdMin, String from, boolean persist) {
        TimeZone tz = TimeZone.getTimeZone(id);
        if (!tz.getID().equals(id)) {                   // a name newer than Android 4.2's tz database
            if (id.equals("Europe/Kyiv")) tz = TimeZone.getTimeZone("Europe/Kiev");
            else tz = TimeZone.getTimeZone(String.format("GMT%+03d:%02d", stdMin / 60, Math.abs(stdMin % 60)));
        }
        boolean changed = !tz.getID().equals(TimeZone.getDefault().getID());
        TimeZone.setDefault(tz);
        source = from;
        if (persist) {
            store.put("tz", id);
            store.put("tzStd", Long.valueOf(stdMin));
            store.put("tzSrc", from);
        }
        if (changed && persist) Root.run("setprop persist.sys.timezone " + tz.getID());   // the system picks it up at boot
    }

    /** {zone id, standard offset minutes, "mixed" or ""} for a point, from the offline map. */
    String[] lookup(double lat, double lon) {
        try {
            if (grid == null) {
                InputStream in = app.getAssets().open("tzgrid.bin");
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                try {
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                } finally { in.close(); }
                grid = o.toByteArray();
            }
            return lookup(grid, lat, lon);
        } catch (Exception e) {
            return null;
        }
    }

    static String[] lookup(byte[] b, double lat, double lon) throws Exception {
        boolean flags = b[3] == '2';                    // TZG2: bit 15 of a run's name marks mixed cells
        if (b[0] != 'T' || b[1] != 'Z' || b[2] != 'G' || (b[3] != '1' && !flags)) return null;
        int o = 4, n = u16(b, o);
        o += 2;
        String[] names = new String[n];
        int[] std = new int[n];
        for (int i = 0; i < n; i++) {
            int l = b[o] & 0xff;
            names[i] = new String(b, o + 1, l, "UTF-8");
            std[i] = (short) u16(b, o + 1 + l);
            o += 3 + l;
        }
        int rows = u16(b, o), cols = u16(b, o + 2);
        o += 4;
        int r = Math.max(0, Math.min(rows - 1, (int) ((90 - lat) * rows / 180)));
        int c = Math.max(0, Math.min(cols - 1, (int) ((lon + 180) * cols / 360)));
        for (int i = 0; i < r; i++) o += 2 + 4 * u16(b, o);
        int runs = u16(b, o);
        o += 2;
        for (int i = 0; i < runs; i++) {
            int len = u16(b, o + 4 * i), v = u16(b, o + 4 * i + 2), idx = flags ? v & 0x7fff : v;
            if (c < len) return new String[] { names[idx], String.valueOf(std[idx]), flags && (v & 0x8000) != 0 ? "mixed" : "" };
            c -= len;
        }
        return null;
    }

    private static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
}
