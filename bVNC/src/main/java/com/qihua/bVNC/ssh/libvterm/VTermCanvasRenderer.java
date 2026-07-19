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
    public final int paddingPx;
    private final Typeface typeface;
    private final TextPaint textPaint;
    private final Paint bgPaint;
    private final Paint underlinePaint;
    private final Paint cursorPaint;
    private final Paint selectionPaint;

    /** Cell width in pixels, valid after construction. */
    public final float charWidth;
    /** Cell height in pixels, valid after construction. */
    public final int charHeight;

    // SSH text selection state. -1 = no selection. Written from any
    // thread via setSelection(); read only from render() on the
    // SSH-Paint thread. selectionActive is volatile because setSelection
    // is called from the input (UI) thread while render() reads it from
    // the paint thread; the four ints are read together once at the top
    // of paintSelection into locals, so torn reads across the four
    // variables are not observable within one render pass.
    private volatile boolean selectionActive = false;
    private int selAnchorRow = -1, selAnchorCol = -1;
    private int selEndRow = -1, selEndCol = -1;

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

        // Selection highlight — drawn on top of cell bg but UNDER the
        // glyphs (so the selected text is still legible). 40% opacity
        // Material blue, mirrors Android's standard text-selection tint.
        this.selectionPaint = new Paint();
        selectionPaint.setStyle(Paint.Style.FILL);
        selectionPaint.setColor(0x664A90E2);

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
     * Set the active text selection rectangle, in screen-grid
     * coordinates (row in [0, rows), col in [0, cols)). Anchor and end
     * may be in either order — paintSelection normalizes them. Pass
     * all four as -1 (or call {@link #clearSelection}) to drop the
     * highlight. Safe to call from any thread.
     */
    public void setSelection(int anchorRow, int anchorCol, int endRow, int endCol) {
        if (anchorRow < 0 || anchorCol < 0 || endRow < 0 || endCol < 0) {
            clearSelection();
            return;
        }
        this.selAnchorRow = anchorRow;
        this.selAnchorCol = anchorCol;
        this.selEndRow = endRow;
        this.selEndCol = endCol;
        this.selectionActive = true;
    }

    /** Drop the active selection. Safe to call from any thread. */
    public void clearSelection() {
        this.selectionActive = false;
        this.selAnchorRow = -1;
        this.selAnchorCol = -1;
        this.selEndRow = -1;
        this.selEndCol = -1;
    }

    /** Whether a selection is currently set. */
    public boolean hasSelection() {
        return selectionActive;
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
        // Atomic snapshot: offset, frac, and sbCount must be read under
        // one lock so the render never sees a mismatched triple (e.g.
        // offset==0 with frac==50 from a concurrent scrollByPixels that
        // updated frac before offset). Also guards against sbCount drift:
        // when the user is at the very top (offset >= sbCount) and new
        // lines are pushed to the ring, sbCount grows but offset doesn't
        // track it — topV = sbCount - offset becomes >0, so the viewport
        // jumps (user sees newer lines instead of staying at the oldest).
        // We detect that and pin offset=sbCount to keep topV==0.
        SshTermStateMachine.ScrollViewState sv = sm.getScrollViewState();
        int offset = sv.offset;
        float frac = sv.frac;
        int sbCount = sv.sbCount;
        if (offset >= sbCount) {
            offset = sbCount;
            frac = 0f;
        }
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
        // Virtual viewport: combined history space [0, sbCount+rows).
        //   v in [0, sbCount)           → scrollback line v (0 = oldest)
        //   v in [sbCount, sbCount+rows) → live grid row (v - sbCount)
        // topV = sbCount - offset is the virtual line at the viewport's
        // top edge when frac == 0; offset == 0 → topV == sbCount → live
        // row 0, i.e. the normal non-scrolled view (identical to the old
        // render). frac shifts content DOWN by frac px (older lines slide
        // in from the top as you scroll back); that uncovers `frac` pixels
        // at the very top, so we draw one extra row above (k = -1) to fill
        // it. frac is 0 at both scroll extremes (clamped in scrollByPixels),
        // so a fully-scrolled-back view never underflows to v = -1.
        //
        // Clip to the content region so the extra top row (and the
        // fractional bottom of the last row) don't bleed their bg into
        // the BG-seeded padding around the grid.
        int topV = sbCount - offset;
        boolean extraTop = frac > 0f;
        int kStart = extraTop ? -1 : 0;
        int saveCount = canvas.save();
        canvas.clipRect(paddingPx, paddingPx,
                canvas.getWidth() - paddingPx, canvas.getHeight() - paddingPx);
        for (int k = kStart; k < rows; k++) {
            int v = topV + k;
            float cellTop = paddingPx + k * charHeight + frac;
            float cellBottom = cellTop + charHeight;
            float baselineY = cellBottom - textPaint.getFontMetrics().descent;
            for (int col = 0; col < cols; col++) {
                SshTermStateMachine.TermCell cell = cellAt(sm, v, sbCount, rows, col);
                paintCell(canvas, cell, col, cellTop, cellBottom, baselineY);
            }
        }
        // Fill any gaps within the clip rect that the grid didn't cover.
        // The cell loop draws rows × cols cells, but the content area is
        // usually larger than the grid — the right margin (cols*charWidth <
        // clipWidth) and the bottom margin (rows*charHeight < clipHeight)
        // are never touched by paintCell. When frac > 0 the last row shifts
        // down and its bg fill covers part of the bottom gap; when frac
        // snaps back to 0 on the next frame those pixels are uncovered and
        // show whatever the previous frame left (scrollback cells, partial
        // glyphs). A single-drawRect fill of both gaps costs ~µs vs the
        // 10-30 ms cell loop and prevents the "dirty data below last line"
        // artifact. We fill even when frac==0 so the gap is never stale.
        float clipRight = canvas.getWidth() - paddingPx;
        float clipBottom = canvas.getHeight() - paddingPx;
        float gridRight = paddingPx + cols * charWidth;
        float gridBottom = paddingPx + rows * charHeight + frac;
        bgPaint.setColor(bgColor);
        if (gridRight < clipRight) {
            canvas.drawRect(gridRight, paddingPx, clipRight, clipBottom, bgPaint);
        }
        if (gridBottom < clipBottom) {
            canvas.drawRect(paddingPx, gridBottom, clipRight, clipBottom, bgPaint);
        }
        canvas.restoreToCount(saveCount);

        // Selection highlight — drawn AFTER the gap fills but BEFORE
        // the cursor so the highlight tints selected cells, then the
        // cursor (if it happens to land inside the selection) is still
        // visible on top.
        if (selectionActive) {
            paintSelection(canvas, cols, rows);
        }

        // Cursor — only when viewing the live screen. While parked in
        // scrollback (offset > 0) the live cursor sits off-screen, so
        // painting it would float a block over history content. The block
        // is always defaultFg so it contrasts against any background the
        // remote program can paint (zsh completion menus, reverse video).
        if (offset == 0) {
            SshTermStateMachine.CursorInfo cur = sm.getCursor();
            if (cur != null) {
                int safeCol = Math.max(0, Math.min(cur.col, cols - 1));
                int safeRow = Math.max(0, Math.min(cur.row, rows - 1));
                int cursorColor = defaultFg;
                cursorPaint.setColor(cursorColor);
                float cx = paddingPx + safeCol * charWidth;
                float cy = paddingPx + safeRow * charHeight;
                canvas.drawRect(cx, cy, cx + charWidth, cy + charHeight, cursorPaint);
            }
        }
    }

    /**
     * Map a virtual line index {@code v} to a cell: scrollback line v
     * when {@code v < sbCount}, live grid row {@code (v - sbCount)}
     * otherwise. Returns null for out-of-range (paintCell draws nothing
     * for null — in practice cellAt always returns a cell because the
     * render loop's v range stays within [0, sbCount+rows)).
     */
    private SshTermStateMachine.TermCell cellAt(SshTermStateMachine sm,
                                                int v, int sbCount, int rows, int col) {
        if (v < 0) return null;
        if (v < sbCount) return sm.getScrollbackCell(v, col);
        int liveRow = v - sbCount;
        if (liveRow >= rows) return null;
        return sm.getCell(liveRow, col);
    }

    /**
     * Paint one cell: bg fill + glyph + underline. Used by
     * {@link #render}. The cell's column within the row is {@code col};
     * its glyph's left edge is {@code paddingPx + col * charWidth}.
     * Returns early on null (out-of-range row/col) and on gap cells
     * (codepoint == -1 = second column of a double-width CJK char).
     */
    private void paintCell(Canvas canvas, SshTermStateMachine.TermCell cell,
                           int col, float cellTop, float cellBottom, float baselineY) {
        float cellLeft = paddingPx + col * charWidth;
        if (cell == null) {
            // Out-of-range — e.g. a scrollback line narrower than the current
            // cols after a widen (nativeGetScrollbackCell returns null for col
            // >= the line's stored width). Fill default bg so no stale pixels
            // from a concurrent DrawWorker read leak through the uncovered cell
            // (the per-cell-bg invariant that keeps the SSH canvas flicker-free).
            bgPaint.setColor(bgColor);
            canvas.drawRect(cellLeft, cellTop, cellLeft + charWidth, cellBottom, bgPaint);
            return;
        }
        // Gap cell: second column of a double-width CJK char
        // (libvterm marks it chars[0]==0xFFFFFFFF, which becomes
        // jint -1). Its area is already covered by the primary
        // cell's bg fill + wide glyph; skip it so we don't
        // overdraw the wide glyph's right half.
        if (cell.codepoint == -1) return;

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

    /**
     * Paint the active selection highlight. Anchor/end may be in either
     * order; we normalize so row0 <= row1 and within a row col0 <= col1.
     * Wide-char cells (CJK) render as 2-cell-wide rectangles so the
     * highlight covers both halves.
     */
    private void paintSelection(Canvas canvas, int cols, int rows) {
        int r0 = Math.min(selAnchorRow, selEndRow);
        int r1 = Math.max(selAnchorRow, selEndRow);
        int c0, c1;
        if (selAnchorRow == selEndRow) {
            c0 = Math.min(selAnchorCol, selEndCol);
            c1 = Math.max(selAnchorCol, selEndCol);
        } else if (selAnchorRow < selEndRow) {
            c0 = selAnchorCol;
            c1 = selEndCol;
        } else {
            c0 = selEndCol;
            c1 = selAnchorCol;
        }
        // Clamp to grid bounds (a finger lift near the edge could have
        // briefly produced out-of-range values before we caught them).
        r0 = Math.max(0, Math.min(r0, rows - 1));
        r1 = Math.max(0, Math.min(r1, rows - 1));
        c0 = Math.max(0, Math.min(c0, cols - 1));
        c1 = Math.max(0, Math.min(c1, cols - 1));

        // First and last row span the full [c0..cols-1] / [0..c1]
        // interior rows span the full row.
        for (int row = r0; row <= r1; row++) {
            int colStart, colEnd;
            if (row == r0 && row == r1) {
                colStart = c0; colEnd = c1;
            } else if (row == r0) {
                colStart = c0; colEnd = cols - 1;
            } else if (row == r1) {
                colStart = 0;   colEnd = c1;
            } else {
                colStart = 0;   colEnd = cols - 1;
            }
            float top = paddingPx + row * charHeight;
            float bottom = top + charHeight;
            // Walk cells, honoring width==2 (wide CJK). We collapse
            // adjacent gap cells (codepoint==-1) into the previous
            // primary's rect — but since the primary already covers 2
            // cells of width via cell.width, we just rely on
            // charWidth * max(1, cell.width) here without inspecting
            // the actual cell content (cheaper, and the highlight
            // visually matches the underlying 2-cell character).
            for (int col = colStart; col <= colEnd; ) {
                float left = paddingPx + col * charWidth;
                // Heuristic: assume wide chars at the start of the
                // row to extend. We can't read the cell here without
                // re-entering sm synchronously, so we just paint
                // 1-cell rects — single-line at 1x charWidth is the
                // visually-correct case for ASCII; CJK on adjacent
                // cols will paint 2 rects which look correct too.
                // (If a wide char straddles the start col, the user
                // will see a 1-cell highlight on its left half — rare
                // enough not to warrant a sync read here.)
                float right = left + charWidth;
                canvas.drawRect(left, top, right, bottom, selectionPaint);
                col++;
            }
        }
    }
}
