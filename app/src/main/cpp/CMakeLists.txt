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

# The same whisper.cpp built a second time for phones whose cores do half-precision arithmetic and dot products
# (ARMv8.2: every Cortex-A55, A75 and newer): its fast paths are compiled in only when the target says so, and it does
# no choosing at run time without loading its parts from files, which an APK's uncompressed libraries are not. The
# baseline above stays for older phones (Cortex-A53 alone). Whisper.kt picks one by the phone's /proc/cpuinfo, as
# whisper.cpp's own Android example does. Built as a separate CMake project so the two never share targets.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    include(ExternalProject)
    set(FAST_DIR ${CMAKE_BINARY_DIR}/whisper-fp16)
    set(FAST_LIBS
        ${FAST_DIR}/src/libwhisper.a
        ${FAST_DIR}/ggml/src/libggml.a
        ${FAST_DIR}/ggml/src/libggml-cpu.a
        ${FAST_DIR}/ggml/src/libggml-base.a)
    ExternalProject_Add(whisper_fp16
        SOURCE_DIR ${whisper_SOURCE_DIR}
        BINARY_DIR ${FAST_DIR}
        DOWNLOAD_COMMAND ""
        CMAKE_GENERATOR ${CMAKE_GENERATOR}
        CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM}
            -DANDROID_STL=${ANDROID_STL}
            -DCMAKE_BUILD_TYPE=Release
            -DCMAKE_C_FLAGS=${CMAKE_C_FLAGS}
            -DCMAKE_CXX_FLAGS=${CMAKE_CXX_FLAGS}
            -DBUILD_SHARED_LIBS=OFF
            -DWHISPER_BUILD_EXAMPLES=OFF
            -DWHISPER_BUILD_TESTS=OFF
            -DWHISPER_BUILD_SERVER=OFF
            -DGGML_OPENMP=OFF
            -DGGML_NATIVE=OFF
            -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16
        INSTALL_COMMAND ""
        BUILD_BYPRODUCTS ${FAST_LIBS})

    add_library(chaya_whisper_fp16 SHARED chaya_whisper.c)
    add_dependencies(chaya_whisper_fp16 whisper_fp16)
    target_include_directories(chaya_whisper_fp16 PRIVATE ${whisper_SOURCE_DIR}/include ${whisper_SOURCE_DIR}/ggml/include)
    target_link_libraries(chaya_whisper_fp16 PRIVATE ${FAST_LIBS} log m)
    # whisper.cpp is C++: linked with the C++ library, as the baseline's whisper target brings it
    set_target_properties(chaya_whisper_fp16 PROPERTIES LINKER_LANGUAGE CXX)
    target_link_options(chaya_whisper_fp16 PRIVATE "-Wl,-z,max-page-size=16384")
endif()
