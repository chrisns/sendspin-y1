/** BtPolicy: when the iPod's Bluetooth is switched automatically. */
public class BtPolicyTest {
    static int failed;
    static final long MIN = 60000L;

    static void check(String what, int want, int got) {
        if (want == got) System.out.println("ok   " + what);
        else { failed++; System.out.println("FAIL " + what + ": want " + want + ", got " + got); }
    }

    static int d(boolean btOn, boolean phones, boolean playing, boolean screen, boolean paired, long idle, long since) {
        try {
            java.lang.reflect.Method m = Class.forName("me.cns.sendspin.android.BtPolicy").getDeclaredMethod("decide",
                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, long.class, long.class);
            m.setAccessible(true);
            return ((Integer) m.invoke(null, btOn, phones, playing, screen, paired, Long.valueOf(idle), Long.valueOf(since))).intValue();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static void main(String[] a) {
        long long_ago = 60 * MIN;
        check("on, quiet 11 min, nothing connected: off", 2, d(true, false, false, false, true, 11 * MIN, long_ago));
        check("on, quiet 9 min: stays on", 0, d(true, false, false, false, true, 9 * MIN, long_ago));
        check("on, headphones connected, quiet for an hour: stays on", 0, d(true, true, false, false, true, 60 * MIN, long_ago));
        check("on, playing on the speaker: stays on", 0, d(true, false, true, false, true, 60 * MIN, long_ago));
        check("on, used in the last minute: stays on", 0, d(true, false, false, true, true, 0, long_ago));
        check("on, screen merely lit (charger), nobody using it for 11 min: off", 2, d(true, false, false, false, true, 11 * MIN, long_ago));
        check("off, somebody uses it, headphones paired before: on", 1, d(false, false, false, true, true, 0, long_ago));
        check("off, somebody uses it, never paired: stays off", 0, d(false, false, false, true, false, 0, long_ago));
        check("off, nobody using it: stays off", 0, d(false, false, false, false, true, 0, long_ago));
        check("off, playing but nobody using it: stays off", 0, d(false, false, true, false, true, 0, long_ago));
        check("just switched on 2 min ago: no switch, even if idle", 0, d(true, false, false, false, true, 30 * MIN, 2 * MIN));
        check("just switched off 2 min ago: no switch, even when used", 0, d(false, false, false, true, true, 0, 2 * MIN));
        System.out.println(failed == 0 ? "BtPolicy: ALL PASS" : "BtPolicy: " + failed + " FAILED");
        if (failed != 0) System.exit(1);
    }
}
