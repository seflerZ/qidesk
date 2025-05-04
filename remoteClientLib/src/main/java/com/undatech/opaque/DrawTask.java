package com.undatech.opaque;

public class DrawTask {
    private int x,y;
    private int width,height;
    private boolean countFps;
    private long inTimeMs;

    public DrawTask(int x, int y, int width, int height) {
        this(x, y, width, height, false);
    }

    public DrawTask(int x, int y, int width, int height, boolean countFps) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        this.countFps = countFps;
        inTimeMs = System.currentTimeMillis();
    }

    public long getInTimeMs() {
        return inTimeMs;
    }

    public boolean isCountFps() {
        return countFps;
    }
}
