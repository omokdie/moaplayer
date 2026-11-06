#!/bin/bash
# Builds the small FFmpeg behind the Android app's fallback audio decoder (Media3's FFmpeg extension,
# app/src/main/java/androidx/media3/decoder/ffmpeg + app/src/main/cpp).
#
# Only the audio formats that phones often cannot decode themselves:
#   E-AC3 / AC3 (Dolby Digital Plus / Dolby Digital), DTS (incl. DTS-HD), TrueHD / MLP, ALAC
# Android's own decoder is still used whenever it can play a format; this one only steps in otherwise.
# Output: static libraries + headers per ABI, for app/src/main/cpp/CMakeLists.txt:
#   <out>/<abi>/include, <out>/<abi>/lib/lib{avcodec,swresample,avutil}.a
#
#   bash android/scripts/build-ffmpeg-android.sh <android-ndk-dir> [out-dir (default: android/ffmpeg-android)]
#
# needs: git, make, an Android NDK (r26b+ — CI uses the r27c named in build-android.yml)
# LGPL build (no --enable-gpl / --enable-nonfree).
set -euo pipefail

NDK="$(cd "$1" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$(mkdir -p "${2:-$HERE/../ffmpeg-android}" && cd "${2:-$HERE/../ffmpeg-android}" && pwd)"
WORK="$(mkdir -p "${WORK:-$PWD/.ffmpeg-android-work}" && cd "${WORK:-$PWD/.ffmpeg-android-work}" && pwd)"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"

# pinned source (GitHub mirror). Media3 1.11.1 tests its FFmpeg extension with FFmpeg 6.0.
FFMPEG_TAG=n6.0.1
API=24                      # = minSdk of the app
DECODERS="ac3 eac3 dca truehd mlp alac"
ABIS="${ABIS:-arm64-v8a armeabi-v7a}"

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$TC/clang" || { echo "no NDK clang in $TC"; exit 1; }

fetch() {   # fetch <dir> <url> <ref>
  if [ ! -d "$1/.git" ]; then
    git init -q "$1"
    git -C "$1" remote add origin "$2"
  fi
  git -C "$1" fetch -q --depth 1 origin "$3"
  git -C "$1" checkout -q -f FETCH_HEAD
}

echo "== FFmpeg $FFMPEG_TAG"
fetch "$WORK/ffmpeg" https://github.com/FFmpeg/FFmpeg "refs/tags/$FFMPEG_TAG"
SRC="$WORK/ffmpeg"

DEC_FLAGS=""
for d in $DECODERS; do DEC_FLAGS="$DEC_FLAGS --enable-decoder=$d"; done

for ABI in $ABIS; do
  case "$ABI" in
    arm64-v8a)   ARCH=aarch64; CPU=armv8-a;  TRIPLE=aarch64-linux-android;   XCFLAGS="";                                XLDFLAGS="" ;;
    armeabi-v7a) ARCH=arm;     CPU=armv7-a;  TRIPLE=armv7a-linux-androideabi; XCFLAGS="-march=armv7-a -mfloat-abi=softfp"; XLDFLAGS="-Wl,--fix-cortex-a8" ;;
    *) echo "unsupported ABI $ABI"; exit 1 ;;
  esac
  echo "== $ABI"
  B="$WORK/build-$ABI"
  rm -rf "$B" "$OUT/$ABI"
  mkdir -p "$B"
  (
    cd "$B"
    "$SRC/configure" \
      --prefix="$OUT/$ABI" \
      --target-os=android \
      --arch="$ARCH" --cpu="$CPU" \
      --enable-cross-compile \
      --cc="$TC/${TRIPLE}${API}-clang" \
      --cxx="$TC/${TRIPLE}${API}-clang++" \
      --nm="$TC/llvm-nm" --ar="$TC/llvm-ar" --ranlib="$TC/llvm-ranlib" --strip="$TC/llvm-strip" \
      --extra-cflags="$XCFLAGS" --extra-ldflags="$XLDFLAGS" \
      --enable-static --disable-shared --enable-pic \
      --disable-doc --disable-programs --disable-debug \
      --disable-everything \
      --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter \
      --disable-symver --disable-v4l2-m2m --disable-vulkan --disable-mediacodec --disable-jni \
      --enable-swresample \
      $DEC_FLAGS \
      > configure.log 2>&1 || { tail -n 40 configure.log; tail -n 60 ffbuild/config.log 2>/dev/null; exit 1; }
    make -j"$JOBS" > make.log 2>&1 || { tail -n 60 make.log; exit 1; }
    make install > install.log 2>&1 || { tail -n 30 install.log; exit 1; }
  )
  for lib in avcodec swresample avutil; do
    test -f "$OUT/$ABI/lib/lib$lib.a" || { echo "missing lib$lib.a for $ABI"; exit 1; }
  done
  rm -rf "$OUT/$ABI/lib/pkgconfig" "$OUT/$ABI/share"
  # the decoders really are in there
  for d in $DECODERS; do
    "$TC/llvm-nm" "$OUT/$ABI/lib/libavcodec.a" 2>/dev/null | grep -q " [DR] ff_${d}_decoder$" \
      || { echo "decoder $d missing in $ABI libavcodec.a"; exit 1; }
  done
done

cat > "$OUT/README.txt" <<EOF
FFmpeg $FFMPEG_TAG (https://ffmpeg.org), LGPL 2.1 or later, built for MoaPlayer (Android) by
android/scripts/build-ffmpeg-android.sh: audio decoders $DECODERS + swresample, for: $ABIS
EOF
du -sh "$OUT"/*
echo "FFmpeg for Android ready: $OUT"
