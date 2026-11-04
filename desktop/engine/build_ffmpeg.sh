#!/usr/bin/env bash
# Builds the ffmpeg Chaya for Windows carries, cut down to what the app asks of it:
#   - copying a picture-only file and a sound-only file into one MP4, and a downloaded stream (MPEG-TS, fragmented
#     MP4 or bare AAC) into an MP4, as they are (no re-encoding);
#   - turning a saved video's sound into the 16 kHz mono WAV whisper.cpp hears, for subtitles made on the PC, and
#     into 8 kHz WAV for the clip timeline's sound track;
#   - exporting clips (step 8): each cut exactly from a saved video, scaled, with its subtitles drawn on by libass,
#     and encoded as H.264 by Windows' own encoder (Media Foundation, which uses the graphics card where it can), or
#     as MPEG-4 where that is missing (and on Linux, for the tests), with AAC sound, its loudness evened when asked;
#     or as a GIF.
# It has no network. Its libraries, built here from their own pinned sources and linked in, are FreeType, FriBidi,
# HarfBuzz and libass (FreeType License, LGPL 2.1, MIT, ISC); with them and without x264 the whole stays under the
# LGPL. The app fetches streams itself; ffmpeg only reads the files it saved.
#
#   desktop/engine/build_ffmpeg.sh windows OUT_DIR   # OUT_DIR/ffmpeg.exe, cross-compiled with mingw-w64
#   desktop/engine/build_ffmpeg.sh host OUT_DIR      # OUT_DIR/ffmpeg for this computer, for development and tests
#
# Needs git, make, a C and C++ compiler, meson, ninja, nasm, pkg-config, and for windows the x86_64-w64-mingw32
# toolchain (apt: mingw-w64 meson ninja-build nasm pkg-config). Every source is a release checked by its commit, so
# the build is of that code and no other.
set -euo pipefail

VERSION=n8.1.3
COMMIT=1041abdc962f4cc4f394aa8de9dc5236c0c3b9e7
SOURCE=https://github.com/FFmpeg/FFmpeg
# name, release, commit, source: the libraries libass needs, then libass.
LIBRARIES=(
  "freetype VER-2-14-3 0a0221a1347e2f1e07c395263540026e9a0aa7c7 https://github.com/freetype/freetype"
  "fribidi v1.0.17 b93119f5fdc7ea47672cc304c1455ffa6dfe7536 https://github.com/fribidi/fribidi"
  "harfbuzz 14.6.0 c7a7457b7385f33178e8cf87615ca077a810bbe7 https://github.com/harfbuzz/harfbuzz"
  "libass 0.17.4 bbb3c7f1570a4a021e52683f3fbdf74fe492ae84 https://github.com/libass/libass"
)

target=${1:?windows or host}
out=$(realpath -m "${2:?an output folder}")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Clones `source` at `release` into `dir`, and stops unless it is `commit`.
fetch() {
  local release=$1 commit=$2 source=$3 dir=$4
  git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$release" "$source" "$dir"
  local actual
  actual=$(git -C "$dir" rev-parse HEAD)
  if [ "$actual" != "$commit" ]; then
    echo "$source $release is commit $actual, expected $commit. Not building it." >&2
    exit 1
  fi
}

case "$target" in
  windows) program=ffmpeg.exe ;;
  host) program=ffmpeg ;;
  *)
    echo "Build for windows or host, not $target." >&2
    exit 1
    ;;
esac

# The libraries, static, into $prefix, where ffmpeg's configure finds them through pkg-config.
prefix="$work/prefix"
mkdir -p "$prefix"
meson_options=(--prefix "$prefix" --libdir lib --default-library static --buildtype release -Db_ndebug=true
  --wrap-mode nodownload)
if [ "$target" = windows ]; then
  cat >"$work/cross.txt" <<CROSS
[binaries]
c = 'x86_64-w64-mingw32-gcc'
cpp = 'x86_64-w64-mingw32-g++'
ar = 'x86_64-w64-mingw32-ar'
strip = 'x86_64-w64-mingw32-strip'
windres = 'x86_64-w64-mingw32-windres'
pkg-config = 'pkg-config'
[host_machine]
system = 'windows'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
CROSS
  meson_options+=(--cross-file "$work/cross.txt")
  # Fonts come from Windows itself (DirectWrite), with its fallback for scripts a font lacks.
  fonts=(-Ddirectwrite=enabled -Dfontconfig=disabled)
else
  # On Linux, for the tests, fonts come from the folder the filter is given.
  fonts=(-Ddirectwrite=disabled -Dfontconfig=disabled -Drequire-system-font-provider=false)
fi
export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
export PKG_CONFIG_PATH="$prefix/lib/pkgconfig"
declare -A library_options=(
  [freetype]="-Dbrotli=disabled -Dbzip2=disabled -Dharfbuzz=disabled -Dpng=disabled -Dzlib=disabled -Dtests=disabled"
  [fribidi]="-Ddocs=false -Dbin=false -Dtests=false -Ddeprecated=false"
  [harfbuzz]="-Dglib=disabled -Dgobject=disabled -Dcairo=disabled -Dchafa=disabled -Dicu=disabled -Dfreetype=disabled
    -Dpng=disabled -Dzlib=disabled -Dtests=disabled -Ddocs=disabled -Dutilities=disabled -Dintrospection=disabled
    -Dsubset=disabled -Draster=disabled -Dvector=disabled"
  [libass]="${fonts[*]} -Dasm=disabled -Dlibunibreak=disabled -Dtest=disabled -Dcompare=disabled -Dprofile=disabled
    -Dfuzz=disabled -Dcheckasm=disabled"
)
licences=()
for library in "${LIBRARIES[@]}"; do
  read -r name release commit source <<<"$library"
  fetch "$release" "$commit" "$source" "$work/$name"
  # shellcheck disable=SC2086 # the options are words
  meson setup "$work/build-$name" "$work/$name" "${meson_options[@]}" ${library_options[$name]} >"$work/$name.log" 2>&1 \
    && ninja -C "$work/build-$name" install >>"$work/$name.log" 2>&1 \
    || { tail -40 "$work/$name.log" >&2; echo "Building $name failed." >&2; exit 1; }
  licences+=("$name $release, from $source at commit $commit")
done

fetch "$VERSION" "$COMMIT" "$SOURCE" "$work/src"
options=(
  --disable-everything --disable-autodetect --disable-network --disable-doc --disable-debug
  --enable-small --disable-ffplay --disable-ffprobe --enable-ffmpeg
  --enable-protocol=file,pipe
  # MP4 and M4A in (mov), WebM in (matroska), and HLS streams' pieces: MPEG-TS, and bare AAC or MP3 sound; and
  # sound saved on its own as Ogg, FLAC or WAV. MP4 out, Matroska for what MP4 cannot hold, and WAV for whisper.cpp.
  # ASS in: the subtitles an exported clip is drawn with.
  --enable-demuxer=mov,matroska,mpegts,aac,mp3,ogg,flac,wav,ass
  --enable-muxer=mp4,mov,ipod,matroska,webm,wav,gif
  --enable-parser=h264,hevc,av1,vp9,aac,opus,mpegaudio,ac3
  --enable-bsf=aac_adtstoasc,h264_mp4toannexb,hevc_mp4toannexb,vp9_superframe
  # Sound of every kind Chaya saves (AAC, MP3, Opus, Vorbis, FLAC, PCM); pictures of every kind it saves (H.264,
  # HEVC, VP8, VP9), for export; and the ASS subtitles libass draws.
  --enable-decoder=aac,mp3float,mp3,opus,vorbis,flac,pcm_s16le,pcm_s24le,pcm_f32le
  --enable-decoder=h264,hevc,vp8,vp9,mpeg4,ass,ssa
  # PCM for whisper.cpp and the timeline; AAC and MPEG-4 for export (H.264 on Windows, below); GIF for a clip as one.
  --enable-encoder=pcm_s16le,aac,mpeg4,gif
  # Even loudness for an exported clip's sound (loudnorm, EBU R128).
  --enable-filter=aresample,aformat,anull,abuffer,abuffersink,loudnorm
  # A GIF's frames at its own rate, and a palette made for the clip (split, palettegen, paletteuse).
  --enable-filter=buffer,buffersink,null,format,scale,setsar,subtitles,fps,split,palettegen,paletteuse
  --enable-swresample --enable-swscale --enable-libass
  --pkg-config=pkg-config --pkg-config-flags=--static
  # HarfBuzz is C++.
  --extra-libs=-lstdc++
)
case "$target" in
  windows)
    # h264_mf: Windows' own H.264 encoder (Media Foundation), which hands frames over through Direct3D 11.
    options+=(--target-os=mingw32 --arch=x86_64 --cross-prefix=x86_64-w64-mingw32- --extra-ldflags=-static
      --enable-mediafoundation --enable-d3d11va --enable-encoder=h264_mf)
    ;;
esac

(cd "$work/src" && { ./configure "${options[@]}" >"$work/configure.log" 2>&1 || { tail -30 ffbuild/config.log >&2; exit 1; }; } \
  && make -j"$(nproc)" "$program" >"$work/make.log" 2>&1) || { tail -40 "$work/make.log" >&2 || true; exit 1; }
mkdir -p "$out"
cp "$work/src/$program" "$out/$program"
if [ "$target" = windows ]; then x86_64-w64-mingw32-strip "$out/$program"; else strip "$out/$program"; fi

# The LGPL asks that the licence and the way to the source go with the program; the libraries' licences ask that
# theirs go with it too, and FreeType's that it be credited.
{
  echo "ffmpeg ($program), shipped with Chaya for Windows to join picture and sound, save streams as MP4, turn a"
  echo "video's sound into WAV for subtitles made on the PC and for the clip timeline, and export clips with their"
  echo "subtitles drawn on."
  echo "FFmpeg $VERSION, built from $SOURCE at commit $COMMIT, unmodified, with:"
  echo "  ${options[*]}"
  echo "Linked with these libraries, each built unmodified from its source:"
  for line in "${licences[@]}"; do echo "  $line"; done
  echo "Portions of this software are copyright (c) The FreeType Project (https://freetype.org). All rights reserved."
  echo "Built by desktop/engine/build_ffmpeg.sh in https://github.com/katariyaVivek/chaya."
  echo
  echo "=== FFmpeg: GNU Lesser General Public License 2.1 ==="
  cat "$work/src/COPYING.LGPLv2.1"
  echo
  echo "=== FreeType: The FreeType Project License ==="
  cat "$work/freetype/docs/FTL.TXT"
  echo
  echo "=== FriBidi: GNU Lesser General Public License 2.1 ==="
  cat "$work/fribidi/COPYING"
  echo
  echo "=== HarfBuzz: MIT ==="
  cat "$work/harfbuzz/COPYING"
  echo
  echo "=== libass: ISC ==="
  cat "$work/libass/COPYING"
} >"$out/ffmpeg-LICENSE.txt"
echo "Built $out/$program ($(du -h "$out/$program" | cut -f1))"
