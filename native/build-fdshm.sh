#!/bin/sh
# Builds libfdshm.so, the shm_open preload for the Wayland helpers (fdshm.c), for one Android ABI:
# a shared library without a C library, loaded into Alpine's musl programs. It may need only
# __errno_location from the program's libc.
#
#   build-fdshm.sh <abi> <ndk dir> <output dir>
#
# abi: arm64-v8a or x86_64. Outputs <output dir>/libfdshm.so.
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
    -nostdlib -shared -Wl,-soname,libfdshm.so -Wl,-z,relro,-z,now -Wl,-z,max-page-size=$PAGE_SIZE -Wl,--build-id=none \
    -o "$OUT/libfdshm.so" "$HERE/fdshm.c"
"$TC/bin/llvm-strip" --strip-all "$OUT/libfdshm.so"

RE=$TC/bin/llvm-readelf
F=$OUT/libfdshm.so
fail=0
"$RE" -dW "$F" | grep -q NEEDED && { echo "FAIL libfdshm.so: needs a library" >&2; fail=1; }
undefined=$("$RE" --dyn-syms -W "$F" | awk '$7 == "UND" && $8 != "" {print $8}')
[ "$undefined" = "__errno_location" ] || { echo "FAIL libfdshm.so: undefined symbols: $undefined" >&2; fail=1; }
for sym in shm_open shm_unlink fchmod; do
    "$RE" --dyn-syms -W "$F" | awk -v s=$sym '$8 == s && $7 != "UND"' | grep -q . || { echo "FAIL libfdshm.so: no $sym" >&2; fail=1; }
done
"$RE" -lW "$F" | grep -q GNU_RELRO || { echo "FAIL libfdshm.so: no RELRO" >&2; fail=1; }
for align in $("$RE" -lW "$F" | awk '$1 == "LOAD" {print $NF}'); do
    [ $((align)) -ge $PAGE_SIZE ] || { echo "FAIL libfdshm.so: LOAD align $align" >&2; fail=1; }
done
[ $fail -eq 0 ] || exit 1
echo "built libfdshm.so [$ABI] into $OUT ($(wc -c < "$F") bytes)"
