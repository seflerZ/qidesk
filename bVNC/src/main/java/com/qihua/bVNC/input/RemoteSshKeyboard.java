package com.qihua.bVNC.input;

import android.content.Context;
import android.os.Handler;
import android.view.KeyEvent;

import com.qihua.bVNC.ssh.libvterm.SshTermStateMachine;
import com.undatech.opaque.RemoteConnectable;

/**
 * Phase 3.7: translates Android KeyEvent -> codepoint ->
 * SshTermStateMachine (libvterm wrapper).
 *
 * <p>Phase 1-3.1 used the AAR's TermSession with .write(int).
 * Phase 3.7 replaces it with libvterm's SshTermStateMachine, where
 * the equivalent is .writeInput(int). The mapping logic (ENTER → '\r',
 * DEL → 0x7f, TAB → '\t', ESC → 0x1b, CJK IME unicode delivery) is
 * identical — libvterm's writeInput takes a Unicode codepoint and
 * synthesizes the right bytes for the server.
 *
 * <p>The keyboard reference is set after construction by
 * SshConnectionInitializer. fold/unfold swap the reference via
 * setTermSession().
 */
public class RemoteSshKeyboard extends RemoteKeyboard {

    private SshTermStateMachine termSession;

    public RemoteSshKeyboard(RemoteConnectable r, Context v, Handler h, boolean debugLog) {
        super(r, v, h, debugLog);
    }

    /** Phase 0: swap the underlying RfbConnectable after a fold/unfold. */
    public void setRfb(RemoteConnectable rfb) {
        this.rfb = rfb;
    }

    /**
     * Phase 3.7: swap the SshTermStateMachine that
     * processLocalKeyEvent writes into. The parameter is now
     * {@link SshTermStateMachine} (libvterm wrapper) instead of
     * the AAR's TermSession.
     */
    public void setTermSession(SshTermStateMachine termSession) {
        this.termSession = termSession;
    }

    /**
     * Returns the underlying SshTermStateMachine. (Kept for
     * symmetry with the prior getTermSession() — IME's
     * SshInputConnection is no longer used since 2026-06-14.)
     */
    public SshTermStateMachine getTermSession() {
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
        // Mirrors RemoteRdpKeyboard.processLocalKeyEvent, which is
        // why RDP's Chinese IME input has always worked.
        if (keyCode == 0 && chars != null && chars.length() > 0) {
            int len = chars.length();
            for (int i = 0; i < len; ) {
                int cp = chars.codePointAt(i);
                termSession.writeInput(cp);
                i += Character.charCount(cp);
            }
            return true;
        }
        if (evt.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                termSession.writeInput('\r');
                return true;
            case KeyEvent.KEYCODE_DEL:
                termSession.writeInput(0x7f); // ASCII DEL — the canonical "backspace" in TTY land
                return true;
            case KeyEvent.KEYCODE_TAB:
                termSession.writeInput('\t');
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                termSession.writeInput(0x1b);
                return true;
        }
        int metaState = evt.getMetaState() | additionalMetaState;
        int codePoint = evt.getUnicodeChar(metaState);
        if (codePoint == 0) {
            // Non-printable, no special handling — let the host keep it.
            return false;
        }
        termSession.writeInput(codePoint);
        return true;
    }

    @Override
    public void sendMetaKey(MetaKeyBean meta) {
        // Phase 1: no extra-keys bar wiring yet. Real SSH shell apps use
        // Ctrl-C / Ctrl-D etc.; those flow through processLocalKeyEvent via
        // the soft keyboard's meta toggles, not this hook.
    }
}
