package me.cns.sendspin.android;

import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 one-time codes (HMAC-SHA1, 30 s steps) with 5 digits, for the grown-up unlock code. 1Password shows the
 * same codes from the shared item. Each step is accepted once: the caller keeps the last accepted step.
 */
public final class Totp {
    public static final int DIGITS = 5, PERIOD_S = 30;
    /** Typing on the wheel takes a while: accept codes up to 90 s old and one step ahead (a fast clock). */
    static final int BEHIND = 3, AHEAD = 1;

    private Totp() { }

    private static Mac mac(byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String code(byte[] key, long step) { return code(mac(key), step); }

    private static String code(Mac mac, long step) {
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) { msg[i] = (byte) step; step >>>= 8; }
        byte[] h = mac.doFinal(msg);
        int o = h[h.length - 1] & 0x0f;
        int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
        String s = Integer.toString(bin % 100000);
        while (s.length() < DIGITS) s = "0" + s;
        return s;
    }

    /** The step the code belongs to, or -1. Only steps after lastUsed count, so a code works once. */
    public static long verify(byte[] key, String code, long nowMs, long lastUsed) {
        return verify(key, code, nowMs, lastUsed, 0);
    }

    /** As above, with slack extra steps either side for a clock that has gone days without being set. */
    public static long verify(byte[] key, String code, long nowMs, long lastUsed, int slack) {
        if (code == null || code.length() != DIGITS) return -1;
        Mac mac = mac(key);
        long cur = nowMs / 1000 / PERIOD_S;
        for (long s = cur - BEHIND - slack; s <= cur + AHEAD + slack; s++) {
            if (s > lastUsed && code(mac, s).equals(code)) return s;
        }
        return -1;
    }

    /**
     * For a clock that is badly wrong (a flat battery reset it, then no Wi-Fi or GPS): two codes typed apart find
     * the real time. first was typed gapMs before second; both are searched within rangeSteps of nowMs. A random
     * pair matches with odds of about 1 in 10,000 per try. The step of second, or -1.
     */
    public static long findPair(byte[] key, String first, String second, long gapMs, long nowMs, long lastUsed, long rangeSteps) {
        if (first == null || second == null || first.length() != DIGITS || second.length() != DIGITS) return -1;
        Mac mac = mac(key);
        long cur = nowMs / 1000 / PERIOD_S, gap = gapMs / 1000 / PERIOD_S;
        for (long s = cur - rangeSteps; s <= cur + rangeSteps; s++) {
            if (!code(mac, s).equals(first)) continue;
            for (long t = s + Math.max(1, gap - 1); t <= s + gap + 4; t++) {   // typing time either side of the gap
                if (t > lastUsed && code(mac, t).equals(second)) return t;
            }
        }
        return -1;
    }

    public static byte[] base32(String s) {
        s = s.replace(" ", "").replace("=", "").toUpperCase();
        byte[] out = new byte[s.length() * 5 / 8];
        int buf = 0, bits = 0, n = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(s.charAt(i));
            if (v < 0) throw new IllegalArgumentException("not base32");
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) { out[n++] = (byte) (buf >> (bits - 8)); bits -= 8; }
        }
        return out;
    }
}
