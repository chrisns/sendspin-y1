package me.cns.sendspin.android;

import android.content.Context;
import android.os.SystemClock;

/** "Hi Freya! Just getting things ready for you!": bobbing dots and twinkling stars, drawn by hand (no media needed). */
final class Greeting extends android.view.View {
    private final String hi;
    private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Typeface bold, reg;
    private final float[] sx = new float[28], sy = new float[28], sp = new float[28];
    private boolean running = true;

    Greeting(Context c, String name) {
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

    public void stop() { running = false; }

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
