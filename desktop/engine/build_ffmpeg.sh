#!/usr/bin/env bash
# Builds the ffmpeg Chaya for Windows carries, cut down to the one thing the app asks of it: copying a
# picture-only file and a sound-only file into one MP4, as they are (no re-encoding). It has no codecs, no
# network and no outside libraries, so it is a few MB and stays under the LGPL.
#
#   desktop/engine/build_ffmpeg.sh windows OUT_DIR   # OUT_DIR/ffmpeg.exe, cross-compiled with mingw-w64
#   desktop/engine/build_ffmpeg.sh host OUT_DIR      # OUT_DIR/ffmpeg for this computer, for development
#
# Needs git, make, a C compiler, and for windows the x86_64-w64-mingw32 toolchain (apt: mingw-w64).
# The source is the FFmpeg release below, checked by its commit, so the build is of that code and no other.
set -euo pipefail

VERSION=n8.1.3
COMMIT=1041abdc962f4cc4f394aa8de9dc5236c0c3b9e7
SOURCE=https://github.com/FFmpeg/FFmpeg

target=${1:?windows or host}
out=$(realpath -m "${2:?an output folder}")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$VERSION" "$SOURCE" "$work/src"
actual=$(git -C "$work/src" rev-parse HEAD)
if [ "$actual" != "$COMMIT" ]; then
  echo "FFmpeg $VERSION is commit $actual, expected $COMMIT. Not building it." >&2
  exit 1
fi

options=(
  --disable-everything --disable-autodetect --disable-network --disable-doc --disable-debug --disable-x86asm
  --enable-small --disable-ffplay --disable-ffprobe --enable-ffmpeg
  --enable-protocol=file,pipe
  # MP4 and M4A in (mov), WebM in (matroska); MP4 out, and Matroska for what MP4 cannot hold.
  --enable-demuxer=mov,matroska
  --enable-muxer=mp4,mov,ipod,matroska,webm
  --enable-parser=h264,hevc,av1,vp9,aac,opus
  --enable-bsf=aac_adtstoasc,h264_mp4toannexb,hevc_mp4toannexb,vp9_superframe
)
case "$target" in
  windows)
    options+=(--target-os=mingw32 --arch=x86_64 --cross-prefix=x86_64-w64-mingw32- --pkg-config=false
      --extra-ldflags=-static)
    program=ffmpeg.exe
    ;;
  host)
    program=ffmpeg
    ;;
  *)
    echo "Build for windows or host, not $target." >&2
    exit 1
    ;;
esac

(cd "$work/src" && ./configure "${options[@]}" >/dev/null && make -j"$(nproc)" "$program" >/dev/null)
mkdir -p "$out"
cp "$work/src/$program" "$out/$program"
if [ "$target" = windows ]; then x86_64-w64-mingw32-strip "$out/$program"; else strip "$out/$program"; fi

# The LGPL asks that the licence and the way to the source go with the program.
{
  echo "ffmpeg ($program), shipped with Chaya for Windows to join picture and sound into one file."
  echo "FFmpeg $VERSION, built from $SOURCE at commit $COMMIT, unmodified, with:"
  echo "  ${options[*]}"
  echo "Built by desktop/engine/build_ffmpeg.sh in https://github.com/katariyaVivek/chaya."
  echo
  cat "$work/src/COPYING.LGPLv2.1"
} >"$out/ffmpeg-LICENSE.txt"
echo "Built $out/$program ($(du -h "$out/$program" | cut -f1))"
