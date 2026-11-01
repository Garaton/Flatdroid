#!/bin/sh
# Builds libfdsec.so, the seccomp helper (fdsec.c), for one Android ABI: a static PIE without a C
# library, so it runs in any root (glibc runtimes and Alpine's musl alike).
#
#   build-fdsec.sh <abi> <ndk dir> <output dir>
#
# abi: arm64-v8a or x86_64. Outputs <output dir>/libfdsec.so.
set -eu

API=24
PAGE_SIZE=16384

[ $# -eq 3 ] || { echo "usage: $0 <abi> <ndk dir> <output dir>" >&2; exit 2; }
ABI=$1 NDK=$2 OUT=$3
HERE=$(cd "$(dirname "$0")" && pwd)

case $ABI in
    arm64-v8a) TRIPLE=aarch64-linux-android ARCH_FLAGS=-mbranch-protection=standard ;;
    x86_64)    TRIPLE=x86_64-linux-android ARCH_FLAGS=-fcf-protection ;;
    *) echo "unsupported ABI: $ABI" >&2; exit 2 ;;
esac

TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
[ -x "$TC/bin/clang" ] || { echo "NDK toolchain not found in $TC" >&2; exit 1; }
mkdir -p "$OUT"

# No C library, so no stack protector (its guard lives in the C library's thread data) and no
# FORTIFY; full RELRO and PIE still apply, and the build fails on any relocation below, since
# nothing would apply it.
"$TC/bin/clang" --target=$TRIPLE$API -O2 -Wall -Wextra -Werror \
    -ffreestanding -fno-builtin -fno-stack-protector -fPIE $ARCH_FLAGS \
    -nostdlib -static-pie -Wl,-z,relro,-z,now -Wl,-z,max-page-size=$PAGE_SIZE -Wl,--build-id=none \
    -o "$OUT/libfdsec.so" "$HERE/fdsec.c"
"$TC/bin/llvm-strip" --strip-all "$OUT/libfdsec.so"
chmod 0755 "$OUT/libfdsec.so"

RE=$TC/bin/llvm-readelf
F=$OUT/libfdsec.so
fail=0
"$RE" -hW "$F" | grep -q 'DYN ' || { echo "FAIL libfdsec.so: not PIE" >&2; fail=1; }
"$RE" -lW "$F" | grep -q INTERP && { echo "FAIL libfdsec.so: has an interpreter" >&2; fail=1; }
"$RE" -rW "$F" | grep -q 'There are no relocations' || { echo "FAIL libfdsec.so: has relocations" >&2; fail=1; }
"$RE" -lW "$F" | grep -q GNU_RELRO || { echo "FAIL libfdsec.so: no RELRO" >&2; fail=1; }
for align in $("$RE" -lW "$F" | awk '$1 == "LOAD" {print $NF}'); do
    [ $((align)) -ge $PAGE_SIZE ] || { echo "FAIL libfdsec.so: LOAD align $align" >&2; fail=1; }
done
[ $fail -eq 0 ] || exit 1
echo "built libfdsec.so [$ABI] into $OUT ($(wc -c < "$F") bytes)"
