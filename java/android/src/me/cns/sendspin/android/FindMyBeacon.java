package me.cns.sendspin.android;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Broadcasts Apple Find My (offline finding) advertisements so passing iPhones report where the iPod is.
 *
 * Android 4.2 has no BLE advertising API, so a small native root helper (native/fmhci.c, shipped as an asset) holds the
 * radio (/dev/stpbt) and writes raw HCI commands the app drops into a file. It opens the device non-blocking and only
 * reads when poll() says so: the driver's blocking read sleeps uninterruptibly, which once left a shell reader holding
 * the radio until a reboot. That only works while Android's own Bluetooth is off, so the beacon steps aside whenever
 * headphones are in use.
 *
 * This chip's radio firmware never answers LE Set Advertising Parameters (0x2006): the shared Wi-Fi/Bluetooth processor
 * hangs and the kernel resets the whole chip, taking Wi-Fi down (rom/bt/NOTES.md). So the beacon never sends it and
 * runs on the spec defaults (connectable, 1.28 s, all three channels, the public address), and sets that public address
 * to the key's with MediaTek's own command (FC1A) before an HCI reset. That way it runs alongside Wi-Fi.
 *
 * While Android's Bluetooth is on (headphones), the radio belongs to mtkbt and this helper steps aside; the ROM's
 * driver shim (native/btshim.c) then carries the same beacon inside Android's own session, from a config file written
 * here (writeShimConfig). Keys come from /sdcard/FindMy/findmy.bin (rom/findmy/findmy_keys.py): either one static
 * OpenHaystack key or a timetable of 15-minute keys copied from a real Find My item. The shim can only follow a static
 * key: its address is classic Bluetooth's too, so it is pinned (pinShimAddress) and keys with another address are not
 * advertised while Bluetooth is on.
 */
public final class FindMyBeacon {
    private final Context ctx;
    private final File dir, fifo, helper, schedule;
    private volatile boolean running;
    public volatile boolean needsInit;
    private byte[] current;
    public volatile String status = "off";

    public FindMyBeacon(Context c) {
        ctx = c;
        dir = c.getFilesDir();
        fifo = new File(dir, "findmy");             // base name of the helper's files: .cmd .events .pid .stop
        helper = new File(dir, "fmhci");
        schedule = new File(android.os.Environment.getExternalStorageDirectory(), "FindMy/findmy.bin");
    }

    public boolean running() { return running; }

    public boolean hasKeys() { return schedule.exists() && schedule.length() >= 48; }

    /** The key the iPod should advertise right now, or null. */
    byte[] currentKey() {
        try {
            FileInputStream in = new FileInputStream(schedule);
            try {
                byte[] h = new byte[20];
                if (in.read(h) != 20 || h[0] != 'F' || h[1] != 'M' || h[2] != 'B' || h[3] != '1') return null;
                long count = le(h, 4, 4), start = le(h, 8, 8), interval = le(h, 16, 4);
                long idx = interval == 0 ? 0 : (System.currentTimeMillis() / 1000 - start) / interval;
                if (idx < 0) idx = 0;
                if (idx >= count) idx = count - 1;      // past the end of the timetable: keep the last key, flag it
                if (interval != 0 && (System.currentTimeMillis() / 1000 - start) / interval >= count) status = "keys ran out: push new ones";
                long skip = idx * 28;
                while (skip > 0) { long s = in.skip(skip); if (s <= 0) return null; skip -= s; }
                byte[] k = new byte[28];
                return in.read(k) == 28 ? k : null;
            } finally { in.close(); }
        } catch (IOException e) {
            return null;
        }
    }

    private static long le(byte[] b, int off, int n) {
        long v = 0;
        for (int i = n - 1; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xff);
        return v;
    }

    /** Copy the helper binary out of the APK when it is missing or changed (by content: a rebuild can keep the size). */
    private boolean installHelper() {
        try {
            java.io.InputStream in = ctx.getAssets().open("fmhci");
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            try {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            } finally { in.close(); }
            byte[] want = buf.toByteArray();
            if (helper.length() != want.length || !java.util.Arrays.equals(sha256(new java.io.ByteArrayInputStream(want)), sha256(new FileInputStream(helper)))) {
                File tmp = new File(dir, "fmhci.tmp");
                FileOutputStream o = new FileOutputStream(tmp);
                try { buf.writeTo(o); } finally { o.close(); }
                if (!tmp.renameTo(helper)) return false;
            }
            return helper.setExecutable(true, false);
        } catch (IOException e) {
            status = "error: " + e.getMessage();
            return false;
        }
    }

    private static byte[] sha256(java.io.InputStream in) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e.toString());
        } finally { in.close(); }
    }

    // "radio busy" (the helper could not open /dev/stpbt) backs off: 10 s, 1 min, 5 min, then every 30 min. Each try
    // costs a root shell, a grep per process and up to 6 s of polling, so retrying every 10 s for hours drains the
    // battery. Cleared when the helper starts, and by stop() (a key push or a Bluetooth handover).
    private static final long[] BUSY_BACKOFF = { 10000L, 60000L, 5 * 60000L, 30 * 60000L };
    private int busyTries;
    private long busyUntil;

    public synchronized void start() {
        if (running || !hasKeys()) return;
        if (android.os.SystemClock.elapsedRealtime() < busyUntil) return;
        if (!installHelper()) { status = "error: helper missing"; return; }
        killHelper();
        File pid = new File(fifo.getPath() + ".pid");
        pid.delete();
        Root.runAsync(helper.getPath() + " " + fifo.getPath());
        for (int i = 0; i < 30 && !pid.exists(); i++) sleep(200);
        if (!pid.exists()) {
            long wait = BUSY_BACKOFF[Math.min(busyTries, BUSY_BACKOFF.length - 1)];
            busyTries++;
            busyUntil = android.os.SystemClock.elapsedRealtime() + wait;
            status = "error: radio busy (" + busyTries + "x, next try in " + (wait >= 60000L ? wait / 60000L + " min" : wait / 1000L + " s") + ")";
            return;
        }
        busyTries = 0;
        busyUntil = 0;
        running = true;
        current = null;
        eventsRead = 0;
        send(pkt(0x0c03));                          // HCI reset
        if (!controllerAnswered()) {                // controller silent: hand the radio back and let the engine decide
            stop();
            status = "radio did not answer";
            needsInit = true;
            return;
        }
        tick();
    }

    private long lastAssert;

    /** Advertising off and on again with the same settings: cheap, and heals a controller that quietly stopped. */
    private void reassert() {
        send(pkt(0x200a, 0), pkt(0x200a, 1));
        lastAssert = System.currentTimeMillis();
    }

    /** No pkill on Android 4.2: find helpers (and any old shell ones) by command line; [m] keeps grep off its own. */
    static void killHelper() {
        Root.run("for p in /proc/[0-9]*; do grep -q -e 'f[m]hci' -e 'fmbeacon[.]sh' $p/cmdline 2>/dev/null && kill -9 ${p#/proc/}; done; sleep 1");
    }

    /** True when no helper process is left (so mtkbt can safely take the radio). */
    static boolean helperGone() {
        return Root.run("for p in /proc/[0-9]*; do grep -q -e 'f[m]hci' -e 'fmbeacon[.]sh' $p/cmdline 2>/dev/null && exit 1; done; exit 0");
    }

    /** Stop and wait until the radio is really free; false if something is still holding it. */
    public boolean releaseAndWait() {
        stop();
        for (int i = 0; i < 20; i++) { if (helperGone()) return true; killHelper(); sleep(250); }
        return false;
    }

    /** True once the controller has answered something since the helper started. */
    private boolean controllerAnswered() {
        File ev = new File(fifo.getPath() + ".events");
        return ev.exists() && ev.length() > 0;
    }

    /** Ask the helper to switch advertising off and close the radio; kill it only if it does not go. */
    public synchronized void stop() {
        running = false;
        current = null;
        busyTries = 0;
        busyUntil = 0;
        File pid = new File(fifo.getPath() + ".pid");
        if (pid.exists()) {
            try { new FileOutputStream(new File(fifo.getPath() + ".stop")).close(); } catch (IOException ignored) { }
            for (int i = 0; i < 30 && pid.exists(); i++) sleep(100);
        }
        if (!helperGone()) killHelper();
        status = "off";
    }

    /** Rotate to the key for the current 15-minute window when it changes. */
    public synchronized void tick() {
        if (!running) return;
        if (!new File(fifo.getPath() + ".pid").exists()) { running = false; current = null; status = "helper stopped"; return; }
        byte[] k = currentKey();
        if (k == null) { status = "no keys"; return; }
        if (current != null && java.util.Arrays.equals(current, k)) {
            if (dropConnections() || System.currentTimeMillis() - lastAssert > 5 * 60000L) reassert();
            return;
        }
        pinShimAddress(k);
        byte[] addr = new byte[6];                  // sent LSB first
        byte[] msb = address(k);
        for (int i = 0; i < 6; i++) addr[i] = msb[5 - i];
        byte[] data = advData(k);
        // never 0x2006 (see above); the address only takes after a reset
        send(pkt(0x200a, 0),
            pkt(0xfc1a, addr),
            pkt(0x0c03),
            pkt(0x2008, data),
            pkt(0x200a, 1));
        sleep(2000);
        reassert();                                 // the first enable straight after a reset is silently ignored
        current = k;
        status = "on";
    }

    private long eventsRead;

    /**
     * Default advertising is connectable, so a phone could connect, and advertising stops while it is connected. Look
     * for LE Connection Complete (04 3e 13 01 status handle...) in the helper's event log and hang up on each one.
     * True if it dropped any, so the caller restarts advertising.
     */
    private boolean dropConnections() {
        File ev = new File(fifo.getPath() + ".events");
        if (ev.length() <= eventsRead) return false;
        boolean dropped = false;
        try {
            java.io.RandomAccessFile f = new java.io.RandomAccessFile(ev, "r");
            try {
                long from = Math.max(0, eventsRead - 6);    // overlap, in case an event straddled the last read
                byte[] b = new byte[(int) Math.min(65536, f.length() - from)];
                f.seek(from);
                f.readFully(b);
                eventsRead = from + b.length;
                for (int i = 0; i + 6 < b.length; i++) {
                    if (b[i] == 0x04 && b[i + 1] == 0x3e && b[i + 3] == 0x01 && b[i + 4] == 0x00) {
                        send(pkt(0x0406, b[i + 5] & 0xff, b[i + 6] & 0x0f, 0x13));   // disconnect: remote user ended
                        dropped = true;
                    }
                }
            } finally { f.close(); }
        } catch (IOException ignored) { }
        return dropped;
    }

    /** The Bluetooth address a key is advertised from: its first six bytes with the top two bits set (most significant first). */
    static byte[] address(byte[] k) {
        byte[] a = new byte[6];
        System.arraycopy(k, 0, a, 0, 6);
        a[0] |= (byte) 0xC0;
        return a;
    }

    /** Length byte + 31 bytes of advertising data for a key. */
    static byte[] advData(byte[] k) {
        byte[] data = new byte[32];
        data[0] = 31;
        data[1] = 30; data[2] = (byte) 0xFF;        // manufacturer specific, 30 bytes follow
        data[3] = 0x4C; data[4] = 0x00;             // Apple
        data[5] = 0x12; data[6] = 25;               // offline finding, 25 bytes
        data[7] = 0x00;                             // status
        System.arraycopy(k, 6, data, 8, 22);        // rest of the public key
        data[30] = (byte) ((k[0] & 0xff) >> 6);     // top bits of the first key byte
        data[31] = 0x00;                            // hint
        return data;
    }

    // ---- while Android's Bluetooth is on, the shim in /system/lib/libbluetoothdrv.so runs the beacon (native/btshim.c)

    private static final File SHIM_MARK = new File("/system/etc/fmshim"), SHIM_CONFIG = new File("/data/local/tmp/fmbeacon.hex"),
        SHIM_PIN = new File("/data/local/tmp/fmbeacon.addr");
    private String shimWritten = "", pinChecked = "";
    private boolean pinWarned;

    /** True when the ROM carries the shim (rom/bt/install_shim.sh). */
    public static boolean shimInstalled() { return SHIM_MARK.exists(); }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /**
     * The shim sets the Bluetooth address at every enable from SHIM_PIN, and classic Bluetooth uses it too: headphones are
     * paired to it, so it must never change. Written once, from the first key seen (before the first Bluetooth session
     * if the beacon ran first), and never rewritten or removed, not even when Find My is switched off. A new static key
     * with another address is not advertised by the shim until the pin is removed by hand
     * (`rm /data/local/tmp/fmbeacon.addr`) and the headphones are paired again.
     */
    void pinShimAddress(byte[] k) {
        String want = hex(address(k));
        if (want.equals(pinChecked) || !shimInstalled()) return;
        if (!SHIM_PIN.exists()) {
            if (Root.run("printf %s " + want + " > " + SHIM_PIN.getPath() + "; chmod 644 " + SHIM_PIN.getPath())) {
                pinChecked = want;
                android.util.Log.i("ab", "find my: Bluetooth address pinned to " + want);
            }
            return;
        }
        String pinned = "";
        try {
            FileInputStream in = new FileInputStream(SHIM_PIN);
            try { byte[] b = new byte[12]; if (in.read(b) == 12) pinned = new String(b, "US-ASCII"); } finally { in.close(); }
        } catch (IOException ignored) { return; }
        pinChecked = want;
        if (!pinned.equalsIgnoreCase(want) && !pinWarned) {
            pinWarned = true;
            android.util.Log.i("ab", "find my: key address " + want + " is not the pinned Bluetooth address " + pinned + ": no beacon while Bluetooth is on");
        }
    }

    /**
     * Hand the shim the current key's advertising data and address. It reads the file every 30 s and advertises it only
     * while the address matches the one pinned at Bluetooth enable, so a rotating timetable goes quiet with Bluetooth on.
     */
    public void writeShimConfig() {
        byte[] k = currentKey();
        if (k == null) return;
        pinShimAddress(k);
        StringBuilder sb = new StringBuilder();
        sb.append(hex(advData(k))).append(hex(address(k)));
        if (sb.toString().equals(shimWritten) && SHIM_CONFIG.exists()) return;
        if (Root.run("printf %s " + sb + " > " + SHIM_CONFIG.getPath() + "; chmod 644 " + SHIM_CONFIG.getPath())) shimWritten = sb.toString();
    }

    /** The shim has no key to advertise (the file is removed with the keys; the pinned address stays). */
    public void clearShimConfig() {
        if (!shimWritten.isEmpty() || SHIM_CONFIG.exists()) { Root.run("rm -f " + SHIM_CONFIG.getPath()); shimWritten = ""; }
    }

    // ---- HCI plumbing

    private static byte[] pkt(int opcode, int... params) {
        byte[] p = new byte[params.length];
        for (int i = 0; i < params.length; i++) p[i] = (byte) params[i];
        return pkt(opcode, p);
    }

    private static byte[] pkt(int opcode, byte[] params) {
        byte[] b = new byte[4 + params.length];
        b[0] = 0x01;
        b[1] = (byte) opcode;
        b[2] = (byte) (opcode >> 8);
        b[3] = (byte) params.length;
        System.arraycopy(params, 0, b, 4, params.length);
        return b;
    }

    /** Hand packets to the helper: one hex line each, written aside and renamed in, so it sees all or nothing. */
    private void send(byte[]... packets) {
        StringBuilder sb = new StringBuilder();
        for (byte[] p : packets) {
            for (byte x : p) sb.append(String.format("%02x", x & 0xff));
            sb.append('\n');
        }
        File cmd = new File(fifo.getPath() + ".cmd"), tmp = new File(fifo.getPath() + ".tmp");
        for (int i = 0; i < 30 && cmd.exists(); i++) sleep(100);   // previous batch not yet taken
        try {
            FileOutputStream o = new FileOutputStream(tmp);
            try { o.write(sb.toString().getBytes("US-ASCII")); } finally { o.close(); }
            tmp.setReadable(true, false);
            if (!tmp.renameTo(cmd)) { status = "error: could not queue command"; return; }
        } catch (IOException e) {
            status = "error: " + e.getMessage();
            return;
        }
        for (int i = 0; i < 30 && cmd.exists(); i++) sleep(100);   // taken
        sleep(100 + 450L * packets.length);         // each packet waits up to 400 ms for its reply
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}
