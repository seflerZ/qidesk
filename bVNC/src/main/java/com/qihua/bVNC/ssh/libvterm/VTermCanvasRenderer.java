package com.qihua.bVNC.ssh.libvterm;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;

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
    /** Solarized base03 — matches SshTerminalRenderer's BG_COLOR. */
    private static final int BG_COLOR = 0xFF002B36;
    /** Solarized base0 — default foreground. */
    private static final int DEFAULT_FG = 0xFF839496;
    private static final int DEFAULT_BG = 0xFF002B36;

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

    public VTermCanvasRenderer(int fontSizePx, int paddingPx, Typeface typeface) {
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
                if (cell == null) continue;
                // Gap cell: second column of a double-width CJK char
                // (libvterm marks it chars[0]==0xFFFFFFFF, which becomes
                // jint -1). Its area is already covered by the primary
                // cell's bg fill + wide glyph; skip it so we don't
                // overdraw the wide glyph's right half.
                if (cell.codepoint == -1) continue;

                float cellLeft = paddingPx + col * charWidth;
                float cellRight = cellLeft + charWidth * Math.max(1, cell.width);

                // Always fill bg — clears stale content from the previous
                // frame without a global clear.
                int bgFill;
                if ((cell.attrs & ATTR_REVERSE) != 0) {
                    // reverse video: swap fg/bg
                    bgFill = (cell.fg == DEFAULT_FG) ? DEFAULT_FG : cell.fg;
                } else if (cell.bg != DEFAULT_BG) {
                    bgFill = cell.bg;
                } else {
                    bgFill = BG_COLOR;
                }
                bgPaint.setColor(bgFill);
                canvas.drawRect(cellLeft, cellTop, cellRight, cellBottom, bgPaint);

                // Glyph
                if (cell.codepoint > 0) {
                    int fgColor = (cell.attrs & ATTR_REVERSE) != 0
                            ? (cell.bg == DEFAULT_BG ? DEFAULT_FG : cell.bg)
                            : (cell.fg == DEFAULT_FG ? DEFAULT_FG : cell.fg);
                    textPaint.setColor(fgColor);
                    textPaint.setFakeBoldText((cell.attrs & ATTR_BOLD) != 0);
                    textPaint.setTextSkewX((cell.attrs & ATTR_ITALIC) != 0 ? -0.25f : 0f);
                    // Char-to-glyph: TextPaint.drawText takes a String, codepoint
                    // may be surrogate-pair. Build a 1-char string.
                    String s = new String(Character.toChars(cell.codepoint));
                    canvas.drawText(s, cellLeft, baselineY, textPaint);
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

        // 3. Cursor — simple solid block at fg color
        SshTermStateMachine.CursorInfo cur = sm.getCursor();
        if (cur != null && cur.visible) {
            int safeCol = Math.max(0, Math.min(cur.col, cols - 1));
            int safeRow = Math.max(0, Math.min(cur.row, rows - 1));
            SshTermStateMachine.TermCell cursorCell = sm.getCell(safeRow, safeCol);
            int cursorColor = cursorCell != null && cursorCell.fg != DEFAULT_FG
                    ? cursorCell.fg : DEFAULT_FG;
            cursorPaint.setColor(cursorColor);
            float cx = paddingPx + safeCol * charWidth;
            float cy = paddingPx + safeRow * charHeight;
            canvas.drawRect(cx, cy, cx + charWidth, cy + charHeight, cursorPaint);
        }
    }
}
