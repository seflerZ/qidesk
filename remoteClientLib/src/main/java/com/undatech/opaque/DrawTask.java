package com.undatech.opaque;

import android.graphics.Bitmap;
import android.graphics.Rect;

public class DrawTask {
    private Rect dirtyRect;
    private boolean count;
    private long inTimeMs;
    public DrawTask(int x, int y, int width, int height) {
        this(x, y, width, height, false);
    }

    public DrawTask(int x, int y, int width, int height, boolean count) {
        this.dirtyRect = new Rect(x, y, x + width, y + height);

        this.count = count;
        inTimeMs = System.currentTimeMillis();
    }

    public long getInTimeMs() {
        return inTimeMs;
    }

    public boolean isCount() {
        return count;
    }

    public Rect getDirtyRect() {
        return dirtyRect;
    }
}
