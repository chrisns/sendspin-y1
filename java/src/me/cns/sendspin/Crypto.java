package me.cns.sendspin;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure-Java crypto for Noise_KKpsk2_25519_ChaChaPoly_SHA256.
 * Only APIs present on Android 4.2 (API 17): MessageDigest, Mac, SecureRandom, BigInteger.
 * X25519 is a BigInteger Montgomery ladder (RFC 7748): not constant time, fine for a
 * once-per-connection handshake on a private LAN.
 */
public final class Crypto {
    private Crypto() {}

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final SecureRandom RNG = new SecureRandom();

    // ------------------------------------------------------------ X25519

    private static BigInteger decodeLE(byte[] b, boolean maskTop) {
        byte[] r = new byte[32];
        for (int i = 0; i < 32; i++) r[31 - i] = b[i];
        if (maskTop) r[0] &= 0x7f;
        return new BigInteger(1, r);
    }

    private static byte[] encodeLE(BigInteger v) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < be.length && i < 33; i++) {
            int src = be.length - 1 - i;
            if (i < 32) out[i] = be[src];
        }
        return out;
    }

    public static byte[] x25519(byte[] scalar, byte[] u) {
        byte[] k = scalar.clone();
        k[0] &= 248;
        k[31] &= 127;
        k[31] |= 64;
        BigInteger x1 = decodeLE(u, true).mod(P);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int kt = (k[t >> 3] >> (t & 7)) & 1;
            swap ^= kt;
            if (swap == 1) {
                BigInteger tx = x2; x2 = x3; x3 = tx;
                BigInteger tz = z2; z2 = z3; z3 = tz;
            }
            swap = kt;
            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);
            BigInteger s = da.add(cb).mod(P);
            BigInteger df = da.subtract(cb).mod(P);
            x3 = s.multiply(s).mod(P);
            z3 = x1.multiply(df.multiply(df).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P);
        }
        if (swap == 1) {
            BigInteger tx = x2; x2 = x3; x3 = tx;
            BigInteger tz = z2; z2 = z3; z3 = tz;
        }
        return encodeLE(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P));
    }

    public static byte[] newPrivateKey() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        return k;
    }

    public static byte[] publicKey(byte[] priv) {
        byte[] base = new byte[32];
        base[0] = 9;
        return x25519(priv, base);
    }

    // ------------------------------------------------- SHA-256 / HMAC / HKDF

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Noise HKDF; returns n (2 or 3) 32-byte outputs. */
    public static byte[][] hkdf(byte[] ck, byte[] ikm, int n) {
        byte[] tmp = hmac(ck, ikm);
        byte[][] out = new byte[n][];
        byte[] prev = new byte[0];
        for (int i = 0; i < n; i++) {
            byte[] in = new byte[prev.length + 1];
            System.arraycopy(prev, 0, in, 0, prev.length);
            in[prev.length] = (byte) (i + 1);
            out[i] = hmac(tmp, in);
            prev = out[i];
        }
        return out;
    }

    // ------------------------------------------------ ChaCha20-Poly1305 (RFC 8439)

    private static int rotl(int v, int c) { return (v << c) | (v >>> (32 - c)); }

    private static void qr(int[] s, int a, int b, int c, int d) {
        s[a] += s[b]; s[d] = rotl(s[d] ^ s[a], 16);
        s[c] += s[d]; s[b] = rotl(s[b] ^ s[c], 12);
        s[a] += s[b]; s[d] = rotl(s[d] ^ s[a], 8);
        s[c] += s[d]; s[b] = rotl(s[b] ^ s[c], 7);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static byte[] chachaBlock(byte[] key, int counter, byte[] nonce) {
        int[] init = new int[16];
        init[0] = 0x61707865; init[1] = 0x3320646e; init[2] = 0x79622d32; init[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) init[4 + i] = le32(key, i * 4);
        init[12] = counter;
        for (int i = 0; i < 3; i++) init[13 + i] = le32(nonce, i * 4);
        int[] s = init.clone();
        for (int i = 0; i < 10; i++) {
            qr(s, 0, 4, 8, 12); qr(s, 1, 5, 9, 13); qr(s, 2, 6, 10, 14); qr(s, 3, 7, 11, 15);
            qr(s, 0, 5, 10, 15); qr(s, 1, 6, 11, 12); qr(s, 2, 7, 8, 13); qr(s, 3, 4, 9, 14);
        }
        byte[] out = new byte[64];
        for (int i = 0; i < 16; i++) {
            int v = s[i] + init[i];
            out[i * 4] = (byte) v; out[i * 4 + 1] = (byte) (v >>> 8);
            out[i * 4 + 2] = (byte) (v >>> 16); out[i * 4 + 3] = (byte) (v >>> 24);
        }
        return out;
    }

    /** ChaCha20 keystream XOR from block counter 1, with one state array for the whole message (no per-block garbage). */
    private static byte[] chachaXor(byte[] key, byte[] nonce, byte[] in, int off, int len) {
        byte[] out = new byte[len];
        int[] init = new int[16], s = new int[16];
        init[0] = 0x61707865; init[1] = 0x3320646e; init[2] = 0x79622d32; init[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) init[4 + i] = le32(key, i * 4);
        for (int i = 0; i < 3; i++) init[13 + i] = le32(nonce, i * 4);
        int counter = 1;
        for (int pos = 0; pos < len; pos += 64) {
            init[12] = counter++;
            System.arraycopy(init, 0, s, 0, 16);
            for (int i = 0; i < 10; i++) {
                qr(s, 0, 4, 8, 12); qr(s, 1, 5, 9, 13); qr(s, 2, 6, 10, 14); qr(s, 3, 7, 11, 15);
                qr(s, 0, 5, 10, 15); qr(s, 1, 6, 11, 12); qr(s, 2, 7, 8, 13); qr(s, 3, 4, 9, 14);
            }
            int n = Math.min(64, len - pos);
            for (int i = 0; i < n; i++) {
                int v = s[i >> 2] + init[i >> 2];
                out[pos + i] = (byte) (in[off + pos + i] ^ (v >>> ((i & 3) << 3)));
            }
        }
        return out;
    }

    /**
     * Poly1305 in 26-bit limbs (poly1305-donna-32): plain long arithmetic. The BigInteger version it replaces cost a
     * multiply and a modulo per 16 bytes, which on the Y1 could not keep up with a PCM stream (half real time).
     */
    private static final class Poly1305 {
        private final long r0, r1, r2, r3, r4, s1, s2, s3, s4;
        private long h0, h1, h2, h3, h4;
        private final int pad0, pad1, pad2, pad3;

        Poly1305(byte[] k) {
            r0 = le32(k, 0) & 0x3ffffff;
            r1 = (le32(k, 3) >>> 2) & 0x3ffff03;
            r2 = (le32(k, 6) >>> 4) & 0x3ffc0ff;
            r3 = (le32(k, 9) >>> 6) & 0x3f03fff;
            r4 = (le32(k, 12) >>> 8) & 0x00fffff;
            s1 = r1 * 5; s2 = r2 * 5; s3 = r3 * 5; s4 = r4 * 5;
            pad0 = le32(k, 16); pad1 = le32(k, 20); pad2 = le32(k, 24); pad3 = le32(k, 28);
        }

        /** One 16-byte block (b holds 16 bytes at o; hibit 1 for a full block, 0 for the padded last one). */
        private void block(byte[] b, int o, long hibit) {
            long t0 = le32(b, o) & 0xffffffffL, t1 = le32(b, o + 4) & 0xffffffffL;
            long t2 = le32(b, o + 8) & 0xffffffffL, t3 = le32(b, o + 12) & 0xffffffffL;
            h0 += t0 & 0x3ffffff;
            h1 += ((t0 >>> 26) | (t1 << 6)) & 0x3ffffff;
            h2 += ((t1 >>> 20) | (t2 << 12)) & 0x3ffffff;
            h3 += ((t2 >>> 14) | (t3 << 18)) & 0x3ffffff;
            h4 += (t3 >>> 8) | (hibit << 24);
            long d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1;
            long d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2;
            long d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3;
            long d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4;
            long d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;
            long c = d0 >>> 26; h0 = d0 & 0x3ffffff;
            d1 += c; c = d1 >>> 26; h1 = d1 & 0x3ffffff;
            d2 += c; c = d2 >>> 26; h2 = d2 & 0x3ffffff;
            d3 += c; c = d3 >>> 26; h3 = d3 & 0x3ffffff;
            d4 += c; c = d4 >>> 26; h4 = d4 & 0x3ffffff;
            h0 += c * 5; c = h0 >>> 26; h0 &= 0x3ffffff;
            h1 += c;
        }

        /** Data padded with zeros to a multiple of 16, as the AEAD construction does. */
        void updatePadded(byte[] m, int off, int len) {
            int i = 0;
            for (; i + 16 <= len; i += 16) block(m, off + i, 1);
            if (i < len) {
                byte[] last = new byte[16];
                System.arraycopy(m, off + i, last, 0, len - i);
                block(last, 0, 1);    // AEAD pads with zeros but the block still counts as full (hibit 1)
            }
        }

        void update16(byte[] b) { block(b, 0, 1); }

        byte[] tag() {
            long c = h1 >>> 26; h1 &= 0x3ffffff;
            h2 += c; c = h2 >>> 26; h2 &= 0x3ffffff;
            h3 += c; c = h3 >>> 26; h3 &= 0x3ffffff;
            h4 += c; c = h4 >>> 26; h4 &= 0x3ffffff;
            h0 += c * 5; c = h0 >>> 26; h0 &= 0x3ffffff;
            h1 += c;
            long g0 = h0 + 5; c = g0 >>> 26; g0 &= 0x3ffffff;
            long g1 = h1 + c; c = g1 >>> 26; g1 &= 0x3ffffff;
            long g2 = h2 + c; c = g2 >>> 26; g2 &= 0x3ffffff;
            long g3 = h3 + c; c = g3 >>> 26; g3 &= 0x3ffffff;
            long g4 = h4 + c - (1L << 26);
            if ((g4 >>> 63) == 0) { h0 = g0; h1 = g1; h2 = g2; h3 = g3; h4 = g4; }   // h >= p: use h - p
            long f0 = ((h0) | (h1 << 26)) & 0xffffffffL;
            long f1 = ((h1 >>> 6) | (h2 << 20)) & 0xffffffffL;
            long f2 = ((h2 >>> 12) | (h3 << 14)) & 0xffffffffL;
            long f3 = ((h3 >>> 18) | (h4 << 8)) & 0xffffffffL;
            f0 += pad0 & 0xffffffffL; f1 += (pad1 & 0xffffffffL) + (f0 >>> 32);
            f2 += (pad2 & 0xffffffffL) + (f1 >>> 32); f3 += (pad3 & 0xffffffffL) + (f2 >>> 32);
            byte[] out = new byte[16];
            long[] f = { f0, f1, f2, f3 };
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) out[i * 4 + j] = (byte) (f[i] >>> (8 * j));
            return out;
        }
    }

    /** The AEAD tag: Poly1305 over aad || pad || ct || pad || lengths, straight from the buffers (no copy of ct). */
    private static byte[] aeadTag(byte[] otk, byte[] aad, byte[] ct, int ctOff, int ctLen) {
        Poly1305 p = new Poly1305(otk);
        p.updatePadded(aad, 0, aad.length);
        p.updatePadded(ct, ctOff, ctLen);
        byte[] lens = new byte[16];
        long al = aad.length, cl = ctLen;
        for (int i = 0; i < 8; i++) { lens[i] = (byte) (al >>> (8 * i)); lens[8 + i] = (byte) (cl >>> (8 * i)); }
        p.update16(lens);
        return p.tag();
    }

    /** Returns ciphertext || 16-byte tag. */
    public static byte[] aeadEncrypt(byte[] key, byte[] nonce, byte[] aad, byte[] pt) {
        byte[] otk = new byte[32];
        System.arraycopy(chachaBlock(key, 0, nonce), 0, otk, 0, 32);
        byte[] ct = chachaXor(key, nonce, pt, 0, pt.length);
        byte[] tag = aeadTag(otk, aad, ct, 0, ct.length);
        byte[] out = new byte[ct.length + 16];
        System.arraycopy(ct, 0, out, 0, ct.length);
        System.arraycopy(tag, 0, out, ct.length, 16);
        return out;
    }

    /** Input is ciphertext || tag. Throws on authentication failure. */
    public static byte[] aeadDecrypt(byte[] key, byte[] nonce, byte[] aad, byte[] in) {
        if (in.length < 16) throw new RuntimeException("short ciphertext");
        int ctLen = in.length - 16;
        byte[] otk = new byte[32];
        System.arraycopy(chachaBlock(key, 0, nonce), 0, otk, 0, 32);
        byte[] tag = aeadTag(otk, aad, in, 0, ctLen);
        int diff = 0;
        for (int i = 0; i < 16; i++) diff |= tag[i] ^ in[ctLen + i];
        if (diff != 0) throw new RuntimeException("AEAD authentication failed");
        return chachaXor(key, nonce, in, 0, ctLen);
    }

    // ------------------------------------------------------------ helpers

    public static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
