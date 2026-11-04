#!/bin/bash
# Builds the FFmpeg that ships with the Windows app — much smaller than a full build (~185 MB):
#   * every built-in decoder, demuxer, parser and bitstream filter (so the SW decoder still plays
#     everything: AVI/WMV/FLV/RMVB/MPEG-2/VC-1/AC3/DTS/TrueHD, PGS/VobSub subtitles, ...)
#   * dav1d for AV1 and x264 for the SW decoder's H.264 output
#   * only the encoders / muxers / filters the app actually uses, local files only (no network, no devices)
#
#   TARGET=win64 bash pc/scripts/build-ffmpeg.sh <out-dir>    (default; on Ubuntu with mingw-w64, see build-windows.yml)
#   TARGET=linux bash pc/scripts/build-ffmpeg.sh <out-dir>    (same components for Linux: used by the UI tests)
#
# needs: git, make, nasm, pkg-config, meson + ninja (MESON=... to use e.g. "python3 /path/meson.py"), zlib headers
# (Ubuntu: mingw-w64 libz-mingw-w64-dev nasm meson ninja-build pkg-config)
set -euo pipefail

TARGET="${TARGET:-win64}"
OUT="$(mkdir -p "${1:-$PWD/ffmpeg-build}" && cd "${1:-$PWD/ffmpeg-build}" && pwd)"
WORK="$(mkdir -p "${WORK:-$PWD/.ffmpeg-work}" && cd "${WORK:-$PWD/.ffmpeg-work}" && pwd)"
PREFIX="$WORK/prefix-$TARGET"
JOBS="${JOBS:-$(nproc)}"
MESON="${MESON:-meson}"

# pinned sources (GitHub mirrors so the build works where only GitHub is reachable)
FFMPEG_TAG=n8.0.3
X264_COMMIT=31e19f92f00c7003fa115047ce50978bc98c3a0d      # x264 "stable" branch
DAV1D_TAG=1.5.4
ZLIB_TAG=v1.3.2

# what the app uses (pc/lib/ffmpeg.js): SW stream = libx264/aac -> fragmented mp4 on a pipe, thumbnails = mjpeg,
# embedded text subtitles -> srt, PGS/VobSub burn-in = overlay; the rest is inserted by ffmpeg itself
# (format conversion, accurate seeking = trim, autorotate = transpose/flip).
ENCODERS=libx264,aac,mjpeg,srt,subrip,ass,ssa,webvtt
MUXERS=mp4,mov,srt,image2,null
FILTERS=buffer,abuffer,buffersink,abuffersink,format,aformat,scale,aresample,null,anull,copy,acopy,trim,atrim,setpts,asetpts,transpose,hflip,vflip,rotate,overlay,crop,pad,fps,split,asplit
PROTOCOLS=file,pipe

if [ "$TARGET" = win64 ]; then
  HOST=x86_64-w64-mingw32
  CROSS="$HOST-"
  export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
else
  HOST=""
  CROSS=""
  export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
fi
mkdir -p "$PREFIX"
cd "$WORK"

fetch() {   # fetch <dir> <url> <ref>
  if [ ! -d "$1/.git" ]; then
    git init -q "$1"
    git -C "$1" remote add origin "$2"
  fi
  git -C "$1" fetch -q --depth 1 origin "$3"
  git -C "$1" checkout -q -f FETCH_HEAD
}

if [ "$TARGET" = win64 ]; then
  # static zlib (MKV header compression, PNG, ...) so the DLLs do not need zlib1.dll
  echo "== zlib $ZLIB_TAG"
  fetch zlib https://github.com/madler/zlib "refs/tags/$ZLIB_TAG"
  make -C zlib -f win32/Makefile.gcc PREFIX="$CROSS" clean >/dev/null 2>&1 || true
  make -C zlib -f win32/Makefile.gcc PREFIX="$CROSS" -j"$JOBS" libz.a
  mkdir -p "$PREFIX/include" "$PREFIX/lib"
  cp zlib/zlib.h zlib/zconf.h "$PREFIX/include/"
  cp zlib/libz.a "$PREFIX/lib/"
fi

echo "== dav1d $DAV1D_TAG"
fetch dav1d https://github.com/videolan/dav1d "refs/tags/$DAV1D_TAG"
rm -rf "dav1d/build-$TARGET"
MESON_CROSS=()
[ "$TARGET" = win64 ] && MESON_CROSS=(--cross-file dav1d/package/crossfiles/x86_64-w64-mingw32.meson)
$MESON setup "dav1d/build-$TARGET" dav1d "${MESON_CROSS[@]}" --prefix="$PREFIX" --libdir=lib \
  --buildtype=release --default-library=static -Denable_tools=false -Denable_tests=false -Denable_examples=false -Denable_docs=false
ninja -C "dav1d/build-$TARGET" install

echo "== x264 ${X264_COMMIT:0:12}"
fetch x264 https://github.com/mirror/x264 "$X264_COMMIT"
(
  cd x264
  make distclean >/dev/null 2>&1 || true
  ./configure --prefix="$PREFIX" ${HOST:+--host=$HOST --cross-prefix=$CROSS} --enable-static --enable-pic --disable-cli \
    --bit-depth=8 --disable-opencl --disable-avs --disable-swscale --disable-lavf --disable-ffms --disable-gpac --disable-lsmash
  make -j"$JOBS"
  make install
)

echo "== FFmpeg $FFMPEG_TAG"
fetch ffmpeg https://github.com/FFmpeg/FFmpeg "refs/tags/$FFMPEG_TAG"
FF_PREFIX="$WORK/ffmpeg-$TARGET"
rm -rf "$FF_PREFIX"
(
  cd ffmpeg
  make distclean >/dev/null 2>&1 || true
  echo "${FFMPEG_TAG#n}" > VERSION          # "ffmpeg -version" shows 8.0.3 instead of a commit hash
  PLATFORM=()
  if [ "$TARGET" = win64 ]; then
    PLATFORM=(--enable-cross-compile --target-os=mingw32 --arch=x86_64 --cross-prefix="$CROSS" --enable-w32threads
      --extra-ldflags=-static-libgcc)
  else
    PLATFORM=(--enable-rpath)
  fi
  ./configure --prefix="$FF_PREFIX" "${PLATFORM[@]}" \
    --pkg-config=pkg-config --pkg-config-flags=--static \
    --extra-cflags="-I$PREFIX/include" --extra-ldflags="-L$PREFIX/lib" \
    --enable-shared --disable-static --enable-gpl \
    --enable-libx264 --enable-libdav1d --enable-zlib \
    --disable-autodetect --disable-debug --disable-doc --disable-network --disable-avdevice \
    --disable-programs --enable-ffmpeg --enable-ffprobe \
    --disable-encoders --enable-encoder="$ENCODERS" \
    --disable-muxers --enable-muxer="$MUXERS" \
    --disable-filters --enable-filter="$FILTERS" \
    --disable-protocols --enable-protocol="$PROTOCOLS"
  make -j"$JOBS"
  make install
)

echo "== collect -> $OUT"
rm -rf "$OUT"/*
if [ "$TARGET" = win64 ]; then
  cp "$FF_PREFIX"/bin/ffmpeg.exe "$FF_PREFIX"/bin/ffprobe.exe "$FF_PREFIX"/bin/*.dll "$OUT"/
  ${CROSS}strip --strip-unneeded "$OUT"/*.dll "$OUT"/*.exe
  # the DLLs must not need anything that is not part of Windows (libgcc, winpthread, zlib1, ...)
  for f in "$OUT"/*.dll "$OUT"/*.exe; do
    for dll in $(${CROSS}objdump -p "$f" | sed -n 's/^\s*DLL Name: //p'); do
      case "${dll,,}" in
        av*.dll|sw*.dll|kernel32.dll|user32.dll|msvcrt.dll|ucrtbase.dll|ws2_32.dll|bcrypt.dll|ole32.dll|oleaut32.dll|shell32.dll|advapi32.dll|gdi32.dll|psapi.dll|shlwapi.dll|secur32.dll|crypt32.dll|ncrypt.dll|winmm.dll|version.dll|imm32.dll|dbghelp.dll|api-ms-win-*) ;;
        *) echo "unexpected dependency of $(basename "$f"): $dll"; exit 1 ;;
      esac
    done
  done
else
  mkdir -p "$OUT/lib"
  cp "$FF_PREFIX"/bin/ffmpeg "$FF_PREFIX"/bin/ffprobe "$OUT"/
  cp -a "$FF_PREFIX"/lib/*.so* "$OUT/lib/"
fi
{
  echo "FFmpeg $FFMPEG_TAG (https://ffmpeg.org) built for MoaPlayer with libx264 and libdav1d."
  echo "FFmpeg is licensed under the GNU General Public License version 2 or later in this configuration;"
  echo "source: https://github.com/FFmpeg/FFmpeg/tree/$FFMPEG_TAG, build script: pc/scripts/build-ffmpeg.sh"
  echo "x264: GPL v2+, https://code.videolan.org/videolan/x264 (commit $X264_COMMIT)"
  echo "dav1d $DAV1D_TAG: BSD 2-clause, https://code.videolan.org/videolan/dav1d"
  echo
  echo "================================================================ FFmpeg, x264 (COPYING.GPLv2)"
  cat ffmpeg/COPYING.GPLv2
  echo
  echo "================================================================ dav1d (COPYING)"
  cat dav1d/COPYING
} > "$OUT/LICENSE.txt"
du -sh "$OUT"
ls -la "$OUT"
