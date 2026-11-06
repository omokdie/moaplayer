# JNI wrapper of Media3's FFmpeg audio decoder (ffmpeg_jni.cc, from Media3 1.11.1).
# Adapted from libraries/decoder_ffmpeg/src/main/jni/CMakeLists.txt (Apache License 2.0): the FFmpeg static
# libraries and headers come from android/scripts/build-ffmpeg-android.sh, one folder per ABI:
#   ${FFMPEG_DIR}/<abi>/include, ${FFMPEG_DIR}/<abi>/lib/lib{avcodec,swresample,avutil}.a
cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)

set(CMAKE_CXX_STANDARD 11)

project(libffmpegJNI C CXX)

if(NOT DEFINED FFMPEG_DIR)
    set(FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../ffmpeg-android")
endif()
set(ffmpeg_abi_dir "${FFMPEG_DIR}/${ANDROID_ABI}")

foreach(ffmpeg_lib avutil swresample avcodec)
    add_library(${ffmpeg_lib} STATIC IMPORTED)
    set_target_properties(${ffmpeg_lib} PROPERTIES
            IMPORTED_LOCATION ${ffmpeg_abi_dir}/lib/lib${ffmpeg_lib}.a)
endforeach()

include_directories(${ffmpeg_abi_dir}/include)
find_library(android_log_lib log)

add_library(ffmpegJNI SHARED ffmpeg_jni.cc)

set_target_properties(ffmpegJNI PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)

target_link_libraries(ffmpegJNI
        PRIVATE android
        PRIVATE swresample
        PRIVATE avcodec
        PRIVATE avutil
        PRIVATE ${android_log_lib})

# keep FFmpeg's own symbols private to this library (smaller, no clashes with other native code)
target_link_options(ffmpegJNI PRIVATE "-Wl,--exclude-libs,ALL")

# Needed for "arm64-v8a" from NDK 23.1.7779620 on (see ExoPlayer issue 9933).
# 16 KB page alignment: newer 64-bit Android devices refuse libraries aligned for 4 KB pages only.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(ffmpegJNI PRIVATE "-Wl,-Bsymbolic" "-Wl,-z,max-page-size=16384")
endif()
