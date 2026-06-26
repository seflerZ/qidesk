cmake_minimum_required(VERSION 3.10)
project(libvterm C)

# Phase 3.7: vendored neovim/libvterm v0.3.3 (pure C, no ncurses, no
# 3rd-party deps). The upstream uses GNU Make + libtool; we provide
# CMake instead so AGP can build it as part of the project's
# externalNativeBuild { cmake { path 'jni/CMakeLists.txt' } }.
#
# Source layout: 9 .c files in src/, 2 .h files in include/ (vterm.h,
# vterm_keycodes.h), plus a few .h files internal to src/. No
# generated headers to worry about (Makefile's .tbl→.inc is for the
# demo programs, not the library).

# src/encoding.c depends on the auto-generated .inc tables (DECdrawing,
# uk). Upstream Makefile builds them from .tbl. The .inc files are
# checked into the upstream tarball, so we use them directly.
file(GLOB VTERM_SRC CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.c"
)
file(GLOB VTERM_INC_TABLES
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.inc"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/encoding/*.inc"
)

add_library(vterm-static STATIC ${VTERM_SRC} ${VTERM_INC_TABLES})
target_include_directories(vterm-static
    PUBLIC
        "${CMAKE_CURRENT_SOURCE_DIR}/include"
    PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src"
)
target_compile_options(vterm-static PRIVATE
    -Wall -Wpedantic -std=c99
)

# Shared version — built when AGP requests it. We use it for the JNI
# bridge (Step 3+) so the JNI .so can dlopen/link against libvterm
# symbols. Step 1/2 don't actually consume symbols yet (hello world
# only), but the target must exist so AGP's build doesn't fail.
add_library(vterm-shared SHARED ${VTERM_SRC} ${VTERM_INC_TABLES})
target_include_directories(vterm-shared
    PUBLIC
        "${CMAKE_CURRENT_SOURCE_DIR}/include"
    PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src"
)
target_compile_options(vterm-shared PRIVATE
    -Wall -Wpedantic -std=c99 -fPIC
)
set_target_properties(vterm-shared PROPERTIES
    OUTPUT_NAME vterm
    CLEAN_DIRECT_OUTPUT 1
)
set_target_properties(vterm-static PROPERTIES
    OUTPUT_NAME vterm
    CLEAN_DIRECT_OUTPUT 1
)
