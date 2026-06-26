cmake_minimum_required(VERSION 3.22.1)
project(vterm_jni LANGUAGES C CXX)

# Phase 3.7 Step 1 hello world. Validates NDK / CMake build pipeline
# works for this project (project's first native build).
# Replaced in Step 2 by libvterm + JNI bridge.
add_library(vterm SHARED src/hello.c)
target_link_libraries(vterm PRIVATE log)
