package com.qihua.bVNC.input;

import android.view.View;
import android.view.inputmethod.BaseInputConnection;

import com.qihua.bVNC.RemoteCanvas;

import jackpal.androidterm.emulatorview.TermSession;

/**
 * Phase 1.1: minimal InputConnection for SSH.
 *
 * The default {@link BaseInputConnection} drops the IME's text — its
 * {@code commitText} / {@code setComposingText} only mutate an internal
 * {@link android.text.SpannableStringBuilder} that nothing ever reads.
 * This class is a thin receiver that forwards the text directly to the
 * {@link TermSession}.
 *
 * <h3>Silent composition</h3>
 * The IME only sends the <em>final</em> text (after the user picks a
 * candidate) — intermediate pinyin states like "n" / "ni" / "nih" never
 * reach the app. So:
 *   - {@code setComposingText} just stores the latest payload in
 *     {@code composing}; nothing is written to the terminal.
 *   - {@code commitText} and {@code finishComposingText} write whatever
 *     is in {@code composing} (or the commit payload) to the terminal
 *     and clear the state.
 * This is the same approach RDP-style terminal apps use. If the IME
 * only calls {@code setComposingText} and never {@code commitText} /
 * {@code finishComposingText}, the text will stay in the IME's
 * internal state and not appear in the terminal — this is the IME's
 * fault, not ours, and there's no good workaround.
 *
 * <h3>Why a dynamic termSession lookup</h3>
 * The {@link RemoteSshKeyboard} gets a new {@link TermSession} on every
 * fold/unfold / rotation. If we cached the TermSession at construction
 * time we'd silently write to a dead session after the first rebuild.
 * Looking it up on every callback is cheap (one field read) and keeps
 * us correct across rebuilds.
 */
public class SshInputConnection extends BaseInputConnection {

    private final RemoteCanvas canvas;
    /** null = no composition in progress. "" = empty composition (cursor-only move). */
    private CharSequence composing = null;

    public SshInputConnection(RemoteCanvas canvas) {
        super(canvas, false);
        this.canvas = canvas;
    }

    private TermSession termSession() {
        if (canvas != null
                && canvas.keyboard instanceof RemoteSshKeyboard) {
            TermSession ts = ((RemoteSshKeyboard) canvas.keyboard).getTermSession();
            if (ts != null) return ts;
        }
        return null;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPos) {
        TermSession ts = termSession();
        if (ts == null || text == null || text.length() == 0) return true;
        // The IME is finalizing. Discard any pending composing (it
        // never reached the terminal in silent mode) and write the
        // committed text.
        composing = null;
        writeCodepoints(ts, text);
        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPos) {
        // Just remember the latest composing text. Don't write to the
        // terminal — intermediate pinyin states are IME-internal and
        // never reach the app.
        composing = (text == null) ? "" : text;
        return true;
    }

    @Override
    public boolean finishComposingText() {
        TermSession ts = termSession();
        if (composing != null && composing.length() > 0 && ts != null) {
            writeCodepoints(ts, composing);
        }
        composing = null;
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        TermSession ts = termSession();
        if (ts == null) return true;
        for (int i = 0; i < beforeLength; i++) {
            ts.write(0x7f); // ASCII DEL — TTY backspace
        }
        return true;
    }

    private void writeCodepoints(TermSession ts, CharSequence text) {
        int len = text.length();
        for (int i = 0; i < len; ) {
            int cp = text.charAt(i);
            if (Character.isHighSurrogate((char) cp) && i + 1 < len) {
                cp = Character.toCodePoint((char) cp, text.charAt(i + 1));
                i += 2;
            } else {
                i++;
            }
            ts.write(cp);
        }
    }
}
