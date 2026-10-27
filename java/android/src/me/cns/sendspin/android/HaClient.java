package me.cns.sendspin.android;

import android.content.Context;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.cns.sendspin.Crypto;
import me.cns.sendspin.Json;

/**
 * Home Assistant, through the Y1 Player integration (custom_components/y1_player). The player holds no
 * Home Assistant token: it has its own X25519 key pair (files/device.key, made on first use, never leaves the device)
 * and knows the integration's public key. Each sync is one sealed message to the integration's webhook carrying the
 * player's sensors, location, bedtime minutes and grown-up unlocks; the sealed reply carries its settings, the bedtime
 * budget and "find" requests. The integration does nothing else for it, and removing the player in Home Assistant
 * revokes it.
 *
 * Message: {"n": base64(nonce[12]), "c": base64(ChaCha20-Poly1305(key, nonce, aad "y1/1", json))}, keys from
 * HKDF("y1-player v1", X25519(player, integration)): the first player-to-integration, the second back. The json
 * carries a counter "c" that only ever goes up, so a captured message cannot be replayed.
 *
 * Settings from files/ha.json (written by deploy/y1_setup.py pair, never in the APK):
 * {"local_url": "https://homeassistant.local:8123", "webhook_id": "...", "cloudhook_url": "https://hooks.nabu.casa/...",
 *  "server_public_key": "base64", "totp": "BASE32"}.
 * The home address is tried first, then the Nabu Casa cloudhook, so it works on any Wi-Fi.
 */
public final class HaClient {
    private static final byte[] AAD = { 'y', '1', '/', '1' };
    private static final SecureRandom RNG = new SecureRandom();

    private final Context app;
    private final TrustedClock clock;
    private final List<String> hooks = new ArrayList<String>();   // full webhook URLs, home first
    private int good;                     // index of the URL that answered last
    public byte[] totpKey;
    private byte[] kOut, kIn;             // player-to-integration, integration-to-player
    private long loaded;                  // ha.json's time when read
    public volatile String lastError = "";
    public volatile boolean removed;      // the integration no longer knows this player: pair it again

    public HaClient(Context c, TrustedClock clk) {
        app = c;
        clock = clk;
        reload();
    }

    /** This player's private key, made on first use. */
    private synchronized byte[] privateKey() {
        File f = new File(app.getFilesDir(), "device.key");
        try {
            if (f.length() == 32) {
                byte[] k = new byte[32];
                InputStream in = new FileInputStream(f);
                try { if (in.read(k) == 32) return k; } finally { in.close(); }
            }
            byte[] k = Crypto.newPrivateKey();
            File tmp = new File(f.getPath() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try { o.write(k); o.getFD().sync(); } finally { o.close(); }
            if (!tmp.renameTo(f)) throw new IOException("could not save the key");
            return k;
        } catch (IOException e) {
            throw new IllegalStateException("player key: " + e.getMessage());
        }
    }

    /** What to pair with in Home Assistant (shown under About; files/device.pub is read by y1_setup.py pair). */
    public String publicKey() {
        String p = Base64.encodeToString(Crypto.publicKey(privateKey()), Base64.NO_WRAP);
        File f = new File(app.getFilesDir(), "device.pub");
        if (f.length() != p.length()) {
            try {
                FileOutputStream o = new FileOutputStream(f);
                try { o.write(p.getBytes("UTF-8")); } finally { o.close(); }
            } catch (IOException ignored) { }
        }
        return p;
    }

    public synchronized void reload() {
        hooks.clear();
        totpKey = null;
        kOut = kIn = null;
        removed = false;
        publicKey();
        File f = new File(app.getFilesDir(), "ha.json");
        loaded = f.lastModified();
        try {
            Map<String, Object> m = Json.obj(Net.readText(f));
            Object id = m.get("webhook_id"), local = m.get("local_url"), cloud = m.get("cloudhook_url"), spub = m.get("server_public_key");
            if (m.get("totp") != null) totpKey = Totp.base32(String.valueOf(m.get("totp")));
            if (spub == null) return;
            byte[] shared = Crypto.x25519(privateKey(), Base64.decode(String.valueOf(spub), Base64.DEFAULT));
            byte[][] k = Crypto.hkdf("y1-player v1".getBytes("UTF-8"), shared, 2);
            kOut = k[0];
            kIn = k[1];
            if (id != null && local != null) hooks.add(String.valueOf(local).replaceAll("/+$", "") + "/api/webhook/" + id);
            if (cloud != null) hooks.add(String.valueOf(cloud));
        } catch (Exception e) {
            hooks.clear();
        }
    }

    /** ha.json was replaced (a new pairing): read it again. */
    public void maybeReload() {
        if (new File(app.getFilesDir(), "ha.json").lastModified() != loaded) reload();
    }

    public boolean configured() { return !hooks.isEmpty() && !removed; }

    /** On home Wi-Fi: go back to trying the home address first (the cloudhook is only for away). */
    public void preferHome() { good = 0; }

    public static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** The request never reached Home Assistant (no connection), so another URL may safely be tried. */
    static final class NotSent extends IOException { NotSent(String m) { super(m); } }

    /** Our counter was behind the integration's (after a reinstall): it told us where it is. */
    static final class Behind extends IOException { final long last; Behind(long l) { super("counter behind"); last = l; } }

    /** One sync: report what is in it; the reply has the settings, bedtime and any find request. */
    public Map<String, Object> sync(Map<String, Object> report) throws IOException {
        try {
            return send(report);
        } catch (Behind b) {
            synchronized (this) { if (b.last > counter()) setCounter(b.last); }
            return send(report);
        }
    }

    private long counter() { return app.getSharedPreferences("ha", 0).getLong("counter", 0); }
    private void setCounter(long v) { app.getSharedPreferences("ha", 0).edit().putLong("counter", v).commit(); }

    /** Above every counter sent before, and kept across restarts. */
    private synchronized long nextCounter() {
        long c = Math.max(counter() + 1, System.currentTimeMillis());
        setCounter(c);
        return c;
    }

    private Map<String, Object> send(Map<String, Object> report) throws IOException {
        if (!configured()) throw new IOException(removed ? "not paired in Home Assistant: pair it again" : "Home Assistant not set up");
        Map<String, Object> msg = new HashMap<String, Object>(report);
        msg.put("c", Long.valueOf(nextCounter()));
        byte[] nonce = new byte[12];
        RNG.nextBytes(nonce);
        byte[] ct = Crypto.aeadEncrypt(kOut, nonce, AAD, Json.write(msg).getBytes("UTF-8"));
        String body = Json.write(args("n", Base64.encodeToString(nonce, Base64.NO_WRAP), "c", Base64.encodeToString(ct, Base64.NO_WRAP)));
        IOException last = null;
        for (int k = 0; k < hooks.size(); k++) {
            int i = (good + k) % hooks.size();
            try {
                Map<String, Object> r = open(post(hooks.get(i), body));
                if (r.get("c") == null && r.get("last") instanceof Number) throw new Behind(((Number) r.get("last")).longValue());
                good = i;
                lastError = "";
                return r;
            } catch (NotSent e) {
                last = e;                       // never reached this URL: try the next
            } catch (Behind e) {
                throw e;
            } catch (IOException e) {
                last = e;
                break;                          // it may have arrived: never resend it elsewhere (the next sync follows)
            }
        }
        lastError = last.getMessage();
        throw last;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> open(String reply) throws IOException {
        Object o = reply.length() == 0 ? null : Json.parse(reply);
        if (!(o instanceof Map) || ((Map<String, Object>) o).get("n") == null) {
            removed = true;                     // Home Assistant answers an unknown webhook with an empty 200
            throw new IOException("not paired in Home Assistant: pair it again");
        }
        Map<String, Object> m = (Map<String, Object>) o;
        try {
            byte[] pt = Crypto.aeadDecrypt(kIn, Base64.decode(String.valueOf(m.get("n")), Base64.DEFAULT), AAD,
                Base64.decode(String.valueOf(m.get("c")), Base64.DEFAULT));
            return Json.obj(new String(pt, "UTF-8"));
        } catch (RuntimeException e) {
            throw new IOException("Home Assistant reply could not be decrypted");
        }
    }

    private String post(String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try { Tls.apply(app, c); } catch (Exception e) { throw new IOException("TLS: " + e.getMessage()); }
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try {
            OutputStream o = c.getOutputStream();   // connects (and does TLS): a failure here means nothing was sent
            try { o.write(body.getBytes("UTF-8")); } finally { o.close(); }
        } catch (IOException e) {
            throw new NotSent(new URL(url).getHost() + ": " + e.getMessage());
        }
        int code = c.getResponseCode();
        clock.fromHttpDate(c.getHeaderField("Date"));   // even a refusal carries the time
        if (code == 400) { removed = true; throw new IOException("Home Assistant does not know this player's key: pair it again"); }
        if (code != 409 && code / 100 != 2) throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
        InputStream in = code == 409 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), "UTF-8").trim();
        } finally { in.close(); }
    }
}
