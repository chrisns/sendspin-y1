package me.cns.sendspin;

import java.io.ByteArrayOutputStream;

/** Base64 without java.util.Base64 (API 26) so it runs on Android 4.2. */
public final class B64 {
    private B64() {}

    private static final String STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    public static String encode(byte[] d, boolean url, boolean pad) {
        String a = url ? URL : STD;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 0xff, b1 = i + 1 < d.length ? d[i + 1] & 0xff : 0, b2 = i + 2 < d.length ? d[i + 2] & 0xff : 0;
            sb.append(a.charAt(b0 >> 2)).append(a.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            if (i + 1 < d.length) sb.append(a.charAt(((b1 & 15) << 2) | (b2 >> 6))); else if (pad) sb.append('=');
            if (i + 2 < d.length) sb.append(a.charAt(b2 & 63)); else if (pad) sb.append('=');
        }
        return sb.toString();
    }

    public static byte[] decode(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v = STD.indexOf(c);
            if (v < 0) v = URL.indexOf(c);
            if (v < 0) continue; // '=' padding or whitespace
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) { bits -= 8; o.write((acc >> bits) & 0xff); }
        }
        return o.toByteArray();
    }
}
