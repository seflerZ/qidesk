// JNI bridge to libvterm.
//
// All native methods declared in
// com.qihua.bVNC.ssh.libvterm.SshTermStateMachine.
//
// Threading: libvterm is not thread-safe. The Java side drives all
// calls from a single thread (SSH-Paint HandlerThread for reads,
// SSH-Connect background thread for input writes). We do NOT
// serialize here — the caller is responsible.

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "vterm.h"
#include "vterm_keycodes.h"

#define LOG_TAG "vterm_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Fixed-size dirty-row ring buffer. libvterm's damage callback fires
// on every cell change; we record the start_row..end_row range as
// dirty rows. 256 is enough for any sane terminal — a full-screen
// redraw is at most ~256 rows on a foldable.
#define MAX_DIRTY_ROWS 256

typedef struct {
    VTerm        *vt;
    VTermScreen  *vts;
    int           rows;
    int           cols;
    // Cursor pos cached from VTermState (libvterm doesn't put cursor
    // in VTermScreenCell; we read it via vterm_state_get_cursorpos
    // on every cursor query).
    int           cursor_row;
    int           cursor_col;
    int           cursor_visible;
    // Damage ring buffer
    int           dirty_rows[MAX_DIRTY_ROWS];
    int           dirty_count;
    // Parser callbacks. libvterm stores this pointer verbatim in
    // vt->parser.callbacks and dereferences it on every byte that
    // hits the NORMAL state. It MUST live as long as the VTerm, so
    // we keep it in the handle (heap-allocated by calloc). Earlier
    // it was a local in nativeCreate and the pointer became dangling
    // after the function returned — every subsequent
    // vterm_input_write jumped into a stack frame that had been
    // reused, dereferenced garbage, and SIGSEGV'd. Tombstone 32 on
    // 2026-06-27.
    VTermParserCallbacks parser_callbacks;
    // Screen callbacks. Same lifetime concern as parser_callbacks
    // above — libvterm stores &this in vt->screen->callbacks and
    // dereferences it on every cell change. Must be heap-stable.
    VTermScreenCallbacks screen_callbacks;
    // Output buffer: vterm_keyboard_unichar() / vterm_keyboard_key()
    // produce bytes here; nativeWriteInput drains it back to Java.
    char         *output_buf;
    size_t        output_len;
    size_t        output_cap;
} jhandle_t;

// Damage callback: called by libvterm when cells in [start_row,
// end_row) of the screen change. We just append to the ring.
static int jni_damage(VTermRect rect, void *user) {
    jhandle_t *h = (jhandle_t *) user;
    LOGI("damage: rect=[%d,%d) cols=%d..%d dirty_count_was=%d",
         rect.start_row, rect.end_row, rect.start_col, rect.end_col, h->dirty_count);
    int n = rect.end_row - rect.start_row;
    for (int i = 0; i < n; i++) {
        int r = rect.start_row + i;
        // dedup adjacent/duplicate rows so a screen-wide redraw
        // (rows 0..rows-1) doesn't overflow the ring
        if (h->dirty_count > 0 && h->dirty_rows[h->dirty_count - 1] == r) continue;
        if (h->dirty_count < MAX_DIRTY_ROWS) {
            h->dirty_rows[h->dirty_count++] = r;
        } else {
            // Ring full: mark "all dirty" via a sentinel (-1)
            // and stop accepting. takeDirtyRows() will report
            // -1 meaning "redraw everything".
            h->dirty_rows[MAX_DIRTY_ROWS - 1] = -1;
            break;
        }
    }
    return 1;
}

// moverect callback: source rect copy to dest. We just damage both.
static int jni_moverect(VTermRect dest, VTermRect src, void *user) {
    jhandle_t *h = (jhandle_t *) user;
    VTermRect d1 = { .start_row = src.start_row, .end_row = src.end_row,
                     .start_col = 0, .end_col = h->cols };
    jni_damage(d1, user);
    VTermRect d2 = { .start_row = dest.start_row, .end_row = dest.end_row,
                     .start_col = 0, .end_col = h->cols };
    jni_damage(d2, user);
    return 1;
}

// Resize callback: only the rows/cols reported; we don't act
// (Java drives the resize via setSize when it sees cols/rows change).
// neovim/libvterm 0.3.3 callback signature: resize(int rows, int cols, void *user)
static int jni_resize(int rows, int cols, void *user) {
    jhandle_t *h = (jhandle_t *) user;
    h->rows = rows;
    h->cols = cols;
    // Treat the whole new screen as dirty
    VTermRect r = { .start_row = 0, .end_row = rows, .start_col = 0, .end_col = cols };
    jni_damage(r, user);
    return 1;
}

// movecursor callback: neovim/libvterm 0.3.3 fires this on every
// cursor move with the new pos, old pos, and current visibility.
static int jni_movecursor(VTermPos pos, VTermPos oldpos, int visible, void *user) {
    (void) oldpos;
    jhandle_t *h = (jhandle_t *) user;
    h->cursor_row = pos.row;
    h->cursor_col = pos.col;
    h->cursor_visible = visible;
    return 1;
}

// settermprop: we mostly ignore OSC-style title/icon-name changes
// (Phase 4+ could route to UI). The one we DO care about is
// VTERM_PROP_ALTSCREEN: libvterm flips screen->buffer here but only
// fires damagerect on ENTER (via erase()); on LEAVE the buffer swap
// happens silently with no damage, so the previous alt-screen frame
// stays painted — that's the "Claude Code TUI doesn't clear the
// existing scrollback" symptom. Force a full-screen damage in both
// directions so Java's takeDirtyRows triggers renderInto.
static int jni_settermprop(VTermProp prop, VTermValue *val, void *user) {
    if (prop == VTERM_PROP_ALTSCREEN) {
        jhandle_t *h = (jhandle_t *) user;
        VTermRect full = {
            .start_row = 0, .end_row = h->rows,
            .start_col = 0, .end_col = h->cols,
        };
        jni_damage(full, h);
    }
    return 1;
}

static int jni_bell(void *user) { (void) user; return 1; }

// Output callback: libvterm queues here the bytes generated by
// vterm_keyboard_unichar/key(). nativeWriteInput drains the buffer
// back to Java, which writes them to the SSH channel's stdin.
static void jni_output_callback(const char *s, size_t len, void *user) {
    jhandle_t *h = (jhandle_t *) user;
    if (!s || len == 0) return;
    if (h->output_len + len > h->output_cap) {
        size_t new_cap = h->output_cap ? h->output_cap : 256;
        while (new_cap < h->output_len + len) new_cap <<= 1;
        char *p = realloc(h->output_buf, new_cap);
        if (!p) {
            LOGE("output buffer realloc failed");
            return;
        }
        h->output_buf = p;
        h->output_cap = new_cap;
    }
    memcpy(h->output_buf + h->output_len, s, len);
    h->output_len += len;
}

// Parser-layer text callback. libvterm 0.3.3's vterm_input_write
// dereferences vt->parser.callbacks->text in the NORMAL state when it
// encounters a printable character. If we don't register a parser
// callback, it SIGSEGV at NULL + offsetof(text) (verified via
// tombstone 30 on 2026-06-27). Same pattern for escape/csi/osc/
// dcs/apc/pm/sos callbacks — each one is a struct field, and
// vterm_input_write dereferences them all even if we don't care
// about the events. Returning 0 from any of them makes vterm's
// internal "do_*" function fall through to the default
// state-machine handling, which is what we want — the screen layer
// already implements all the rendering.
static int jni_text(const char *bytes, size_t len, void *user) {
    (void) bytes; (void) user;
    return 0;  // 0 = "didn't consume" — vterm falls through to default
}

static int jni_control(unsigned char c, void *user) {
    (void) c; (void) user;
    return 0;
}

static int jni_escape(const char *bytes, size_t len, void *user) {
    (void) bytes; (void) user; (void) len;
    return 0;
}

static int jni_csi(const char *leader, const long *args, int argcount,
                    const char *intermed, char command, void *user) {
    (void) leader; (void) args; (void) argcount; (void) intermed; (void) command; (void) user;
    return 0;
}

// Current default fg/bg the SSH terminal palette is using. The
// theme-aware setters are nativeSetDefaultColors and
// nativeSetPalette; OSC 10/11 query responses (below) read
// from g_default_fg_argb so the answer stays in sync with
// whatever's on screen. Forward-declared here so the OSC
// callback (which is registered early in VTerm state machine
// setup) can refer to it; nativeSetDefaultColors /
// nativeSetPalette sit further down the file.
static int g_default_fg_argb = 0xFF839496;  // Solarized base0
static int g_default_bg_argb = 0xFF002B36;  // Solarized base03

// OSC query response. The remote shell (notably zsh's
// `osc_support` and several prompt themes) sends
//   \e]10;?\a  or  \e]11;?\a
// to ask the terminal for its current default fg/bg, then
// re-picks a contrast-friendly prompt colour. Without a real
// reply, those prompts stay in their dark-mode default forever
// and look out of place on a light-themed terminal. We answer
// the same RGB the renderer is using on screen (g_default_*_argb
// from nativeSetDefaultColors), in xterm's "rgb:RRRR/GGGG/BBBB"
// 16-bit-per-channel form. The reply is pushed into jni_output_callback
// so it travels back over SSH as if the user typed it on the
// terminal — no extra plumbing needed.
static void emit_osc_color_reply(jhandle_t *h, int command, int argb) {
    int r = (argb >> 16) & 0xFF;
    int g = (argb >>  8) & 0xFF;
    int b =  argb        & 0xFF;
    char reply[64];
    int n = snprintf(reply, sizeof(reply),
                     "\x1B]%d;rgb:%04x/%04x/%04x\x1B\\",
                     command, r * 257, g * 257, b * 257);
    if (n > 0) {
        jni_output_callback(reply, (size_t) n, h);
    }
}

static int jni_osc(int command, VTermStringFragment frag, void *user) {
    jhandle_t *h = (jhandle_t *) user;
    if (command == 10 || command == 11) {
        // The fragment is a short literal "?": libvterm splits OSC
        // payloads at the ; boundary so the body ("?", or the
        // actual colour spec) is what reaches us. Anything other
        // than "?" is the shell trying to SET the colour, not
        // query it — we accept it silently and let g_default_*_argb
        // track whatever the application prefers (Windows Terminal
        // ignores the SET path on Windows because the OS owns the
        // theme; we follow the same convention).
        if (frag.len == 1 && frag.str && frag.str[0] == '?') {
            int argb = command == 10 ? g_default_fg_argb : g_default_bg_argb;
            __android_log_print(ANDROID_LOG_INFO, "vterm_jni",
                "OSC %d ?  ->  rgb: 0x%08X", command, argb);
            emit_osc_color_reply(h, command, argb);
            return 1;
        }
        return 0; // SET — drop on the floor
    }
    (void) frag;
    return 0;
}

static int jni_dcs(const char *command, size_t commandlen, VTermStringFragment frag, void *user) {
    (void) command; (void) commandlen; (void) frag; (void) user;
    return 0;
}

static int jni_apc(VTermStringFragment frag, void *user) {
    (void) frag; (void) user;
    return 0;
}

static int jni_pm(VTermStringFragment frag, void *user) {
    (void) frag; (void) user;
    return 0;
}

static int jni_sos(VTermStringFragment frag, void *user) {
    (void) frag; (void) user;
    return 0;
}

// (neovim/libvterm 0.3.3 doesn't have vterm_state_set_cursorvis or
// vterm_state_set_cursorpos; cursor updates come through the
// movecursor screen callback above.)

// Helper: extract jhandle_t from a Java long. JNI guarantees that
// pointer-sized long fits; on 32-bit ABIs we'd need a different
// scheme, but we only build arm64-v8a.
static jhandle_t *getHandle(JNIEnv *env, jlong handle) {
    if (handle == 0) {
        LOGE("null handle from Java");
        return NULL;
    }
    return (jhandle_t *) (uintptr_t) handle;
}

JNIEXPORT jlong JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeCreate(
        JNIEnv *env, jclass clazz, jint cols, jint rows) {
    (void) clazz;
    jhandle_t *h = (jhandle_t *) calloc(1, sizeof(jhandle_t));
    if (!h) {
        LOGE("calloc jhandle failed");
        return 0;
    }
    h->vt = vterm_new(rows, cols);
    if (!h->vt) {
        LOGE("vterm_new failed for %dx%d", rows, cols);
        free(h);
        return 0;
    }
    h->rows = rows;
    h->cols = cols;
    h->cursor_visible = 1;  // default visible

    // Capture the bytes generated by vterm_keyboard_unichar/key().
    vterm_output_set_callback(h->vt, jni_output_callback, h);

    h->vts = vterm_obtain_screen(h->vt);
    // Order MUST match include/vterm.h VTermScreenCallbacks. We do
    // NOT register sb_pushline / sb_popline / sb_clear — scrollback
    // is driven from Java via nativeScrollUp / nativeScrollDown,
    // which call vterm_scroll_rect directly. libvterm itself does
    // not retain scrolled-off rows (screen.c buffers[2] holds only the
    // current viewport); the rows "scrolled off the top" become
    // garbage in the viewport, which renderInto paints as BG.
    h->screen_callbacks.damage     = jni_damage;
    h->screen_callbacks.moverect   = jni_moverect;
    h->screen_callbacks.movecursor = jni_movecursor;
    h->screen_callbacks.settermprop = jni_settermprop;
    h->screen_callbacks.bell       = jni_bell;
    h->screen_callbacks.resize     = jni_resize;
    vterm_screen_set_callbacks(h->vts, &h->screen_callbacks, h);
    vterm_screen_enable_altscreen(h->vts, 1);

    // VTermStateFallbacks handles OSC commands that on_osc()
    // doesn't recognise by hand (i.e. anything other than
    // OSC 0/1/2/52). OSC 10/11 fall in that bucket — without
    // registering jni_osc here, the OSC 11 query the remote
    // shell sends (e.g. zsh's osc_support / osc_watcher) is
    // silently dropped, so the shell never learns the
    // terminal's current bg colour and stuck with whatever
    // default it had at startup. Register the OSC slot only —
    // control/csi/dcs/apc/pm/sos all use the same handler
    // signature and could be added if needed.
    static const VTermStateFallbacks state_fallbacks = {
        .osc = jni_osc,
    };
    vterm_state_set_unrecognised_fallbacks(vterm_screen_get_state(h->vts), &state_fallbacks, h);

    // Initialise the VTermState. vterm_obtain_screen() creates the state
    // object but does NOT run vterm_state_reset(); without it the G0/G1/G2/G3
    // encoding instances are NULL and the first ASCII byte in on_text() hits
    // encoding->enc->decode with a NULL function pointer (SIGSEGV at offset 8).
    // Set UTF-8 mode first so reset picks the UTF-8 encoding tables.
    vterm_set_utf8(h->vt, 1);
    vterm_screen_reset(h->vts, 1);

    // Parser-layer callbacks: do NOT call vterm_parser_set_callbacks
    // here. vterm_obtain_screen() above internally calls
    // vterm_obtain_state() which installs state.c's on_text/on_csi/
    // on_osc/etc. Those route through the state machine → putglyph
    // → screen.putglyph → damagerect → our damage callback. If we
    // overrode them with no-op jni_text/jni_csi etc., every printable
    // byte would be silently dropped (libvterm forces pos += 1 with
    // eaten=0 from the text callback, but no putglyph fires) — that's
    // why the terminal rendered as a blank screen.
    //
    // The earlier parser-callback boilerplate was added to defeat
    // SIGSEGV from NULL function pointers in parser.c; that risk
    // doesn't exist because vterm_obtain_state's parser-callbacks
    // struct has all 9 slots populated (see state.c line 2046-2057).

    // Initial paint: mark everything dirty
    VTermRect r = { .start_row = 0, .end_row = rows, .start_col = 0, .end_col = cols };
    jni_damage(r, h);

    return (jlong) (uintptr_t) h;
}

JNIEXPORT void JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeSetSize(
        JNIEnv *env, jclass clazz, jlong handle, jint cols, jint rows) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h) return;
    vterm_set_size(h->vt, rows, cols);
    h->rows = rows;
    h->cols = cols;
}

JNIEXPORT jint JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeGetCols(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h) return 0;
    return h->cols;
}

JNIEXPORT jint JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeGetRows(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h) return 0;
    return h->rows;
}

JNIEXPORT void JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeWrite(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray data, jint off, jint len) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h) return;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return;
    vterm_input_write(h->vt, (const char *) (bytes + off), (size_t) len);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT jbyteArray JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeWriteInput(
        JNIEnv *env, jclass clazz, jlong handle, jint codepoint, jint mods) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h) return NULL;
    if (codepoint > 0 && codepoint < 0x110000) {
        // Pass mods through: libvterm applies CTRL via `c &= 0x1f` (so
        // Ctrl+R sends 0x12, Ctrl+C sends 0x03, etc.) and ALT as an ESC
        // prefix. Android's getUnicodeChar() does NOT map Ctrl+letter to
        // control chars, so the Java side strips CTRL/ALT before fetching
        // the codepoint and passes them here instead.
        vterm_keyboard_unichar(h->vt, (uint32_t) codepoint,
                               (VTermModifier) (mods & VTERM_ALL_MODS_MASK));
    }
    if (h->output_len == 0) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, (jsize) h->output_len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) h->output_len,
                                     (const jbyte *) h->output_buf);
    }
    h->output_len = 0;
    return result;
}

// Send a special key (arrows, Home/End, PageUp/Down, Insert/Delete, F-keys,
// keypad). Unlike nativeWriteInput (which takes a Unicode codepoint), this
// goes through vterm_keyboard_key so libvterm generates the correct byte
// sequence for the terminal's CURRENT cursor-key mode (application vs
// normal) — e.g. Right Arrow is \eOC in application mode, \e[C in normal.
// This is why zsh-autosuggestions' right-arrow "accept suggestion" binding
// (which is mode-dependent) only fires when we let libvterm pick the bytes.
JNIEXPORT jbyteArray JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeWriteKey(
        JNIEnv *env, jclass clazz, jlong handle, jint key, jint mods) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h) return NULL;
    if (key > 0) {
        vterm_keyboard_key(h->vt, (VTermKey) key, (VTermModifier) (mods & VTERM_ALL_MODS_MASK));
    }
    if (h->output_len == 0) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, (jsize) h->output_len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) h->output_len,
                                     (const jbyte *) h->output_buf);
    }
    h->output_len = 0;
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeDrainOutput(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h || h->output_len == 0) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, (jsize) h->output_len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) h->output_len,
                                   (const jbyte *) h->output_buf);
    }
    h->output_len = 0;
    return result;
}
JNIEXPORT jboolean JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativePollDirty(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h) return JNI_FALSE;
    return h->dirty_count > 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeTakeDirtyRows(
        JNIEnv *env, jclass clazz, jlong handle, jintArray outRows) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h) return 0;
    if (h->dirty_count == 0) return 0;

    jsize cap = (*env)->GetArrayLength(env, outRows);
    int n = h->dirty_count < cap ? h->dirty_count : cap;
    if (n > 0) {
        jint *out = (*env)->GetIntArrayElements(env, outRows, NULL);
        if (out) {
            // Sentinel -1 means "everything is dirty"; honor it by
            // returning a single -1 and clearing the ring. The
            // Java paint path treats -1 as "redraw all rows".
            if (h->dirty_rows[h->dirty_count - 1] == -1) {
                out[0] = -1;
                n = 1;
            } else {
                memcpy(out, h->dirty_rows, n * sizeof(int));
            }
            (*env)->ReleaseIntArrayElements(env, outRows, out, 0);
        }
    }
    h->dirty_count = 0;
    return n;
}

// Map VTermScreenCellAttrs to our packed int bits. See Java docs.
static int packAttrs(const VTermScreenCellAttrs *a) {
    int bits = 0;
    if (a->bold)      bits |= 1;
    if (a->italic)    bits |= 2;
    if (a->underline) bits |= 4;
    if (a->reverse)   bits |= 8;
    if (a->blink)     bits |= 16;
    if (a->strike)    bits |= 32;
    return bits;
}

// libvterm's color is either an indexed palette entry (idx < 256)
// or an RGB triple. The VTermColor struct has an .indexed flag.
// We convert to 0xRRGGBB packed. Default fg/bg we override to Solarized
// base0/base03 (we never call vterm_screen_set_default_colors, so libvterm's
// own defaults of 240,240,240 / 0,0,0 would otherwise show through). For any
// non-default colour we let libvterm resolve it via its standard 256-colour
// palette (vterm_screen_convert_color_to_rgb): indexed colour 8 (bright
// black, used by zsh-autosuggestions' default `fg=8`) resolves to
// 128,128,128 — a visible gray — instead of our previous hardcoded
// 0x002B36 which happened to equal the Solarized background and rendered
// the autosuggestion invisible.
//
// Order matters: VTERM_COLOR_IS_DEFAULT_FG/BG must be checked BEFORE
// convert, because vterm_screen_convert_color_to_rgb clears those flag
// bits (it does `type &= VTERM_COLOR_TYPE_MASK`).
//
// DEFAULT fg/bg are runtime-settable so the AppCompat day/night theme
// can drive them — Java calls nativeSetDefaultColors on theme flip,
// then SshTermStateMachine.nativeGetCell returns the new ARGB instead
// of the hardcoded Solarized values. Initial values match the
// legacy Solarized fallback so connections opened before the
// SshConnectionInitializer.applyTheme() call still get a sensible
// colour.

// ANSI 16-colour palette. libvterm 0.3.3 has its own hardcoded
// 16-colour table that vterm_screen_convert_color_to_rgb uses to
// translate indexed cells (\e[31m etc.). That table is fixed
// regardless of the SSH terminal's actual default. We override
// it: Java calls nativeSetPalette to push the AppCompat
// day/night theme's 16 colours here, then colorToRgb looks
// them up by index when it sees an indexed cell. The fallback
// values mirror the original Solarized palette so connections
// opened before the first applyTheme still see a sensible
// colour.
static int g_palette[16] = {
    0xFF000000, // 0  black
    0xFFDC322F, // 1  red (Solarized red)
    0xFF859900, // 2  green
    0xFFB58900, // 3  yellow
    0xFF268BD2, // 4  blue
    0xFFD33682, // 5  magenta
    0xFF2AA198, // 6  cyan
    0xFFEEE8D5, // 7  white (Solarized base2)
    0xFF002B36, // 8  bright black   (Solarized base03)
    0xFFCB4B16, // 9  bright red     (Solarized orange)
    0xFF586E75, // 10 bright green   (Solarized base01)
    0xFF657B83, // 11 bright yellow  (Solarized base00)
    0xFF839496, // 12 bright blue    (Solarized base0)
    0xFF6C71C4, // 13 bright magenta (Solarized violet)
    0xFF93A1A1, // 14 bright cyan    (Solarized base1)
    0xFFFDF6E3, // 15 bright white   (Solarized base3)
};

// c is const-qualified because the cells vterm_screen_get_cell
// hands us are read-only; we still need to convert indexed → RGB
// for the non-default branch, so cast away const there.
static int colorToRgb(VTermScreen *screen, const VTermColor *c) {
    if (VTERM_COLOR_IS_DEFAULT_FG(c)) return g_default_fg_argb;
    if (VTERM_COLOR_IS_DEFAULT_BG(c)) return g_default_bg_argb;
    if (VTERM_COLOR_IS_INDEXED(c)) {
        int idx = c->indexed.idx;
        if (idx >= 0 && idx < 16) {
            return g_palette[idx];
        }
    }
    // RGB cell or out-of-range index — let libvterm normalise the
    // metadata flags, then return the raw RGB. Cast away const
    // because libvterm's prototype for this fn isn't const-correct
    // even though it logically only mutates the metadata flags.
    vterm_screen_convert_color_to_rgb(screen, (VTermColor *) c);
    return (0xFF << 24) | ((c->rgb.red   & 0xFF) << 16)
                         | ((c->rgb.green & 0xFF) << 8)
                         |  (c->rgb.blue  & 0xFF);
}

// Called by Java when AppCompat day/night theme flips. Updates the
// two static globals that `colorToRgb` consults when a cell carries
// VTERM_COLOR_DEFAULT_FG/BG. libvterm 0.3.3 does NOT expose a
// public API to write cell internals — VTermScreen is forward-
// declared in vterm.h and `buffers[0]` is only visible inside
// screen.c — so we can't recolour cells that were already painted
// with raw RGB. The mbitmap seed (SshTerminalRenderer.seedBackground)
// and the Java-side defaultFg/defaultBg (VTermCanvasRenderer) follow
// the new theme immediately, so newly-drawn cells and the padding
// ring look right; cells the remote program wrote earlier in raw
// RGB will keep their old colour until the remote program paints
// them again. The Android fallback of forcing a redraw via
// SshConnectionInitializer.applyTheme (canvas.reDraw after seed) is
// what flips the visible canvas to the new palette.
JNIEXPORT void JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeSetDefaultColors(
        JNIEnv *env, jclass clazz, jlong handle, jint fgArgb, jint bgArgb) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    int oldFg = g_default_fg_argb;
    int oldBg = g_default_bg_argb;
    g_default_fg_argb = (int) fgArgb;
    g_default_bg_argb = (int) bgArgb;
    __android_log_print(ANDROID_LOG_INFO, "vterm_jni",
                        "nativeSetDefaultColors: handle=%p vts=%p oldFg=0x%08X->0x%08X oldBg=0x%08X->0x%08X",
                        (void*) handle, h ? (void*) h->vts : NULL,
                        oldFg, g_default_fg_argb, oldBg, g_default_bg_argb);
}

// Push the AppCompat day/night theme's 16-colour palette so
// indexed cells (\e[31m red, etc.) use the new theme's red/green/etc.
// instead of libvterm's hardcoded Solarized defaults.
JNIEXPORT void JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeSetPalette(
        JNIEnv *env, jclass clazz, jintArray palette) {
    (void) clazz;
    if (!palette) return;
    jsize len = (*env)->GetArrayLength(env, palette);
    if (len > 16) len = 16;
    jint *arr = (*env)->GetIntArrayElements(env, palette, NULL);
    if (!arr) return;
    for (jsize i = 0; i < len; i++) {
        g_palette[i] = (int) arr[i];
    }
    (*env)->ReleaseIntArrayElements(env, palette, arr, JNI_ABORT);
}

// Forward declaration so nativeGetCell below can call marshallTermCell
// before its full definition further down.
static jobject marshallTermCell(JNIEnv *env, VTermScreen *screen,
                                 const VTermScreenCell *cell);

JNIEXPORT jobject JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeGetCell(
        JNIEnv *env, jclass clazz, jlong handle, jint row, jint col) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h) return NULL;
    if (row < 0 || row >= h->rows || col < 0 || col >= h->cols) {
        return NULL;
    }
    VTermPos pos = { .row = row, .col = col };
    VTermScreenCell cell;
    vterm_screen_get_cell(h->vts, pos, &cell);
    return marshallTermCell(env, h->vts, &cell);
}

// Build a TermCell Java object from a VTermScreenCell. Shared by
// nativeGetCell (live grid) and nativeGetScrollbackCell (ring). The
// `cell` is read-only here — VTermScreenCell is a POD aggregate
// (chars[6]=uint32_t, attrs bitfield, fg/bg VTermColor with three
// uint8_t + a type tag), no deep-copy needed because Java only stores
// the packed ints.
static jobject marshallTermCell(JNIEnv *env, VTermScreen *screen,
                                 const VTermScreenCell *cell) {
    jclass cls = (*env)->FindClass(env, "com/qihua/bVNC/ssh/libvterm/SshTermStateMachine$TermCell");
    if (!cls) return NULL;
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "()V");
    jobject obj = (*env)->NewObject(env, cls, ctor);
    if (!obj) return NULL;
    jfieldID fcp = (*env)->GetFieldID(env, cls, "codepoint", "I");
    jfieldID fwid = (*env)->GetFieldID(env, cls, "width", "I");
    jfieldID ffg = (*env)->GetFieldID(env, cls, "fg", "I");
    jfieldID fbg = (*env)->GetFieldID(env, cls, "bg", "I");
    jfieldID fattrs = (*env)->GetFieldID(env, cls, "attrs", "I");

    (*env)->SetIntField(env, obj, fcp, (jint) cell->chars[0]);
    (*env)->SetIntField(env, obj, fwid, cell->width);
    (*env)->SetIntField(env, obj, ffg, colorToRgb(screen, &cell->fg));
    (*env)->SetIntField(env, obj, fbg, colorToRgb(screen, &cell->bg));
    (*env)->SetIntField(env, obj, fattrs, packAttrs(&cell->attrs));

    return obj;
}

JNIEXPORT jobject JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeGetCursor(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h) return NULL;

    jclass cls = (*env)->FindClass(env, "com/qihua/bVNC/ssh/libvterm/SshTermStateMachine$CursorInfo");
    if (!cls) return NULL;
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "()V");
    jobject obj = (*env)->NewObject(env, cls, ctor);
    jfieldID frow = (*env)->GetFieldID(env, cls, "row", "I");
    jfieldID fcol = (*env)->GetFieldID(env, cls, "col", "I");
    jfieldID fvis = (*env)->GetFieldID(env, cls, "visible", "Z");

    (*env)->SetIntField(env, obj, frow, h->cursor_row);
    (*env)->SetIntField(env, obj, fcol, h->cursor_col);
    (*env)->SetBooleanField(env, obj, fvis, h->cursor_visible ? JNI_TRUE : JNI_FALSE);

    return obj;
}

// Roll the libvterm viewport up by `rows` rows. After this call,
// vterm_screen_get_cell sees the (rows, rows-1) range shifted into
// (0, rows-2); rows 0..rows-1 of the viewport contain whatever
// libvterm's moverect_internal memmoved into them (see screen.c:239)
// — typically garbage because libvterm's buffers[2] is exactly the
// viewport size. renderInto paints those N rows as BG so the user sees
// a clean "scrollback" header.
JNIEXPORT void JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeScrollUp(
        JNIEnv *env, jclass clazz, jlong handle, jint rows) {
    (void) clazz;
    if (rows <= 0) return;
    jhandle_t *h = getHandle(env, handle);
    if (!h || !h->vts) return;
    if (rows > h->rows) rows = h->rows;
    VTermRect rect = {
        .start_row = 0, .end_row = h->rows,
        .start_col = 0, .end_col = h->cols,
    };
    // libvterm 0.3.3 vterm_scroll_rect unconditionally invokes the
    // eraserect callback (vterm.c:371) — passing NULL here SIGSEGVs
    // at PC=0. We feed libvterm's own moverect_internal /
    // erase_internal (declared extern in libvterm's screen.h after
    // our fork's patch; see deps/libvterm/src/screen.c).
    extern int moverect_internal(VTermRect dest, VTermRect src, void *user);
    extern int erase_internal(VTermRect rect, int selective, void *user);
    vterm_scroll_rect(rect, rows, 0, moverect_internal, erase_internal, h->vts);
}

// Roll the libvterm viewport down by `rows` rows. Symmetric to
// nativeScrollUp. The top `rows` rows become garbage — renderInto
// draws them as BG. To get back to the live screen the user must
// accept the BG header until the remote shell redraws.
JNIEXPORT void JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeScrollDown(
        JNIEnv *env, jclass clazz, jlong handle, jint rows) {
    (void) clazz;
    if (rows <= 0) return;
    jhandle_t *h = getHandle(env, handle);
    if (!h || !h->vts) return;
    if (rows > h->rows) rows = h->rows;
    VTermRect rect = {
        .start_row = 0, .end_row = h->rows,
        .start_col = 0, .end_col = h->cols,
    };
    extern int moverect_internal(VTermRect dest, VTermRect src, void *user);
    extern int erase_internal(VTermRect rect, int selective, void *user);
    vterm_scroll_rect(rect, -rows, 0, moverect_internal, erase_internal, h->vts);
}

JNIEXPORT void JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h) return;
    if (h->vt) vterm_free(h->vt);
    free(h->output_buf);
    free(h);
}
