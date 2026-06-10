package com.qihua.bVNC.connection;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

import com.qihua.bVNC.App;
import com.qihua.bVNC.Constants;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.communicator.SshCommunicator;
import com.qihua.bVNC.input.RemoteSshKeyboard;
import com.qihua.bVNC.input.RemoteSshPointer;
import com.undatech.opaque.Connection;

/**
 * SSH lifecycle owner. Moved out of RemoteCanvas so that adding a 6th
 * protocol doesn't touch the host.
 *
 * Phase 0: stub. Builds a SshCommunicator with a fixed framebuffer,
 * paints a "Hello SSH" placeholder, and runs a 30 FPS redraw heartbeat
 * because there's no decoder/network to push DrawTasks.
 *
 * Phase 1+ will swap the placeholder for a TermSession-backed renderer.
 */
public class SshConnectionInitializer extends ConnectionInitializer {
    private static final String TAG = "SshConnectionInitializer";

    private final Connection conn;
    private final Context ctx;

    /**
     * 30 FPS redraw heartbeat. VNC/RDP/SPICE get DrawTasks pushed by
     * the network thread; SSH Phase 0 has neither, so we drive the
     * pipeline ourselves. Stopped in teardown().
     */
    private final Handler sshRedrawHandler = new Handler(Looper.getMainLooper());
    private final Runnable sshRedrawRunnable = new Runnable() {
        @Override
        public void run() {
            if (!canvas.isRunning || canvas.bitmapData == null || canvas.rfbconn == null) {
                return;
            }
            canvas.reDraw(0, 0, canvas.rfbconn.framebufferWidth(), canvas.rfbconn.framebufferHeight());
            sshRedrawHandler.postDelayed(this, heartbeatIntervalMs());
        }
    };

    private RemoteCanvas canvas;

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
        Log.i(TAG, "initialize: Phase 0 stub.");

        float w = canvas.displayRect != null ? canvas.displayRect.width() : 0;
        float h = canvas.displayRect != null ? canvas.displayRect.height() : 0;
        if (w <= 0 || h <= 0) {
            // displayRect not yet populated (initializeCanvas may run
            // before the view is laid out). Fall back to screen metrics.
            DisplayMetrics metrics = ctx.getResources().getDisplayMetrics();
            w = metrics.widthPixels;
            h = metrics.heightPixels;
        }
        int fbW = Math.max(1, (int) (w * Constants.SSH_SMART_RESOLUTION_FACTOR));
        int fbH = Math.max(1, (int) (h * Constants.SSH_SMART_RESOLUTION_FACTOR));
        Log.i(TAG, "SSH framebuffer size = " + fbW + " x " + fbH
                + " (displayRect " + w + "x" + h
                + ", factor " + Constants.SSH_SMART_RESOLUTION_FACTOR + ")");

        canvas.rfbconn = new SshCommunicator(App.debugLog, canvas.handler, fbW, fbH);
        canvas.pointer = new RemoteSshPointer(canvas.rfbconn, canvas, canvas.handler, App.debugLog);
        canvas.keyboard = new RemoteSshKeyboard(canvas.rfbconn, ctx, canvas.handler, App.debugLog);
    }

    @Override
    public void start(RemoteCanvas canvas) throws Exception {
        Log.i(TAG, "start: Phase 0 stub — no real SSH connection.");
        canvas.waitUntilInflated();
        canvas.reallocateDrawable(canvas.displayRect.width(), canvas.displayRect.height());
        drawPlaceholder();
        canvas.onConnectionSuccess();
        // No decoder/network pushes DrawTasks. Start heartbeat.
        sshRedrawHandler.removeCallbacks(sshRedrawRunnable);
        sshRedrawHandler.post(sshRedrawRunnable);
    }

    @Override
    public void teardown(RemoteCanvas canvas) {
        sshRedrawHandler.removeCallbacks(sshRedrawRunnable);
    }

    @Override
    public void onSurfaceCreated(RemoteCanvas canvas) {
        // Phase 0 SSH has no decoder/network to push DrawTasks, so push
        // one whenever the surface is (re)created. This handles the
        // case where start ran before the surface was ready (its
        // reDraw was silently dropped) and also covers surface
        // re-creation on fold/unfold, screen rotation, etc.
        if (canvas.bitmapData != null && canvas.rfbconn != null) {
            canvas.reDraw(0, 0, canvas.rfbconn.framebufferWidth(), canvas.rfbconn.framebufferHeight());
        }
    }

    @Override
    public void onDisplayRectChanged(RemoteCanvas canvas, Rect oldRect, Rect newRect) {
        // SSH has no server to send a new framebuffer size, so the
        // mbitmap would keep its old dimensions after fold/unfold and
        // the new view would be letterboxed with black bars. Detect a
        // meaningful rect change and rebuild rfbconn + bitmapData at
        // the new size.
        if (canvas.rfbconn != null && oldRect != null
                && (oldRect.width() != newRect.width()
                    || oldRect.height() != newRect.height())) {
            Log.i(TAG, "displayRect changed "
                    + oldRect.width() + "x" + oldRect.height()
                    + " -> " + newRect.width() + "x" + newRect.height()
                    + ", rebuilding SSH framebuffer");
            rebuildFramebuffer();
        }
    }

    @Override
    public boolean needsRedrawHeartbeat() {
        return true;
    }

    @Override
    public int heartbeatIntervalMs() {
        return 33;
    }

    /**
     * Paint a hardcoded "Hello SSH" frame into bitmapData.mbitmap.
     * Phase 1+ will replace this with a TermSession renderer that
     * draws live terminal state.
     */
    private void drawPlaceholder() {
        if (canvas.bitmapData == null || canvas.bitmapData.mbitmap == null) {
            Log.w(TAG, "drawPlaceholder: bitmapData or mbitmap is null");
            return;
        }
        int w = canvas.bitmapData.mbitmap.getWidth();
        int h = canvas.bitmapData.mbitmap.getHeight();
        // Use a density-based text size so glyphs occupy the same physical
        // size on cover (~430 PPI) and main (~340 PPI) foldable displays.
        // Effective on-screen size still varies with SSH_SMART_RESOLUTION_FACTOR
        // (fit-center scales the whole mbitmap up/down).
        float density = ctx.getResources().getDisplayMetrics().density;
        float textSize = Constants.SSH_FONT_SIZE_DP * density;
        float margin = textSize * 0.8f;
        float lineHeight = textSize * 1.4f;
        Canvas c = new Canvas(canvas.bitmapData.mbitmap);
        Paint bg = new Paint();
        bg.setColor(0xFF002B36); // Solarized base03
        c.drawRect(0, 0, w, h, bg);
        Paint text = new Paint();
        text.setColor(0xFF839496); // Solarized base0
        text.setTextSize(textSize);
        text.setAntiAlias(true);
        text.setTypeface(Typeface.MONOSPACE);
        c.drawText("Hello SSH", margin, margin + textSize, text);
        c.drawText("Phase 0: minimal skeleton", margin, margin + textSize + lineHeight, text);
    }

    /**
     * Recreate the SSH stub RemoteConnectable and the mbitmap at the
     * current displayRect's size, then redraw the placeholder. Used
     * after a fold/unfold/rotation that changes the available view area.
     */
    private void rebuildFramebuffer() {
        try {
            int w = canvas.displayRect.width();
            int h = canvas.displayRect.height();
            int fbW = Math.max(1, (int) (w * Constants.SSH_SMART_RESOLUTION_FACTOR));
            int fbH = Math.max(1, (int) (h * Constants.SSH_SMART_RESOLUTION_FACTOR));
            canvas.rfbconn = new SshCommunicator(App.debugLog, canvas.handler, fbW, fbH);
            if (canvas.pointer instanceof RemoteSshPointer) {
                ((RemoteSshPointer) canvas.pointer).setProtocomm(canvas.rfbconn);
            }
            if (canvas.keyboard instanceof RemoteSshKeyboard) {
                ((RemoteSshKeyboard) canvas.keyboard).setRfb(canvas.rfbconn);
            }
            canvas.reallocateDrawable(w, h);
            drawPlaceholder();
        } catch (Throwable e) {
            Log.e(TAG, "rebuildFramebuffer failed", e);
        }
    }
}
