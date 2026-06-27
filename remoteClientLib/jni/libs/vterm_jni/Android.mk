# Android.mk for libvterm + JNI bridge (SSH Phase 3.7).
#
# Two libraries produced:
#   libvterm.so     — vendored neovim/libvterm v0.3.3, the pure-C
#                     terminal state machine. ~5000 lines, no
#                     external deps (no ncurses, no libtool, no
#                     glib). System.loadLibrary("vterm") picks it up.
#   libvterm_jni.so — Java_com_qihua_..._nativeXxx bridge that
#                     wraps VTerm* in a Java-side handle (SshTermStateMachine).
#                     Static-links libvterm so callers don't have to
#                     link two .so's. System.loadLibrary("vterm_jni").
#
# This mirrors the layout of remoteClientLib/jni/libs/deps/moonlight-android/
# app/src/main/jni/{Android.mk,moonlight-core/Android.mk}, which is the
# project's established pattern for native libs inside Gradle modules:
#   - per-library LOCAL_* variables in a subdir Android.mk
#   - explicit -Wl,-z,max-page-size=16384 for Android 15+ alignment
#   - APP_SUPPORT_FLEXIBLE_PAGE_SIZES + mllvm -page-size=16384 in the
#     top-level Application.mk for the AGP/ndk-build integration
#
# Path note: when AGP invokes ndk-build via externalNativeBuild, the
# working directory is an intermediates dir under build/, NOT the
# remoteClientLib/jni/ directory. The parent dispatcher
# (remoteClientLib/jni/Android.mk) sets LOCAL_PATH=$(call my-dir) so
# $(LOCAL_PATH) always resolves to remoteClientLib/jni/ regardless of
# cwd. We prefix all paths below with $(LOCAL_PATH) for the same
# reason moonlight-core's Android.mk does.
include $(CLEAR_VARS)

# ---- libvterm (vendored at libs/deps/libvterm/) ----
LOCAL_MODULE := vterm

# Upstream Makefile builds src/*.c (9 files). encoding.c depends on
# fullwidth.inc, which is checked into the tarball so we don't need
# to run tbl2inc_c.pl at build time.
LOCAL_SRC_FILES := \
    $(LOCAL_PATH)/libs/deps/libvterm/src/encoding.c \
    $(LOCAL_PATH)/libs/deps/libvterm/src/keyboard.c \
    $(LOCAL_PATH)/libs/deps/libvterm/src/mouse.c \
    $(LOCAL_PATH)/libs/deps/libvterm/src/parser.c \
    $(LOCAL_PATH)/libs/deps/libvterm/src/pen.c \
    $(LOCAL_PATH)/libs/deps/libvterm/src/screen.c \
    $(LOCAL_PATH)/libs/deps/libvterm/src/state.c \
    $(LOCAL_PATH)/libs/deps/libvterm/src/unicode.c \
    $(LOCAL_PATH)/libs/deps/libvterm/src/vterm.c

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/libs/deps/libvterm/include \
    $(LOCAL_PATH)/libs/deps/libvterm/src

LOCAL_CFLAGS := -Wall -Wpedantic -std=c99 -fPIC -O2 -include stdbool.h

# libvterm's own header has an inline function in vterm_keycodes.h that
# expects a VTerm * parameter; harmless on Android NDK.
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384,--exclude-libs,ALL

include $(BUILD_SHARED_LIBRARY)

# ---- JNI bridge (vterm_jni.c) ----
include $(CLEAR_VARS)
LOCAL_MODULE := vterm_jni

LOCAL_SRC_FILES := $(LOCAL_PATH)/src/vterm_jni.c

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/libs/deps/libvterm/include \
    $(LOCAL_PATH)/libs/deps/libvterm/src

LOCAL_CFLAGS := -Wall -std=c11 -O2 -fvisibility=hidden

# Static-link libvterm into the JNI bridge so we only ship one
# .so file alongside libvterm.so (Java code's load order:
#   System.loadLibrary("vterm");      // state machine
#   System.loadLibrary("vterm_jni");  // bridge, links statically
# We still build libvterm.so separately above because future
# native consumers (Phase 4+ OSC 52 / mouse helpers) may want
# to dlopen it without going through JNI.
LOCAL_STATIC_LIBRARIES := vterm
LOCAL_LDLIBS := -llog

LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384,--exclude-libs,ALL

include $(BUILD_SHARED_LIBRARY)