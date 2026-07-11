package com.qihua.bVNC.ssh.libvterm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;

import com.qihua.bVNC.R;

/**
 * Renders the libvterm state machine's grid to an Android Canvas.
 * Replaces {@code TermRenderHelper} (which drew the AAR's
 * {@code TranscriptScreen} via the AAR's {@code PaintRenderer}).
 *
 * <p>Does not use any AAR type — only {@link SshTermStateMachine}
 * (a Java wrapper over JNI to libvterm) and standard Android
 * graphics. The font + cell metrics are measured once in the
 * constructor; subsequent {@link #render} calls reuse the same
 * {@link TextPaint} and {@link Paint} instances.
 */
public final class VTermCanvasRenderer {
    /** Background paint colour — sourced from {@code ssh_terminal_bg}
     *  so the SSH terminal follows the AppCompat day/night theme. */
    private int bgColor;
    /** libvterm's VTERM_COLOR_IS_DEFAULT_FG → argb mapping. */
    private int defaultFg;
    /** libvterm's VTERM_COLOR_IS_DEFAULT_BG → argb mapping (matches bgColor by convention). */
    private int defaultBg;

    /**
     * Original (Solarized) default fg/bg, used to remap cells that
     * libvterm painted with raw RGB instead of the VTERM_COLOR_DEFAULT_FG
     * flag. libvterm 0.3.3 has no public cell-setter API, so when the
     * AppCompat day/night theme flips the cells the remote program
     * already painted stay in the old theme unless we translate at
     * the output: any cell whose raw fg/bg ARGB matches one of
     * {@code LEGACY_DEFAULT_FG} / {@code LEGACY_DEFAULT_BG} is treated
     * as a "default-flavoured" cell and painted with the new
     * {@code defaultFg} / {@code defaultBg} instead. Cells the
     * remote program explicitly painted with a non-default colour
     * (red, green, syntax highlight) pass through unchanged because
     * they don't match the legacy ARGB.
     */
    private static final int LEGACY_DEFAULT_FG = 0xFF839496; // Solarized base0
    private static final int LEGACY_DEFAULT_BG = 0xFF002B36; // Solarized base03

    private static final int ATTR_BOLD      = 1;
    private static final int ATTR_ITALIC    = 2;
    private static final int ATTR_UNDERLINE = 4;
    private static final int ATTR_REVERSE   = 8;
    private static final int ATTR_BLINK     = 16;
    private static final int ATTR_STRIKE    = 32;

    private final int fontSizePx;
    private final int paddingPx;
    private final Typeface typeface;
    private final TextPaint textPaint;
    private final Paint bgPaint;
    private final Paint underlinePaint;
    private final Paint cursorPaint;

    /** Cell width in pixels, valid after construction. */
    public final float charWidth;
    /** Cell height in pixels, valid after construction. */
    public final int charHeight;

    public VTermCanvasRenderer(int fontSizePx, int paddingPx, Typeface typeface, Context ctx) {
        this.fontSizePx = fontSizePx;
        this.paddingPx = paddingPx;
        this.typeface = typeface;

        this.textPaint = new TextPaint();
        textPaint.setAntiAlias(true);
        textPaint.setTypeface(typeface);
        textPaint.setTextSize(fontSizePx);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        this.charHeight = (int) Math.ceil(fm.descent - fm.ascent);
        float measured = textPaint.measureText("M");
        this.charWidth = (measured > 0f) ? measured : charHeight * 0.6f;

        this.bgPaint = new Paint();
        bgPaint.setStyle(Paint.Style.FILL);

        this.underlinePaint = new Paint();
        underlinePaint.setStyle(Paint.Style.STROKE);
        underlinePaint.setStrokeWidth(Math.max(1f, fontSizePx / 14f));

        this.cursorPaint = new Paint();
        cursorPaint.setStyle(Paint.Style.FILL);

        // Read the theme-driven defaults from resources. The SSH
        // initializer calls setPalette() again on theme flips so the
        // values-night/ variant takes over without restarting the
        // renderer.
        if (ctx != null) {
            this.bgColor = ctx.getResources().getColor(R.color.ssh_terminal_bg, ctx.getTheme());
            this.defaultFg = ctx.getResources().getColor(R.color.ssh_terminal_fg, ctx.getTheme());
            this.defaultBg = this.bgColor;
        } else {
            // Fallback for legacy call sites that pre-date the Context
            // argument (kept until callers are migrated). Solarized
            // hardcoded so the renderer doesn't crash if used bare.
            this.bgColor = 0xFF002B36;
            this.defaultFg = 0xFF839496;
            this.defaultBg = 0xFF002B36;
        }
    }

    /**
     * Update the renderer's bg / default-fg / default-bg from a Context.
     * Called from {@code SshConnectionInitializer} when the AppCompat
     * night mode flips (e.g. user changes the theme, or system day/
     * night flips on Android 10+). The {@link SshTerminalRenderer}
     * also rebuilds the mbitmap via {@code seedBackground} so the
     * freshly-coloured cells show on next paint.
     */
    public void setPalette(Context ctx) {
        if (ctx == null) return;
        int newBg = ctx.getResources().getColor(R.color.ssh_terminal_bg, ctx.getTheme());
        int newFg = ctx.getResources().getColor(R.color.ssh_terminal_fg, ctx.getTheme());
        android.util.Log.i("VTermCanvasRenderer",
                "setPalette: bg=0x" + Integer.toHexString(newBg)
                + " fg=0x" + Integer.toHexString(newFg)
                + " (old bg=0x" + Integer.toHexString(bgColor)
                + " fg=0x" + Integer.toHexString(defaultFg) + ")");
        this.bgColor = newBg;
        this.defaultFg = newFg;
        this.defaultBg = this.bgColor;
    }

    /**
     * Paint the current state of {@code sm} into {@code canvas}.
     * Iterates every cell, fills background where non-default, and
     * draws the character where non-empty. Cursor is painted last
     * (on top).
     */
    public void render(SshTermStateMachine sm, Canvas canvas) {
        int cols = sm.getCols();
        int rows = sm.getRows();

        // We deliberately do NOT clear the whole bitmap first. renderInto
        // runs on the SSH-Paint thread while DrawWorker reads the same
        // mbitmap on its own thread (UltraCompactBitmapDrawable.draw ->
        // canvas.drawBitmap(mbitmap)), with no synchronization between
        // them. A global drawColor(BG) followed by a 10-30 ms cell-by-cell
        // redraw lets DrawWorker snapshot a half-cleared (blank) frame —
        // that is the flicker on every keystroke (each echoed byte fires
        // sshUpdateRunnable -> renderInto). Instead, each cell paints its
        // own background rectangle first, so a concurrent read only ever
        // sees a mix of previous-frame and current-frame cells, both
        // fully rendered. Stale content in a now-empty cell is cleared
        // by that cell's own bg fill. The padding around the grid was
        // seeded BG by SshTerminalRenderer.seedBackground and is never
        // touched here, so it stays BG.
        for (int row = 0; row < rows; row++) {
            float cellTop = paddingPx + row * charHeight;
            float cellBottom = paddingPx + (row + 1) * charHeight;
            float baselineY = cellBottom - textPaint.getFontMetrics().descent;
            for (int col = 0; col < cols; col++) {
                SshTermStateMachine.TermCell cell = sm.getCell(row, col);
                paintCell(canvas, cell, col, cellTop, cellBottom, baselineY);
            }
        }

        // 3. Cursor — simple solid block at fg color
        SshTermStateMachine.CursorInfo cur = sm.getCursor();
        if (cur != null && cur.visible) {
            int safeCol = Math.max(0, Math.min(cur.col, cols - 1));
            int safeRow = Math.max(0, Math.min(cur.row, rows - 1));
            SshTermStateMachine.TermCell cursorCell = sm.getCell(safeRow, safeCol);
            // Match the same translation paintCell does, so the
            // cursor block flips palette with the rest of the grid
            // when the cell under it carries the legacy default
            // colour.
            int cursorColor = (cursorCell == null
                    || cursorCell.fg == defaultFg
                    || cursorCell.fg == LEGACY_DEFAULT_FG)
                    ? defaultFg : cursorCell.fg;
            cursorPaint.setColor(cursorColor);
            float cx = paddingPx + safeCol * charWidth;
            float cy = paddingPx + safeRow * charHeight;
            canvas.drawRect(cx, cy, cx + charWidth, cy + charHeight, cursorPaint);
        }
    }

    /**
     * Paint a single scrollback row onto {@code canvas}, starting at
     * {@code cellTop} (pixels, top of the row). Reads cells from
     * {@code sm.getScrollbackCell(rowIndex, col)} — rowIndex 0 is the
     * oldest row still in the ring.
     *
     * <p>Unlike {@link #render}, no cursor is drawn and no gap-cell
     * skip is needed: scrollback rows are pushed by libvterm as whole
     * rows (memcpy in jni_sb_pushline), so codepoint==-1 (second half
     * of a CJK double-width) cannot occur in the ring. The per-cell
     * bg fill + drawText logic is identical to {@link #render}; only
     * the cell source differs.
     */
    /**
     * Paint one cell: bg fill + glyph + underline. Used by
     * {@link #render}. The cell's column within the row is {@code col};
     * its glyph's left edge is {@code paddingPx + col * charWidth}.
     * Returns early on null (out-of-range row/col) and on gap cells
     * (codepoint == -1 = second column of a double-width CJK char).
     */
    private void paintCell(Canvas canvas, SshTermStateMachine.TermCell cell,
                           int col, float cellTop, float cellBottom, float baselineY) {
        if (cell == null) return;
        // Gap cell: second column of a double-width CJK char
        // (libvterm marks it chars[0]==0xFFFFFFFF, which becomes
        // jint -1). Its area is already covered by the primary
        // cell's bg fill + wide glyph; skip it so we don't
        // overdraw the wide glyph's right half.
        if (cell.codepoint == -1) return;

        float cellLeft = paddingPx + col * charWidth;
        float cellRight = cellLeft + charWidth * Math.max(1, cell.width);

        // Translate raw-RGB cells whose colour happens to match
        // the *original* (Solarized) default into "this is a default-
        // flavoured cell" — then the rest of the bg / glyph logic
        // maps them onto the current theme's defaultFg/defaultBg
        // without us having to teach libvterm 0.3.3 about theme
        // flips.
        int cellFg = (cell.fg == defaultFg || cell.fg == LEGACY_DEFAULT_FG)
                ? defaultFg : cell.fg;
        int cellBg = (cell.bg == defaultBg || cell.bg == LEGACY_DEFAULT_BG)
                ? defaultBg : cell.bg;
        android.util.Log.d("VTermCanvasRenderer",
                "paintCell: cell.fg=0x" + Integer.toHexString(cell.fg)
                + " cell.bg=0x" + Integer.toHexString(cell.bg)
                + " -> cellFg=0x" + Integer.toHexString(cellFg)
                + " cellBg=0x" + Integer.toHexString(cellBg)
                + " (defaultFg=0x" + Integer.toHexString(defaultFg)
                + " defaultBg=0x" + Integer.toHexString(defaultBg) + ")");

        // Always fill bg — clears stale content from the previous
        // frame without a global clear.
        int bgFill;
        if ((cell.attrs & ATTR_REVERSE) != 0) {
            // reverse video: swap fg/bg
            bgFill = (cellFg == defaultFg) ? defaultFg : cellFg;
        } else if (cellBg != defaultBg) {
            bgFill = cellBg;
        } else {
            bgFill = bgColor;
        }
        bgPaint.setColor(bgFill);
        canvas.drawRect(cellLeft, cellTop, cellRight, cellBottom, bgPaint);

        // Glyph
        if (cell.codepoint > 0) {
            int fgColor = (cell.attrs & ATTR_REVERSE) != 0
                    ? (cellBg == defaultBg ? defaultFg : cellBg)
                    : (cellFg == defaultFg ? defaultFg : cellFg);
            textPaint.setColor(fgColor);
            textPaint.setFakeBoldText((cell.attrs & ATTR_BOLD) != 0);
            textPaint.setTextSkewX((cell.attrs & ATTR_ITALIC) != 0 ? -0.25f : 0f);
            // Char-to-glyph: TextPaint.drawText takes a String, codepoint
            // may be surrogate-pair. Build a 1-char string.
            String str = new String(Character.toChars(cell.codepoint));
            canvas.drawText(str, cellLeft, baselineY, textPaint);
            textPaint.setFakeBoldText(false);
            textPaint.setTextSkewX(0f);
        }

        // Underline
        if ((cell.attrs & ATTR_UNDERLINE) != 0 && cell.codepoint > 0) {
            underlinePaint.setColor(textPaint.getColor());
            float underlineY = cellBottom - Math.max(1f, fontSizePx / 14f);
            canvas.drawLine(cellLeft, underlineY, cellRight, underlineY, underlinePaint);
        }
    }
}
