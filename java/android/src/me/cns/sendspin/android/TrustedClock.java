package me.cns.sendspin.android;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * The time the bedtime limit trusts. Fixes come from NTP (on any Wi-Fi), the Date header of Home Assistant's replies
 * (when UDP is blocked) and GPS (away from Wi-Fi). Between fixes it counts on with elapsedRealtime, which keeps going
 * through sleep and cannot be changed by anyone, and a good fix also corrects the system clock (as root).
 * After a reboot with no fix yet, the clock never goes back before the last time it knew (persisted).
 */
public final class TrustedClock {
    // home Wi-Fi drops most NTP packets (each server answers now and then), so ask many, twice round
    private static final String[] NTP = { "pool.ntp.org", "time.apple.com", "time.google.com", "0.uk.pool.ntp.org",
        "time.euro.apple.com", "1.uk.pool.ntp.org", "time.cloudflare.com", "2.uk.pool.ntp.org" };

    private final Context app;
    private final Store store;
    private volatile long baseUtc, baseElapsed;   // a fix: UTC at elapsedRealtime baseElapsed
    public volatile String source = "system clock";
    public Geo geo;                                   // gets each GPS position, for the timezone
    private long lastSaved;

    private long softUtc, softElapsed;               // after a flat battery: count on from the last time we knew
    private boolean wasReset;                        // this boot started with the clock wound back (flat battery)

    public TrustedClock(Context c, Store s) {
        app = c;
        store = s;
        long floor = store.num("clockFloor", 0);
        if (System.currentTimeMillis() < floor) {    // a flat battery reset the clock to the past: never trust that
            softUtc = floor;
            softElapsed = SystemClock.elapsedRealtime();
            source = "last known time (clock was reset)";
            wasReset = true;
            // put the system clock there too: in 1970 every TLS certificate looks invalid, so not even Home
            // Assistant's Date header could fix the time
            Root.runAsync("TZ=UTC date -s " + utcStamp(floor));
        }
    }

    public long now() {
        if (baseElapsed != 0) return baseUtc + (SystemClock.elapsedRealtime() - baseElapsed);
        if (softElapsed != 0) return softUtc + (SystemClock.elapsedRealtime() - softElapsed);
        return Math.max(System.currentTimeMillis(), store.num("clockFloor", 0));
    }

    public boolean fixed() { return baseElapsed != 0; }

    public long fixAgeMs() { return baseElapsed == 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - baseElapsed; }

    /**
     * Extra 30 s steps either side for the grown-up code: a cheap crystal drifts a few seconds a day, so allow a step
     * a day since the last fix (up to 10 minutes, about three weeks away from Wi-Fi and GPS).
     */
    public int codeSlack() {
        long age = fixAgeMs();
        if (age == Long.MAX_VALUE && !wasReset) {     // a plain reboot: the clock kept time since the last fix
            long last = store.num("clockFixUtc", 0);
            if (last > 0) age = Math.max(0, now() - last);
        }
        return age == Long.MAX_VALUE ? 20 : (int) Math.min(20, 1 + age / 86400000L);
    }

    /** Remember how far time has got, so a reboot cannot wind it back. Call every minute or so. */
    public void persist() {
        long n = now();
        if (n - lastSaved > 60000) { lastSaved = n; store.put("clockFloor", Long.valueOf(n)); }
    }

    public synchronized void fix(long utc, String from) {
        baseUtc = utc;
        baseElapsed = SystemClock.elapsedRealtime();
        source = from;
        persist();
        store.put("clockFixUtc", Long.valueOf(utc));
        if (Math.abs(System.currentTimeMillis() - utc) > 2000) Root.run("TZ=UTC date -s " + utcStamp(utc));   // keep everything else (lyrics, logs) right too
    }

    private static String utcStamp(long utc) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd.HHmmss", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(utc));
    }

    /** One SNTP exchange; true on a fix. Blocking, a few seconds at most. */
    public boolean syncNtp() {
        for (int round = 0; round < 2; round++) for (String host : NTP) {
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                s.setSoTimeout(2500);
                byte[] b = new byte[48];
                b[0] = 0x1B;                                       // LI 0, version 3, client
                InetAddress a = InetAddress.getByName(host);
                long t0 = System.currentTimeMillis(), e0 = SystemClock.elapsedRealtime();
                writeTs(b, 40, t0);
                s.send(new DatagramPacket(b, b.length, a, 123));
                DatagramPacket r = new DatagramPacket(new byte[48], 48);
                s.receive(r);
                long e1 = SystemClock.elapsedRealtime();
                byte[] d = r.getData();
                if ((d[1] & 0xff) == 0 || (d[0] & 0x07) != 4) continue;   // kiss-of-death or not a server reply
                long rx = readTs(d, 32), tx = readTs(d, 40);
                long t1 = t0 + (e1 - e0);
                long offset = ((rx - t0) + (tx - t1)) / 2;
                fix(t1 + offset, "NTP " + host);
                return true;
            } catch (IOException ignored) {
            } finally {
                if (s != null) s.close();
            }
        }
        return false;
    }

    /** A Date header (1 s resolution): only used when NTP has not answered for a while. */
    public void fromHttpDate(String date) {
        if (date == null || fixAgeMs() < 6 * 3600000L) return;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
            fix(f.parse(date).getTime() + 500, "Home Assistant");
        } catch (java.text.ParseException ignored) { }
    }

    // ---- GPS: the Y1's MT6572 has a GPS receiver; its fix carries satellite time

    private long gpsTriedAt;

    public boolean hasGps() {
        try {
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.getProvider(LocationManager.GPS_PROVIDER) != null;
        } catch (RuntimeException e) { return false; }
    }

    /** The last GPS position (for Home Assistant's map): 0 time = none yet. */
    public volatile double fixLat, fixLon;
    public volatile float fixAccuracy;
    public volatile long fixAtUtc;

    /** Record a position (from GPS, or the app's test hook). */
    public void position(double lat, double lon, float accuracy, long utc) {
        fixLat = lat; fixLon = lon; fixAccuracy = accuracy; fixAtUtc = utc;
        store.put("fix", lat + "," + lon + "," + accuracy + "," + utc);   // survives a restart
    }

    /** The last position after a restart. */
    public void loadPosition() {
        String[] p = store.str("fix", "").split(",");
        if (p.length == 4) try { fixLat = Double.parseDouble(p[0]); fixLon = Double.parseDouble(p[1]);
            fixAccuracy = Float.parseFloat(p[2]); fixAtUtc = Long.parseLong(p[3]); } catch (NumberFormatException ignored) { }
    }

    /**
     * Away from Wi-Fi (on Wi-Fi, NTP and GeoIP do the job, and Home Assistant knows it is home): listen to GPS for up to
     * 2 minutes, at most every 30 minutes (battery), for the time, the timezone and where the iPod is. It stops at the
     * first fix. Indoors it often hears nothing, which is fine.
     */
    public void maybeGps(boolean onWifi) {
        long el = SystemClock.elapsedRealtime();
        if (onWifi || (gpsTriedAt != 0 && el - gpsTriedAt < 30 * 60000L) || !hasGps()) return;
        gpsTriedAt = el;
        final LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        final LocationListener l = new LocationListener() {
            public void onLocationChanged(Location loc) {
                // getTime() is the satellite UTC of the fix (delivered within a second of it)
                fix(loc.getTime(), "GPS");
                position(loc.getLatitude(), loc.getLongitude(), loc.hasAccuracy() ? loc.getAccuracy() : 50f, loc.getTime());
                if (geo != null) geo.fromLocation(loc.getLatitude(), loc.getLongitude());
                lm.removeUpdates(this);
            }
            public void onStatusChanged(String p, int s, Bundle b) { }
            public void onProviderEnabled(String p) { }
            public void onProviderDisabled(String p) { }
        };
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, l, Looper.getMainLooper());
            new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() { lm.removeUpdates(l); }
            }, 120000);
        } catch (RuntimeException ignored) { }   // GPS switched off in settings, or no permission
    }

    private static void writeTs(byte[] b, int off, long ms) {
        long secs = ms / 1000 + 2208988800L, frac = (ms % 1000) * 0x100000000L / 1000;
        for (int i = 3; i >= 0; i--) { b[off + i] = (byte) secs; secs >>= 8; }
        for (int i = 7; i >= 4; i--) { b[off + i] = (byte) frac; frac >>= 8; }
    }

    private static long readTs(byte[] b, int off) {
        long secs = 0, frac = 0;
        for (int i = 0; i < 4; i++) secs = (secs << 8) | (b[off + i] & 0xff);
        for (int i = 4; i < 8; i++) frac = (frac << 8) | (b[off + i] & 0xff);
        return (secs - 2208988800L) * 1000 + frac * 1000 / 0x100000000L;
    }
}
