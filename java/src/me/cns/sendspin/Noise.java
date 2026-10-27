package me.cns.sendspin;

import java.nio.charset.Charset;

/** Noise_KKpsk2_25519_ChaChaPoly_SHA256, responder side (Sendspin: client is the responder). */
public final class Noise {
    private Noise() {}

    private static final byte[] NAME = "Noise_KKpsk2_25519_ChaChaPoly_SHA256".getBytes(Charset.forName("UTF-8"));

    static byte[] nonce(long n) {
        byte[] b = new byte[12];
        for (int i = 0; i < 8; i++) b[4 + i] = (byte) (n >>> (8 * i));
        return b;
    }

    static byte[] cat(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    private static final class Symmetric {
        byte[] h = Crypto.sha256(NAME), ck = h, k = null;
        long n = 0;

        Symmetric(byte[] prologue) { mixHash(prologue); }

        void mixHash(byte[] d) { h = Crypto.sha256(h, d); }

        void mixKey(byte[] ikm) {
            byte[][] o = Crypto.hkdf(ck, ikm, 2);
            ck = o[0]; k = o[1]; n = 0;
        }

        void mixKeyAndHash(byte[] ikm) {
            byte[][] o = Crypto.hkdf(ck, ikm, 3);
            ck = o[0]; mixHash(o[1]); k = o[2]; n = 0;
        }

        byte[] encryptAndHash(byte[] pt) {
            byte[] ct = pt;
            if (k != null) { ct = Crypto.aeadEncrypt(k, nonce(n), h, pt); n++; }
            mixHash(ct);
            return ct;
        }

        byte[] decryptAndHash(byte[] ct) {
            byte[] pt = ct;
            if (k != null) { pt = Crypto.aeadDecrypt(k, nonce(n), h, ct); n++; }
            mixHash(ct);
            return pt;
        }
    }

    public interface PskChooser {
        /** Given the decoded message-1 JSON payload, return the 32-byte PSK to mix in. */
        byte[] choose(String msg1PayloadJson);
    }

    public static final class Transport {
        private final byte[] sendKey, recvKey;
        private long sn = 0, rn = 0;

        Transport(byte[] sendKey, byte[] recvKey) { this.sendKey = sendKey; this.recvKey = recvKey; }

        public byte[] encrypt(byte[] pt) { return Crypto.aeadEncrypt(sendKey, nonce(sn++), new byte[0], pt); }

        public byte[] decrypt(byte[] ct) { return Crypto.aeadDecrypt(recvKey, nonce(rn++), new byte[0], ct); }
    }

    public static final class Result {
        public final byte[] msg2;
        public final Transport transport;

        Result(byte[] msg2, Transport t) { this.msg2 = msg2; this.transport = t; }
    }

    public static Result respond(byte[] prologue, byte[] myPriv, byte[] serverPub, byte[] msg1, PskChooser chooser) {
        Symmetric ss = new Symmetric(prologue);
        ss.mixHash(serverPub);               // <- s (initiator static, premessage)
        ss.mixHash(Crypto.publicKey(myPriv)); // -> s (responder static, premessage)
        // message 1: e, es, ss
        byte[] re = new byte[32];
        System.arraycopy(msg1, 0, re, 0, 32);
        byte[] rest = new byte[msg1.length - 32];
        System.arraycopy(msg1, 32, rest, 0, rest.length);
        ss.mixHash(re);
        ss.mixKey(re);                                  // PSK handshakes: e also mixes the key
        ss.mixKey(Crypto.x25519(myPriv, re));           // es
        ss.mixKey(Crypto.x25519(myPriv, serverPub));    // ss
        byte[] payload = ss.decryptAndHash(rest);
        byte[] psk = chooser.choose(new String(payload, Charset.forName("UTF-8")));
        // message 2: e, ee, se, psk
        byte[] ePriv = Crypto.newPrivateKey();
        byte[] ePub = Crypto.publicKey(ePriv);
        ss.mixHash(ePub);
        ss.mixKey(ePub);
        ss.mixKey(Crypto.x25519(ePriv, re));            // ee
        ss.mixKey(Crypto.x25519(ePriv, serverPub));     // se
        ss.mixKeyAndHash(psk);                          // psk2
        byte[] msg2 = cat(ePub, ss.encryptAndHash("{}".getBytes(Charset.forName("UTF-8"))));
        byte[][] c = Crypto.hkdf(ss.ck, new byte[0], 2);  // c[0]: initiator->responder
        return new Result(msg2, new Transport(c[1], c[0]));
    }
}
