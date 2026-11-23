#!/bin/sh
# Builds libfdframe.so (fdframe.c), the camera's per-frame work (turns, RGBA to NV21), for one
# Android ABI, for Android 7.0's API (the app's minimum). NEON is part of every arm64 CPU, so the
# arm64 build always has its turns.
#
#   build-fdframe.sh <abi> <ndk dir> <output dir>
set -eu

API=24
PAGE_SIZE=16384

[ $# -eq 3 ] || { echo "usage: $0 <abi> <ndk dir> <output dir>" >&2; exit 2; }
ABI=$1 NDK=$2 OUT=$3
HERE=$(cd "$(dirname "$0")" && pwd)

case $ABI in
    arm64-v8a) TRIPLE=aarch64-linux-android ;;
    x86_64)    TRIPLE=x86_64-linux-android ;;
    *) echo "unsupported ABI: $ABI" >&2; exit 2 ;;
esac

TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
[ -x "$TC/bin/clang" ] || { echo "NDK toolchain not found in $TC" >&2; exit 1; }
mkdir -p "$OUT"
"$TC/bin/clang" --target=$TRIPLE$API -O2 -Wall -Wextra -Werror -fPIC -shared \
    -Wl,-z,max-page-size=$PAGE_SIZE -o "$OUT/libfdframe.so" "$HERE/fdframe.c"
"$TC/bin/llvm-strip" --strip-unneeded "$OUT/libfdframe.so"
