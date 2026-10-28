package me.cns.sendspin.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
 * button pauses, Back or Menu leaves. With extra "loud" (the gift moment: the first switch-on after arming) it plays at full volume.
 */
public final class HelpActivity extends Activity {
    /** True while it is open: the volume limit stands aside and bedtime does not count it as idle. */
    public static volatile boolean open;
    /** Until when (ms since 1970) the gift video is about to be shown: nothing else may take the front meanwhile. */
    public static volatile long comingUntil;
    private boolean completed;

    private VideoView video;
    private TextView label;
    private List<Help.Chapter> chapters;
    private final Handler handler = new Handler();
    private AudioManager audio;
    private int volumeBefore = -1, shown = -1;
    private long labelUntil, pausedAt;
    private int tries, relaunches;
    private boolean leftByUser;
    private Loading loading;
    private boolean pausedMusic, cleaned;
    private static final long PAUSED_MAX_MS = 120000;       // a paused video nobody is watching does not hold the screen on

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        int f = WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;       // a gifted iPod may be asleep when it is unlocked
        getWindow().setFlags(f, f);
        open = true;
        Engine eng = Engine.get(this);
        if (eng.playing) { eng.pause(); pausedMusic = true; }   // her music waits while the video explains things
        chapters = Help.chapters();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        // until the media system has the video ready (a few seconds, early after power-on): a friendly screen with something moving
        loading = new Loading(this, eng.id.owner());
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
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
            public void onPrepared(MediaPlayer mp) {
                Engine.get(HelpActivity.this).log("help: prepared, " + mp.getDuration() / 1000 + " s, " + mp.getVideoWidth() + "x" + mp.getVideoHeight());
                final boolean gift = getIntent().getBooleanExtra("gift", false);     // the gift moment: bedtime does not matter
                if (getIntent().getBooleanExtra("loud", false)) {
                    Engine eng = Engine.get(HelpActivity.this);
                    eng.setGiftArmed(false);                    // the gift moment is spent only now the video really plays
                    // deliberate full volume, but not through headphones or a bedtime lock, where the limit stands
                    if ((gift || !eng.bedtimeLocked()) && !audio.isWiredHeadsetOn() && !audio.isBluetoothA2dpOn() && volumeBefore < 0) {
                        volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
                    }
                }
                video.seekTo((int) (chapters.get(start).start * 1000)); video.start();
                handler.postDelayed(new Runnable() { public void run() { if (loading != null) { loading.stop(); loading.setVisibility(android.view.View.GONE); } } }, 350);   // after the first frame
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) { completed = true; Engine.get(HelpActivity.this).log("help: video completed at " + mp.getCurrentPosition() + " ms"); finish(); }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Engine.get(HelpActivity.this).log("help: player error " + what + "/" + extra);
                if (getIntent().getBooleanExtra("gift", false) && tries++ < 5) {      // just after power-on the media system may not be ready: try again
                    handler.postDelayed(new Runnable() { public void run() { video.setVideoPath(Help.video().getPath()); } }, 1200);
                    return true;
                }
                Engine.get(HelpActivity.this).notice("Couldn\u2019t play the Help video"); finish(); return true;
            }
        });
        final int slow = getIntent().getIntExtra("slow", 0);                      // test: pretend the media system is slow
        if (slow > 0) handler.postDelayed(new Runnable() { public void run() { video.setVideoPath(Help.video().getPath()); } }, slow);
        else video.setVideoPath(Help.video().getPath());
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
            if (video.isPlaying()) pausedAt = 0;
            else if (pausedAt == 0) pausedAt = SystemClock.uptimeMillis();
            else if (SystemClock.uptimeMillis() - pausedAt > PAUSED_MAX_MS && video.getCurrentPosition() > 0) { finish(); return; }
            if (video.isPlaying() || video.getCurrentPosition() > 0) {
                int c = chapterAt(video.getCurrentPosition());
                if (c != shown) { shown = c; label.setText(chapters.get(c).title); labelUntil = SystemClock.uptimeMillis() + 3500; }
            }
            boolean waiting = loading != null && loading.getVisibility() == android.view.View.VISIBLE;     // the greeting has the screen to itself
            label.setVisibility(!waiting && (SystemClock.uptimeMillis() < labelUntil || !video.isPlaying()) ? android.view.View.VISIBLE : android.view.View.GONE);
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
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU: leftByUser = true; Engine.get(this).log("help: left with " + code); finish(); return true;
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
        cleanup();                                          // asleep or covered: the limit and the idle clock apply again
        final int again = getIntent().getIntExtra("again", 0);
        // The gift video, covered by something starting up after power-on (not the user leaving, not done): bring it back, a few
        // times. (Starting the player's own screen, a singleTask, also makes Android finish this one: so this applies even if finishing.)
        if (getIntent().getBooleanExtra("gift", false) && !leftByUser && !completed && again < 4
            && android.os.SystemClock.elapsedRealtime() < 5 * 60000L && video.getCurrentPosition() < 5000) {
            Engine.get(this).log("help: covered at boot, bringing it back (" + (again + 1) + ")");
            final Intent next = new Intent(this, HelpActivity.class).putExtras(getIntent()).putExtra("again", again + 1)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            final Context app = getApplicationContext();
            comingUntil = System.currentTimeMillis() + 15000;
            new Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() { public void run() { try { app.startActivity(next); } catch (RuntimeException ignored) { } } }, 700);
        } else if (!isFinishing()) {
            finish();
        }
        super.onPause();
    }

    @Override
    protected void onStop() { cleanup(); super.onStop(); }

    @Override
    protected void onDestroy() { cleanup(); super.onDestroy(); }

    private void cleanup() {
        open = false;
        handler.removeCallbacks(poll);
        if (volumeBefore >= 0) { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBefore, 0); volumeBefore = -1; }
        if (!cleaned) {
            cleaned = true;
            if (pausedMusic && !started()) Engine.get(this).resume();   // the video never played: her book carries on
        }
    }

    private boolean started() { try { return shown >= 0 || video.getCurrentPosition() > 0; } catch (RuntimeException e) { return false; } }

    /** "Hi Freya! Just getting things ready for you!": bobbing dots and twinkling stars, drawn by hand (no media needed). */
    private static final class Loading extends android.view.View {
        private final String hi;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Typeface bold, reg;
        private final float[] sx = new float[28], sy = new float[28], sp = new float[28];
        private boolean running = true;

        Loading(Context c, String name) {
            super(c);
            hi = name.length() > 0 ? "Hi " + name + "!" : "Hi there!";
            android.graphics.Typeface b, r;
            try { b = android.graphics.Typeface.createFromAsset(c.getAssets(), "fonts/Inter-Bold.ttf"); } catch (RuntimeException e) { b = android.graphics.Typeface.DEFAULT_BOLD; }
            try { r = android.graphics.Typeface.createFromAsset(c.getAssets(), "fonts/Inter-Regular.ttf"); } catch (RuntimeException e) { r = android.graphics.Typeface.DEFAULT; }
            bold = b;
            reg = r;
            java.util.Random rnd = new java.util.Random(7);
            for (int i = 0; i < sx.length; i++) { sx[i] = rnd.nextFloat(); sy[i] = rnd.nextFloat(); sp[i] = 0.6f + rnd.nextFloat() * 1.6f; }
        }

        void stop() { running = false; }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            int w = getWidth(), h = getHeight();
            float u = Math.min(w, h) / 20f;
            long t = SystemClock.uptimeMillis();
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFF14143A, 0xFF05050C, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            for (int i = 0; i < sx.length; i++) {                                         // twinkling stars
                float tw = 0.5f + 0.5f * (float) Math.sin(t / 1000.0 * sp[i] * 2 + i);
                p.setColor(((int) (40 + 190 * tw) << 24) | 0xFFFFFF);
                c.drawCircle(sx[i] * w, sy[i] * h * 0.9f, u * (0.07f + 0.09f * tw), p);
            }
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            p.setColor(0xFFFFFFFF);
            p.setTypeface(bold);
            p.setTextSize(u * 2.5f);
            float bob = (float) Math.sin(t / 420.0) * u * 0.18f;
            c.drawText(hi, w / 2f, h * 0.36f + bob, p);
            p.setTypeface(reg);
            p.setColor(0xFFC9CCE8);
            p.setTextSize(u * 1.15f);
            c.drawText("Just getting things ready for you!", w / 2f, h * 0.36f + u * 2.4f, p);
            int[] col = { 0xFFFF6FA5, 0xFFFFC857, 0xFF6FE3FF };                           // three dots that hop in turn
            for (int i = 0; i < 3; i++) {
                float ph = (float) ((t / 520.0) - i * 0.28);
                float hop = Math.max(0f, (float) Math.sin((ph % 1.0 + 1.0) % 1.0 * Math.PI * 1.0));
                p.setColor(col[i]);
                c.drawCircle(w / 2f + (i - 1) * u * 2.2f, h * 0.68f - hop * u * 1.7f, u * (0.55f + 0.12f * hop), p);
            }
            if (running) postInvalidateDelayed(33);
        }
    }
}
