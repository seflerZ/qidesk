package com.qihua.bVNC.ssh;

import android.graphics.Matrix;
import android.widget.ImageView.ScaleType;

import com.qihua.bVNC.AbstractScaling;
import com.qihua.bVNC.R;
import com.qihua.bVNC.RemoteCanvas;

/**
 * 1:1 scaling strategy for the SSH terminal canvas.
 *
 * <p>The RDP/VNC stack uses {@link com.qihua.bVNC.AbstractScaling}
 * subclasses ({@code FitToScreenScaling}, {@code OneToOneScaling},
 * {@code ZoomScaling}) for two reasons: (a) the remote desktop image
 * and the on-screen viewport usually differ in resolution and aspect,
 * so a matrix is needed to map between them, and (b) panning the
 * "larger-than-viewport" backing image is how the IME push-up effect
 * is achieved — DrawWorker's {@code glCanvas.translate(-absoluteYPosition, ...)}
 * draws the part of mbitmap that should land above the soft keyboard.
 *
 * <p>SSH never had a scaler, so {@code canvas.scaler == null} and the
 * RDP/VNC IME-push formula in {@code RemoteCanvasActivity} short-
 * circuited to "no-op" for SSH ({@code relativePan} checks
 * {@code scaler != null && !scaler.isAbleToPan()} but
 * {@code absolutePan} unconditionally bails when {@code scaler == null},
 * and {@code DrawWorker} only reads {@code scaler.getMatrix()} when it
 * is non-null). Without a scaler, the terminal grid stays hidden
 * under the IME and there is no path for it to be pushed up.
 *
 * <p>This class installs a 1:1 scaler with pan enabled. The mbitmap is
 * exactly the viewport (no zoom, no aspect change), so {@code scaling=1}
 * and {@code minimumScale=1}. The matrix is the identity so DrawWorker
 * draws each pixel 1:1. {@code isAbleToPan()} returns true so
 * {@code RemoteCanvas.relativePan} accepts IME pushes and updates
 * {@code absoluteYPosition}, which DrawWorker then turns into a
 * {@code glCanvas.translate} on every frame.
 *
 * <p>It deliberately does NOT call
 * {@link AbstractScaling#setScaleTypeForActivity} — that path also
 * writes the scale mode back to the SSH connection bean and saves the
 * activity's input handler, both of which are RDP/VNC concerns we
 * don't want to inherit (SSH doesn't have a "fit to screen" menu item
 * and the input handler is set up by {@code RemoteCanvasActivity}'s
 * SSH code path, not via scaler activation).
 */
public class SshTerminalScaling extends AbstractScaling {

    private final Matrix matrix = new Matrix();

    public SshTerminalScaling() {
        // R.id.itemOneToOne is the closest match for "1:1" — the SSH
        // activity's scale-mode menu doesn't expose this id, but
        // AbstractScaling only requires *some* id, and OneToOne's id
        // semantically describes what we're doing.
        super(R.id.itemOneToOne, ScaleType.CENTER);
    }

    @Override
    public boolean isAbleToPan() {
        return true;
    }

    @Override
    public boolean isValidInputMode(int mode) {
        return true;
    }

    @Override
    public int getDefaultHandlerId() {
        return R.id.itemInputTouchpad;
    }

    @Override
    public float getZoomFactor() {
        return 1.0f;
    }

    @Override
    public Matrix getMatrix() {
        return matrix;
    }

    /**
     * Initialise (or re-initialise after a framebuffer resize) the
     * scaler against the current {@link RemoteCanvas}. Sets a clean
     * identity matrix and zeroes the pan position so DrawWorker
     * paints the SSH terminal from (0,0) of the mbitmap. {@code setScaler}
     * is also called here so {@code canvas.scaler} becomes non-null
     * and {@code relativePan} / {@code DrawWorker} start working.
     *
     * <p>Does NOT call {@code canvas.reDraw} — the caller is expected
     * to issue its own reDraw after the mbitmap is in its final state,
     * so we don't paint twice.
     */
    public void attachTo(RemoteCanvas canvas) {
        if (canvas == null) return;
        canvas.setScaler(this);
        canvas.absoluteXPosition = 0;
        canvas.absoluteYPosition = 0;
        matrix.reset();
    }
}