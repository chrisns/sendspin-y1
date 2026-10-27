package me.cns.sendspin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.security.SecureRandom;

/** Minimal RFC 6455 client over a plain socket: no extensions, no TLS (LAN only). */
public final class WebSocket {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_MESSAGE = 16 << 20;  // MA library pages run to a few MB; beyond this the length is garbage
    private static final int MAX_STALLS = 4;          // timeouts in a row inside a frame before giving up
    private final Socket sock;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom rng = new SecureRandom();

    public static final class Message {
        public final boolean binary;
        public final byte[] data;

        Message(boolean b, byte[] d) { binary = b; data = d; }
    }

    public WebSocket(String host, int port, String path, int timeoutMs) throws IOException {
        sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(host, port), 10000);
            sock.setSoTimeout(timeoutMs);
            sock.setTcpNoDelay(true);
            in = sock.getInputStream();
            out = sock.getOutputStream();
            byte[] k = new byte[16];
            rng.nextBytes(k);
            String key = B64.encode(k, false, true);
            String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(UTF8));
            out.flush();
            // read header up to blank line, byte by byte so no payload is consumed
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4) {
                int b = in.read();
                if (b < 0) throw new IOException("closed during upgrade");
                if (head.size() > 16384) throw new IOException("upgrade header too long");
                head.write(b);
                if ((b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3))) state++;
                else state = (b == '\r') ? 1 : 0;
            }
            String h = new String(head.toByteArray(), UTF8);
            if (!h.startsWith("HTTP/1.1 101")) throw new IOException("upgrade failed: " + h.split("\r\n")[0]);
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /** Read timeout for later receive() calls (0 = block forever). */
    public void setTimeout(int timeoutMs) throws IOException { sock.setSoTimeout(timeoutMs); }

    /**
     * A timeout counts as idle only when idleOk and nothing has been read yet. Once a frame has
     * started, the bytes already read cannot be pushed back, so keep reading through short stalls
     * and fail the connection on a long one rather than resync from the middle of a payload.
     */
    private void readFully(byte[] b, int len, boolean idleOk) throws IOException {
        int o = 0, stalls = 0;
        while (o < len) {
            int n;
            try {
                n = in.read(b, o, len - o);
            } catch (SocketTimeoutException e) {
                if (idleOk && o == 0) throw e;
                if (++stalls >= MAX_STALLS) throw new IOException("stalled mid-frame");
                continue;
            }
            if (n < 0) throw new IOException("closed");
            o += n;
            stalls = 0;
        }
    }

    public synchronized void send(byte[] data, boolean binary) throws IOException {
        int n = data.length;
        ByteArrayOutputStream f = new ByteArrayOutputStream(n + 14);
        f.write(0x80 | (binary ? 2 : 1));
        if (n < 126) f.write(0x80 | n);
        else if (n < 65536) { f.write(0x80 | 126); f.write(n >> 8); f.write(n); }
        else {
            f.write(0x80 | 127);
            for (int s = 56; s >= 0; s -= 8) f.write((int) (((long) n) >>> s));
        }
        byte[] mask = new byte[4];
        rng.nextBytes(mask);
        f.write(mask, 0, 4);
        byte[] m = new byte[n];
        for (int i = 0; i < n; i++) m[i] = (byte) (data[i] ^ mask[i & 3]);
        f.write(m, 0, n);
        out.write(f.toByteArray());
        out.flush();
    }

    private synchronized void control(int op, byte[] d) throws IOException {
        byte[] mask = new byte[4];
        rng.nextBytes(mask);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x80 | op);
        f.write(0x80 | d.length);
        f.write(mask, 0, 4);
        for (int i = 0; i < d.length; i++) f.write(d[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    /** Blocks until one complete message arrives; SocketTimeoutException only if idle at a message boundary. */
    public Message receive() throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        boolean binary = false, midMessage = false;
        byte[] hdr = new byte[2];
        while (true) {
            readFully(hdr, 2, !midMessage);
            boolean fin = (hdr[0] & 0x80) != 0;
            int op = hdr[0] & 0x0f;
            long n = hdr[1] & 0x7f;
            if (n == 126) { byte[] e = new byte[2]; readFully(e, 2, false); n = ((e[0] & 0xff) << 8) | (e[1] & 0xff); }
            else if (n == 127) {
                byte[] e = new byte[8]; readFully(e, 8, false);
                n = 0;
                for (int i = 0; i < 8; i++) n = (n << 8) | (e[i] & 0xff);
            }
            if (n < 0 || n > MAX_MESSAGE - msg.size()) throw new IOException("frame too large: " + n);
            byte[] d = new byte[(int) n];
            readFully(d, (int) n, false);
            if (op == 8) throw new IOException("server closed");
            if (op == 9) { control(10, d); continue; }
            if (op == 10) continue;
            if (op == 1 || op == 2) binary = op == 2;
            msg.write(d, 0, d.length);
            if (fin) return new Message(binary, msg.toByteArray());
            midMessage = true;
        }
    }

    public void close() {
        try { sock.close(); } catch (IOException ignored) { }
    }
}
