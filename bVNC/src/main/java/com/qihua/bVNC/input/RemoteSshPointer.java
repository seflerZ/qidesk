package com.qihua.bVNC.input;

import android.os.Handler;

import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.ssh.SshTerminalRenderer;
import com.qihua.bVNC.ssh.libvterm.SshTermStateMachine;
import com.undatech.opaque.RemoteConnectable;

/**
 * Phase 0: no-op pointer. SSH has no mouse cursor to drive; this exists
 * only so the InputHandler* chain (which always reads pointer.getX/getY)
 * doesn't NPE.
 *
 * Phase 2 will repurpose this for text selection / clipboard drag.
 */
public class RemoteSshPointer extends RemotePointer {

    public RemoteSshPointer(RemoteConnectable protocomm, RemoteCanvas canvas, Handler handler, boolean debugLogging) {
        super(protocomm, canvas, handler, debugLogging);
    }

    /** Phase 0: swap the underlying RfbConnectable after a fold/unfold. */
    public void setProtocomm(RemoteConnectable protocomm) {
        this.protocomm = protocomm;
    }

    // --- Scrollback (Phase 3.8) ---
    // RemoteSshPointer is the convergence point for ALL scroll input
    // (mouse wheel via InputHandlerGeneric.ACTION_SCROLL → sendScrollEvents,
    // touchpad two-finger via InputHandlerTouchpad.onScroll → doScroll →
    // sendScrollEvents — both call pointer.scrollUp/Down). We decode the
    // per-source `speed` into a signed pixel delta, feed it to the state
    // machine's view offset, and request a coalesced repaint. The state
    // machine + renderer do the actual history rendering.
    private static final int MOUSE_WHEEL_LINES = 3;

    /**
     * Touchpad scroll sensitivity. doScroll's {@code newY = distanceY*density/2}
     * yields small per-sample magnitudes — a gentle swipe gives mag ~5-10, and
     * with charHeight ~53 that never accumulates to a whole row, so the user
     * sees only a sub-row jitter and no history scrolls into view. ×3 makes a
     * gentle swipe commit ~1 row and a flick scroll several. Tunable: raise
     * for faster, lower for finer control.
     */
    private static final float TOUCH_SCROLL_SCALE = 3.0f;

    private SshTermStateMachine termMachine;
    private Runnable requestRepaint;
    // Back-reference to the renderer so px↔cell conversion works from
    // here. Wired by SshConnectionInitializer after construction
    // (alongside setScrollback).
    private SshTerminalRenderer sshRenderer;

    // --- SSH text selection (Phase 2) ---
    // Selection rectangle in SCREEN-grid coordinates (row in [0, rows),
    // col in [0, cols)). -1 = no selection. Guarded by the same
    // requestRepaint hook so updates from the input thread trigger a
    // paint on the SSH-Paint thread.
    private int selAnchorRow = -1, selAnchorCol = -1;
    private int selEndRow = -1, selEndCol = -1;
    // Cached selected text snapshot — rebuilt on every extend, returned
    // + cleared by consumeSelectedText() at ACTION_UP time so the popup
    // menu can show it without re-walking the grid.
    private String pendingSelectedText = "";
    // Last-known viewport snapshot for text extraction. Cached on
    // extend so ACTION_UP can read it without re-acquiring the lock
    // (and racing with a concurrent repaint).
    private int cachedRows = -1, cachedCols = -1, cachedSbCount = -1;

    /** Wire the scrollback state machine + a coalesced-repaint hook. */
    public void setScrollback(SshTermStateMachine termMachine, Runnable requestRepaint) {
        this.termMachine = termMachine;
        this.requestRepaint = requestRepaint;
    }

    /** Wire the renderer reference for px↔cell conversion. Idempotent. */
    public void setRenderer(SshTerminalRenderer renderer) {
        this.sshRenderer = renderer;
    }

    @Override
    public void leftButtonDown(int x, int y, int metaState) {
    }

    @Override
    public void middleButtonDown(int x, int y, int metaState) {
    }

    @Override
    public void rightButtonDown(int x, int y, int metaState) {
    }

    @Override
    public void scrollUp(int x, int y, int speed, int metaState) {
        if (termMachine == null) return;
        if (speed == -1) {
            termMachine.scrollByLines(+MOUSE_WHEEL_LINES);
        } else {
            termMachine.scrollByPixels(+decodeTouchMagnitude(speed, true));
        }
        if (requestRepaint != null) requestRepaint.run();
    }

    // ===== SSH text-selection API (Phase 2) =====

    /**
     * Begin a selection at bitmap-pixel coordinates (the same
     * coordinate space as MotionEvent.x/y in the touchpad view, after
     * zoom inversion by getDragPointerX/Y in the caller). Anchor and
     * end both set to this point — the user then drags to extend.
     */
    public void enterSelectionPx(float pxX, float pxY) {
        int[] cell = pxToCell(pxX, pxY);
        if (cell == null) return;
        selAnchorRow = selEndRow = cell[0];
        selAnchorCol = selEndCol = cell[1];
        pendingSelectedText = "";
        cachedRows = cachedCols = cachedSbCount = -1;
        if (sshRenderer != null) sshRenderer.setSelection(cell[0], cell[1], cell[0], cell[1]);
        if (requestRepaint != null) requestRepaint.run();
    }

    /**
     * Move the selection end to bitmap-pixel coordinates. Rebuilds the
     * cached text snapshot and requests a repaint.
     */
    public void extendSelectionPx(float pxX, float pxY) {
        int[] cell = pxToCell(pxX, pxY);
        if (cell == null) return;
        if (selAnchorRow < 0) {
            selAnchorRow = selEndRow = cell[0];
            selAnchorCol = selEndCol = cell[1];
            pendingSelectedText = "";
            cachedRows = cachedCols = cachedSbCount = -1;
        } else {
            selEndRow = cell[0];
            selEndCol = cell[1];
        }
        rebuildSelectedText();
        if (sshRenderer != null) sshRenderer.setSelection(selAnchorRow, selAnchorCol, selEndRow, selEndCol);
        if (requestRepaint != null) requestRepaint.run();
    }

    /**
     * Map a bitmap-pixel coordinate to a (row, col) in the live screen
     * grid. Returns null if the renderer or state machine hasn't been
     * wired yet, or if the point is outside the grid padding.
     */
    public int[] pxToCell(float pxX, float pxY) {
        if (sshRenderer == null || termMachine == null) {
            android.util.Log.e("RemoteSshPointer",
                    "DBG pxToCell: NOT WIRED, sshRenderer=" + sshRenderer
                    + " termMachine=" + termMachine);
            return null;
        }
        float charW = sshRenderer.getCellWidth();
        int charH = sshRenderer.getCellHeight();
        int pad = sshRenderer.getPaddingPx();
        int cols = termMachine.getCols();
        int rows = termMachine.getRows();
        float relX = pxX - pad;
        float relY = pxY - pad;
        if (relX < 0f || relY < 0f) {
            // Click landed in the padding — snap to nearest edge cell.
            relX = Math.max(0f, relX);
            relY = Math.max(0f, relY);
        }
        int col = Math.min(cols - 1, (int) (relX / charW));
        int row = Math.min(rows - 1, Math.max(0, (int) (relY / charH)));
        android.util.Log.e("RemoteSshPointer",
                "DBG pxToCell: px=(" + pxX + "," + pxY + ") pad=" + pad
                + " charW=" + charW + " charH=" + charH
                + " cols=" + cols + " rows=" + rows
                + " -> (row=" + row + ", col=" + col + ")");
        return new int[] { row, col };
    }

    /** Drop the selection. Safe to call from any thread. */
    public void cancelSelection() {
        selAnchorRow = selAnchorCol = selEndRow = selEndCol = -1;
        pendingSelectedText = "";
        cachedRows = cachedCols = cachedSbCount = -1;
        if (sshRenderer != null) sshRenderer.clearSelection();
        if (requestRepaint != null) requestRepaint.run();
    }

    /**
     * Snapshot the current selection as the entire visible grid (live
     * screen only — NOT scrollback). Sets anchor to (0,0), end to
     * (rows-1, cols-1). Cached text is rebuilt synchronously.
     */
    public void selectAllVisible() {
        if (termMachine == null) return;
        cachedRows = termMachine.getRows();
        cachedCols = termMachine.getCols();
        cachedSbCount = termMachine.getScrollbackCount();
        selAnchorRow = 0;
        selAnchorCol = 0;
        selEndRow = cachedRows - 1;
        selEndCol = cachedCols - 1;
        if (sshRenderer != null) sshRenderer.setSelection(0, 0, cachedRows - 1, cachedCols - 1);
        rebuildSelectedText();
        if (requestRepaint != null) requestRepaint.run();
    }

    /**
     * Return and clear the cached selection text. Called by the popup
     * menu at ACTION_UP time. Returns "" if no selection or if the
     * selection collapsed (anchor == end).
     */
    public String consumeSelectedText() {
        String t = pendingSelectedText;
        pendingSelectedText = "";
        return t;
    }

    /** Whether a non-empty selection rectangle is currently active. */
    public boolean hasSelection() {
        return selAnchorRow >= 0 && selAnchorCol >= 0
                && selEndRow >= 0 && selEndCol >= 0;
    }

    public int getSelectionAnchorRow() { return selAnchorRow; }
    public int getSelectionAnchorCol() { return selAnchorCol; }
    public int getSelectionEndRow() { return selEndRow; }
    public int getSelectionEndCol() { return selEndCol; }

    /**
     * Walk the selection rectangle (in screen-grid coords) and
     * assemble the corresponding text from the state machine. Uses
     * the cached rows/cols/sbCount if present (set by enterSelection
     * / selectAllVisible / extendSelection), otherwise reads them
     * once via the state machine's synchronized getters.
     *
     * <p>Codepoint semantics (per TermCell):
     * <ul>
     *   <li>{@code codepoint > 0}: real character — emit as UTF-16
     *       (handles surrogate pairs via {@code Character.toChars}).
     *   <li>{@code codepoint == 0}: empty cell — emit a single space.
     *   <li>{@code codepoint == -1}: right half of a wide (CJK) char
     *       — skip; the primary cell already emitted the codepoint.
     * </ul>
     *
     * <p>Each row gets a trailing {@code '\n'} except the last row
     * of the selection (matches Unix xterm-style copy semantics —
     * pasting into a shell inserts a clean newline-terminated block
     * but doesn't tack a trailing blank line).
     */
    private void rebuildSelectedText() {
        if (termMachine == null || !hasSelection()) {
            pendingSelectedText = "";
            return;
        }
        int rows, cols, sbCount;
        if (cachedRows > 0 && cachedCols > 0 && cachedSbCount >= 0) {
            rows = cachedRows;
            cols = cachedCols;
            sbCount = cachedSbCount;
        } else {
            rows = termMachine.getRows();
            cols = termMachine.getCols();
            sbCount = termMachine.getScrollbackCount();
            cachedRows = rows;
            cachedCols = cols;
            cachedSbCount = sbCount;
        }
        // Normalize: row0<=row1, col0<=col1 within a row.
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
        // Clamp — should already be in range from the caller, but be defensive.
        r0 = Math.max(0, Math.min(r0, rows - 1));
        r1 = Math.max(0, Math.min(r1, rows - 1));
        c0 = Math.max(0, Math.min(c0, cols - 1));
        c1 = Math.max(0, Math.min(c1, cols - 1));

        // Live grid only for now — scrollback integration is a future
        // extension. The screen row `r` corresponds to live row `r`
        // when the viewport is at the bottom (offset==0). When the user
        // has scrolled back, screen row r corresponds to virtual line
        // sbCount - offset + r; if that v < sbCount it's in scrollback.
        // We always emit from the LIVE grid here, even when scrolled —
        // a selection made while scrolled back still maps to the same
        // screen rows. (Caveat: cells in scrollback rows show the
        // scrollback content visually, but getCell() returns the live
        // row at that index. To get scrollback content the caller
        // would need to pass getScrollbackCell; left for follow-up.)
        StringBuilder sb = new StringBuilder((r1 - r0 + 1) * (c1 - c0 + 1) + 4);
        for (int row = r0; row <= r1; row++) {
            int colStart = (row == r0) ? c0 : 0;
            int colEnd   = (row == r1) ? c1 : (cols - 1);
            for (int col = colStart; col <= colEnd; col++) {
                SshTermStateMachine.TermCell cell = termMachine.getCell(row, col);
                if (cell == null) {
                    sb.append(' ');
                    continue;
                }
                if (cell.codepoint == -1) {
                    // right half of a wide char — primary already emitted
                    continue;
                }
                if (cell.codepoint <= 0) {
                    sb.append(' ');
                    continue;
                }
                sb.append(new String(Character.toChars(cell.codepoint)));
            }
            if (row < r1) sb.append('\n');
        }
        pendingSelectedText = sb.toString();
    }

    @Override
    public void scrollDown(int x, int y, int speed, int metaState) {
        if (termMachine == null) return;
        if (speed == -1) {
            termMachine.scrollByLines(-MOUSE_WHEEL_LINES);
        } else {
            termMachine.scrollByPixels(-decodeTouchMagnitude(speed, false));
        }
        if (requestRepaint != null) requestRepaint.run();
    }

    /**
     * Recover the per-sample pixel magnitude from doScroll's folded {@code speed}.
     * InputHandlerTouchpad.doScroll computes {@code newY = -(distanceY*density/2)},
     * clamps to [-255,255], then folds negatives via {@code 256 + delta} so the
     * value passed to sendScrollEvents is always in [1,255]. Direction is encoded
     * by WHICH method is called (scrollUp vs scrollDown), not the sign:
     * <ul>
     *   <li>scrollUp branch: delta was positive, {@code speed = |newY|} directly.
     *   <li>scrollDown branch: delta was negative, {@code speed = 256 - |newY|}.
     * </ul>
     * Mouse wheel passes {@code speed == -1} (handled before this). Coupled to
     * doScroll's encoding — if that changes, update here.
     *
     * <p>The result is scaled by {@link #TOUCH_SCROLL_SCALE}: doScroll's {@code /2}
     * plus the px→row conversion (÷ charHeight ~53) leaves gentle swipes (mag
     * 5-10) stuck sub-row, so nothing scrolls. The scale makes a gentle swipe
     * commit ~1 row.
     */
    private float decodeTouchMagnitude(int speed, boolean upBranch) {
        int s = speed & 0xff;
        int mag = upBranch ? s : (256 - s);
        if (mag > 255) mag = 255;  // 256-s hits 256 when s==0
        return mag * TOUCH_SCROLL_SCALE;
    }

    @Override
    public void scrollLeft(int x, int y, int speed, int metaState) {
    }

    @Override
    public void scrollRight(int x, int y, int speed, int metaState) {
    }

    @Override
    public void releaseButton(int x, int y, int metaState) {
    }

    @Override
    public void moveMouse(int x, int y, int metaState) {
    }

    @Override
    public void moveMouseButtonDown(int x, int y, int metaState) {
    }

    @Override
    public void moveMouseButtonUp(int x, int y, int metaState) {
    }

    @Override
    public void touchDown(int x, int y, int contactId) {
    }

    @Override
    public void touchUpdate(int x, int y, int contactId) {
    }

    @Override
    public void touchCancel(int x, int y, int contactId) {
    }

    @Override
    public void touchUp(int x, int y, int contactId) {
    }
}
