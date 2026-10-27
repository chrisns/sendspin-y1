package me.cns.sendspin.android;

/** Runs a shell command as root (the ROM ships su). Used for sleep, power off and system settings. */
public final class Root {
    public static boolean run(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] { "su", "-c", cmd });
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void runAsync(final String cmd) {
        new Thread(new Runnable() { public void run() { Root.run(cmd); } }, "root").start();
    }

    /** Screen off now (the Y1 has no power button). */
    public static void sleep() { runAsync("input keyevent 26"); }

    public static void powerOff() { runAsync("sync; reboot -p"); }

    public static void restart() { runAsync("sync; reboot"); }
}
