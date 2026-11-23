#!/bin/sh
# Builds libfdwalk.so for one Android ABI: fdwalk.c (the batched file system reads of Flatdroid's
# walks), fdcheck.c (the file check's walk and compare) and fdsnap.c (Alpine safe launch's walk).
# Built for Android 7.0's API (the app's minimum): it uses only readdir, readlinkat, fstatat,
# realpath and POSIX threads.
#
#   build-fdwalk.sh <abi> <ndk dir> <output dir>
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
    -Wl,-z,max-page-size=$PAGE_SIZE -o "$OUT/libfdwalk.so" "$HERE/fdwalk.c" "$HERE/fdcheck.c" "$HERE/fdsnap.c"
"$TC/bin/llvm-strip" --strip-unneeded "$OUT/libfdwalk.so"
