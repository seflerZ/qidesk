package com.qihua.bVNC.ssh;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Log;

import com.qihua.bVNC.Constants;

import java.io.IOException;

import jackpal.androidterm.emulatorview.TermRenderHelper;
import jackpal.androidterm.emulatorview.TermSession;
import jackpal.androidterm.emulatorview.UpdateCallback;

/**
 * Phase 1 SSH terminal renderer. Owns the TermSession + the fake-shell
 * loopback that feeds it. Each renderInto(Bitmap) pass:
 *
 *   1. Recomputes char cell metrics via TermRenderHelper.probe (cheap).
 *   2. If cols/rows changed, calls TermSession.updateSize.
 *   3. Clears the bitmap to Solarized base03.
 *   4. Delegates to TermRenderHelper.render to paint the grid.
 *
 * Lifecycle: caller creates -> open() -> renderInto* -> close().
 * close() finishes the TermSession (which joins its reader/writer threads)
 * and closes the loopback. Both must happen on fold/unfold so a stale
 * reader doesn't keep blocking on a dead pipe.
 */
public class SshTerminalRenderer {
    private static final String TAG = "SshTerminalRenderer";

    private static final int BG_COLOR = 0xFF002B36; // Solarized base03

    /** Inset on all four sides of the grid, in dp. */
    private static final float PADDING_DP = 8f;

    private final float density;
    private final TermRenderHelper helper = new TermRenderHelper();
    private final TermSession termSession = new TermSession();
    private final FakeShellLoopback loopback;

    private int currentCols = -1;
    private int currentRows = -1;
    private boolean closed;

    public SshTerminalRenderer(float density) throws IOException {
        this.density = density;
        this.loopback = new FakeShellLoopback();
        termSession.setTermIn(loopback.getTerminalIn());
        termSession.setTermOut(loopback.getTerminalOut());
    }

    /**
     * Start the TermSession + the fake-shell echo loop. Must be called
     * after construction and before renderInto. The {@code onUpdate}
     * callback fires whenever TermSession's screen mutates, so the caller
     * can request an immediate redraw instead of waiting for the next
     * heartbeat tick.
     *
     * @param initialPxW  initial bitmap width  (used to seed cols)
     * @param initialPxH  initial bitmap height (used to seed rows)
     * @param onUpdate    invoked on each TermSession screen change
     */
    public void open(int initialPxW, int initialPxH, Runnable onUpdate) {
        helper.probe(fontSizePx());
        int pad = paddingPx();
        int cols = TermRenderHelper.computeCols(emptyCanvasOf(initialPxW), helper.charWidth, pad);
        int rows = TermRenderHelper.computeRows(emptyCanvasOf(initialPxH), helper.charHeight, pad);
        currentCols = cols;
        currentRows = rows;
        termSession.updateSize(cols, rows); // also initializes the emulator
        if (onUpdate != null) {
            termSession.setUpdateCallback(new UpdateCallback() {
                @Override public void onUpdate() { onUpdate.run(); }
            });
        }
        loopback.start();
        Log.i(TAG, "open: " + cols + " cols x " + rows + " rows, charW="
                + helper.charWidth + " charH=" + helper.charHeight
                + " pad=" + pad + "px");
    }

    public void renderInto(Bitmap target) {
        if (closed || target == null || target.isRecycled()) return;
        Canvas c = new Canvas(target);
        c.drawColor(BG_COLOR);
        int pad = paddingPx();
        int cols = TermRenderHelper.computeCols(c, helper.charWidth, pad);
        int rows = TermRenderHelper.computeRows(c, helper.charHeight, pad);
        if (cols != currentCols || rows != currentRows) {
            currentCols = cols;
            currentRows = rows;
            termSession.updateSize(cols, rows);
        }
        helper.render(termSession, c, fontSizePx(), pad);
    }

    public TermSession getTermSession() {
        return termSession;
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            termSession.finish();
        } catch (Throwable t) {
            Log.w(TAG, "termSession.finish failed", t);
        }
        loopback.close();
    }

    private int fontSizePx() {
        return Math.max(1, Math.round(Constants.SSH_FONT_SIZE_DP * density));
    }

    private int paddingPx() {
        return Math.max(0, Math.round(PADDING_DP * density));
    }

    /**
     * Build a throwaway Canvas of the given width/height for computeCols/Rows
     * pre-paint. Avoids allocating a real Bitmap before we know the final
     * size.
     */
    private static Canvas emptyCanvasOf(int wOrH) {
        Canvas c = new Canvas();
        // Canvas dimensions are read from the backing bitmap; without one we
        // need to use setBitmap with a 1x1 bitmap of the right "size axis".
        Bitmap b = Bitmap.createBitmap(Math.max(1, wOrH), Math.max(1, wOrH), Bitmap.Config.ALPHA_8);
        c.setBitmap(b);
        return c;
    }

    @SuppressWarnings("unused")
    private static int unused = Color.BLACK; // keep Color import in case Phase 1.5 reuses for theming
}
