package com.undatech.opaque;

import android.graphics.Rect;

public class DrawTask {
    private Rect dirtyRect;
    private boolean countFps;
    private long inTimeMs;

    public DrawTask(int x, int y, int width, int height) {
        this(x, y, width, height, false);
    }

    public DrawTask(int x, int y, int width, int height, boolean countFps) {
        this.dirtyRect = new Rect(x, y, x + width, y + height);

        this.countFps = countFps;
        inTimeMs = System.currentTimeMillis();
    }

    public long getInTimeMs() {
        return inTimeMs;
    }

    public boolean isCountFps() {
        return countFps;
    }

    public Rect getDirtyRect() {
        return dirtyRect;
    }
}
