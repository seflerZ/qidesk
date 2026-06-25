package jackpal.androidterm.emulatorview;

import android.graphics.Canvas;
import android.graphics.Typeface;

/**
 * Bridge into the AAR's package-private rendering surface. Lives in the
 * emulatorview package so it can reach TermSession.getTranscriptScreen,
 * TermSession.getEmulator, TranscriptScreen.drawText, and the PaintRenderer
 * class — all of which are package-private and otherwise unreachable from
 * com.qihua.*.
 *
 * <p>One instance per TermSession. Caches the PaintRenderer so the heartbeat
 * doesn't reallocate every frame; call rebuild() to refresh after the font
 * size changes.
 */
public final class TermRenderHelper {

    /** Solarized base0 / base03 — same colors SshTerminalRenderer clears to. */
    private static final int FORE_COLOR = 0xFF839496;
    private static final int BACK_COLOR = 0xFF002B36;
    private static final ColorScheme SOLARIZED = new ColorScheme(FORE_COLOR, BACK_COLOR);

    private PaintRenderer renderer;
    private int rendererFontSize = -1;

    /** Cell width in pixels, valid after the first render(). */
    public float charWidth;

    /** Cell height in pixels, valid after the first render(). */
    public int charHeight;

    /**
     * Paint the active screen of {@code session} into {@code canvas}.
     * Assumes {@code session.initializeEmulator(cols, rows)} has already
     * been called (caller guarantees this — initializer + renderer wire it
     * up at startup).
     *
     * @param typeface the monospace font to render with. Pass
     *                 {@link TermFontFactory#load} from
     *                 SshConnectionInitializer in production; a default
     *                 {@code Typeface.MONOSPACE} is used if null.
     */
    public void render(TermSession session, Canvas canvas, int fontSizePx, int paddingPx, Typeface typeface) {
        TerminalEmulator emu = session.getEmulator();
        TranscriptScreen screen = session.getTranscriptScreen();
        if (emu == null || screen == null) {
            return;
        }
        if (renderer == null || rendererFontSize != fontSizePx) {
            renderer = new PaintRenderer(fontSizePx, SOLARIZED,
                    typeface != null ? typeface : Typeface.MONOSPACE);
            rendererFontSize = fontSizePx;
        }
        charWidth = renderer.getCharacterWidth();
        charHeight = renderer.getCharacterHeight();

        renderer.setReverseVideo(emu.getReverseVideo());

        int cols = computeCols(canvas, charWidth, paddingPx);
        int rows = computeRows(canvas, charHeight, paddingPx);
        int cy = emu.getCursorRow();
        int cx = emu.getCursorCol();
        boolean cursorVisible = emu.getShowCursor();

        float x = paddingPx;
        float y = paddingPx + charHeight; // baseline of first row
        for (int row = 0; row < rows; row++) {
            int cursorX = (cursorVisible && row == cy) ? cx : -1;
            screen.drawText(row, canvas, x, y, renderer, cursorX, -1, -1, "", 0);
            y += charHeight;
        }
    }

    /** Cells that fit horizontally in canvas, with equal padding on both sides. */
    public static int computeCols(Canvas canvas, float charWidth, int paddingPx) {
        if (charWidth <= 0) return 1;
        int inner = canvas.getWidth() - 2 * paddingPx;
        return Math.max(1, inner / (int) charWidth);
    }

    /** Cells that fit vertically in canvas, with equal padding on both sides. */
    public static int computeRows(Canvas canvas, int charHeight, int paddingPx) {
        if (charHeight <= 0) return 1;
        int inner = canvas.getHeight() - 2 * paddingPx;
        return Math.max(1, inner / charHeight);
    }

    /**
     * Probe the cell size of a given font without rendering anything.
     * Used by the renderer before its first paint, so it can call
     * TermSession.updateSize(cols, rows) with sensible values.
     *
     * @param typeface the monospace font that will be used to render.
     *                 Cell metrics depend on the font, so the probe must
     *                 use the same font that {@code render} will use.
     */
    public void probe(int fontSizePx, Typeface typeface) {
        if (renderer == null || rendererFontSize != fontSizePx) {
            renderer = new PaintRenderer(fontSizePx, SOLARIZED,
                    typeface != null ? typeface : Typeface.MONOSPACE);
            rendererFontSize = fontSizePx;
        }
        charWidth = renderer.getCharacterWidth();
        charHeight = renderer.getCharacterHeight();
    }
}
