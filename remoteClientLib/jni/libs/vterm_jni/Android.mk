# Android.mk for libvterm + JNI bridge (SSH Phase 3.7).
#
# Two libraries produced:
#   libvterm.so     — vendored neovim/libvterm v0.3.3, the pure-C
#                     terminal state machine. ~5000 lines, no
#                     external deps (no ncurses, no libtool, no
#                     glib). System.loadLibrary("vterm") picks it up.
#   libvterm_jni.so — Java_com_qihua_..._nativeXxx bridge that
#                     wraps VTerm* in a Java-side handle
#                     (SshTermStateMachine). System.loadLibrary("vterm_jni").
#
# Layout mirrors remoteClientLib/jni/libs/deps/moonlight-android/
# app/src/main/jni/{Android.mk,moonlight-core/Android.mk}: the
# top-level dispatcher (jni/Android.mk) sets LOCAL_PATH=jni/, this
# file lives in jni/libs/vterm_jni/, and we restore LOCAL_PATH to
# jni/libs/vterm_jni/ via MY_LOCAL_PATH so relative LOCAL_SRC_FILES
# and LOCAL_C_INCLUDES below resolve against THIS directory — not
# the dispatcher. ndk-build then prepends NDK_PROJECT_PATH (.) on
# top, so the final src path is `./libs/deps/libvterm/src/...`
# relative to NDK_PROJECT_PATH=remoteClientLib/.
MY_LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_PATH := $(MY_LOCAL_PATH)

# ---- libvterm (vendored at libs/deps/libvterm/) ----
LOCAL_MODULE := vterm

LOCAL_SRC_FILES := \
    ../deps/libvterm/src/encoding.c \
    ../deps/libvterm/src/keyboard.c \
    ../deps/libvterm/src/mouse.c \
    ../deps/libvterm/src/parser.c \
    ../deps/libvterm/src/pen.c \
    ../deps/libvterm/src/screen.c \
    ../deps/libvterm/src/state.c \
    ../deps/libvterm/src/unicode.c \
    ../deps/libvterm/src/vterm.c

# Absolute include paths. ndk-build does NOT prefix LOCAL_C_INCLUDES
# with NDK_PROJECT_PATH (unlike LOCAL_SRC_FILES), so relative paths
# here resolve against ndk-build's cwd which depends on the caller.
# prepare_project.sh cwd=remoteClientLib/, AGP cwd=intermediates/.
# Absolute paths work for both.
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/../deps/libvterm/include \
    $(LOCAL_PATH)/../deps/libvterm/src

LOCAL_CFLAGS := -Wall -Wpedantic -std=c99 -fPIC -O2 -include stdbool.h

LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384,--exclude-libs,ALL

include $(BUILD_SHARED_LIBRARY)

# ---- JNI bridge (vterm_jni.c) ----
include $(CLEAR_VARS)
LOCAL_PATH := $(MY_LOCAL_PATH)
LOCAL_MODULE := vterm_jni

LOCAL_SRC_FILES := ../../src/vterm_jni.c

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/../deps/libvterm/include \
    $(LOCAL_PATH)/../deps/libvterm/src

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