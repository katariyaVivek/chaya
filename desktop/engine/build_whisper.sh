#!/usr/bin/env bash
# Builds whisper.cpp's command-line program, which Chaya for Windows runs to make subtitles from a video's sound on
# the PC (core::speech). It reads a 16 kHz WAV (ffmpeg makes it) and a speech model Chaya fetches when first needed,
# and writes what was said with each word's time. This build runs on the processor: AVX2 and FMA, which every PC
# that runs Windows 11 has. It is one program with nothing beside it, under the MIT licence.
#
#   desktop/engine/build_whisper.sh windows OUT_DIR   # OUT_DIR/whisper-cli.exe, cross-compiled with mingw-w64
#   desktop/engine/build_whisper.sh host OUT_DIR      # OUT_DIR/whisper-cli for this computer, for the core's tests
#
# Needs git, cmake, a C++ compiler, and for windows the x86_64-w64-mingw32 toolchain with POSIX threads (apt:
# mingw-w64, g++-mingw-w64-x86-64-posix). The source is the whisper.cpp release below, checked by its commit.
set -euo pipefail

VERSION=v1.9.5
COMMIT=d1be6fde11ac6e0407606b4e42fe72d34add8037
SOURCE=https://github.com/ggml-org/whisper.cpp

target=${1:?windows or host}
out=$(realpath -m "${2:?an output folder}")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$VERSION" "$SOURCE" "$work/src"
actual=$(git -C "$work/src" rev-parse HEAD)
if [ "$actual" != "$COMMIT" ]; then
  echo "whisper.cpp $VERSION is commit $actual, expected $COMMIT. Not building it." >&2
  exit 1
fi

options=(
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF -DWHISPER_SDL2=OFF -DWHISPER_CURL=OFF -DWHISPER_FFMPEG=OFF
  # Not this computer's processor but every one since 2013: AVX2, FMA and F16C. ggml's own threads, not OpenMP's,
  # so nothing else needs to come with it.
  -DGGML_NATIVE=OFF -DGGML_AVX=ON -DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON -DGGML_OPENMP=OFF
)
case "$target" in
  windows)
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
    ;;
  host)
    program=whisper-cli
    strip=strip
    ;;
  *)
    echo "Build for windows or host, not $target." >&2
    exit 1
    ;;
esac

# Quiet unless it fails, then the end of what it said.
cmake -S "$work/src" -B "$work/build" -G Ninja "${options[@]}" >"$work/log" 2>&1 &&
  cmake --build "$work/build" --target whisper-cli -j"$(nproc)" >>"$work/log" 2>&1 ||
  { tail -n 60 "$work/log" >&2; exit 1; }
mkdir -p "$out"
cp "$work/build/bin/$program" "$out/$program"
"$strip" "$out/$program"

# MIT asks that the licence go with the program.
{
  echo "whisper-cli ($program), shipped with Chaya for Windows to make subtitles on the PC."
  echo "whisper.cpp $VERSION, built from $SOURCE at commit $COMMIT, unmodified (for Windows, with a header defining"
  echo "THREAD_POWER_THROTTLING_STATE as Windows' SDK does, which the build's mingw-w64 lacks), with:"
  echo "  ${options[*]}"
  echo "Built by desktop/engine/build_whisper.sh in https://github.com/katariyaVivek/chaya."
  echo
  cat "$work/src/LICENSE"
} >"$out/whisper-LICENSE.txt"
echo "Built $out/$program ($(du -h "$out/$program" | cut -f1))"
