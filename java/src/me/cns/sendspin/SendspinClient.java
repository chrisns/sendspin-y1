package me.cns.sendspin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sendspin player@v1 client (client-initiated connection). Java 6/7 syntax, Android 4.2 APIs only.
 * Mirrors sendspin_min.py, which is the executable specification this was checked against.
 */
public final class SendspinClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final byte[] SENTINEL_PSK = Crypto.sha256("sendspin-sentinel-psk-v1".getBytes(UTF8));

    public interface AudioSink {
        void format(int sampleRate, int channels, int bitDepth);
        /** serverTimestampUs is in the server clock; offsetUs = server - local (monotonic microseconds). */
        void write(long serverTimestampUs, byte[] pcm, int off, int len);
        void end();
    }

    /**
     * Optional extras a sink may implement. clear() drops buffered audio but keeps the stream
     * (stream/clear, sent on a seek); setVolume() applies server/command volume and mute.
     * Volume and mute are only advertised to the server when the sink implements this.
     */
    public interface SinkControl {
        void clear();
        /** volume 0-100 as Music Assistant shows it; muted overrides it. */
        void setVolume(int volume, boolean muted);
    }

    public interface Log { void log(String line); }

    private final String host, name;
    private final int port;
    private final byte[] priv;
    private final String clientId;
    private final AudioSink sink;
    private final Log log;
    private final long t0;   // local clock origin (System.nanoTime); shared with the audio sink
    private WebSocket ws;
    private Noise.Transport tr;
    private long offsetUs = Long.MIN_VALUE, bestRttUs = Long.MAX_VALUE;
    private boolean stateSent;
    private int volume = 100;
    private boolean muted;
    public long chunks, pcmBytes;
    public volatile boolean stop;

    public SendspinClient(String host, int port, String name, File keyFile, AudioSink sink, Log log) throws IOException {
        this(host, port, name, keyFile, sink, log, System.nanoTime());
    }

    public SendspinClient(String host, int port, String name, File keyFile, AudioSink sink, Log log, long t0Nanos)
            throws IOException {
        this.t0 = t0Nanos;
        this.host = host; this.port = port; this.name = name; this.sink = sink; this.log = log;
        this.priv = loadOrCreateKey(keyFile);
        this.clientId = B64.encode(Crypto.publicKey(priv), true, false);
    }

    /** A stable identity matters: the server remembers approval and group membership per client_id. */
    static byte[] loadOrCreateKey(File f) throws IOException {
        if (f != null && f.length() >= 32) {
            byte[] k = new byte[32];
            FileInputStream in = new FileInputStream(f);
            try {
                int o = 0;
                while (o < 32) { int n = in.read(k, o, 32 - o); if (n < 0) break; o += n; }
                if (o == 32) return k;
            } finally { in.close(); }
        }
        // missing, or cut short by a power loss mid-write: a new identity beats a speaker that never starts
        byte[] k = Crypto.newPrivateKey();
        if (f != null) {
            File tmp = new File(f.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(k);
                out.getFD().sync();
            } finally { out.close(); }
            if (!tmp.renameTo(f)) {
                f.delete();
                if (!tmp.renameTo(f)) throw new IOException("cannot rename " + tmp + " to " + f);
            }
        }
        return k;
    }

    public String clientId() { return clientId; }

    private long nowUs() { return (System.nanoTime() - t0) / 1000L; }

    private void sendJson(Map<String, Object> m) throws IOException {
        byte[] j = Json.write(m).getBytes(UTF8);
        byte[] pt = new byte[j.length + 1];
        System.arraycopy(j, 0, pt, 1, j.length);
        ws.send(tr.encrypt(pt), true);
    }

    private static Map<String, Object> msg(String type, Map<String, Object> payload) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("payload", payload);
        return m;
    }

    private static Map<String, Object> map() { return new LinkedHashMap<String, Object>(); }

    private void connect() throws IOException {
        ws = new WebSocket(host, port, "/sendspin", 5000);
        Map<String, Object> ip = map();
        ip.put("client_id", clientId);
        ip.put("version", Long.valueOf(1));
        ip.put("suite", "25519_ChaChaPoly_SHA256");
        String init = Json.write(msg("client/init", ip));
        ws.send(init.getBytes(UTF8), false);
        String serverInit = new String(ws.receive().data, UTF8);
        Map<String, Object> si = Json.obj(serverInit);
        if (!"server/init".equals(si.get("type"))) throw new IOException("expected server/init: " + serverInit);
        byte[] serverPub = B64.decode((String) Json.map(si.get("payload")).get("server_id"));
        String hs = new String(ws.receive().data, UTF8);
        byte[] msg1 = B64.decode((String) Json.map(Json.obj(hs).get("payload")).get("data"));
        if (msg1.length < 48) throw new IOException("noise msg1 too short: " + msg1.length);  // e + tag
        byte[] prologue = Noise.cat(init.getBytes(UTF8), serverInit.getBytes(UTF8));
        Noise.Result r = Noise.respond(prologue, priv, serverPub, msg1, new Noise.PskChooser() {
            public byte[] choose(String payloadJson) {
                log.log("noise msg1 payload " + payloadJson);
                return SENTINEL_PSK; // no pairing records yet: lookup miss falls back to the Sentinel PSK
            }
        });
        tr = r.transport;
        Map<String, Object> hp = map();
        hp.put("data", B64.encode(r.msg2, true, false));
        ws.send(Json.write(msg("noise/handshake", hp)).getBytes(UTF8), false);
        log.log("handshake complete, server " + B64.encode(serverPub, true, false));
    }

    private Object[] recv() throws IOException {
        byte[] pt = tr.decrypt(ws.receive().data);
        if (pt.length < 1) throw new IOException("empty message");
        byte[] body = new byte[pt.length - 1];
        System.arraycopy(pt, 1, body, 0, body.length);
        return new Object[] { Integer.valueOf(pt[0] & 0xff), body };
    }

    private void sendTime() throws IOException {
        Map<String, Object> p = map();
        p.put("client_transmitted", Long.valueOf(nowUs()));
        sendJson(msg("client/time", p));
    }

    private void onServerTime(Map<String, Object> p) {
        long t4 = nowUs();
        long t1 = ((Number) p.get("client_transmitted")).longValue();
        long t2 = ((Number) p.get("server_received")).longValue();
        long t3 = ((Number) p.get("server_transmitted")).longValue();
        long rtt = (t4 - t1) - (t3 - t2);
        if (rtt <= bestRttUs) { bestRttUs = rtt; offsetUs = ((t2 - t1) + (t3 - t4)) / 2; }
    }

    public long offsetUs() { return offsetUs; }

    private void sendHello() throws IOException {
        Map<String, Object> fmt = map();
        fmt.put("codec", "pcm"); fmt.put("channels", Long.valueOf(2));
        fmt.put("sample_rate", Long.valueOf(44100)); fmt.put("bit_depth", Long.valueOf(16));
        List<Object> fmts = new ArrayList<Object>();
        fmts.add(fmt);
        Map<String, Object> support = map();
        support.put("supported_formats", fmts);
        support.put("buffer_capacity", Long.valueOf(1048576));
        List<Object> roles = new ArrayList<Object>();
        roles.add("player@v1");
        Map<String, Object> pair = map();
        pair.put("pairing_psk", map());
        Map<String, Object> unpaired = map();
        unpaired.put("enabled", Boolean.TRUE);
        Map<String, Object> dev = map();
        dev.put("product_name", "Innioasis Y1");
        dev.put("software_version", "0.1");
        Map<String, Object> p = map();
        p.put("client_id", clientId);
        p.put("name", name);
        p.put("device_info", dev);
        p.put("supported_roles", roles);
        p.put("player@v1_support", support);
        p.put("supported_pair_methods", pair);
        p.put("unpaired_access", unpaired);
        sendJson(msg("client/hello", p));
    }

    private void sendState() throws IOException {
        List<Object> cmds = new ArrayList<Object>();
        if (sink instanceof SinkControl) { cmds.add("volume"); cmds.add("mute"); }
        Map<String, Object> player = map();
        player.put("volume", Long.valueOf(volume));
        player.put("muted", Boolean.valueOf(muted));
        player.put("output_delay_ms", Long.valueOf(0));
        player.put("required_lead_time_ms", Long.valueOf(200));
        player.put("min_buffer_ms", Long.valueOf(300));
        player.put("supported_commands", cmds);
        Map<String, Object> p = map();
        p.put("available", Boolean.TRUE);
        p.put("player", player);
        sendJson(msg("client/state", p));
        stateSent = true;
    }

    /** Runs until stop is set, the connection drops, or maxSeconds elapses (0 = no limit). */
    public void run(int maxSeconds) throws IOException {
        connect();
        sendHello();
        long deadline = maxSeconds > 0 ? System.currentTimeMillis() + maxSeconds * 1000L : Long.MAX_VALUE;
        try {
            while (!stop && System.currentTimeMillis() < deadline) {
                Object[] m;
                try {
                    m = recv();
                } catch (SocketTimeoutException e) {
                    if (stateSent) sendTime();
                    continue;
                }
                int id = ((Integer) m[0]).intValue();
                byte[] body = (byte[]) m[1];
                if (id == 0) handleJson(Json.obj(new String(body, UTF8)));
                else if (id == 4) handleAudio(body);
                else log.log("ignored binary message id " + id);
            }
        } finally {
            sink.end();
            ws.close();
        }
        log.log("done: " + chunks + " audio chunks, " + pcmBytes + " PCM bytes");
    }

    private void handleJson(Map<String, Object> m) throws IOException {
        String t = (String) m.get("type");
        Map<String, Object> p = m.get("payload") instanceof Map ? Json.map(m.get("payload")) : map();
        String s = Json.write(p);
        log.log("<- " + t + " " + (s.length() > 200 ? s.substring(0, 200) : s));
        if ("server/activate".equals(t)) {
            if (offsetUs == Long.MIN_VALUE) {
                for (int i = 0; i < 8; i++) {   // burst of time exchanges; must converge before available:true
                    sendTime();
                    Object[] r;
                    do { r = recv(); } while (((Integer) r[0]).intValue() != 0);
                    Map<String, Object> tm = Json.obj(new String((byte[]) r[1], UTF8));
                    if ("server/time".equals(tm.get("type"))) onServerTime(Json.map(tm.get("payload")));
                }
                log.log("clock offset " + offsetUs + " us, best rtt " + bestRttUs + " us");
            }
            sendState();
        } else if ("server/time".equals(t)) {
            onServerTime(p);
        } else if ("stream/start".equals(t) && p.get("player") != null) {
            Map<String, Object> pl = Json.map(p.get("player"));
            if (!"pcm".equals(pl.get("codec"))) throw new IOException("unsupported codec " + pl.get("codec"));
            sink.format(((Number) pl.get("sample_rate")).intValue(), ((Number) pl.get("channels")).intValue(),
                ((Number) pl.get("bit_depth")).intValue());
        } else if ("stream/clear".equals(t)) {
            // drop what is buffered (a seek); the stream goes on and new chunks follow
            if (sink instanceof SinkControl) ((SinkControl) sink).clear();
        } else if ("stream/end".equals(t)) {
            sink.end();
        } else if ("server/command".equals(t) && p.get("player") instanceof Map) {
            onPlayerCommand(Json.map(p.get("player")));
        }
    }

    private void onPlayerCommand(Map<String, Object> c) throws IOException {
        Object cmd = c.get("command");
        if ("volume".equals(cmd) && c.get("volume") instanceof Number) {
            volume = Math.max(0, Math.min(100, ((Number) c.get("volume")).intValue()));
        } else if ("mute".equals(cmd) && c.get("mute") instanceof Boolean) {
            muted = ((Boolean) c.get("mute")).booleanValue();
        } else {
            log.log("ignored player command " + cmd);
            return;
        }
        if (!(sink instanceof SinkControl)) return;
        ((SinkControl) sink).setVolume(volume, muted);
        if (stateSent) sendState();   // report what is now in force, as the spec asks
    }

    private void handleAudio(byte[] body) {
        long ts = 0;
        for (int i = 0; i < 8; i++) ts = (ts << 8) | (body[i] & 0xff);
        // body[8..11] is send_ahead (uint32), used later to size min_buffer_ms
        chunks++;
        pcmBytes += body.length - 12;
        sink.write(ts, body, 12, body.length - 12);
    }
}
