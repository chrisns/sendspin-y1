package me.cns.sendspin.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/** Small blocking HTTP and file helpers. */
public final class Net {
    /** Every byte downloaded by the app, for the sync speed estimate. */
    public static final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Asked between files: may the sync carry on (still on Wi-Fi, battery fine)? */
    public interface Gate { boolean ok(); }

    public static String getText(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " " + url);
        InputStream in = c.getInputStream();
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return new String(o.toByteArray(), UTF8);
        } finally { in.close(); }
    }

    /** Download to dest, resuming a partial file with a Range request. */
    public static void download(String url, File dest) throws IOException {
        long have = dest.exists() ? dest.length() : 0;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        int code = c.getResponseCode();
        if (code == 416) return; // already complete
        if (code != 200 && code != 206) throw new IOException("HTTP " + code);
        if (code == 200) have = 0;
        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(dest, have > 0);
        try {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); bytes.addAndGet(n); }
        } finally { out.close(); in.close(); }
    }

    public static String readText(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return new String(o.toByteArray(), UTF8);
        } finally { in.close(); }
    }

    public static void writeText(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        try { o.write(s.getBytes(UTF8)); } finally { o.close(); }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }
}
