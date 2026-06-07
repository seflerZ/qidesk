package com.qihua.bVNC.input;

import android.content.Context;
import android.os.Handler;
import android.view.KeyEvent;

import com.undatech.opaque.RemoteConnectable;

/**
 * Phase 0: keyboard sends nothing. We don't have a TermSession yet.
 * The on-screen IME / hardware keys will work but produce no output.
 *
 * Phase 1 will translate Android KeyEvent -> ANSI byte -> TermSession.getTermIn().
 * Phase 2 will additionally plumb TermSession.getTermOut() into the SSH
 * channel for real network I/O.
 */
public class RemoteSshKeyboard extends RemoteKeyboard {

    public RemoteSshKeyboard(RemoteConnectable r, Context v, Handler h, boolean debugLog) {
        super(r, v, h, debugLog);
    }

    /** Phase 0: swap the underlying RfbConnectable after a fold/unfold. */
    public void setRfb(RemoteConnectable rfb) {
        this.rfb = rfb;
    }

    @Override
    public boolean processLocalKeyEvent(int keyCode, KeyEvent evt, int additionalMetaState) {
        return false;
    }

    @Override
    public void sendMetaKey(MetaKeyBean meta) {
    }
}
