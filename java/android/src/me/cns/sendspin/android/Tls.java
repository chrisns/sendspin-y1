package me.cns.sendspin.android;

import android.content.Context;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Modern HTTPS for Android 4.2: Home Assistant (its own hostname and the Nabu Casa remote URL) needs TLS 1.2 with
 * AES-GCM, which 4.2's built-in stack lacks, and a Let's Encrypt chain (YR1 > Root YR > ISRG Root X1) newer than its
 * trust store. So: Conscrypt (bundled, libs/) for the protocol, and the roots in assets/certs as the only trust
 * anchors for these connections: Let's Encrypt, and Amazon Root CA 1 (the Nabu Casa cloudhook, hooks.nabu.casa).
 */
public final class Tls {
    private static SSLSocketFactory factory;

    private Tls() { }

    public static synchronized SSLSocketFactory factory(Context c) throws Exception {
        if (factory != null) return factory;
        Provider p = (Provider) Class.forName("org.conscrypt.Conscrypt").getMethod("newProvider").invoke(null);
        Security.insertProviderAt(p, 1);
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        for (String name : c.getAssets().list("certs")) {
            InputStream in = c.getAssets().open("certs/" + name);
            try { ks.setCertificateEntry(name, (X509Certificate) cf.generateCertificate(in)); } finally { in.close(); }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS", p);
        ctx.init(null, tmf.getTrustManagers(), null);
        factory = new Handshaking(ctx.getSocketFactory());
        return factory;
    }

    /**
     * Shakes hands as soon as the socket is made. Android 4.2's HttpsURLConnection answers any failed handshake by
     * retrying with SSLv3 only, which Conscrypt refuses, so the real reason was lost; this way it is logged, and a
     * good handshake is done before 4.2 can meddle with the protocols.
     */
    private static final class Handshaking extends SSLSocketFactory {
        private final SSLSocketFactory d;
        Handshaking(SSLSocketFactory d) { this.d = d; }
        private static java.net.Socket shake(java.net.Socket s, String host) throws java.io.IOException {
            try {
                ((javax.net.ssl.SSLSocket) s).startHandshake();
            } catch (java.io.IOException e) {
                android.util.Log.i("ab", "TLS to " + host + " failed: " + e);
                throw e;
            }
            return s;
        }
        public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return shake(d.createSocket(s, host, port, autoClose), host);
        }
        public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
            return shake(d.createSocket(host, port), host);
        }
        public java.net.Socket createSocket(String host, int port, java.net.InetAddress la, int lp) throws java.io.IOException {
            return shake(d.createSocket(host, port, la, lp), host);
        }
        public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return shake(d.createSocket(host, port), host.getHostName());
        }
        public java.net.Socket createSocket(java.net.InetAddress host, int port, java.net.InetAddress la, int lp) throws java.io.IOException {
            return shake(d.createSocket(host, port, la, lp), host.getHostName());
        }
    }

    /** Apply to a connection when it is HTTPS. */
    public static void apply(Context c, java.net.HttpURLConnection conn) throws Exception {
        if (conn instanceof HttpsURLConnection) ((HttpsURLConnection) conn).setSSLSocketFactory(factory(c));
    }
}
