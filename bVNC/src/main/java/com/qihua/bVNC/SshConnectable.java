package com.qihua.bVNC;

import android.os.Handler;

import com.undatech.opaque.RfbConnectable;

/**
 * Phase 0: stub RfbConnectable for SSH. Fixed framebuffer 1280x720,
 * no real connection. Goal is to validate the "render-to-mbitmap"
 * architectural contract before any SSH networking is wired in.
 *
 * Most write* methods are no-ops: there is no real protocol to write
 * to in Phase 0. They are only present to satisfy RfbConnectable's
 * abstract API.
 *
 * Phase 2 will replace the hardcoded dimensions with the actual
 * TermSession rows/cols, and trigger redraws on PTY output.
 */
public class SshConnectable extends RfbConnectable {

    private final int framebufferWidth;
    private final int framebufferHeight;
    private boolean inNormalProtocol = false;
    private boolean certificateAccepted = false;

    public SshConnectable(boolean debugLogging, Handler handler,
                          int framebufferWidth, int framebufferHeight) {
        super(debugLogging, handler);
        this.framebufferWidth = framebufferWidth;
        this.framebufferHeight = framebufferHeight;
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
