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
 * SSH terminal renderer. Owns the TermSession + a transport channel
 * (Phase 1: a local fake-shell loopback; Phase 2: a real trilead-backed
 * {@link SshShellChannel}). Each renderInto(Bitmap) pass:
 *
 *   1. Recomputes char cell metrics via TermRenderHelper.probe (cheap).
 *   2. If cols/rows changed, calls TermSession.updateSize.
 *   3. Clears the bitmap to Solarized base03.
 *   4. Delegates to TermRenderHelper.render to paint the grid.
 *
 * Lifecycle: caller creates -> open() -> renderInto* -> close().
 * close() finishes the TermSession (which joins its reader/writer threads)
 * and closes the channel. Both must happen on fold/unfold so a stale
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
    private final SshShellChannel channel;

    private int currentCols = -1;
    private int currentRows = -1;
    private boolean open;
    private boolean closed;
    /** Notified when the terminal grid size changes (fold/unfold/etc). */
    private GridSizeListener gridSizeListener;

    /**
     * @param density device density, used for font-size and padding.
     * @param channel transport between TermSession and the shell. Phase 2
     *                uses {@link SshShellChannel}; the interface matches
     *                the Phase 1 fake-shell so this class doesn't need
     *                to know which one it got.
     */
    public SshTerminalRenderer(float density, SshShellChannel channel) throws IOException {
        this.density = density;
        this.channel = channel;
        termSession.setTermIn(channel.getTerminalIn());
        termSession.setTermOut(channel.getTerminalOut());
    }

    /**
     * Initialise the target bitmap with our background colour so empty
     * cells (which {@code TranscriptScreen.drawText} does NOT paint)
     * start out the right colour instead of the default transparent
     * black that {@code Bitmap.createBitmap} yields. Called once per
     * bitmap allocation by {@code SshConnectionInitializer.openRenderer}.
     */
    public void seedBackground(Bitmap target) {
        if (target == null || target.isRecycled()) return;
        target.eraseColor(BG_COLOR);
    }

    /** Notified when {@link #renderInto} detects cols/rows changed. */
    public void setGridSizeListener(GridSizeListener listener) {
        this.gridSizeListener = listener;
    }

    /** Callback for grid-size changes (e.g. PTY resize over SSH). */
    public interface GridSizeListener {
        void onGridSizeChanged(int cols, int rows);
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
        // UTF-8 on from boot. TermSession's mDefaultUTF8Mode defaults to
        // false, so the emulator interprets input as Latin-1. With UTF-8
        // off, write(0x4F60) for 你 writes the bytes [E4 BD A0] which
        // display as 3 separate Latin-1 glyphs. Setting this before
        // updateSize() so initializeEmulator() picks it up.
        termSession.setDefaultUTF8Mode(true);
        termSession.updateSize(cols, rows); // also initializes the emulator
        if (onUpdate != null) {
            termSession.setUpdateCallback(new UpdateCallback() {
                @Override public void onUpdate() { onUpdate.run(); }
            });
        }
        channel.start();
        open = true;
        Log.i(TAG, "open: " + cols + " cols x " + rows + " rows, charW="
                + helper.charWidth + " charH=" + helper.charHeight
                + " pad=" + pad + "px");
    }

    public void renderInto(Bitmap target) {
        if (closed || target == null || target.isRecycled()) return;
        Canvas c = new Canvas(target);
        // Do NOT clear here — TranscriptScreen.drawText paints BG_COLOR
        // for every occupied cell (and PaintRenderer.drawTextRun handles
        // the foreground glyph). The bitmap itself was seeded with
        // BG_COLOR via seedBackground() the first time it was allocated,
        // so empty cells stay blue. Clearing each paint would force a
        // full-bitmap pass that masks the natural cursor blink and
        // contributes to flicker.
        int pad = paddingPx();
        int cols = TermRenderHelper.computeCols(c, helper.charWidth, pad);
        int rows = TermRenderHelper.computeRows(c, helper.charHeight, pad);
        if (cols != currentCols || rows != currentRows) {
            currentCols = cols;
            currentRows = rows;
            termSession.updateSize(cols, rows);
            if (gridSizeListener != null) {
                gridSizeListener.onGridSizeChanged(cols, rows);
            }
        }
        helper.render(termSession, c, fontSizePx(), pad);
    }

    public TermSession getTermSession() {
        return termSession;
    }

    public void close() {
        if (closed) return;
        closed = true;
        open = false;
        Log.w(TAG, "close: finishing TermSession (stack=" + new Throwable().getStackTrace()[1] + ")");
        try {
            termSession.finish();
        } catch (Throwable t) {
            Log.w(TAG, "termSession.finish failed", t);
        }
        channel.close();
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
