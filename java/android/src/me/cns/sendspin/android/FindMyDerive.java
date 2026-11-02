package me.cns.sendspin.android;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The key schedule of a Find My accessory: what a real tag does to get from its stored secret to the key it advertises.
 *
 * It holds the master PUBLIC key P and a secret sk that moves one way, every 15 minutes:
 *   sk(i)  = SHA-256(sk(i-1) || 00000001 || "update")
 *   (u, v) = three SHA-256 blocks over sk(i) with "diversify" (counters 1 to 3), 72 bytes: u from the first 36, v from the rest,
 *            each taken mod (n-1), plus 1
 *   key(i) = x coordinate of u*P + v*G, on NIST P-224 (28 bytes: this is what is advertised)
 * The owner's private key is not needed to broadcast, only to read the reports, so it never has to be on the iPod. And sk is
 * one way: storing sk for now exposes later keys, never earlier ones.
 *
 * Plain BigInteger arithmetic in Jacobian coordinates; one key takes two scalar multiplications, which an iPod does in well
 * under a second, once every 15 minutes. Checked against FindMy.py in test/FindMyDeriveTest.java.
 */
final class FindMyDerive {
    private static final BigInteger P = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF000000000000000000000001", 16);
    private static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFF16A2E0B8F03E13DD29455C5C2A3D", 16);
    private static final BigInteger GX = new BigInteger("B70E0CBD6BB4BF7F321390B94A03C1D356C21122343280D6115C1D21", 16);
    private static final BigInteger GY = new BigInteger("BD376388B5F723FB4C22DFE6CD4375A05A07476444D5819985007E34", 16);

    private FindMyDerive() { }

    /** The next secret: one 15-minute step forward. */
    static byte[] next(byte[] sk) {
        return kdf(sk, "update", 32);
    }

    /** The secret n steps on. */
    static byte[] skip(byte[] sk, long n) {
        for (long i = 0; i < n; i++) sk = next(sk);
        return sk;
    }

    /**
     * The 28-byte key to advertise.
     * @param pub the master public key, x then y, 28 bytes each
     * @param sk  the secret for this period
     */
    static byte[] advKey(byte[] pub, byte[] sk) {
        if (pub.length != 56 || sk.length != 32) throw new IllegalArgumentException("public key is 56 bytes, secret 32");
        byte[] at = kdf(sk, "diversify", 72);
        BigInteger nm1 = N.subtract(BigInteger.ONE);
        BigInteger u = new BigInteger(1, java.util.Arrays.copyOfRange(at, 0, 36)).mod(nm1).add(BigInteger.ONE);
        BigInteger v = new BigInteger(1, java.util.Arrays.copyOfRange(at, 36, 72)).mod(nm1).add(BigInteger.ONE);
        BigInteger[] base = { new BigInteger(1, java.util.Arrays.copyOfRange(pub, 0, 28)), new BigInteger(1, java.util.Arrays.copyOfRange(pub, 28, 56)), BigInteger.ONE };
        BigInteger[] sum = add(mul(u, base), mul(v, new BigInteger[] { GX, GY, BigInteger.ONE }));
        if (sum[2].signum() == 0) throw new IllegalStateException("point at infinity");
        BigInteger zi = sum[2].modInverse(P);
        BigInteger x = sum[0].multiply(zi.multiply(zi).mod(P)).mod(P);
        byte[] raw = x.toByteArray(), out = new byte[28];
        for (int i = 0; i < 28 && i < raw.length; i++) out[27 - i] = raw[raw.length - 1 - i];     // big-endian, padded or sign byte dropped
        return out;
    }

    /** X9.63 KDF with SHA-256: SHA-256(secret || counter, 4 bytes big-endian from 1 || info), blocks joined and cut to length. */
    private static byte[] kdf(byte[] secret, String info, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = new byte[length];
            byte[] inf = info.getBytes("US-ASCII");
            for (int block = 0, got = 0; got < length; block++) {
                md.reset();
                md.update(secret);
                md.update(new byte[] { 0, 0, 0, (byte) (block + 1) });
                md.update(inf);
                byte[] h = md.digest();
                int n = Math.min(h.length, length - got);
                System.arraycopy(h, 0, out, got, n);
                got += n;
            }
            return out;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- P-224 in Jacobian coordinates (x = X/Z^2, y = Y/Z^3), a = -3. Z = 0 is the point at infinity.

    private static BigInteger[] mul(BigInteger k, BigInteger[] pt) {
        BigInteger[] r = { BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO };
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = dbl(r);
            if (k.testBit(i)) r = add(r, pt);
        }
        return r;
    }

    private static BigInteger[] dbl(BigInteger[] a) {
        if (a[2].signum() == 0 || a[1].signum() == 0) return new BigInteger[] { BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO };
        BigInteger delta = a[2].multiply(a[2]).mod(P);
        BigInteger gamma = a[1].multiply(a[1]).mod(P);
        BigInteger beta = a[0].multiply(gamma).mod(P);
        BigInteger alpha = BigInteger.valueOf(3).multiply(a[0].subtract(delta)).multiply(a[0].add(delta)).mod(P);
        BigInteger x3 = alpha.multiply(alpha).subtract(beta.shiftLeft(3)).mod(P);
        BigInteger z3 = a[1].add(a[2]).pow(2).subtract(gamma).subtract(delta).mod(P);
        BigInteger y3 = alpha.multiply(beta.shiftLeft(2).subtract(x3)).subtract(gamma.multiply(gamma).shiftLeft(3)).mod(P);
        return new BigInteger[] { x3, y3, z3 };
    }

    private static BigInteger[] add(BigInteger[] a, BigInteger[] b) {
        if (a[2].signum() == 0) return b;
        if (b[2].signum() == 0) return a;
        BigInteger z1z1 = a[2].multiply(a[2]).mod(P), z2z2 = b[2].multiply(b[2]).mod(P);
        BigInteger u1 = a[0].multiply(z2z2).mod(P), u2 = b[0].multiply(z1z1).mod(P);
        BigInteger s1 = a[1].multiply(b[2]).multiply(z2z2).mod(P), s2 = b[1].multiply(a[2]).multiply(z1z1).mod(P);
        BigInteger h = u2.subtract(u1).mod(P);
        if (h.signum() == 0) {
            if (s1.equals(s2)) return dbl(a);
            return new BigInteger[] { BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO };
        }
        BigInteger i = h.shiftLeft(1).pow(2).mod(P);
        BigInteger j = h.multiply(i).mod(P);
        BigInteger r = s2.subtract(s1).shiftLeft(1).mod(P);
        BigInteger v = u1.multiply(i).mod(P);
        BigInteger x3 = r.multiply(r).subtract(j).subtract(v.shiftLeft(1)).mod(P);
        BigInteger y3 = r.multiply(v.subtract(x3)).subtract(s1.multiply(j).shiftLeft(1)).mod(P);
        BigInteger z3 = a[2].add(b[2]).pow(2).subtract(z1z1).subtract(z2z2).multiply(h).mod(P);
        return new BigInteger[] { x3, y3, z3 };
    }
}
