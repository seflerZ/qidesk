package com.qihua.bVNC.input;

import android.os.Handler;

import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.input.RemotePointer;
import com.undatech.opaque.RfbConnectable;

/**
 * Phase 0: no-op pointer. SSH has no mouse cursor to drive; this exists
 * only so the InputHandler* chain (which always reads pointer.getX/getY)
 * doesn't NPE.
 *
 * Phase 2 will repurpose this for text selection / clipboard drag.
 */
public class RemoteSshPointer extends RemotePointer {

    public RemoteSshPointer(RfbConnectable protocomm, RemoteCanvas canvas, Handler handler, boolean debugLogging) {
        super(protocomm, canvas, handler, debugLogging);
    }

    /** Phase 0: swap the underlying RfbConnectable after a fold/unfold. */
    public void setProtocomm(RfbConnectable protocomm) {
        this.protocomm = protocomm;
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
    }

    @Override
    public void scrollDown(int x, int y, int speed, int metaState) {
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
