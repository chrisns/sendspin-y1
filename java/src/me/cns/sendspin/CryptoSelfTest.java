package me.cns.sendspin;

import java.nio.charset.Charset;

/** RFC 7748 / RFC 8439 vectors. Run before trusting anything else. */
public final class CryptoSelfTest {
    public static void main(String[] args) throws Exception {
        boolean ok = true;

        // RFC 7748 section 5.2, first vector
        byte[] r = Crypto.x25519(
            Crypto.hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
            Crypto.hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"));
        ok &= check("x25519 vector 1", Crypto.toHex(r).equals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"));

        // RFC 7748 section 6.1 Diffie-Hellman
        byte[] aPriv = Crypto.hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] bPriv = Crypto.hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        byte[] aPub = Crypto.publicKey(aPriv), bPub = Crypto.publicKey(bPriv);
        ok &= check("x25519 pubkey A", Crypto.toHex(aPub).equals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"));
        ok &= check("x25519 shared", Crypto.toHex(Crypto.x25519(aPriv, bPub))
            .equals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
            && Crypto.toHex(Crypto.x25519(bPriv, aPub)).equals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"));

        // RFC 8439 section 2.8.2 AEAD
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) (0x80 + i);
        byte[] nonce = Crypto.hex("070000004041424344454647");
        byte[] aad = Crypto.hex("50515253c0c1c2c3c4c5c6c7");
        byte[] pt = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, "
            + "sunscreen would be it.").getBytes(Charset.forName("UTF-8"));
        byte[] out = Crypto.aeadEncrypt(key, nonce, aad, pt);
        String hex = Crypto.toHex(out);
        ok &= check("aead ciphertext start", hex.startsWith("d31a8d34648e60db7b86afbc53ef7ec2"));
        ok &= check("aead tag", hex.endsWith("1ae10b594f09e26a7e902ecbd0600691"));
        ok &= check("aead round trip", new String(Crypto.aeadDecrypt(key, nonce, aad, out), "UTF-8")
            .equals(new String(pt, "UTF-8")));
        out[5] ^= 1;
        boolean rejected = false;
        try { Crypto.aeadDecrypt(key, nonce, aad, out); } catch (RuntimeException e) { rejected = true; }
        ok &= check("aead rejects tampering", rejected);

        // HKDF (Noise style) is HMAC based; check HMAC-SHA256 RFC 4231 case 1
        byte[] mac = Crypto.hmac(Crypto.hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), "Hi There".getBytes("UTF-8"));
        ok &= check("hmac-sha256", Crypto.toHex(mac).equals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"));

        System.out.println(ok ? "ALL PASS" : "FAILURES");
        System.exit(ok ? 0 : 1);
    }

    private static boolean check(String name, boolean cond) {
        System.out.println((cond ? "PASS " : "FAIL ") + name);
        return cond;
    }
}
