import me.cns.sendspin.android.Bedtime;
import me.cns.sendspin.android.Totp;
import java.util.Calendar;
import java.util.TimeZone;

/** Desktop checks for the bedtime policy and the unlock codes: sh test/run.sh */
public class BedtimeTest {
    static int fails;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Europe/London"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    public static void main(String[] a) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
        // RFC 6238 SHA1 vector: T=59 s gives 94287082, so 5 digits are its last 5
        byte[] rfc = "12345678901234567890".getBytes();
        check(Totp.code(rfc, 59 / 30).equals("87082"), "RFC 6238 vector, 5 digits");
        check(Totp.code(rfc, 1111111109L / 30).equals("81804"), "RFC 6238 vector 2 (07081804)");
        long now = 1_800_000_000_000L;
        String c = Totp.code(rfc, now / 1000 / 30);
        check(Totp.verify(rfc, c, now, -1) == now / 1000 / 30, "current code accepted");
        check(Totp.verify(rfc, c, now, now / 1000 / 30) == -1, "same code refused the second time");
        check(Totp.verify(rfc, Totp.code(rfc, now / 1000 / 30 - 3), now, -1) > 0, "90 s old code accepted");
        check(Totp.verify(rfc, Totp.code(rfc, now / 1000 / 30 - 5), now, -1) == -1, "150 s old code refused");
        check(Totp.verify(rfc, "12345", now, -1) == -1 || Totp.code(rfc, now / 1000 / 30).equals("12345"), "wrong code refused");
        check(new String(Totp.base32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")).equals("12345678901234567890"), "base32 decode");

        Bedtime b = new Bedtime();
        check(!b.inWindow(at(2026, 10, 26, 19, 44)), "19:44 outside");
        check(b.inWindow(at(2026, 10, 26, 19, 45)), "19:45 inside");
        check(b.inWindow(at(2026, 10, 27, 6, 29)), "06:29 inside");
        check(!b.inWindow(at(2026, 10, 27, 6, 30)), "06:30 outside");
        check(b.inWindow(at(2026, 10, 25, 1, 30)), "inside across the clocks-go-back night");

        long t = at(2026, 10, 26, 20, 0);
        for (int i = 0; i < 19; i++) b.playedMinute(t + i * 60000L);
        check(!b.locked(t + 19 * 60000L), "19 of 20 min: still playing");
        b.playedMinute(t + 19 * 60000L);
        check(b.locked(t + 20 * 60000L), "20 of 20 min: locked");
        check(b.locked(at(2026, 10, 27, 2, 0)), "still locked at 2am (same night)");
        check(!b.locked(at(2026, 10, 27, 7, 0)), "free in the morning");
        check(b.used(at(2026, 10, 27, 20, 0)) == 0, "next night starts from zero");

        Bedtime s = new Bedtime();
        long n = at(2026, 10, 26, 21, 0);
        s.applyServer(19 * 60 + 45, 6 * 60 + 30, 20, 15, n);
        for (int i = 0; i < 5; i++) s.playedMinute(n + i * 60000L);
        check(s.locked(n + 5 * 60000L), "HA's 15 (her Sonos) + 5 here = locked");
        s.sent(5, 20);
        check(s.localUnsent == 0 && s.used(n + 6 * 60000L) == 20, "sent minutes fold into HA's counter");
        s.applyServer(19 * 60 + 45, 6 * 60 + 30, 30, 20, n + 7 * 60000L);
        check(!s.locked(n + 7 * 60000L), "parent raises the cap in HA: unlocked");
        Bedtime o = new Bedtime();
        o.applyServer(19 * 60 + 45, 6 * 60 + 30, 20, 21, at(2026, 10, 27, 12, 0));
        check(o.used(at(2026, 10, 27, 20, 0)) == 0, "a daytime read of last night's counter is ignored");

        long lockAt = n + 10 * 60000L;
        String code = Totp.code(rfc, lockAt / 1000 / 30);
        s.applyServer(19 * 60 + 45, 6 * 60 + 30, 20, 20, lockAt);
        check(s.locked(lockAt), "locked before the code");
        check(!s.tryUnlock(rfc, "00000".equals(code) ? "11111" : "00000", lockAt), "wrong code does nothing");
        check(s.tryUnlock(rfc, code, lockAt), "right code unlocks");
        check(!s.locked(lockAt + 7 * 3600000L), "still unlocked after 7 h");
        check(!s.tryUnlock(rfc, code, lockAt + 1000), "same code cannot be reused");
        check(s.locked(at(2026, 10, 27, 19, 50) + 0) == false, "next evening: budget fresh");

        Bedtime r = new Bedtime();
        r.load(s.save());
        check(r.unlockUntil == s.unlockUntil && r.lastCodeStep == s.lastCodeStep && r.capMin == s.capMin, "survives a restart");

        // away from Wi-Fi for days: the clock drifts, and the window widens a step a day
        long real = at(2026, 10, 30, 20, 30), slow = real - 150000L;   // 2.5 min slow after 5 days
        String now5 = Totp.code(rfc, real / 1000 / 30);
        check(!new Bedtime().tryUnlock(rfc, now5, slow, 0), "2.5 min drift: refused with no slack");
        check(new Bedtime().tryUnlock(rfc, now5, slow, 6), "2.5 min drift after 5 days: accepted");

        Bedtime w = new Bedtime();
        for (int i = 0; i < 5; i++) w.tryUnlock(rfc, "00000".equals(now5) ? "11111" : "00000", real + i * 1000, 0);
        check(w.codeLockedOut(real + 5000), "five wrong codes lock the entry");
        check(!w.tryUnlock(rfc, now5, real + 6000, 0), "even the right code waits out the lock");
        check(w.tryUnlock(rfc, Totp.code(rfc, (real + 70000) / 1000 / 30), real + 70000, 0), "right code works after a minute");
        Bedtime w2 = new Bedtime();
        w2.load(w.save());
        check(w2.wrongInRow == 0 && !w2.codeLockedOut(real + 70000), "a good code clears the count");

        // flat battery, no Wi-Fi or GPS: the clock is three days behind, two codes put it right
        Bedtime f = new Bedtime();
        long dev = real - 3 * 86400000L;
        String c1 = Totp.code(rfc, real / 1000 / 30), c2 = Totp.code(rfc, (real + 70000) / 1000 / 30);
        check(!f.tryUnlock(rfc, c1, dev, 20), "clock 3 days out: one code is not enough");
        long t0 = System.currentTimeMillis();
        check(f.tryUnlock(rfc, c2, dev + 70000, 20), "the next code with it unlocks");
        System.out.println("  (pair search took " + (System.currentTimeMillis() - t0) + " ms here)");
        check(Math.abs(f.resyncedUtc - (real + 70000)) < 60000, "and tells the real time");
        check(Math.abs(f.unlockUntil - (real + 70000 + Bedtime.UNLOCK_MS)) < 60000, "unlocked for 8 h of real time");
        Bedtime g = new Bedtime();
        g.tryUnlock(rfc, "13579", dev, 20);
        check(!g.tryUnlock(rfc, "24680", dev + 70000, 20) || Totp.code(rfc, 0).equals("x"), "two made-up codes do nothing");
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"));
        Bedtime h = new Bedtime();
        check(h.inWindow(at(2027, 7, 15, 17, 50)), "on holiday: 19:50 in Belek (17:50 London) is bedtime");

        // hold: buttons stop after a quiet spell; the same button 3 times quickly brings them back
        me.cns.sendspin.android.KeyHold kh = new me.cns.sendspin.android.KeyHold();
        kh.idleS = 60; kh.windowMs = 1000;
        long k0 = 1000000;
        kh.activity(k0);
        check(!kh.held(k0 + 59000), "hold: not yet after 59 s");
        check(kh.held(k0 + 60000), "hold: on after 60 s quiet");
        long k1 = k0 + 70000;
        check(kh.press(23, k1) == 2, "hold: first press, 2 more to go");
        check(kh.press(82, k1 + 200) == 2, "hold: a different button starts the count again");
        check(kh.press(82, k1 + 400) == 1 && kh.remaining(k1 + 400) == 1, "hold: second press of it, 1 more");
        check(kh.press(82, k1 + 600) == 0 && !kh.held(k1 + 600), "hold: third within 1 s unlocks");
        kh.engage();
        check(kh.press(23, k1 + 2000) == 2 && kh.press(23, k1 + 2600) == 1, "hold: two presses in time");
        check(kh.press(23, k1 + 3100) == 2, "hold: third too late (1.1 s after the first) starts over");
        kh.windowMs = 500; kh.engage();
        kh.press(23, k1 + 5000); kh.press(23, k1 + 5200);
        check(kh.press(23, k1 + 5450) == 0, "hold: 0.5 s window, three in 0.45 s unlocks");
        check(kh.remaining(k1 + 5450) == 0, "hold: nothing pending once unlocked");
        kh.idleS = 0;
        check(!kh.held(k1 + 999999999L), "hold: never when switched off");

        // parental settings: the same code, once, without lifting the bedtime limit
        Bedtime pc = new Bedtime();
        long pt = at(2026, 10, 27, 12, 0);
        String pcode = Totp.code(rfc, pt / 1000 / 30);
        check(pc.checkCode(rfc, pcode, pt, 0) && pc.unlockUntil == 0, "parental code: accepted, bedtime limit untouched");
        check(!pc.checkCode(rfc, pcode, pt + 1000, 0), "parental code: the same code is refused the second time");
        check(!pc.tryUnlock(rfc, pcode, pt + 2000, 0), "parental code: and cannot then unlock bedtime either");

        // audiobook chapters
        me.cns.sendspin.android.Chapters ch = me.cns.sendspin.android.Chapters.from(me.cns.sendspin.Json.parse(
            "[{\"name\":\"Intro\",\"start\":0.0},{\"name\":\"Ch 1\",\"start\":177.65},{\"name\":\"Ch 2\",\"start\":620.17}]"));
        check(ch != null && ch.size() == 3, "chapters: parsed from Music Assistant's list");
        check(ch.at(200000) == 1 && ch.label(200000).equals("Chapter 2 of 3"), "chapters: the one playing and its label");
        check(ch.next(200000) == 620170, "chapters: Next goes to the next chapter's start");
        check(ch.next(700000) == -1, "chapters: Next in the last chapter does nothing");
        check(ch.previous(200000) == 177650, "chapters: Previous goes back to the start of this chapter");
        check(ch.previous(179000) == 0, "chapters: Previous right after a start goes to the chapter before");
        check(ch.previous(1000) == 0, "chapters: Previous in the first chapter stays at 0");
        check(me.cns.sendspin.android.Chapters.from(me.cns.sendspin.Json.parse("[]")) == null, "chapters: none means none");
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
        // HA's counter from another night (abroad: HA resets on UK time) is ignored; a parent lowering it tonight counts
        Bedtime ab = new Bedtime();
        long t1 = at(2026, 10, 26, 21, 0);
        ab.applyServer(19 * 60 + 45, 6 * 60 + 30, 20, 20, t1, "2026-10-26");
        check(ab.locked(t1), "abroad: tonight's 20 minutes used, locked");
        ab.applyServer(19 * 60 + 45, 6 * 60 + 30, 20, 0, t1 + 60000L, "none");
        check(ab.locked(t1 + 60000L), "abroad: HA's counter for a different night does not hand out a second budget");
        ab.applyServer(19 * 60 + 45, 6 * 60 + 30, 20, 5, t1 + 120000L, "2026-10-26");
        check(!ab.locked(t1 + 120000L), "a parent lowering tonight's counter in HA unlocks");
        Bedtime op = new Bedtime();
        long open = at(2026, 10, 26, 19, 45) + 30000L;
        op.applyServer(19 * 60 + 45, 6 * 60 + 30, 20, 20, open, "2026-10-26");
        check(op.used(open) == 0, "window open: last night's unreset counter is not read in the first minutes");
        op.applyServer(19 * 60 + 45, 6 * 60 + 30, 20, 0, open + 60000L, "2026-10-26");
        op.applyServer(19 * 60 + 45, 6 * 60 + 30, 20, 0, open + 180000L, "2026-10-26");
        check(op.used(open + 180000L) == 0, "window open: last night's unreset counter is dropped once HA's reset has run");
        Bedtime nn = new Bedtime();
        nn.applyServer(19 * 60 + 45, 6 * 60 + 30, 20, 12, t1, null);
        nn.applyServer(19 * 60 + 45, 6 * 60 + 30, 20, 3, t1 + 60000L, null);
        check(nn.used(t1 + 60000L) == 12, "without HA's night the counter only rises");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
