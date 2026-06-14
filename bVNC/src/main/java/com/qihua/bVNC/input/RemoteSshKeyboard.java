package com.qihua.bVNC.input;

import android.content.Context;
import android.os.Handler;
import android.view.KeyEvent;

import com.undatech.opaque.RemoteConnectable;

import jackpal.androidterm.emulatorview.TermSession;

/**
 * Phase 1: translates Android KeyEvent -> ANSI byte -> TermSession.
 *
 * The session is set after construction by SshConnectionInitializer (the
 * initializer owns the TermSession lifecycle — keyboard just gets a
 * reference). fold/unfold swap the session via setTermSession(), which
 * mirrors the existing setRfb() pattern.
 *
 * Phase 2 will additionally plumb TermSession.getTermOut() into the SSH
 * channel for real network I/O; processLocalKeyEvent stays unchanged.
 */
public class RemoteSshKeyboard extends RemoteKeyboard {

    private TermSession termSession;

    public RemoteSshKeyboard(RemoteConnectable r, Context v, Handler h, boolean debugLog) {
        super(r, v, h, debugLog);
    }

    /** Phase 0: swap the underlying RfbConnectable after a fold/unfold. */
    public void setRfb(RemoteConnectable rfb) {
        this.rfb = rfb;
    }

    /** Phase 1: swap the TermSession that processLocalKeyEvent writes into. */
    public void setTermSession(TermSession termSession) {
        this.termSession = termSession;
    }

    /**
     * Phase 1.1: hand the TermSession to {@link SshInputConnection} so the
     * IME's commitText / setComposingText path can write into the same
     * terminal that the keyboard path does.
     */
    public TermSession getTermSession() {
        return termSession;
    }

    @Override
    public boolean processLocalKeyEvent(int keyCode, KeyEvent evt, int additionalMetaState) {
        String chars = evt.getCharacters();
        if (termSession == null) return false;
        // Unicode-text delivery. The IME dispatches the final candidate
        // text (e.g. "你好") as a KeyEvent with no keycode but with the
        // text in getCharacters(). Two flavors are seen in the wild:
        //
        //   (a) ACTION_DOWN + keyCode=0 (KEYCODE_UNKNOWN) + getCharacters()
        //       — the most common Chinese IME path; goes through
        //       OnKeyListener.onKey (which only fires for ACTION_DOWN) and
        //       then inputHandler.onKeyDown → keyboard.keyEvent.
        //   (b) ACTION_MULTIPLE + getCharacters() — older / less common
        //       IMEs; handled here too for completeness.
        //
        // Mirrors RemoteRdpKeyboard.processLocalKeyEvent at line 65, which
        // is why RDP's Chinese IME input has always worked.
        if (keyCode == 0 && chars != null && chars.length() > 0) {
            int len = chars.length();
            for (int i = 0; i < len; ) {
                int cp = chars.codePointAt(i);
                termSession.write(cp);
                i += Character.charCount(cp);
            }
            return true;
        }
        // Phase 1 consumes both DOWN and UP so the host (Android IME) doesn't
        // try to interpret the event. We only act on ACTION_DOWN.
        if (evt.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                termSession.write('\r');
                return true;
            case KeyEvent.KEYCODE_DEL:
                termSession.write(0x7f); // ASCII DEL — the canonical "backspace" in TTY land
                return true;
            case KeyEvent.KEYCODE_TAB:
                termSession.write('\t');
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                termSession.write(0x1b);
                return true;
        }
        int metaState = evt.getMetaState() | additionalMetaState;
        int codePoint = evt.getUnicodeChar(metaState);
        if (codePoint == 0) {
            // Non-printable, no special handling — let the host keep it.
            return false;
        }
        termSession.write(codePoint);
        return true;
    }

    @Override
    public void sendMetaKey(MetaKeyBean meta) {
        // Phase 1: no extra-keys bar wiring yet. Real SSH shell apps use
        // Ctrl-C / Ctrl-D etc.; those flow through processLocalKeyEvent via
        // the soft keyboard's meta toggles, not this hook.
    }
}
