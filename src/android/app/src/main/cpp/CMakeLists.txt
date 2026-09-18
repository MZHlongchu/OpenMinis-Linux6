cmake_minimum_required(VERSION 3.22.1)
project(minis_native CXX C)

add_library(pty_bridge SHARED pty_bridge.c)

# T283: NDK signal handler for native crashes (SIGSEGV/SIGABRT/...).
# Separate library so loadLibrary() can pull it in independently of
# pty_bridge — the crash handler must be installed before any other
# native lib has a chance to fault.
add_library(minis_crash_handler SHARED crash_handler.cpp)

# [T-android-text-segmenter] CppJieba JNI bridge for TextSegmenter's Chinese
# path. cppjieba is header-only (no static lib to build); this version vendors
# limonp inline, so a single include root (cppjieba/include) is all it needs.
# Headers are included as "cppjieba/<Name>.hpp", so we add the parent dir.
add_library(jieba_jni SHARED jieba_jni.cpp)
target_include_directories(jieba_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/cppjieba/include
)

find_library(log-lib log)
target_link_libraries(pty_bridge ${log-lib})
# crash_handler.cpp uses _Unwind_Backtrace. NDK r28+ dropped shared
# libunwind.so; scripts/build_libunwind_aarch64.sh installs libunwind.a
# into the sysroot so -lunwind resolves.
find_library(UNWIND_LIB unwind)
if(UNWIND_LIB)
    target_link_libraries(minis_crash_handler ${log-lib} ${UNWIND_LIB})
else()
    target_link_libraries(minis_crash_handler ${log-lib} unwind)
endif()
target_link_libraries(jieba_jni ${log-lib})
