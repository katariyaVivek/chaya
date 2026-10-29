#!/usr/bin/env bash
# Builds whisper.cpp's command-line program, which Chaya for Windows runs to make subtitles from a video's sound on
# the PC (core::speech). It reads a 16 kHz WAV (ffmpeg makes it) and a speech model Chaya fetches when first needed,
# and writes what was said with each word's time. The processor build runs on AVX2 and FMA, which every PC that runs
# Windows 11 has. The Vulkan build also runs on the graphics card (NVIDIA, AMD or Intel) through the Vulkan driver
# that comes with it, and on the processor where there is none; Chaya runs it when Windows has Vulkan
# (vulkan-1.dll), and the processor build when not. Each is one program with nothing beside it, under the MIT
# licence.
#
#   desktop/engine/build_whisper.sh windows OUT_DIR         # OUT_DIR/whisper-cli.exe, cross-compiled with mingw-w64
#   desktop/engine/build_whisper.sh windows-vulkan OUT_DIR  # OUT_DIR/whisper-cli-vulkan.exe, the same with Vulkan
#   desktop/engine/build_whisper.sh host OUT_DIR            # OUT_DIR/whisper-cli for this computer, for the tests
#
# Needs git, cmake, a C++ compiler, and for windows the x86_64-w64-mingw32 toolchain with POSIX threads (apt:
# mingw-w64, g++-mingw-w64-x86-64-posix); for windows-vulkan also glslc (apt: glslc), which compiles ggml's shaders.
# The sources are the releases below, each checked by its commit.
set -euo pipefail

VERSION=v1.9.5
COMMIT=d1be6fde11ac6e0407606b4e42fe72d34add8037
SOURCE=https://github.com/ggml-org/whisper.cpp
# For windows-vulkan, Khronos' headers for Vulkan and SPIR-V, and the list of what vulkan-1.dll exports (from its
# loader's source), which mingw-w64's dlltool makes the import library from: nothing of them but the headers is in
# the program, and vulkan-1.dll is Windows' own, installed with the graphics driver.
VULKAN=v1.4.309
VULKAN_HEADERS=952f776f6573aafbb62ea717d871cd1d6816c387
VULKAN_LOADER=809941a4ca137df69dc9c6e8eb456bd70309197c
SPIRV=vulkan-sdk-1.4.309.0
SPIRV_HEADERS=09913f088a1197aba4aefd300a876b2ebbaa3391
KHRONOS=https://github.com/KhronosGroup

target=${1:?windows or host}
out=$(realpath -m "${2:?an output folder}")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Clones repository $1 at tag $2 into $4, and checks it is commit $3.
fetch() {
  git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$2" "$1" "$4"
  local actual
  actual=$(git -C "$4" rev-parse HEAD)
  if [ "$actual" != "$3" ]; then
    echo "$1 $2 is commit $actual, expected $3. Not building it." >&2
    exit 1
  fi
}
fetch "$SOURCE" "$VERSION" "$COMMIT" "$work/src"

options=(
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF -DWHISPER_SDL2=OFF -DWHISPER_CURL=OFF -DWHISPER_FFMPEG=OFF
  # Not this computer's processor but every one since 2013: AVX2, FMA and F16C. ggml's own threads, not OpenMP's,
  # so nothing else needs to come with it.
  -DGGML_NATIVE=OFF -DGGML_AVX=ON -DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON -DGGML_OPENMP=OFF
)
case "$target" in
  windows | windows-vulkan)
    # Ubuntu's mingw-w64 headers predate the Windows 11 hint ggml gives its threads so Windows does not park them on a
    # few cores (THREAD_POWER_THROTTLING_STATE). Its definition, as Windows' SDK has it, comes in ahead of ggml's C.
    cat >"$work/compat.h" <<'HEADER'
#if defined(_WIN32)
#include <windows.h>
#ifndef THREAD_POWER_THROTTLING_CURRENT_VERSION
typedef struct _THREAD_POWER_THROTTLING_STATE {
  ULONG Version;
  ULONG ControlMask;
  ULONG StateMask;
} THREAD_POWER_THROTTLING_STATE;
#define THREAD_POWER_THROTTLING_CURRENT_VERSION 1
#define THREAD_POWER_THROTTLING_EXECUTION_SPEED 0x1
#endif
#endif
HEADER
    options+=(-DCMAKE_SYSTEM_NAME=Windows -DCMAKE_SYSTEM_PROCESSOR=x86_64 "-DCMAKE_C_FLAGS=-include $work/compat.h"
      -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc-posix -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++-posix
      -DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres
      "-DCMAKE_EXE_LINKER_FLAGS=-static -static-libgcc -static-libstdc++")
    program=whisper-cli.exe
    strip=x86_64-w64-mingw32-strip
    name=whisper-cli.exe
    licence=whisper-LICENSE.txt
    if [ "$target" = windows-vulkan ]; then
      fetch "$KHRONOS/Vulkan-Headers" "$VULKAN" "$VULKAN_HEADERS" "$work/vulkan-headers"
      fetch "$KHRONOS/Vulkan-Loader" "$VULKAN" "$VULKAN_LOADER" "$work/vulkan-loader"
      fetch "$KHRONOS/SPIRV-Headers" "$SPIRV" "$SPIRV_HEADERS" "$work/spirv-headers"
      mkdir -p "$work/vulkan/include" "$work/vulkan/lib"
      cp -r "$work/vulkan-headers/include/." "$work/spirv-headers/include/." "$work/vulkan/include/"
      x86_64-w64-mingw32-dlltool -d "$work/vulkan-loader/loader/vulkan-1.def" -D vulkan-1.dll \
        -l "$work/vulkan/lib/libvulkan-1.a"
      # ggml looks for SPIR-V's headers by their CMake package, which installing them writes.
      cmake -S "$work/spirv-headers" -B "$work/spirv-build" -DCMAKE_INSTALL_PREFIX="$work/spirv" \
        -DSPIRV_HEADERS_ENABLE_TESTS=OFF >"$work/log" 2>&1 && cmake --install "$work/spirv-build" >>"$work/log" 2>&1 ||
        { tail -n 30 "$work/log" >&2; exit 1; }
      options+=(-DGGML_VULKAN=ON "-DVulkan_INCLUDE_DIR=$work/vulkan/include"
        "-DVulkan_LIBRARY=$work/vulkan/lib/libvulkan-1.a" "-DVulkan_GLSLC_EXECUTABLE=$(command -v glslc)"
        "-DSPIRV-Headers_DIR=$work/spirv/share/cmake/SPIRV-Headers")
      name=whisper-cli-vulkan.exe
      licence=whisper-vulkan-LICENSE.txt
    fi
    ;;
  host)
    program=whisper-cli
    strip=strip
    name=whisper-cli
    licence=whisper-LICENSE.txt
    ;;
  *)
    echo "Build for windows, windows-vulkan or host, not $target." >&2
    exit 1
    ;;
esac

# Quiet unless it fails, then the end of what it said.
cmake -S "$work/src" -B "$work/build" -G Ninja "${options[@]}" >"$work/log" 2>&1 &&
  cmake --build "$work/build" --target whisper-cli -j"$(nproc)" >>"$work/log" 2>&1 ||
  { tail -n 60 "$work/log" >&2; exit 1; }
mkdir -p "$out"
cp "$work/build/bin/$program" "$out/$name"
"$strip" "$out/$name"

# MIT asks that the licence go with the program.
{
  echo "whisper-cli ($name), shipped with Chaya for Windows to make subtitles on the PC."
  echo "whisper.cpp $VERSION, built from $SOURCE at commit $COMMIT, unmodified (for Windows, with a header defining"
  echo "THREAD_POWER_THROTTLING_STATE as Windows' SDK does, which the build's mingw-w64 lacks), with:"
  echo "  ${options[*]}"
  echo "Built by desktop/engine/build_whisper.sh in https://github.com/katariyaVivek/chaya."
  echo
  cat "$work/src/LICENSE"
  if [ "$target" = windows-vulkan ]; then
    echo
    echo "With the headers of Khronos' Vulkan-Headers $VULKAN ($KHRONOS/Vulkan-Headers, commit $VULKAN_HEADERS) and"
    echo "SPIRV-Headers $SPIRV ($KHRONOS/SPIRV-Headers, commit $SPIRV_HEADERS), under these licences:"
    echo
    cat "$work/vulkan-headers/LICENSE.md"
    echo
    cat "$work/spirv-headers/LICENSE"
  fi
} >"$out/$licence"
echo "Built $out/$name ($(du -h "$out/$name" | cut -f1))"
