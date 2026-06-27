cmake_minimum_required(VERSION 3.22.1)
project(vterm_jni LANGUAGES C CXX)

# Phase 3.7 Step 2: vendor neovim/libvterm v0.3.3 (the pure C, no
# ncurses version Neovim uses internally). TragicWarrior/libvterm
# turned out to be a fork that hard-depends on ncurses (vterm.h
# uses cchar_t, attr_t, MEVENT, bool from curses), which doesn't
# work on Android NDK. The neovim/libvterm mirror is the canonical
# pure C state machine. Last commit 2025-11-21, still receiving
# action bumps.
#
# We provide our own CMakeLists inside the vendored libvterm
# directory (see libs/deps/libvterm/CMakeLists.txt) because upstream
# uses GNU Make + libtool, not CMake.
#
# 16KB page alignment: Android 15+ requires all native libraries to
# be aligned on 16KB page boundaries. Default NDK toolchain emits
# 4KB-aligned ELF LOAD segments, which the new kernel refuses to
# load. Pass -z max-page-size=16384 to ld to bump the alignment.
# Apply to both vterm-shared (built by libvterm's CMake) and our
# vterm_jni bridge. Static lib vterm-static is consumed by the
# shared lib, so the static's sections also get re-aligned at the
# final link.
add_link_options("-Wl,-z,max-page-size=16384")
add_subdirectory(libs/deps/libvterm)

# JNI bridge — Step 3: full libvterm JNI bridge (replaces Step 1
# hello world). Library name "vterm_jni" maps to
# System.loadLibrary("vterm_jni") on the Java side (underscore
# convention for Android).
add_library(vterm_jni SHARED
    src/vterm_jni.c
)
target_link_libraries(vterm_jni PRIVATE vterm-static log)
target_include_directories(vterm_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/libs/deps/libvterm/include"
)

