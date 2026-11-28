#!/bin/sh
# Builds libfdshm.so, the shm_open preload for the Wayland helpers (fdshm.c), libfdmemfd.so,
# its memfd_create alone for apps on kernels without memfd, and libfdstatx.so, syscall() with
# statx's mount ID and fstatfs() on O_PATH descriptors for Alpine's commands on kernels before 5.8
# (fdstatx.c), for one Android ABI:
# shared libraries without a C library, loaded into musl and glibc programs. They may need only
# __errno_location from the program's libc.
#
#   build-fdshm.sh <abi> <ndk dir> <output dir>
#
# abi: arm64-v8a or x86_64. Outputs <output dir>/libfdshm.so, libfdmemfd.so and libfdstatx.so.
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

# libfdshm.so for the helpers; libfdmemfd.so, memfd_create alone, for apps on kernels without memfd.
fail=0
build() {
    name=$1 defines=$2 symbols=$3 source=${4:-fdshm.c}
    F=$OUT/$name
    "$TC/bin/clang" --target=$TRIPLE$API -O2 -Wall -Wextra -Werror \
        -ffreestanding -fno-builtin -fno-stack-protector -fPIC $ARCH_FLAGS $defines \
        -nostdlib -shared -Wl,-soname,$name -Wl,-z,relro,-z,now -Wl,-z,max-page-size=$PAGE_SIZE -Wl,--build-id=none \
        -o "$F" "$HERE/$source"
    "$TC/bin/llvm-strip" --strip-all "$F"
    RE=$TC/bin/llvm-readelf
    "$RE" -dW "$F" | grep -q NEEDED && { echo "FAIL $name: needs a library" >&2; fail=1; }
    undefined=$("$RE" --dyn-syms -W "$F" | awk '$7 == "UND" && $8 != "" {print $8}')
    [ "$undefined" = "__errno_location" ] || { echo "FAIL $name: undefined symbols: $undefined" >&2; fail=1; }
    exported=$("$RE" --dyn-syms -W "$F" | awk '$7 != "UND" && $5 == "GLOBAL" {print $8}' | sort | tr '\n' ' ')
    [ "$exported" = "$symbols " ] || { echo "FAIL $name: exports $exported, not $symbols" >&2; fail=1; }
    "$RE" -lW "$F" | grep -q GNU_RELRO || { echo "FAIL $name: no RELRO" >&2; fail=1; }
    for align in $("$RE" -lW "$F" | awk '$1 == "LOAD" {print $NF}'); do
        [ $((align)) -ge $PAGE_SIZE ] || { echo "FAIL $name: LOAD align $align" >&2; fail=1; }
    done
    echo "built $name [$ABI] into $OUT ($(wc -c < "$F") bytes)"
}
build libfdshm.so "" "fchmod memfd_create shm_open shm_unlink"
build libfdmemfd.so -DFDSHM_MEMFD_ONLY "memfd_create"
build libfdstatx.so "" "fstatfs syscall" fdstatx.c
[ $fail -eq 0 ] || exit 1
