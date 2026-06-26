package com.qihua.bVNC.communicator;

import android.os.Handler;
import android.util.Log;

import com.qihua.bVNC.ssh.SshTerminalConnection;
import com.undatech.opaque.RemoteConnectable;

/**
 * {@link RemoteConnectable} adapter for the SSH terminal protocol. The
 * "connection" here is a trilead-backed shell session, not a graphics
 * protocol — most {@code write*} methods are no-ops, there is no
 * per-frame framebuffer-update cycle, and the only state pushed back
 * to the canvas is via TermSession's {@code setUpdateCallback}
 * (handled by {@code SshConnectionInitializer}, not via this class).
 *
 * <p>Phase 3.1 holds a {@link SshTerminalConnection} reference so
 * {@link #close()} can tear down the underlying SSH terminal session.
 * The reference is injected by {@code SshConnectionInitializer.initialize()}
 * via the package-private {@link #setSshTerminalConnection(SshTerminalConnection)}
 * setter.
 */
public class SshCommunicator extends RemoteConnectable {
    private static final String TAG = "SshCommunicator";

    private final int framebufferWidth;
    private final int framebufferHeight;
    private boolean inNormalProtocol = false;
    private boolean certificateAccepted = false;
    /** Injected by SshConnectionInitializer so close() can tear down the SSH tunnel. */
    private SshTerminalConnection sshTerminal;

    public SshCommunicator(boolean debugLogging, Handler handler,
                           int framebufferWidth, int framebufferHeight) {
        super(debugLogging, handler);
        this.framebufferWidth = framebufferWidth;
        this.framebufferHeight = framebufferHeight;
    }

    /** Inject the SshTerminalConnection so {@link #close()} can tear it down. */
    public void setSshTerminalConnection(SshTerminalConnection sshTerminal) {
        this.sshTerminal = sshTerminal;
    }

    @Override
    public int framebufferWidth() {
        return framebufferWidth;
    }

    @Override
    public int framebufferHeight() {
        return framebufferHeight;
    }

    @Override
    public String desktopName() {
        return "SSH Terminal";
    }

    @Override
    public String getEncoding() {
        return "ssh";
    }

    @Override
    public void requestUpdate(boolean incremental) {
    }

    @Override
    public void requestResolution(int x, int y) throws Exception {
    }

    @Override
    public void writeClientCutText(String text) {
    }

    @Override
    public void setIsInNormalProtocol(boolean state) {
        inNormalProtocol = state;
    }

    @Override
    public boolean isInNormalProtocol() {
        return inNormalProtocol;
    }

    @Override
    public void writePointerEvent(int x, int y, int metaState, int pointerMask, boolean relative) {
    }

    @Override
    public void writeKeyEvent(int key, int metaState, boolean down) {
    }

    @Override
    public void writeSetPixelFormat(int bitsPerPixel, int depth, boolean bigEndian,
                                    boolean trueColour, int redMax, int greenMax, int blueMax,
                                    int redShift, int greenShift, int blueShift, boolean fGreyScale) {
    }

    @Override
    public void writeFramebufferUpdateRequest(int x, int y, int w, int h, boolean b) {
    }

    @Override
    public void close() {
        if (sshTerminal != null) {
            try {
                Log.i(TAG, "close: terminating SSH terminal session");
                sshTerminal.close();
            } catch (Throwable t) {
                Log.w(TAG, "close: sshTerminal.close failed", t);
            }
            sshTerminal = null;
        }
    }

    @Override
    public void reconnect() {
    }

    @Override
    public boolean isCertificateAccepted() {
        return certificateAccepted;
    }

    @Override
    public void setCertificateAccepted(boolean certificateAccepted) {
        this.certificateAccepted = certificateAccepted;
    }
}
