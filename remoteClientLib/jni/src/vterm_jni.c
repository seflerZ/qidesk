// Phase 3.7 Step 3: JNI bridge to libvterm (neovim/libvterm v0.3.3).
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
} jhandle_t;

// Damage callback: called by libvterm when cells in [start_row,
// end_row) of the screen change. We just append to the ring.
static int jni_damage(VTermRect rect, void *user) {
    jhandle_t *h = (jhandle_t *) user;
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

// settermprop: ignore. libvterm sends these for OSC sequences; we
// don't act on title/icon-name changes (Phase 4+ could route to UI).
static int jni_settermprop(VTermProp prop, VTermValue *val, void *user) {
    (void) prop; (void) val; (void) user;
    return 1;
}

static int jni_bell(void *user) { (void) user; return 1; }

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

    h->vts = vterm_obtain_screen(h->vt);
    VTermScreenCallbacks cb = {
        .damage    = jni_damage,
        .moverect  = jni_moverect,
        .resize    = jni_resize,
        .movecursor = jni_movecursor,
        .settermprop = jni_settermprop,
        .bell      = jni_bell,
    };
    vterm_screen_set_callbacks(h->vts, &cb, h);
    vterm_screen_enable_altscreen(h->vts, 1);

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

JNIEXPORT void JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeWriteInput(
        JNIEnv *env, jclass clazz, jlong handle, jint codepoint) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h) return;
    if (codepoint > 0 && codepoint < 0x110000) {
        vterm_keyboard_unichar(h->vt, (uint32_t) codepoint, VTERM_MOD_NONE);
    }
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
static int packAttrs(VTermScreenCellAttrs *a) {
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
// We convert to 0xRRGGBB packed; default is unspecified, in which
// case libvterm reports the default fg/bg (callers handle the
// default-substitution if needed; for now we pass through the raw
// RGB which the default color resolves to Solarized base0/03 at
// the VTermScreen level via vterm_screen_set_default_colors).
static int colorToRgb(VTermColor *c) {
    if (VTERM_COLOR_IS_DEFAULT_FG(c)) return 0xFF839496;  // Solarized base0
    if (VTERM_COLOR_IS_DEFAULT_BG(c)) return 0xFF002B36;  // Solarized base03
    if (VTERM_COLOR_IS_INDEXED(c)) {
        // 8 standard ANSI + 8 bright ANSI; extended 16-255 we just
        // approximate as base0 (libvterm doesn't expose a 256-color
        // palette by default — apps that want truecolor set RGB mode).
        switch (c->indexed.idx) {
            case 0:  return 0xFF073642;  // bright black
            case 1:  return 0xFFDC322F;  // red
            case 2:  return 0xFF859900;  // green
            case 3:  return 0xFFB58900;  // yellow
            case 4:  return 0xFF268BD2;  // blue
            case 5:  return 0xFFD33682;  // magenta
            case 6:  return 0xFF2AA198;  // cyan
            case 7:  return 0xFFEEE8D5;  // white
            case 8:  return 0xFF002B36;  // bright black (bg)
            case 9:  return 0xFFCB4B16;  // bright red
            case 10: return 0xFF586E75;  // bright green
            case 11: return 0xFF657B83;  // bright yellow
            case 12: return 0xFF839496;  // bright blue
            case 13: return 0xFF6C71C4;  // bright magenta
            case 14: return 0xFF93A1A1;  // bright cyan
            case 15: return 0xFFFDF6E3;  // bright white
            default: return 0xFF839496;  // unknown indexed → base0
        }
    }
    // 24-bit RGB
    return (0xFF << 24) | ((c->rgb.red   & 0xFF) << 16)
                         | ((c->rgb.green & 0xFF) << 8)
                         |  (c->rgb.blue  & 0xFF);
}

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

    // Build TermCell Java object
    jclass cls = (*env)->FindClass(env, "com/qihua/bVNC/ssh/libvterm/SshTermStateMachine$TermCell");
    if (!cls) return NULL;
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "()V");
    jobject obj = (*env)->NewObject(env, cls, ctor);
    jfieldID fcp = (*env)->GetFieldID(env, cls, "codepoint", "I");
    jfieldID fwid = (*env)->GetFieldID(env, cls, "width", "I");
    jfieldID ffg = (*env)->GetFieldID(env, cls, "fg", "I");
    jfieldID fbg = (*env)->GetFieldID(env, cls, "bg", "I");
    jfieldID fattrs = (*env)->GetFieldID(env, cls, "attrs", "I");

    (*env)->SetIntField(env, obj, fcp, (jint) cell.chars[0]);
    (*env)->SetIntField(env, obj, fwid, cell.width);
    (*env)->SetIntField(env, obj, ffg, colorToRgb(&cell.fg));
    (*env)->SetIntField(env, obj, fbg, colorToRgb(&cell.bg));
    (*env)->SetIntField(env, obj, fattrs, packAttrs(&cell.attrs));

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

JNIEXPORT void JNICALL
Java_com_qihua_bVNC_ssh_libvterm_SshTermStateMachine_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    jhandle_t *h = getHandle(env, handle);
    if (!h) return;
    if (h->vt) vterm_free(h->vt);
    free(h);
}
