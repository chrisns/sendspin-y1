import java.lang.reflect.Method;

/**
 * Checks FindMyDerive against FindMy.py. The accessory below is random and made up for this test (no real secrets): its master
 * public key, its first secret (SKN) and the keys FindMy.py gives for a handful of indexes, up to 5000 steps on.
 */
public class FindMyDeriveTest {
    static final String PUB = "4702e2c3b9a8110317330b2448e1ccffee1682bf6cf660c0d8274e66c88eac8304bc69af627baf3a806aaa1c021acd94f26c11d13234c82e";
    static final String SKN = "2e82732bfb78e3b242bdacd967d31c50c8a8abdf19b7b4fcd9f1afa31df46482";
    static final String SK300 = "6642c2412492060b8944a8569d081cb2c0f4bc36d47b6d994f9e18fc3168196b";
    static int failed;
    static Class<?> c;
    static Method next, skip, advKey;

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static void check(int index, String want) throws Exception {
        byte[] sk = (byte[]) skip.invoke(null, unhex(SKN), Long.valueOf(index));
        String got = hex((byte[]) advKey.invoke(null, unhex(PUB), sk));
        if (!got.equals(want)) { failed++; System.out.println("FAIL key " + index + ": got " + got + " want " + want); }
    }

    public static void main(String[] a) throws Exception {
        c = Class.forName("me.cns.sendspin.android.FindMyDerive");
        next = c.getDeclaredMethod("next", byte[].class); next.setAccessible(true);
        skip = c.getDeclaredMethod("skip", byte[].class, long.class); skip.setAccessible(true);
        advKey = c.getDeclaredMethod("advKey", byte[].class, byte[].class); advKey.setAccessible(true);
        String sk300 = hex((byte[]) skip.invoke(null, unhex(SKN), Long.valueOf(300)));
        if (!sk300.equals(SK300)) { failed++; System.out.println("FAIL secret after 300 steps"); }
        check(0, "37a4bf2465c08848f9363cf5c708e66c0377f78ec6121cc5336091cd");
        check(1, "8d34cddec41e09886087583142f8a044dc2ec969228cf7860cec36f3");
        check(2, "8694da8617d234372ff8784a0eadb094a89d731e601d6584f97453be");
        check(3, "9361fd1f740a90b52d688e512dc92bbb136c6f5194917faeafcea5ca");
        check(10, "f1c0c57d2422e5e3d84a916cd7570491f746d066cf0880c3070450ac");
        check(99, "49f5140ada362bf4718c7bbd0e46ec00280a9645b3a4aef62a4ae3a9");
        check(300, "0e8541876021a3e0ef6c1607f5218fc8bceddfa45c39e06e2791854a");
        check(1000, "202c28b0e3dd1c55ebf098441b458a4e5e4a352c2f518f3479bb79a9");
        check(5000, "3991946820e11ef8abd4b310756016da5ef67eeb672fbee96545659d");
        long t0 = System.currentTimeMillis();
        advKey.invoke(null, unhex(PUB), unhex(SKN));
        System.out.println("one key took " + (System.currentTimeMillis() - t0) + " ms on this machine");
        System.out.println(failed == 0 ? "FindMyDerive: ALL PASS" : "FindMyDerive: " + failed + " FAILED");
        if (failed != 0) System.exit(1);
    }
}
