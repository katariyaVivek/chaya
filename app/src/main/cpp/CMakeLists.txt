# whisper.cpp for Chaya's "Make subtitles": the version Chaya for Windows uses (desktop/engine/build_whisper.sh),
# fetched by its commit and built unmodified, on the processor only, with a small bridge (chaya_whisper.c) that
# hands its output to Kotlin as the JSON whisper-cli writes with -ojf.
cmake_minimum_required(VERSION 3.22.1)
project(chaya_whisper C CXX)

include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp
    # v1.9.5
    GIT_TAG d1be6fde11ac6e0407606b4e42fe72d34add8037
    GIT_SHALLOW FALSE
)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(whisper)

add_library(chaya_whisper SHARED chaya_whisper.c)
target_link_libraries(chaya_whisper PRIVATE whisper log)
# 16 KB pages (Android 15 and newer phones)
target_link_options(chaya_whisper PRIVATE "-Wl,-z,max-page-size=16384")
