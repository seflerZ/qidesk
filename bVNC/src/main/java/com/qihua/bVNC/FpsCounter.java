package com.qihua.bVNC;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.List;

public class FpsCounter {
    private int fps = 0;
    private int avg = 0;
    private int max = 0;
    private int lst = 0;
    private long lastCountMs = 0;
    private long maxlatency = 0;
    private long avglatency = 0;
    private long maxlastMs = 0;
    private int qsize = 0;
    private int frameDropped = 0;
    private long frameDroppedMs = 0;

    Paint _textPaint;

    public FpsCounter() {
        _textPaint = new Paint();

        Typeface font = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        _textPaint.setTypeface(font);
        _textPaint.setTextSize(32);

        _textPaint.setColor(0xffffffff);
    }

    public synchronized void count() {
        fps += 1;
    }

    public void finish(long inFpsMs, int qsize) {
        long latency = System.currentTimeMillis() - inFpsMs;
        avglatency = (avglatency + latency)/ 2;
        if (latency > maxlatency) {
            maxlatency = latency;
        }

        if (System.currentTimeMillis() - maxlastMs > 5000) {
            maxlatency = 0;
            maxlastMs = System.currentTimeMillis();
        }

        if (System.currentTimeMillis() - frameDroppedMs > 10000) {
            frameDropped = 0;
            frameDroppedMs = System.currentTimeMillis();
        }

        this.qsize  = qsize;
    }

    public void drawFps(Canvas canvas) {
        char[] text = ("FPS:" + lst + ", AVG:" + avg).toCharArray();
        canvas.drawText(text, 0, text.length, 100f, 100f, _textPaint);

        char[] latText = ("DELAY: MAX-5=" + maxlatency + ", AVG=" + avglatency).toCharArray();
        canvas.drawText(latText, 0, latText.length, 100f, 140f, _textPaint);

        char[] qText = ("Q-SIZE:" + qsize + ", DROP-10:" + frameDropped).toCharArray();
        canvas.drawText(qText, 0, qText.length, 100f, 180f, _textPaint);

        reset();
    }

    public void reset() {
        if (System.currentTimeMillis() - lastCountMs > 1000) {
            if (fps > max) {
                max = fps;
            }

            avg = (avg + fps)/ 2;
            lst = fps;

            lastCountMs = System.currentTimeMillis();

            fps = 0;
        }
    }

    public synchronized void frameDrop() {
        frameDropped += 1;
    }
}