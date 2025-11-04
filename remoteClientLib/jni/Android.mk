LOCAL_PATH 	:= $(call my-dir)
COMMON_ROOT	:= libs/deps/$(TARGET_ARCH_ABI)
PREBUILT_ROOT   := $(COMMON_ROOT)/root
GSTREAMER_ROOT  := $(COMMON_ROOT)/gstreamer
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_CFLAGS += -mllvm -page-size=16384
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

include $(call all-subdir-makefiles)
