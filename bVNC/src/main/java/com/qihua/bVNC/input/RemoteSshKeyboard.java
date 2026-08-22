package com.qihua.bVNC.input;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
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
            // Soft-keyboard (IME) text delivery. Apply on-screen modifier
            // toggles (extra-keys bar Ctrl/Alt) so Ctrl+R etc. work even
            // when the IME delivers 'r' as commitText. Ctrl only makes
            // sense for ASCII (libvterm does c &= 0x1f, which mangles
            // non-ASCII), so for CJK / multi-char commits we send literal
            // text with no mods. One-shot: auto-release the toggles after.
            int mods = metaToVtermMods(onScreenMetaState | additionalMetaState);
            boolean usedOnScreenMods = onScreenMetaState != 0;
            int len = chars.length();
            for (int i = 0; i < len; ) {
                int cp = chars.codePointAt(i);
                int cpMods = (cp < 0x80) ? mods : SshTermStateMachine.MOD_NONE;
                termSession.writeInput(cp, cpMods);
                i += Character.charCount(cp);
            }
            if (usedOnScreenMods) resetOnScreenModsAfterInput();
            return true;
        }
        // Modifier keycodes from the extra-keys bar toggle. The bar's
        // onExtraKeySpecialButtonState sends KEYCODE_CTRL_LEFT / ALT_LEFT /
        // SHIFT_LEFT / META_LEFT (ACTION_DOWN on toggle-on, UP on toggle-off)
        // via keyboard.keyEvent(). RDP handles these in its keyboard mapper;
        // SSH must translate them into onScreenMetaState so the NEXT key
        // (which arrives with no hardware meta) picks up the modifier.
        // Hardware-held modifiers also pass through here but are harmless —
        // they set onScreenMetaState redundantly (the key's own metaState
        // already carries it), and resetOnScreenModsAfterInput only fires
        // when onScreenMetaState is non-zero, which is correct either way.
        if (handleModifierKeyCode(keyCode, evt)) return true;
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
        // Special keys with no Unicode glyph (arrows, Home/End, PageUp/Down,
        // Insert, Forward-Delete, F-keys). getUnicodeChar() returns 0 for
        // these, so the printable fallback below would silently drop them.
        // Route through libvterm's vterm_keyboard_key, which emits the
        // correct byte sequence for the terminal's CURRENT cursor-key mode
        // (application vs normal) — e.g. Right Arrow is \eOC in application
        // mode. This is why zsh-autosuggestions' right-arrow "accept
        // suggestion" binding (mode-dependent) only fires via this path.
        int vkey = androidKeyToVtermKey(keyCode);
        if (vkey != SshTermStateMachine.KEY_NONE) {
            int mods = metaToVtermMods(evt.getMetaState() | additionalMetaState | onScreenMetaState);
            boolean usedOnScreenMods = onScreenMetaState != 0;
            termSession.writeKey(vkey, mods);
            if (usedOnScreenMods) resetOnScreenModsAfterInput();
            return true;
        }
        // Printable fallback. Android's getUnicodeChar() does NOT map
        // Ctrl+letter → control char (it returns 0, dropping the key), so
        // we strip CTRL/ALT from the meta used to fetch the codepoint and
        // pass them to libvterm instead — vterm_keyboard_unichar applies
        // CTRL via `c &= 0x1f` (Ctrl+R → 0x12) and ALT as an ESC prefix.
        // SHIFT is kept for getUnicodeChar so letters uppercase correctly.
        // onScreenMetaState carries the extra-keys bar's Ctrl/Alt/Shift
        // toggle state (keyEvent() passes additionalMetaState=0, so without
        // this the on-screen Ctrl would be invisible and Ctrl+R would send
        // a bare 'r').
        int combinedMeta = evt.getMetaState() | additionalMetaState | onScreenMetaState;
        int mods = metaToVtermMods(combinedMeta);
        boolean usedOnScreenMods = onScreenMetaState != 0;
        int metaForChar = combinedMeta & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK);
        int codePoint = evt.getUnicodeChar(metaForChar);
        if (codePoint == 0) {
            // Non-printable, no special handling — let the host keep it.
            return false;
        }
        termSession.writeInput(codePoint, mods);
        if (usedOnScreenMods) resetOnScreenModsAfterInput();
        return true;
    }

    /**
     * Handle bare modifier keycodes (Ctrl/Alt/Shift/Meta left+right) by
     * toggling the parent's {@code onScreenMetaState}. The extra-keys bar's
     * special-button toggle sends these via {@code onExtraKeySpecialButtonState}
     * → {@code keyboard.keyEvent(KEYCODE_CTRL_LEFT, ...)}; without this
     * handler SSH drops them (getUnicodeChar returns 0) and the on-screen
     * Ctrl never takes effect. Returns true if consumed.
     */
    private boolean handleModifierKeyCode(int keyCode, KeyEvent evt) {
        boolean down = evt.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                if (down) onScreenCtrlOn(); else onScreenCtrlOff();
                return true;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                if (down) onScreenAltOn(); else onScreenAltOff();
                return true;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                if (down) onScreenShiftOn(); else onScreenShiftOff();
                return true;
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                if (down) onScreenSuperOn(); else onScreenSuperOff();
                return true;
            default:
                return false;
        }
    }

    @Override
    public void sendMetaKey(MetaKeyBean meta) {
        // Phase 1: no extra-keys bar wiring yet. Real SSH shell apps use
        // Ctrl-C / Ctrl-D etc.; those flow through processLocalKeyEvent via
        // the soft keyboard's meta toggles, not this hook.
    }

    /**
     * Map Android {@link KeyEvent} keyCodes for non-printable special keys
     * to libvterm's {@code VTermKey} enum values. Returns
     * {@code KEY_NONE} for keys with no libvterm equivalent (printable
     * chars are handled separately via getUnicodeChar).
     */
    private static int androidKeyToVtermKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:     return SshTermStateMachine.KEY_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:   return SshTermStateMachine.KEY_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:   return SshTermStateMachine.KEY_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:  return SshTermStateMachine.KEY_RIGHT;
            case KeyEvent.KEYCODE_MOVE_HOME:   return SshTermStateMachine.KEY_HOME;
            case KeyEvent.KEYCODE_MOVE_END:    return SshTermStateMachine.KEY_END;
            case KeyEvent.KEYCODE_PAGE_UP:     return SshTermStateMachine.KEY_PAGEUP;
            case KeyEvent.KEYCODE_PAGE_DOWN:   return SshTermStateMachine.KEY_PAGEDOWN;
            case KeyEvent.KEYCODE_INSERT:      return SshTermStateMachine.KEY_INS;
            case KeyEvent.KEYCODE_FORWARD_DEL: return SshTermStateMachine.KEY_DEL;
            case KeyEvent.KEYCODE_F1:  return SshTermStateMachine.keyFunction(1);
            case KeyEvent.KEYCODE_F2:  return SshTermStateMachine.keyFunction(2);
            case KeyEvent.KEYCODE_F3:  return SshTermStateMachine.keyFunction(3);
            case KeyEvent.KEYCODE_F4:  return SshTermStateMachine.keyFunction(4);
            case KeyEvent.KEYCODE_F5:  return SshTermStateMachine.keyFunction(5);
            case KeyEvent.KEYCODE_F6:  return SshTermStateMachine.keyFunction(6);
            case KeyEvent.KEYCODE_F7:  return SshTermStateMachine.keyFunction(7);
            case KeyEvent.KEYCODE_F8:  return SshTermStateMachine.keyFunction(8);
            case KeyEvent.KEYCODE_F9:  return SshTermStateMachine.keyFunction(9);
            case KeyEvent.KEYCODE_F10: return SshTermStateMachine.keyFunction(10);
            case KeyEvent.KEYCODE_F11: return SshTermStateMachine.keyFunction(11);
            case KeyEvent.KEYCODE_F12: return SshTermStateMachine.keyFunction(12);
            default: return SshTermStateMachine.KEY_NONE;
        }
    }

    /**
     * Convert an Android KeyEvent meta-state (hardware meta OR on-screen
     * meta-toggle state) to libvterm's VTermModifier bitmask (SHIFT/ALT/
     * CTRL). Uses the combined MASK constants so left/right variants are
     * covered. Sym and function modifiers are ignored.
     */
    private static int metaToVtermMods(int meta) {
        int mods = SshTermStateMachine.MOD_NONE;
        if ((meta & KeyEvent.META_SHIFT_MASK) != 0)
            mods |= SshTermStateMachine.MOD_SHIFT;
        if ((meta & KeyEvent.META_ALT_MASK) != 0)
            mods |= SshTermStateMachine.MOD_ALT;
        if ((meta & KeyEvent.META_CTRL_MASK) != 0)
            mods |= SshTermStateMachine.MOD_CTRL;
        return mods;
    }
}
