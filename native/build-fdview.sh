#!/bin/sh
# Builds libfdview.so (fdview.c), the embedded viewer's frames, for one Android ABI. Built for Android
# 10's API: the app loads it only there and later.
#
#   build-fdview.sh <abi> <ndk dir> <output dir>
set -eu

API=29
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
    -Wl,-z,max-page-size=$PAGE_SIZE -o "$OUT/libfdview.so" "$HERE/fdview.c" -lnativewindow -landroid
