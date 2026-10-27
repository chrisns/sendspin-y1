package me.cns.sendspin.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;
import java.util.List;

/**
 * Plays the Help video from a chapter and carries on through the rest. Wheel/Next/Previous change chapter, the middle
 * button pauses, Back or Menu leaves. With extra "loud" (the first unlock of a gifted iPod) it plays at full volume.
 */
public final class HelpActivity extends Activity {
    /** True while it is open: the volume limit stands aside and bedtime does not count it as idle. */
    public static volatile boolean open;

    private VideoView video;
    private TextView label;
    private List<Help.Chapter> chapters;
    private final Handler handler = new Handler();
    private AudioManager audio;
    private int volumeBefore = -1, shown = -1;
    private long labelUntil;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        int f = WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;       // a gifted iPod may be asleep when it is unlocked
        getWindow().setFlags(f, f);
        open = true;
        Engine eng = Engine.get(this);
        if (eng.playing) eng.pause();                       // her music waits while the video explains things
        chapters = Help.chapters();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (getIntent().getBooleanExtra("loud", false)) {
            volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(18);
        label.setPadding(24, 12, 24, 12);
        label.setBackgroundColor(0xAA000000);
        label.setGravity(Gravity.CENTER);
        root.addView(label, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
        final int start = Math.max(0, Math.min(chapters.size() - 1, getIntent().getIntExtra("chapter", 0)));
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) { video.seekTo((int) (chapters.get(start).start * 1000)); video.start(); }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) { Engine.get(HelpActivity.this).log("help: video completed at " + mp.getCurrentPosition() + " ms"); finish(); }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) { Engine.get(HelpActivity.this).log("help: player error " + what + "/" + extra); Engine.get(HelpActivity.this).notice("Couldn’t play the Help video"); finish(); return true; }
        });
        video.setVideoPath(Help.video().getPath());
        handler.post(poll);
    }

    private int chapterAt(int ms) {
        int c = 0;
        for (int i = 0; i < chapters.size(); i++) if (chapters.get(i).start * 1000 <= ms + 300) c = i;
        return c;
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            Engine.get(HelpActivity.this).hold.activity(SystemClock.uptimeMillis());   // watching is not idle
            if (video.isPlaying() || video.getCurrentPosition() > 0) {
                int c = chapterAt(video.getCurrentPosition());
                if (c != shown) { shown = c; label.setText(chapters.get(c).title); labelUntil = SystemClock.uptimeMillis() + 3500; }
            }
            label.setVisibility(SystemClock.uptimeMillis() < labelUntil || !video.isPlaying() ? android.view.View.VISIBLE : android.view.View.GONE);
            handler.postDelayed(this, 300);
        }
    };

    private void go(int c) {
        c = Math.max(0, Math.min(chapters.size() - 1, c));
        video.seekTo((int) (chapters.get(c).start * 1000));
        if (!video.isPlaying()) video.start();
        shown = c;
        label.setText(chapters.get(c).title);
        labelUntil = SystemClock.uptimeMillis() + 3500;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (e.getRepeatCount() > 0) return true;
        int now = video.getCurrentPosition(), cur = chapterAt(now);
        switch (code) {
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU: Engine.get(this).log("help: left with " + code); finish(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (video.isPlaying()) video.pause(); else video.start();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (cur + 1 < chapters.size()) go(cur + 1); else finish();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                go(now - chapters.get(cur).start * 1000 > 3000 ? cur : cur - 1);   // like a music player: restart, then go back
                return true;
            default: return super.onKeyDown(code, e);
        }
    }

    @Override
    protected void onPause() {
        Engine.get(this).log("help: paused, finishing=" + isFinishing() + " at " + video.getCurrentPosition() + " ms");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        open = false;
        handler.removeCallbacks(poll);
        if (volumeBefore >= 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBefore, 0);
        super.onDestroy();
    }
}
