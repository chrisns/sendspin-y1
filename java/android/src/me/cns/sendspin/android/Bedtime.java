package me.cns.sendspin.android;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * A bedtime listening budget, shared with Home Assistant (its counter and an automation that adds the device's
 * minutes): inside the night window (default 19:45 to 06:30) the listener gets cap minutes of playing time
 * across her Sonos and this iPod. Home Assistant holds the shared counter; the iPod keeps a copy and its own
 * unsent minutes, so the limit holds with no Wi-Fi at all. Times are local wherever the iPod is (Geo sets the zone). A one-time grown-up code (Totp) lifts it for 8 hours.
 *
 * Pure Java on purpose: it is unit-tested on the desktop (test/BedtimeTest.java).
 */
public final class Bedtime {
    public static final long UNLOCK_MS = 8 * 3600 * 1000L;

    // settings, as last read from Home Assistant (defaults = what it says today)
    public int startMin = 19 * 60 + 45, endMin = 6 * 60 + 30, capMin = 20;
    // the current night
    public String night = "";       // date the window opened, "" outside it
    public int serverUsed;          // HA's counter when last read (her Sonos minutes plus ours already sent)
    public int localUnsent;         // minutes played here and not yet added to HA's counter
    public long unlockUntil;        // epoch ms
    public long lastCodeStep = -1;  // highest one-time code step accepted
    public long settingsAt;         // when HA last answered (0 = never)

    /** The night (date the window opened) that now falls in, or null outside the window. */
    String nightAt(long now) {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.setTimeInMillis(now);
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        boolean overnight = startMin > endMin;
        boolean in = overnight ? (m >= startMin || m < endMin) : (m >= startMin && m < endMin);
        if (!in) return null;
        if (overnight && m < endMin) c.add(Calendar.DAY_OF_MONTH, -1);
        return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** A new night starts from zero (HA resets its counter at the window start too). */
    private void roll(long now) {
        String n = nightAt(now);
        if (n == null) { night = ""; return; }
        if (!n.equals(night)) { night = n; serverUsed = 0; localUnsent = 0; }
    }

    public boolean inWindow(long now) { return nightAt(now) != null; }

    /** Minutes since the window opened (only meaningful inside it). */
    int minutesIntoWindow(long now) {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.setTimeInMillis(now);
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return (m - startMin + 1440) % 1440;
    }

    public int used(long now) { roll(now); return night.length() == 0 ? 0 : serverUsed + localUnsent; }

    public int remaining(long now) { return Math.max(0, capMin - used(now)); }

    public boolean unlocked(long now) { return now < unlockUntil; }

    /** True when playback must stop: inside the window, budget spent, no grown-up unlock running. */
    public boolean locked(long now) { return inWindow(now) && used(now) >= capMin && !unlocked(now); }

    /** One more minute of listening (only counts inside the window). */
    public void playedMinute(long now) {
        roll(now);
        if (night.length() > 0) localUnsent++;
    }

    /** Fresh settings and counter from Home Assistant. The counter only means tonight's while inside the window. */
    public void applyServer(int start, int end, int cap, int used, long now) { applyServer(start, end, cap, used, now, null); }

    /**
     * serverNight is the night HA's counter belongs to in HA's own time zone ("2026-10-26", "none" outside its window,
     * null if unknown). Away from home HA's night starts at a different moment from the iPod's, so its counter is only
     * taken when both mean the same night: HA resetting at its window start (UK time) must not hand out a second budget,
     * nor last night's total lock tonight early. When the nights match HA is the truth (a parent can lower the counter
     * there). In the first two minutes after the window opens HA's reset may not have run yet, so its counter is not
     * read at all. Without a night from HA the counter can only raise the count.
     */
    public void applyServer(int start, int end, int cap, int used, long now, String serverNight) {
        startMin = start;
        endMin = end;
        capMin = cap;
        settingsAt = now;
        roll(now);
        if (night.length() == 0) return;
        if (serverNight != null && !serverNight.equals(night)) return;
        if (minutesIntoWindow(now) < 2) return;           // HA's reset may not have run yet: count here until it has
        serverUsed = serverNight != null ? used : Math.max(serverUsed, used);
    }

    /** Our unsent minutes were added to HA's counter, which now reads newTotal. */
    public void sent(int minutes, int newTotal) {
        localUnsent = Math.max(0, localUnsent - minutes);
        serverUsed = newTotal;
    }

    /** Epoch ms when the current (or next) window ends. */
    public long windowEnd(long now) {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, endMin / 60);
        c.set(Calendar.MINUTE, endMin % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** Lift the limit for 8 hours if the code is right and not used before. */
    public boolean tryUnlock(byte[] key, String code, long now) { return tryUnlock(key, code, now, 0); }

    // a wrong code, kept in case it was right for a clock that is badly out (see Totp.findPair)
    private String pendingCode;
    private long pendingAt;
    public int wrongInRow;
    public long lockoutUntil;
    /** After a two-code match: what the time really is (epoch ms, about 45 s either way), else 0. */
    public long resyncedUtc;

    /**
     * slack widens the window by that many 30 s steps either side when the clock has gone a while without being set
     * (away from Wi-Fi for days). If a code fails, the next code typed within 10 minutes is tried with it as a pair
     * across 30 days either way, which also tells us the real time. Five wrong codes in a row lock the entry for a
     * minute, doubling each time, so the wider window cannot be guessed.
     */
    public boolean tryUnlock(byte[] key, String code, long now, int slack) {
        if (!checkCode(key, code, now, slack)) return false;
        unlockUntil = (resyncedUtc != 0 ? resyncedUtc : now) + UNLOCK_MS;
        return true;
    }

    /**
     * The grown-up code alone, with the same rules (each code once, the wider window, the two-code clock fix and the
     * lockout), without lifting the bedtime limit: for parental settings.
     */
    public boolean checkCode(byte[] key, String code, long now, int slack) {
        resyncedUtc = 0;
        if (key == null || codeLockedOut(now)) return false;
        long step = Totp.verify(key, code, now, lastCodeStep, slack);
        // only a clock nobody has set (slack at its widest) can be that far out; the search takes a few seconds
        if (step < 0 && slack >= 20 && pendingCode != null && now - pendingAt >= 0 && now - pendingAt < 600000) {
            step = Totp.findPair(key, pendingCode, code, now - pendingAt, now, lastCodeStep, 30 * 2880L);
            if (step >= 0) resyncedUtc = step * Totp.PERIOD_S * 1000L + 45000;
        }
        if (step < 0) {
            pendingCode = code;
            pendingAt = now;
            if (++wrongInRow >= 5) lockoutUntil = now + Math.min(3600000L, 60000L << Math.min(6, wrongInRow - 5));
            return false;
        }
        pendingCode = null;
        wrongInRow = 0;
        lockoutUntil = 0;
        lastCodeStep = step;
        return true;
    }

    public static String hhmm(int min) { return String.format("%d:%02d", min / 60, min % 60); }

    public static int parseHhmm(String s, int dflt) {
        try {
            String[] p = s.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    // ---- persistence (Store section "bedtime")

    public Map<String, Object> save() {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("start", Long.valueOf(startMin));
        m.put("end", Long.valueOf(endMin));
        m.put("cap", Long.valueOf(capMin));
        m.put("night", night);
        m.put("serverUsed", Long.valueOf(serverUsed));
        m.put("localUnsent", Long.valueOf(localUnsent));
        m.put("unlockUntil", Long.valueOf(unlockUntil));
        m.put("lastCodeStep", Long.valueOf(lastCodeStep));
        m.put("settingsAt", Long.valueOf(settingsAt));
        m.put("wrongInRow", Long.valueOf(wrongInRow));
        m.put("lockoutUntil", Long.valueOf(lockoutUntil));
        return m;
    }

    public void load(Map<String, Object> m) {
        if (m == null) return;
        startMin = (int) num(m, "start", startMin);
        endMin = (int) num(m, "end", endMin);
        capMin = (int) num(m, "cap", capMin);
        night = m.get("night") == null ? "" : String.valueOf(m.get("night"));
        serverUsed = (int) num(m, "serverUsed", 0);
        localUnsent = (int) num(m, "localUnsent", 0);
        unlockUntil = num(m, "unlockUntil", 0);
        lastCodeStep = num(m, "lastCodeStep", -1);
        settingsAt = num(m, "settingsAt", 0);
        wrongInRow = (int) num(m, "wrongInRow", 0);
        lockoutUntil = num(m, "lockoutUntil", 0);
    }

    /** Wrong codes have locked the entry for now: true until then (never more than an hour, whatever the clock did). */
    public boolean codeLockedOut(long now) { return now < lockoutUntil && lockoutUntil - now <= 3600000L; }

    private static long num(Map<String, Object> m, String k, long d) {
        Object o = m.get(k);
        return o instanceof Number ? ((Number) o).longValue() : d;
    }
}
