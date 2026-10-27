package me.cns.sendspin.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/** Media keys (the Y1's own buttons or a headset's) while the screen is off and no activity has focus. */
public final class MediaButtonReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MEDIA_BUTTON.equals(i.getAction())) return;
        KeyEvent e = (KeyEvent) i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (e == null || e.getAction() != KeyEvent.ACTION_DOWN) return;
        Engine eng = Engine.get(c);
        if (fromWheel(e)) return;   // the wheel's MEDIA_PLAY/PAUSE turned in a pocket: never playback
        if (!fromHeadphones(e) && eng.hold.held(android.os.SystemClock.uptimeMillis())) return;   // on hold: only headphones
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK: eng.togglePlay(); break;
            // headphones (AVRCP) say which they mean, but they track the state themselves and the iPod tells them
            // nothing back (no track info slot is written), so a "play" while already playing means pause
            case KeyEvent.KEYCODE_MEDIA_PLAY: if (eng.playing) eng.pause(); else eng.resume(); break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: eng.pause(); break;
            case KeyEvent.KEYCODE_MEDIA_NEXT: eng.next(); break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: eng.previous(); break;
            default: return;
        }
        abortBroadcast();
    }

    /**
     * The Y1's click wheel turns arrive as MEDIA_PLAY / MEDIA_PAUSE from its keypad. Headphone buttons arrive with the
     * same key codes from the "AVRCP" input device, so only that device counts as a headphone press: everything else
     * (the real wheel comes through more than one keypad device, and getDevice() can be null) is the wheel.
     * No event (the app's own test hook) counts as the wheel too.
     */
    /** Headphone (AVRCP) buttons: these still work on hold, like a real iPod's remote. */
    static boolean fromHeadphones(KeyEvent e) {
        android.view.InputDevice d = e == null ? null : e.getDevice();
        return d != null && "AVRCP".equals(d.getName());
    }

    static boolean fromWheel(KeyEvent e) {
        if (e == null) return true;
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_MEDIA_PLAY && code != KeyEvent.KEYCODE_MEDIA_PAUSE) return false;
        android.view.InputDevice d = e.getDevice();
        return d == null || !"AVRCP".equals(d.getName());
    }
}
