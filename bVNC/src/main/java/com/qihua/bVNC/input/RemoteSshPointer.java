package com.qihua.bVNC.input;

import android.os.Handler;

import com.qihua.bVNC.RemoteCanvas;
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

    /** Wire the scrollback state machine + a coalesced-repaint hook. */
    public void setScrollback(SshTermStateMachine termMachine, Runnable requestRepaint) {
        this.termMachine = termMachine;
        this.requestRepaint = requestRepaint;
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
