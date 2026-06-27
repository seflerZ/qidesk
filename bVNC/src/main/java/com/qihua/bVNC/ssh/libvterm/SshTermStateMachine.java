package com.qihua.bVNC.ssh.libvterm;

import android.util.Log;

/**
 * Java-side handle to a libvterm state machine. Holds a native
 * {@code VTerm*} + {@code VTermScreen*} pointer pair, exposes
 * methods that the renderer + keyboard use to feed input and read
 * the current grid.
 *
 * <p>Threading model: libvterm itself is not thread-safe. All
 * calls into native (write / setSize / getCell / etc.) must
 * happen on a single thread — we use the SSH-Paint HandlerThread
 * for paint-driven reads, and the SSH-Connect background thread
 * for input writes. Both touch different methods on different
 * schedules, but if you ever need them on the same thread, use
 * a mutex.
 *
 * <p>Damage tracking: libvterm's
 * {@code VTermScreenCallbacks.damage} fires every time a cell
 * becomes dirty. We record the dirty row in a fixed-size ring
 * buffer (the native handle has 256 slots). Java polls via
 * {@link #pollDirty()} / {@link #takeDirtyRows(int[])}.
 */
public final class SshTermStateMachine {
    private static final String TAG = "SshTermStateMachine";

    static {
        try {
            System.loadLibrary("vterm");
            System.loadLibrary("vterm_jni");
            Log.i(TAG, "libvterm + vterm_jni loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "native lib load failed: " + e.getMessage());
            throw e;
        }
    }

    private long nativeHandle;

    public SshTermStateMachine(int cols, int rows) {
        if (cols < 1 || rows < 1) {
            throw new IllegalArgumentException("cols/rows must be >= 1, got " + cols + "x" + rows);
        }
        nativeHandle = nativeCreate(cols, rows);
        if (nativeHandle == 0) {
            throw new OutOfMemoryError("vterm_create failed for " + cols + "x" + rows);
        }
    }

    public void setSize(int cols, int rows) {
        if (cols < 1 || rows < 1) {
            throw new IllegalArgumentException("cols/rows must be >= 1, got " + cols + "x" + rows);
        }
        nativeSetSize(nativeHandle, cols, rows);
    }

    public int getCols() {
        return nativeGetCols(nativeHandle);
    }

    public int getRows() {
        return nativeGetRows(nativeHandle);
    }

    /** Feed SSH bytes into the terminal state machine. */
    public void write(byte[] data, int offset, int len) {
        if (len <= 0) return;
        nativeWrite(nativeHandle, data, offset, len);
    }

    /** Feed a single Unicode codepoint as if the user typed it. */
    public void writeInput(int codepoint) {
        nativeWriteInput(nativeHandle, codepoint);
    }

    /** True if any row is dirty since the last {@link #takeDirtyRows}. */
    public boolean pollDirty() {
        return nativePollDirty(nativeHandle);
    }

    /**
     * Drain all dirty rows into {@code outRows}. Returns the number
     * of rows written. After this call {@link #pollDirty()} returns
     * false until libvterm reports new damage.
     */
    public int takeDirtyRows(int[] outRows) {
        return nativeTakeDirtyRows(nativeHandle, outRows);
    }

    /** Read the cell at (row, col). */
    public TermCell getCell(int row, int col) {
        return nativeGetCell(nativeHandle, row, col);
    }

    /** Read cursor position + visibility. */
    public CursorInfo getCursor() {
        return nativeGetCursor(nativeHandle);
    }

    public void destroy() {
        if (nativeHandle != 0) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (nativeHandle != 0) {
                Log.w(TAG, "finalize: native handle leaked, destroying");
                destroy();
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * One grid cell. Fields:
     * <ul>
     *   <li>{@code codepoint} — 0 means empty cell, otherwise the
     *       Unicode codepoint painted there.
     *   <li>{@code width} — 0/1/2. libvterm's reported width; 2 means
     *       a double-width CJK character (occupies two columns).
     *   <li>{@code fg} / {@code bg} — packed 0xRRGGBB (alpha
     *       implicit 0xFF). Default fg/bg are Solarized base0/base03
     *       if the cell has no explicit color.
     *   <li>{@code attrs} — bitmask: bit 0 = bold, bit 1 = italic,
     *       bit 2 = underline, bit 3 = reverse, bit 4 = blink.
     * </ul>
     */
    public static final class TermCell {
        public int codepoint;
        public int width;
        public int fg;
        public int bg;
        public int attrs;
    }

    public static final class CursorInfo {
        public int row;
        public int col;
        public boolean visible;
    }

    private static native long  nativeCreate(int cols, int rows);
    private static native void  nativeSetSize(long h, int cols, int rows);
    private static native int   nativeGetCols(long h);
    private static native int   nativeGetRows(long h);
    private static native void  nativeWrite(long h, byte[] data, int off, int len);
    private static native void  nativeWriteInput(long h, int codepoint);
    private static native boolean nativePollDirty(long h);
    private static native int   nativeTakeDirtyRows(long h, int[] outRows);
    private static native TermCell  nativeGetCell(long h, int row, int col);
    private static native CursorInfo nativeGetCursor(long h);
    private static native void  nativeDestroy(long h);
}
