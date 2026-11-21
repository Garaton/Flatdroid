#!/bin/sh
# Builds libfdv4l2.so, the V4L2 preload for apps that open cameras themselves (fdv4l2.c), for one
# Android ABI: a shared library without a C library, loaded into the apps' programs. It may need
# only __errno_location from the program's libc.
#
#   build-fdv4l2.sh <abi> <ndk dir> <output dir>
#
# abi: arm64-v8a or x86_64. Outputs <output dir>/libfdv4l2.so.
# FDV4L2_DEFINES: extra compiler flags (the host test sets the cameras list's path with -DCONFIG=...).
set -eu

API=24
PAGE_SIZE=16384

[ $# -eq 3 ] || { echo "usage: $0 <abi> <ndk dir> <output dir>" >&2; exit 2; }
ABI=$1 NDK=$2 OUT=$3
HERE=$(cd "$(dirname "$0")" && pwd)

case $ABI in
    # Inline atomics: the outlined ones call into a runtime library musl doesn't have.
    arm64-v8a) TRIPLE=aarch64-linux-android ARCH_FLAGS="-mbranch-protection=standard -mno-outline-atomics" ;;
    x86_64)    TRIPLE=x86_64-linux-android ARCH_FLAGS=-fcf-protection ;;
    *) echo "unsupported ABI: $ABI" >&2; exit 2 ;;
esac

TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
[ -x "$TC/bin/clang" ] || { echo "NDK toolchain not found in $TC" >&2; exit 1; }
mkdir -p "$OUT"

# shellcheck disable=SC2086
"$TC/bin/clang" --target=$TRIPLE$API -O2 -Wall -Wextra -Werror \
    -ffreestanding -fno-builtin -fno-stack-protector -fPIC $ARCH_FLAGS \
    ${FDV4L2_DEFINES:-} -nostdlib -shared -Wl,-soname,libfdv4l2.so -Wl,-z,relro,-z,now -Wl,-z,max-page-size=$PAGE_SIZE -Wl,--build-id=none \
    -o "$OUT/libfdv4l2.so" "$HERE/fdv4l2.c"
"$TC/bin/llvm-strip" --strip-all "$OUT/libfdv4l2.so"

RE=$TC/bin/llvm-readelf
F=$OUT/libfdv4l2.so
fail=0
"$RE" -dW "$F" | grep -q NEEDED && { echo "FAIL libfdv4l2.so: needs a library" >&2; fail=1; }
undefined=$("$RE" --dyn-syms -W "$F" | awk '$7 == "UND" && $8 != "" {print $8}')
[ "$undefined" = "__errno_location" ] || { echo "FAIL libfdv4l2.so: undefined symbols: $undefined" >&2; fail=1; }
for sym in open open64 openat openat64 __open_2 __open64_2 __openat_2 __openat64_2 dup close ioctl mmap mmap64 \
    stat stat64 lstat lstat64 fstat fstat64 fstatat fstatat64 statx __xstat __xstat64 __lxstat __lxstat64 __fxstat __fxstat64 __fxstatat __fxstatat64; do
    "$RE" --dyn-syms -W "$F" | awk -v s=$sym '$8 == s && $7 != "UND"' | grep -q . || { echo "FAIL libfdv4l2.so: no $sym" >&2; fail=1; }
done
"$RE" -lW "$F" | grep -q GNU_RELRO || { echo "FAIL libfdv4l2.so: no RELRO" >&2; fail=1; }
for align in $("$RE" -lW "$F" | awk '$1 == "LOAD" {print $NF}'); do
    [ $((align)) -ge $PAGE_SIZE ] || { echo "FAIL libfdv4l2.so: LOAD align $align" >&2; fail=1; }
done
[ $fail -eq 0 ] || exit 1
echo "built libfdv4l2.so [$ABI] into $OUT ($(wc -c < "$F") bytes)"
