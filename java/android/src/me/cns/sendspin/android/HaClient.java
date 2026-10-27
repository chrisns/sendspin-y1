package me.cns.sendspin.android;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.cns.sendspin.Crypto;
import me.cns.sendspin.Json;

/**
 * Home Assistant, as a mobile app device (the same API as the companion app): the player is a device in Home Assistant
 * with its own sensors, and reads and updates the shared bedtime budget. No user token on the device: it was registered
 * once from a computer (deploy/push_bedtime.py child) as a read-only Home Assistant user and only holds the device's
 * webhook. It changes nothing directly: it fires events that automations act on (adding its minutes, telling a parent
 * about an unlock). Deleting the device in Home Assistant revokes it (the webhook then answers 410).
 *
 * Settings come from files/ha.json (pushed by deploy/push_bedtime.py push, never in the APK):
 * {"local_url": "https://homeassistant.local:8123", "webhook_id": "...", "cloudhook_url": "https://hooks.nabu.casa/...",
 *  "totp": "BASE32", "secret": "hex", "entities": {...}, "events": {...}, "sensor_prefix": "y1_"}.
 * entities/events/sensor_prefix name the Home Assistant helpers this device uses (see entity(), defaults y1_*).
 * The home address is tried first, then the Nabu Casa cloudhook, so it works on any Wi-Fi.
 */
public final class HaClient {
    private final Context app;
    private final TrustedClock clock;
    private final List<String> hooks = new ArrayList<String>();   // full webhook URLs, home first
    private int good;                     // index of the URL that answered last
    public byte[] totpKey;
    private byte[] boxKey, legacyKey;     // payload encryption (registered with supports_encryption): see SecretBox
    private Map<String, Object> entities = new HashMap<String, Object>(), events = new HashMap<String, Object>();
    private String sensorPrefix = "y1_";
    /** First-boot identity values from setup (owner_name, device_name, ...): used until Home Assistant has been read. */
    public volatile Map<String, Object> identity = new HashMap<String, Object>();

    /** Generic Home Assistant helper names; ha.json "entities" overrides any of them. */
    private static final String[][] DEFAULT_ENTITIES = {
        { "window_start", "input_datetime.y1_bedtime_start" }, { "window_end", "input_datetime.y1_bedtime_end" },
        { "cap", "input_number.y1_bedtime_cap_minutes" }, { "used", "input_number.y1_bedtime_used_minutes" },
        { "find", "input_button.y1_find" },
        { "owner_name", "input_text.y1_owner_name" }, { "device_name", "input_text.y1_device_name" },
        { "lost_contact", "input_text.y1_lost_contact" }, { "home_wifi", "input_text.y1_home_wifi" },
        { "counted_speakers", "input_text.y1_counted_speakers" },
    };

    /** The entity id this device uses for key (window_start, cap, find, owner_name, ...). */
    public String entity(String key) {
        Object v = entities.get(key);
        if (v != null) return String.valueOf(v);
        for (String[] d : DEFAULT_ENTITIES) if (d[0].equals(key)) return d[1];
        return null;
    }

    /** The event type for key ("played", "unlocked"): ha.json "events", default y1_<key>. */
    public String eventName(String key) {
        Object v = events.get(key);
        return v != null ? String.valueOf(v) : "y1_" + key;
    }

    /** Prefix of this device's sensor unique ids (keep it once chosen: changing it makes new sensors in HA). */
    public String sensorPrefix() { return sensorPrefix; }
    public volatile String lastError = "";
    public volatile boolean removed;      // the device was deleted in Home Assistant (410)

    public HaClient(Context c, TrustedClock clk) {
        app = c;
        clock = clk;
        reload();
    }

    @SuppressWarnings("unchecked")
    public synchronized void reload() {
        hooks.clear();
        totpKey = null;
        boxKey = null;
        legacyKey = null;
        removed = false;
        try {
            Map<String, Object> m = Json.obj(Net.readText(new File(app.getFilesDir(), "ha.json")));
            Object id = m.get("webhook_id"), local = m.get("local_url"), cloud = m.get("cloudhook_url");
            if (id != null && local != null) hooks.add(String.valueOf(local).replaceAll("/+$", "") + "/api/webhook/" + id);
            if (cloud != null) hooks.add(String.valueOf(cloud));
            if (m.get("totp") != null) totpKey = Totp.base32(String.valueOf(m.get("totp")));
            entities = m.get("entities") instanceof Map ? Json.map(m.get("entities")) : new HashMap<String, Object>();
            events = m.get("events") instanceof Map ? Json.map(m.get("events")) : new HashMap<String, Object>();
            sensorPrefix = m.get("sensor_prefix") != null ? String.valueOf(m.get("sensor_prefix")) : "y1_";
            identity = m.get("identity") instanceof Map ? Json.map(m.get("identity")) : new HashMap<String, Object>();
            if (m.get("secret") != null) {   // HA's hex secret: the key is its bytes (older replies use the text itself)
                String sec = String.valueOf(m.get("secret"));
                boxKey = Crypto.hex(sec);
                legacyKey = java.util.Arrays.copyOf(sec.getBytes("UTF-8"), 32);
            }
        } catch (Exception e) {
            lastError = "not set up";
            if (new File(app.getFilesDir(), "ha.json").exists()) android.util.Log.i("ab", "ha.json unreadable: " + e);
        }
    }

    private long loadedStamp = -1;

    /** Pick up a freshly pushed ha.json (deploy/push_bedtime.py) without a restart. */
    public void maybeReload() {
        long m = new File(app.getFilesDir(), "ha.json").lastModified();
        if (m != loadedStamp) { loadedStamp = m; reload(); }
    }

    public boolean configured() { return !hooks.isEmpty() && !removed; }

    /** Render named templates in Home Assistant: name -> template in, name -> result out. */
    public Map<String, Object> templates(Map<String, String> named) throws IOException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, String> e : named.entrySet()) data.put(e.getKey(), args("template", e.getValue()));
        return Json.map(Json.parse(webhook("render_template", data)));
    }

    /**
     * Fire an event for Home Assistant's automations to act on (eventName("played"), eventName("unlocked")). The device
     * is a read-only Home Assistant user, so it cannot change entities itself; the automations only accept its events.
     */
    public void event(String type, Map<String, Object> data) throws IOException {
        webhook("fire_event", args("event_type", type, "event_data", data));
    }

    /** Create or refresh one sensor (re-registering updates it). type is "sensor" or "binary_sensor". */
    public void registerSensor(Map<String, Object> sensor) throws IOException {
        webhook("register_sensor", sensor);
    }

    /** Update sensor states; returns the unique ids Home Assistant does not know yet (to register them). */
    @SuppressWarnings("unchecked")
    public List<String> updateSensors(List<Map<String, Object>> states) throws IOException {
        List<String> missing = new ArrayList<String>();
        Object r = Json.parse(webhook("update_sensor_states", states));
        if (r instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) r).entrySet()) {
                Object v = e.getValue();
                if (v instanceof Map && Boolean.FALSE.equals(((Map<String, Object>) v).get("success"))) missing.add(e.getKey());
            }
        }
        return missing;
    }

    /** Where the iPod is, for its device tracker: GPS coordinates, or a zone name ("home", "not_home"). */
    public void location(Map<String, Object> data) throws IOException {
        webhook("update_location", data);
    }

    public static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** The request never reached Home Assistant (no connection), so another URL may safely be tried. */
    static final class NotSent extends IOException { NotSent(String m) { super(m); } }

    /** On home Wi-Fi: go back to trying the home address first (the cloudhook is only for away). */
    public void preferHome() { good = 0; }

    private String webhook(String type, Object data) throws IOException {
        if (!configured()) throw new IOException(removed ? "removed from Home Assistant" : "Home Assistant not set up");
        String body = boxKey == null ? Json.write(args("type", type, "data", data))
            : Json.write(args("type", type, "encrypted", Boolean.TRUE, "encrypted_data",
                android.util.Base64.encodeToString(SecretBox.seal(boxKey, Json.write(data).getBytes("UTF-8")), android.util.Base64.NO_WRAP)));
        IOException last = null;
        for (int k = 0; k < hooks.size(); k++) {
            int i = (good + k) % hooks.size();
            try {
                String r = decrypt(post(hooks.get(i), body));
                good = i;
                lastError = "";
                return r;
            } catch (NotSent e) {
                last = e;                       // never reached this URL: try the next
            } catch (IOException e) {
                last = e;
                if (removed || type.equals("fire_event")) break;   // an event may have been counted: never replay it elsewhere
            }
        }
        lastError = last.getMessage();
        throw last;
    }

    /** An encrypted reply ({"encrypted": true, "encrypted_data": ...}) as its plain JSON; anything else as it is. */
    @SuppressWarnings("unchecked")
    private String decrypt(String reply) throws IOException {
        if (boxKey == null || !reply.contains("encrypted_data")) return reply;
        Object o = Json.parse(reply);
        if (!(o instanceof Map) || !Boolean.TRUE.equals(((Map<String, Object>) o).get("encrypted"))) return reply;
        byte[] box = android.util.Base64.decode(String.valueOf(((Map<String, Object>) o).get("encrypted_data")), android.util.Base64.DEFAULT);
        byte[] plain = SecretBox.open(boxKey, box);
        if (plain == null) plain = SecretBox.open(legacyKey, box);
        if (plain == null) throw new IOException("Home Assistant reply could not be decrypted");
        return new String(plain, "UTF-8");
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
        String host = new URL(url).getHost();
        if (code == 410) { removed = true; throw new IOException("removed from Home Assistant: register again"); }
        if (code / 100 != 2) throw new IOException("HTTP " + code + " from " + host);
        InputStream in = c.getInputStream();
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            String r = new String(b.toByteArray(), "UTF-8").trim();
            return r.length() == 0 ? "{}" : r;
        } finally { in.close(); }
    }
}
