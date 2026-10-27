package me.cns.sendspin.android;

import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;

/** Hosts the hand-drawn UI. Intent extras (host, port, token, gw_prefix, gw_port) provision the app over adb. */
public final class MainActivity extends Activity {
    /** True while an instance exists; the service relaunches the app when this goes false. */
    public static volatile boolean alive;
    private UiView ui;
    private Engine eng;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        alive = true;
        startService(new Intent(this, PlayService.class));
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        eng = Engine.get(this);
        provision(getIntent());
        ui = new UiView(this, eng);
        setContentView(ui);
        // Test hook: `su -c am broadcast -a me.cns.ab.KEY --ei code N` presses a key inside the app. Guarded by a
        // signature permission, so only root (adb) can use it.
        registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                final int code = i.getIntExtra("code", 0);
                ui.post(new Runnable() { public void run() { if (!ui.holdKey(code, null, true)) ui.onKey(code, null); } });
            }
        }, new IntentFilter("me.cns.ab.KEY"), "me.cns.ab.permission.DEBUG", null);
        // rom/findmy/findmy_keys.py push: new keys were copied to /sdcard/FindMy
        registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { eng.reloadBeacon(); }
        }, new IntentFilter("me.cns.ab.FINDMY_RELOAD"), "me.cns.ab.permission.DEBUG", null);
    }

    private void provision(Intent i) {
        if (i != null && i.hasExtra("token")) {
            eng.configure(i.getStringExtra("host"), i.getIntExtra("port", 8095), i.getStringExtra("token"),
                i.getStringExtra("gw_prefix"), i.getIntExtra("gw_port", 18099));
        }
    }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); provision(i); }

    @Override
    protected void onPause() { ui.cancelVoice(); super.onPause(); }

    @Override
    protected void onDestroy() {
        alive = false;
        super.onDestroy();
    }

    /** Back never closes the app: the only way out is the deliberate \"Open Solar\" row. */
    @Override
    public void onBackPressed() { }

    @Override
    protected void onResume() { super.onResume(); eng.syncNow(); }

    /** If the wheel reports rotation as a scroll axis instead of keys, treat a notch like an up/down key. */
    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_SCROLL) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            int code = v > 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
            if (v != 0) return ui.holdKey(code, null, true) || ui.onKey(code, null);
        }
        return super.onGenericMotionEvent(e);
    }

    private boolean longDone;
    private int swallowUp = -1;   // the release of the key that stopped the find chime

    private static boolean holdable(int code) {
        return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER   // hold centre: favourite, or voice search
            || code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS;   // hold: 30 s in a book
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (eng.finder.stop()) { swallowUp = code; return true; }   // ringing to be found: any button stops it
        if (ui.holdKey(code, e, true)) return true;   // on hold: counted towards unlocking, or ignored
        if (holdable(code)) {           // decided on release, so a hold can mean something else
            if (e.getRepeatCount() == 0) { longDone = false; e.startTracking(); }
            return true;
        }
        if (e.getRepeatCount() > 0 && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE)) return ui.onKey(code, e);
        if (e.getRepeatCount() > 0) return true;
        return ui.onKey(code, e) || super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (ui.holdSwallows(code)) return true;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) { longDone = true; ui.openPowerMenu(); return true; }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { longDone = true; ui.showNowPlaying(); return true; }  // hold Play: Now Playing (sleep is hold Menu)
        if (code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            longDone = true;
            ui.onKey(code == KeyEvent.KEYCODE_MEDIA_NEXT ? KeyEvent.KEYCODE_MEDIA_FAST_FORWARD : KeyEvent.KEYCODE_MEDIA_REWIND, e);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {   // hold centre on a song: favourite it
            if (ui.toggleFavourite()) { longDone = true; return true; }
            if (ui.startVoice()) { longDone = true; return true; }                      // anywhere else: voice search
        }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == swallowUp) { swallowUp = -1; longDone = false; return true; }
        if (ui.holdKey(code, e, false)) { longDone = false; return true; }
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && ui.listening()) {   // let go: search
            ui.stopVoice();
            longDone = false;
            return true;
        }
        if (holdable(code)) {
            if (!longDone && e.isTracking() && !e.isCanceled()) ui.onKey(code, e);
            longDone = false;
            return true;
        }
        return super.onKeyUp(code, e);
    }
}
