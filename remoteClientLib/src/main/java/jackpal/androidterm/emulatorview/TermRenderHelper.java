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
     * TranscriptScreen reference the last time render() or
     * renderAndDetectScreenFlip() painted. Used to detect alt/main
     * buffer flips so callers can re-seed the mbitmap background
     * (otherwise TUI applications like vim would leave alt-buffer
     * residue on the mbitmap when they exit, making the next prompt
     * appear as a "second prompt" on top of stale content). The
     * reference is package-private to the emulatorview package,
     * which is why this detection lives here and the renderer side
     * uses the public {@link #renderAndDetectScreenFlip} wrapper.
     */
    private TranscriptScreen lastPaintedScreen;

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
        // Use the emulator's *current* screen (mScreen), not the session's
        // mTranscriptScreen. The latter is always the main buffer; the
        // former flips to the alt buffer when an application sends
        // CSI ? 47 h / ? 1047 h / ? 1049 h (vim, less, htop, ...). Without
        // this, TUI applications would render into the alt buffer but
        // SshTerminalRenderer would keep reading from the main buffer,
        // showing only the previous prompt and a blank painted region
        // for the TUI's rows.
        TranscriptScreen screen = emu.getScreen();
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

    /**
     * Like {@link #render}, but reports whether the emulator's current
     * screen reference differs from the marker the caller passes in.
     * This is how the SshTerminalRenderer detects a TUI application's
     * alt-screen enter/exit and re-seeds the mbitmap background; the
     * TranscriptScreen class is package-private and cannot be exposed
     * across package boundaries, so callers compare an integer marker
     * that we mutate when the underlying screen reference changes.
     *
     * @param marker   the caller's last-seen marker value (initial
     *                 call: 0; subsequent calls: previous return value)
     * @return the same marker if the screen reference is unchanged;
     *         a different value (marker + 1) if the reference changed
     *         since the previous call. Callers can re-seed the
     *         mbitmap background and call {@link #render} again when
     *         the returned value differs from the input.
     */
    public int renderAndDetectScreenFlip(TermSession session, Canvas canvas,
                                         int fontSizePx, int paddingPx, Typeface typeface,
                                         int marker) {
        TerminalEmulator emu = session.getEmulator();
        TranscriptScreen screen = emu != null ? emu.getScreen() : null;
        if (emu == null || screen == null) {
            return marker;
        }
        render(session, canvas, fontSizePx, paddingPx, typeface);
        if (lastPaintedScreen != screen) {
            lastPaintedScreen = screen;
            // Marker advances by 1 each time the screen reference
            // changes. Caller compares the returned marker against
            // the one it passed in to detect a flip.
            return marker + 1;
        }
        return marker;
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
