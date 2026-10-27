package me.cns.sendspin.android;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import me.cns.sendspin.Json;
import me.cns.sendspin.WebSocket;

/** Music Assistant websocket API: authenticated request/response over one socket, reconnecting on demand. */
public final class MaClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int HANDSHAKE_MS = 15000;

    private static final class Slot {
        Object result; String error; boolean done;
    }

    private final String host, token;
    private final int port;
    private WebSocket ws;
    private int seq;
    private final Map<String, Slot> waiting = new HashMap<String, Slot>();

    public MaClient(String host, int port, String token) { this.host = host; this.port = port; this.token = token; }

    private synchronized void ensure() throws IOException {
        if (ws != null) return;
        // bounded handshake: a link that dies between connect and auth must not hold this monitor forever
        final WebSocket w = new WebSocket(host, port, "/ws", HANDSHAKE_MS);
        long deadline = System.currentTimeMillis() + HANDSHAKE_MS;
        try {
            w.receive(); // server info
            Map<String, Object> a = new LinkedHashMap<String, Object>();
            a.put("token", token);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("message_id", "auth");
            m.put("command", "auth");
            m.put("args", a);
            w.send(Json.write(m).getBytes(UTF8), false);
            while (true) {
                Map<String, Object> r = Json.obj(new String(w.receive().data, UTF8));
                if ("auth".equals(r.get("message_id"))) {
                    if (r.containsKey("error_code")) throw new IOException("MA auth failed: " + r.get("details"));
                    break;
                }
                if (System.currentTimeMillis() > deadline) throw new IOException("MA auth timed out");
            }
            w.setTimeout(0); // from here readLoop owns the socket; idle is normal and call() has its own timeout
        } catch (IOException e) {
            w.close();
            throw e;
        }
        ws = w;
        Thread t = new Thread(new Runnable() { public void run() { readLoop(w); } }, "ma-reader");
        t.setDaemon(true);
        t.start();
    }

    private void readLoop(WebSocket w) {
        try {
            while (true) {
                Map<String, Object> r = Json.obj(new String(w.receive().data, UTF8));
                Object id = r.get("message_id");
                if (id == null) continue; // server event
                synchronized (this) {
                    Slot s = waiting.get(String.valueOf(id));
                    if (s == null) continue;
                    if (r.containsKey("error_code")) s.error = String.valueOf(r.get("details"));
                    else s.result = r.get("result");
                    s.done = true;
                    notifyAll();
                }
            }
        } catch (Exception e) {
            // dropped: fail every waiter, next call reconnects
        }
        synchronized (this) {
            if (ws == w) ws = null;
            for (Slot s : waiting.values()) { if (!s.done) { s.error = "connection lost"; s.done = true; } }
            notifyAll();
        }
        w.close();
    }

    public Object call(String command, Map<String, Object> args, int timeoutMs) throws IOException {
        String id;
        Slot slot = new Slot();
        synchronized (this) {
            ensure();
            id = String.valueOf(++seq);
            waiting.put(id, slot);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("message_id", id);
            m.put("command", command);
            m.put("args", args == null ? new LinkedHashMap<String, Object>() : args);
            try {
                ws.send(Json.write(m).getBytes(UTF8), false);
            } catch (IOException e) {
                waiting.remove(id);
                ws.close();
                ws = null;
                throw e;
            }
        }
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (this) {
            try {
                while (!slot.done) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) {
                        waiting.remove(id);
                        if (ws != null) ws.close(); // a silent drop would leave the reader blocked; force a reconnect
                        throw new IOException("timeout: " + command);
                    }
                    wait(left);
                }
            } catch (InterruptedException e) {
                waiting.remove(id);
                throw new IOException("interrupted");
            }
            waiting.remove(id);
        }
        if (slot.error != null) throw new IOException(command + ": " + slot.error);
        return slot.result;
    }

    public synchronized void close() {
        if (ws != null) { ws.close(); ws = null; }
    }

    /** Convenience: build an args map from alternating key, value pairs. */
    public static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
