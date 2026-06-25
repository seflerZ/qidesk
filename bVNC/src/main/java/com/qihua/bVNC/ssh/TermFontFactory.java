package com.qihua.bVNC.ssh;

import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.util.Log;

/**
 * Loads the terminal font from APK assets for the SSH renderer.
 *
 * <p>Phase 2 added real Nerd Font + CJK support. The renderer used to
 * rely on Typeface.MONOSPACE which is whatever the OEM ships as the
 * system monospace font — typically Roboto Mono or Noto Sans Mono.
 * Neither covers the Nerd Font Private Use Area
 * (U+E000 to U+F8FF), so PUA icons like the
 * powerline triangles (U+E0B0) render as a blank gap.
 *
 * <p>We bundle a single asset —
 * {@code fonts/SarasaMonoSCNerd-Regular.ttf}, ~24 MB, 48 556 codepoints
 * including 20 976 CJK Unified ideographs, the full Hiragana / Katakana
 * / Hangul Syllables sets, and 1 356 Nerd PUA-A icons (the subset
 * relevant to terminal prompts: powerline, devicons, fa-regular,
 * fa-solid, seti-ui, octicons, codicons, font-awesome-extension, and
 * part of material-design-icons). PUA-B (U+F0000 to U+FFFFD, the
 * Material Design Icons home) and the supplementary PUA are not
 * covered, but real-world prompt tooling (starship / p10k / oh-my-zsh)
 * uses PUA-A almost exclusively.
 *
 * <p><b>Why not also bundle a fallback font?</b> A second Nerd
 * candidate (CaskaydiaMono Nerd Font Mono) was evaluated. The two
 * fonts disagree on unitsPerEm (1000 vs 2048) and on the Latin
 * advance (Sarasa: 500 / em, CaskaydiaMono: 1200 / em — i.e. a 17 %
 * width mismatch). Merging them into a single Typeface via
 * {@code Typeface.Builder.addFont} makes Android pick the second
 * font's metrics for any codepoint it owns, so Nerd icons rendered
 * from CaskaydiaMono sit in a column 17 % wider than the ASCII text
 * rendered from Sarasa. Visually that means a prompt like
 * "main *  master" ends up with each icon pushed one cell to
 * the right of where it should be, defeating the point of Nerd
 * alignment. Keeping a single font is the only way to guarantee
 * column alignment.
 *
 * <p>If the asset is missing or unreadable we fall back to
 * {@link Typeface#MONOSPACE} so the terminal still works — Nerd icons
 * will then disappear (the user's local font likely has no glyphs
 * in PUA-A), but text remains legible.
 */
public final class TermFontFactory {
    private static final String TAG = "TermFontFactory";

    /** Path inside {@code assets/}. Relative, no leading slash. */
    private static final String PRIMARY = "fonts/SarasaMonoSCNerd-Regular.ttf";

    /** Lazily-computed Typeface. Cleared on failure so the next call retries. */
    private static volatile Typeface cached;

    private TermFontFactory() { /* no instances */ }

    /**
     * @param assets an {@link AssetManager} from the renderer context.
     *               Must not be {@code null}.
     * @return a usable Typeface. Never {@code null} — on asset failure
     *         returns {@link Typeface#MONOSPACE}.
     */
    public static Typeface load(AssetManager assets) {
        if (assets == null) {
            Log.w(TAG, "load: null AssetManager, using Typeface.MONOSPACE");
            return Typeface.MONOSPACE;
        }
        Typeface t = cached;
        if (t != null) return t;

        synchronized (TermFontFactory.class) {
            if (cached != null) return cached;
            try {
                t = Typeface.createFromAsset(assets, PRIMARY);
                Log.i(TAG, "load: loaded " + PRIMARY);
            } catch (RuntimeException e) {
                // createFromAsset throws RuntimeException for both
                // "file not found" and "not a valid ttf" — same recovery
                // either way: fall back to the system monospace.
                Log.w(TAG, "load: " + PRIMARY + " unavailable, falling back to Typeface.MONOSPACE", e);
                t = Typeface.MONOSPACE;
            }
            cached = t;
            return t;
        }
    }

    /** Test hook: drop the cached Typeface so {@link #load} re-reads the asset. */
    static void invalidate() {
        cached = null;
    }
}