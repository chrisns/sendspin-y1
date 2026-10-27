package me.cns.sendspin.android;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * NaCl secretbox (XSalsa20-Poly1305), as Home Assistant's mobile_app encrypts webhook payloads: the message is
 * base64(nonce[24] + mac[16] + ciphertext), the key is the 32 bytes of the registration's hex "secret". With it the
 * Nabu Casa cloudhook only ever sees ciphertext. Checked against PyNaCl (test/BedtimeTest.java).
 */
public final class SecretBox {
    private static final SecureRandom RNG = new SecureRandom();
    private static final int[] SIGMA = { 0x61707865, 0x3320646e, 0x79622d32, 0x6b206574 };   // "expand 32-byte k"

    private SecretBox() { }

    private static int rotl(int v, int c) { return (v << c) | (v >>> (32 - c)); }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); b[o + 2] = (byte) (v >>> 16); b[o + 3] = (byte) (v >>> 24);
    }

    /** 20 Salsa20 rounds over x (in place). */
    private static void rounds(int[] x) {
        for (int i = 0; i < 10; i++) {
            x[4] ^= rotl(x[0] + x[12], 7);  x[8] ^= rotl(x[4] + x[0], 9);   x[12] ^= rotl(x[8] + x[4], 13);  x[0] ^= rotl(x[12] + x[8], 18);
            x[9] ^= rotl(x[5] + x[1], 7);   x[13] ^= rotl(x[9] + x[5], 9);  x[1] ^= rotl(x[13] + x[9], 13);  x[5] ^= rotl(x[1] + x[13], 18);
            x[14] ^= rotl(x[10] + x[6], 7); x[2] ^= rotl(x[14] + x[10], 9); x[6] ^= rotl(x[2] + x[14], 13);  x[10] ^= rotl(x[6] + x[2], 18);
            x[3] ^= rotl(x[15] + x[11], 7); x[7] ^= rotl(x[3] + x[15], 9);  x[11] ^= rotl(x[7] + x[3], 13);  x[15] ^= rotl(x[11] + x[7], 18);
            x[1] ^= rotl(x[0] + x[3], 7);   x[2] ^= rotl(x[1] + x[0], 9);   x[3] ^= rotl(x[2] + x[1], 13);   x[0] ^= rotl(x[3] + x[2], 18);
            x[6] ^= rotl(x[5] + x[4], 7);   x[7] ^= rotl(x[6] + x[5], 9);   x[4] ^= rotl(x[7] + x[6], 13);   x[5] ^= rotl(x[4] + x[7], 18);
            x[11] ^= rotl(x[10] + x[9], 7); x[8] ^= rotl(x[11] + x[10], 9); x[9] ^= rotl(x[8] + x[11], 13);  x[10] ^= rotl(x[9] + x[8], 18);
            x[12] ^= rotl(x[15] + x[14], 7); x[13] ^= rotl(x[12] + x[15], 9); x[14] ^= rotl(x[13] + x[12], 13); x[15] ^= rotl(x[14] + x[13], 18);
        }
    }

    private static int[] state(byte[] key, byte[] in16) {
        int[] s = new int[16];
        s[0] = SIGMA[0]; s[5] = SIGMA[1]; s[10] = SIGMA[2]; s[15] = SIGMA[3];
        for (int i = 0; i < 4; i++) { s[1 + i] = le32(key, i * 4); s[11 + i] = le32(key, 16 + i * 4); }
        for (int i = 0; i < 4; i++) s[6 + i] = le32(in16, i * 4);
        return s;
    }

    /** HSalsa20: the subkey for the first 16 nonce bytes. */
    private static byte[] hsalsa(byte[] key, byte[] n16) {
        int[] x = state(key, n16);
        rounds(x);
        byte[] out = new byte[32];
        int[] pick = { 0, 5, 10, 15, 6, 7, 8, 9 };
        for (int i = 0; i < 8; i++) put32(out, i * 4, x[pick[i]]);
        return out;
    }

    /** XSalsa20 keystream XOR (counter from 0). */
    private static byte[] xsalsa(byte[] key, byte[] nonce24, byte[] in) {
        byte[] sub = hsalsa(key, Arrays.copyOfRange(nonce24, 0, 16));
        byte[] out = new byte[in.length];
        byte[] blk = new byte[16];
        System.arraycopy(nonce24, 16, blk, 0, 8);
        byte[] ks = new byte[64];
        for (long ctr = 0, off = 0; off < in.length; ctr++, off += 64) {
            for (int i = 0; i < 8; i++) blk[8 + i] = (byte) (ctr >>> (8 * i));
            int[] s = state(sub, blk);
            int[] x = s.clone();
            rounds(x);
            for (int i = 0; i < 16; i++) put32(ks, i * 4, x[i] + s[i]);
            for (int i = 0; i < 64 && off + i < in.length; i++) out[(int) off + i] = (byte) (in[(int) off + i] ^ ks[i]);
        }
        return out;
    }

    private static final BigInteger P = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5));
    private static final BigInteger CLAMP = new BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16);

    private static BigInteger le(byte[] b, int off, int len) {
        byte[] r = new byte[len + 1];
        for (int i = 0; i < len; i++) r[len - i] = b[off + i];
        return new BigInteger(r);
    }

    private static byte[] poly1305(byte[] otk, byte[] m) {
        BigInteger r = le(otk, 0, 16).and(CLAMP), s = le(otk, 16, 16), acc = BigInteger.ZERO;
        for (int i = 0; i < m.length; i += 16) {
            int n = Math.min(16, m.length - i);
            byte[] blk = new byte[n + 1];
            System.arraycopy(m, i, blk, 0, n);
            blk[n] = 1;
            acc = acc.add(le(blk, 0, n + 1)).multiply(r).mod(P);
        }
        byte[] t = acc.add(s).toByteArray();
        byte[] out = new byte[16];
        for (int i = 0; i < 16 && i < t.length; i++) out[i] = t[t.length - 1 - i];
        return out;
    }

    /** nonce + mac + ciphertext for plain under key (32 bytes). */
    public static byte[] seal(byte[] key, byte[] plain) {
        byte[] nonce = new byte[24];
        RNG.nextBytes(nonce);
        return seal(key, nonce, plain);
    }

    static byte[] seal(byte[] key, byte[] nonce, byte[] plain) {
        byte[] padded = new byte[32 + plain.length];
        System.arraycopy(plain, 0, padded, 32, plain.length);
        byte[] c = xsalsa(key, nonce, padded);                 // first 32 bytes of keystream: the Poly1305 key
        byte[] ct = Arrays.copyOfRange(c, 32, c.length);
        byte[] mac = poly1305(Arrays.copyOfRange(c, 0, 32), ct);
        byte[] out = new byte[24 + 16 + ct.length];
        System.arraycopy(nonce, 0, out, 0, 24);
        System.arraycopy(mac, 0, out, 24, 16);
        System.arraycopy(ct, 0, out, 40, ct.length);
        return out;
    }

    /** The plaintext of nonce + mac + ciphertext, or null if it was not made with this key (or was altered). */
    public static byte[] open(byte[] key, byte[] box) {
        if (box == null || box.length < 40) return null;
        byte[] nonce = Arrays.copyOfRange(box, 0, 24), mac = Arrays.copyOfRange(box, 24, 40);
        byte[] ct = Arrays.copyOfRange(box, 40, box.length);
        byte[] otk = Arrays.copyOfRange(xsalsa(key, nonce, new byte[32]), 0, 32);
        if (!java.security.MessageDigest.isEqual(mac, poly1305(otk, ct))) return null;
        byte[] padded = new byte[32 + ct.length];
        System.arraycopy(ct, 0, padded, 32, ct.length);
        return Arrays.copyOfRange(xsalsa(key, nonce, padded), 32, 32 + ct.length);
    }
}
