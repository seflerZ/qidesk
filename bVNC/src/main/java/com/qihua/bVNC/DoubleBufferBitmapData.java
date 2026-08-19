package com.qihua.bVNC;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.undatech.opaque.RemoteConnectable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tear-free double-buffered bitmap data for protocols whose renderer
 * paints whole frames from a background thread (SSH/RDP/NVSTREAM/SPICE).
 * Writers paint into a private back buffer and atomically publish it
 * into the front slot; readers (DrawWorker) see only whole frames.
 *
 * <p>Independent of {@link UltraCompactBitmapData} /
 * {@link CompactBitmapData} / etc. — those classes keep their existing
 * incremental-update logic; this one is a separate strategy the SSH /
 * RDP path can opt into.
 */
public class DoubleBufferBitmapData extends AbstractBitmapData {
    private final int width;
    private final int height;
    private final AtomicReference<Bitmap> front = new AtomicReference<>();
    private Bitmap back;
    /** Cached Canvas wrapping back. Rebuilt when back is swapped. */
    private Canvas backCanvas;

    public DoubleBufferBitmapData(RemoteConnectable rfb, RemoteCanvas c,
                                   int w, int h, Bitmap.Config config) {
        super(rfb, c);
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("bad size " + w + "x" + h);
        this.width = w;
        this.height = h;
        this.bitmapwidth = w;
        this.bitmapheight = h;
        this.framebufferwidth = w;
        this.framebufferheight = h;
        // Two distinct buffers from the start — if back == front, painting
        // into back while DrawWorker reads front would tear (same Bitmap).
        this.back = Bitmap.createBitmap(w, h, config);
        this.front.set(Bitmap.createBitmap(w, h, config));
        this.mbitmap = front.get();
        drawable.startDrawing();
    }

    /** Paint onto back buffer then atomically publish as front. */
    public void paintAndPublish(Painter painter) {
        if (backCanvas == null) backCanvas = new Canvas(back);
        painter.draw(backCanvas);
        Bitmap newFront = back;
        Bitmap oldFront = front.getAndSet(newFront);
        back = oldFront != null && oldFront != newFront
                ? oldFront
                : Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        backCanvas = null;
        mbitmap = newFront;
    }

    public void eraseFront(int color) {
        front.get().eraseColor(color);
    }

    public Bitmap getFront() {
        return front.get();
    }

    public int getWidth()  { return width; }
    public int getHeight() { return height; }

    @Override
    public void frameBufferSizeChanged() {
        // Reuse painter's callsite for resize — caller must build a new
        // DoubleBufferBitmapData and reassign.
    }

    @Override
    protected void syncScroll() { }

    @Override
    protected void scrollChanged(int newx, int newy) { }

    @Override
    public boolean validDraw(int x, int y, int w, int h) {
        return true;
    }

    @Override
    public int offset(int x, int y) {
        return y * width + x;
    }

    @Override
    protected AbstractBitmapDrawable createDrawable() {
        return new Drawable(this);
    }

    @Override
    public void updateBitmap(int x, int y, int w, int h) { }
    @Override
    public void updateBitmap(Bitmap b, int x, int y, int w, int h) { }
    @Override
    public void copyRect(int sx, int sy, int dx, int dy, int w, int h) { }
    @Override
    protected void drawRect(int x, int y, int w, int h, Paint paint) { }

    @Override
    public void dispose() {
        Bitmap f = front.getAndSet(null);
        if (f != null && !f.isRecycled()) f.recycle();
        if (back != null && !back.isRecycled()) {
            back.recycle();
            back = null;
        }
    }

    public interface Painter {
        void draw(Canvas canvas);
    }

    static class Drawable extends AbstractBitmapDrawable {
        Drawable(DoubleBufferBitmapData data) {
            super(data);
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawBitmap(((DoubleBufferBitmapData) data).getFront(),
                    0, 0, _defaultPaint);
        }
    }
}