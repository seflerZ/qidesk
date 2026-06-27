LOCAL_PATH 	:= $(call my-dir)
COMMON_ROOT	:= libs/deps/$(TARGET_ARCH_ABI)
PREBUILT_ROOT   := $(COMMON_ROOT)/root
GSTREAMER_ROOT  := $(COMMON_ROOT)/gstreamer
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_CFLAGS += -mllvm -page-size=16384
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

# Phase 3.7: build libvterm + vterm_jni via ndk-build (mirrors the
# moonlight-core pattern). We do NOT use $(call all-subdir-makefiles)
# here because the jni/ directory has symlinks into moonlight-core
# and evdev_reader — those belong to their own AGP modules and must
# not be built by this dispatcher. Include our own subdir explicitly.
include $(LOCAL_PATH)/libs/vterm_jni/Android.mk
