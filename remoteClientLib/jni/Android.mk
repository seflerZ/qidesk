LOCAL_PATH 	:= $(call my-dir)
COMMON_ROOT	:= libs/deps/$(TARGET_ARCH_ABI)
PREBUILT_ROOT   := $(COMMON_ROOT)/root
GSTREAMER_ROOT  := $(COMMON_ROOT)/gstreamer

include $(call all-subdir-makefiles)
