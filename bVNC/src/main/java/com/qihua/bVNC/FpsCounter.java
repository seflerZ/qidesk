package com.qihua.bVNC;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

public class FpsCounter {
    private int fps = 0;
    private int avg = 0;
    private int max = 0;
    private int lst = 0;
    private int inputFps = 0;
    private long lastCountMs = 0;
    private int inputCount = 0;
    private long maxlatency = 0;
    private long avglatency = 0;
    private long maxlastMs = 0;
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
    public synchronized void countInput() {
        inputCount += 1;
    }

    public long getLastCountMs() {
        return lastCountMs;
    }

    public void finish(long inFpsMs) {
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

        if (System.currentTimeMillis() - lastCountMs > 1000) {
            if (fps > max) {
                max = fps;
            }

            avg = (avg + fps)/ 2;
            lst = fps;
            inputFps = inputCount;

            lastCountMs = System.currentTimeMillis();

            fps = 0;
            inputCount = 0;
            frameDropped = 0;
        }
    }

    public void drawFps(Canvas canvas) {
        char[] text = ("FPS-DRAW:" + lst + ", AVG:" + avg +", DROP:" + frameDropped).toCharArray();
        canvas.drawText(text, 0, text.length, 100f, 100f, _textPaint);

        char[] latText = ("DRAW COST: MAX-5:" + maxlatency + ", AVG:" + avglatency).toCharArray();
        canvas.drawText(latText, 0, latText.length, 100f, 140f, _textPaint);

        char[] inputText = ("INPUT-FREQ: " + inputFps).toCharArray();
        canvas.drawText(inputText, 0, inputText.length, 100f, 180f, _textPaint);
    }

    public synchronized void frameDrop() {
        frameDropped += 1;
    }

    public void drawDebugMsg(Canvas canvas, String debugMsg) {
        if (debugMsg == null) {
            return;
        }

        String[] lines = debugMsg.split("\n");
        for (int i = 0; i < lines.length; i++) {
            char[] text = lines[i].toCharArray();
            canvas.drawText(text, 0, text.length, 100f, 240f + i * 40, _textPaint);
        }
    }
}