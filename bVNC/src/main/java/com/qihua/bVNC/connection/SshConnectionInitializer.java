package com.qihua.bVNC.connection;

import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import com.qihua.bVNC.App;
import com.qihua.bVNC.Constants;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.communicator.SshCommunicator;
import com.qihua.bVNC.input.RemoteSshKeyboard;
import com.qihua.bVNC.input.RemoteSshPointer;
import com.qihua.bVNC.ssh.SshTerminalRenderer;
import com.undatech.opaque.Connection;

import jackpal.androidterm.emulatorview.TermSession;

/**
 * SSH lifecycle owner. Moved out of RemoteCanvas so that adding a 6th
 * protocol doesn't touch the host.
 *
 * Phase 1: wires a SshTerminalRenderer (TermSession + fake-shell loopback)
 * into the canvas. Paints are driven entirely by TermSession's UpdateCallback
 * (fired on every screen mutation: user keystrokes, echo, cursor moves). The
 * AAR's emulator has no auto-blink, so an idle terminal legitimately has no
 * repaint work to do. fold/unfold tears down the renderer and rebuilds it
 * at the new size — Phase 1 accepts that the screen contents are reset.
 */
public class SshConnectionInitializer extends ConnectionInitializer {
    private static final String TAG = "SshConnectionInitializer";

    private final Connection conn;
    private final Context ctx;

    /**
     * UpdateCallback fired by TermSession whenever the screen mutates
     * (user typed something, echo arrived, etc). The only paint driver
     * in Phase 1.
     */
    private final Runnable sshUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!canvas.isRunning || canvas.bitmapData == null || canvas.rfbconn == null) {
                return;
            }
            paintAndRedraw();
        }
    };

    private RemoteCanvas canvas;
    private SshTerminalRenderer renderer;
    private float density;

    public SshConnectionInitializer(Connection conn, Context ctx) {
        this.conn = conn;
        this.ctx = ctx;
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.SSH;
    }

    @Override
    public boolean supports(Connection c, Context c2) {
        return c != null && c.getConnectionType() == Constants.CONN_TYPE_SSH;
    }

    @Override
    public void initialize(RemoteCanvas canvas) throws Exception {
        this.canvas = canvas;
        this.density = ctx.getResources().getDisplayMetrics().density;
        Log.i(TAG, "initialize: Phase 1 — TermSession + fake shell.");

        int fbW = computeFbW();
        int fbH = computeFbH();
        Log.i(TAG, "SSH framebuffer size = " + fbW + " x " + fbH
                + " (displayRect " + canvas.displayRect.width() + "x" + canvas.displayRect.height()
                + ", factor " + Constants.SSH_SMART_RESOLUTION_FACTOR + ")");

        canvas.rfbconn = new SshCommunicator(App.debugLog, canvas.handler, fbW, fbH);
        canvas.pointer = new RemoteSshPointer(canvas.rfbconn, canvas, canvas.handler, App.debugLog);
        canvas.keyboard = new RemoteSshKeyboard(canvas.rfbconn, ctx, canvas.handler, App.debugLog);

        // Build the renderer; it owns its own TermSession + FakeShellLoopback.
        // We hand the session to the keyboard so processLocalKeyEvent has
        // somewhere to write bytes.
        renderer = new SshTerminalRenderer(density);
        TermSession termSession = renderer.getTermSession();
        ((RemoteSshKeyboard) canvas.keyboard).setTermSession(termSession);
    }

    @Override
    public void start(RemoteCanvas canvas) throws Exception {
        Log.i(TAG, "start: Phase 1 — opening terminal.");
        canvas.waitUntilInflated();
        canvas.reallocateDrawable(canvas.displayRect.width(), canvas.displayRect.height());
        openRenderer();
        canvas.onConnectionSuccess();
    }

    /**
     * Tear down the TermSession / loopback. Called by
     * RemoteCanvas.closeConnection(). SSH-specific hook; lives on this
     * subclass (not the abstract base) because no other protocol needs
     * a teardown callback today.
     */
    public void teardown(RemoteCanvas canvas) {
        closeRenderer();
    }

    /**
     * Surface recreated (background→foreground, fold/unfold, rotation).
     * UpdateCallback doesn't fire on surface recreate alone, so paint the
     * current TermSession state explicitly here.
     */
    public void onSurfaceCreated(RemoteCanvas canvas) {
        if (canvas.bitmapData != null && canvas.rfbconn != null) {
            paintAndRedraw();
        }
    }

    /**
     * displayRect changed (foldable fold/unfold, rotation). Rebuild the
     * framebuffer, mbitmap, and renderer at the new size.
     */
    @Override
    public void onDisplayRectChanged(Display display) {
        if (canvas.rfbconn == null) {
            return;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);

        Rect newRect = new Rect();
        display.getRectSize(newRect);

        Rect oldRect = canvas.getDisplayRect();

        if (oldRect.equals(newRect)) {
            return;
        }

        canvas.setDisplayRect(newRect);
        canvas.setDisplayDensity(metrics.density);

        Log.i(TAG, "displayRect changed "
                + oldRect.width() + "x" + oldRect.height()
                + " -> " + newRect.width() + "x" + newRect.height()
                + ", rebuilding SSH framebuffer");

        rebuildSSHFramebuffer();

        canvas.bitmapData.frameBufferSizeChanged();
    }

    /**
     * Open the TermSession at the current mbitmap size and paint the first
     * frame. The UpdateCallback becomes the paint driver for all subsequent
     * screen mutations; this call IS the first-frame trigger that replaces
     * the removed heartbeat — paintInto+reDraw here so the surface shows
     * the (currently empty) grid immediately, then the WELCOME bytes
     * arriving through the pipe will fire UpdateCallback and repaint with
     * the banner.
     */
    private void openRenderer() {
        if (canvas.bitmapData == null || canvas.bitmapData.mbitmap == null) {
            Log.w(TAG, "openRenderer: bitmapData or mbitmap is null");
            return;
        }

        int w = canvas.bitmapData.mbitmap.getWidth();
        int h = canvas.bitmapData.mbitmap.getHeight();
        renderer.open(w, h, sshUpdateRunnable);
        paintAndRedraw();
    }

    /**
     * Paint the current TermSession grid into mbitmap and post a reDraw.
     * Called by the UpdateCallback (every screen mutation) and by
     * onSurfaceCreated.
     */
    private void paintAndRedraw() {
        if (renderer == null) return;
        renderer.renderInto(canvas.bitmapData.mbitmap);
        canvas.reDraw(0, 0, canvas.rfbconn.framebufferWidth(), canvas.rfbconn.framebufferHeight());
    }

    private void closeRenderer() {
        if (renderer == null) return;
        try {
            renderer.close();
        } catch (Throwable t) {
            Log.w(TAG, "renderer.close failed", t);
        }
        renderer = null;
    }

    private int computeFbW() {
        float w = canvas.displayRect != null ? canvas.displayRect.width() : 0;
        if (w <= 0) {
            w = ctx.getResources().getDisplayMetrics().widthPixels;
        }
        return Math.max(1, (int) (w * Constants.SSH_SMART_RESOLUTION_FACTOR));
    }

    private int computeFbH() {
        float h = canvas.displayRect != null ? canvas.displayRect.height() : 0;
        if (h <= 0) {
            h = ctx.getResources().getDisplayMetrics().heightPixels;
        }
        return Math.max(1, (int) (h * Constants.SSH_SMART_RESOLUTION_FACTOR));
    }

    /**
     * Recreate the SSH RemoteConnectable, the mbitmap, and the renderer at
     * the current displayRect's size, then swap the new TermSession into the
     * keyboard. Used after a fold/unfold/rotation that changes the available
     * view area. Phase 1 accepts that the visible grid is reset.
     */
    private void rebuildSSHFramebuffer() {
        try {
            int w = canvas.displayRect.width();
            int h = canvas.displayRect.height();
            int fbW = Math.max(1, (int) (w * Constants.SSH_SMART_RESOLUTION_FACTOR));
            int fbH = Math.max(1, (int) (h * Constants.SSH_SMART_RESOLUTION_FACTOR));

            canvas.rfbconn = new SshCommunicator(App.debugLog, canvas.handler, fbW, fbH);
            canvas.reallocateDrawable(w, h);

            ((RemoteSshPointer) canvas.pointer).setProtocomm(canvas.rfbconn);
            ((RemoteSshKeyboard) canvas.keyboard).setRfb(canvas.rfbconn);
            ((RemoteSshKeyboard) canvas.keyboard).setTermSession(renderer.getTermSession());

            // Tear down the old renderer (which finishes the old TermSession +
            // closes the old pipe) and build a new one at the new size.
            closeRenderer();
            renderer = new SshTerminalRenderer(density);
            openRenderer();
        } catch (Exception e) {
            Log.e(TAG, "rebuildFramebuffer failed", e);
        }
    }
}
