#!/bin/sh
# Builds libproot.so (dynamic PIE against the system libc, talloc linked in statically) and
# libproot-loader.so (freestanding, fixed address) for one Android ABI, from pinned sources.
#
#   build-proot.sh <abi> <ndk dir> <work dir> <output dir>
#
# abi: arm64-v8a or x86_64. Outputs <output dir>/libproot.so and libproot-loader.so.
# Host needs: sh, make, git, curl, sha256sum, python3 (talloc's waf), gawk and readelf
# (proot's loader-info.awk on aarch64).
set -eu

PROOT_REPO=https://github.com/termux/proot.git
PROOT_COMMIT=a179d3e8a4e045aaa1fb8cc3284f23509d96d353 # v5.1.107.96
TALLOC_VERSION=2.5.0
TALLOC_SHA256=912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007
API=24
PAGE_SIZE=16384
# proot falls back to <this>/loader when PROOT_LOADER is unset; the app always sets it.
# No 32-bit loader is shipped, so 32-bit executables fail to start (verify).
NO_LOADER_DIR=/proc/self/flatdroid-no-loader

[ $# -eq 4 ] || { echo "usage: $0 <abi> <ndk dir> <work dir> <output dir>" >&2; exit 2; }
ABI=$1 NDK=$2 WORK=$3/$1 OUT=$4
HERE=$(cd "$(dirname "$0")" && pwd)

case $ABI in
    arm64-v8a) TRIPLE=aarch64-linux-android ARCH_FLAGS=-mbranch-protection=standard ;;
    x86_64)    TRIPLE=x86_64-linux-android ARCH_FLAGS=-fcf-protection ;;
    *) echo "unsupported ABI: $ABI" >&2; exit 2 ;;
esac

TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
[ -x "$TC/bin/clang" ] || { echo "NDK toolchain not found in $TC" >&2; exit 1; }
CC="$TC/bin/clang --target=$TRIPLE$API"
for tool in make git curl sha256sum python3 gawk readelf; do
    command -v $tool >/dev/null || { echo "missing host tool: $tool" >&2; exit 1; }
done

# Build hardening. The loader can't use most of it (see LOADER_* below).
HARDEN_CFLAGS="-O2 -fPIE -fstack-protector-strong -D_FORTIFY_SOURCE=2 $ARCH_FLAGS"
HARDEN_LDFLAGS="-pie -Wl,-z,relro,-z,now -Wl,-z,max-page-size=$PAGE_SIZE"

rm -rf "$WORK"
mkdir -p "$WORK/bin" "$OUT"
# loader-info.awk needs gawk's strtonum().
ln -s "$(command -v gawk)" "$WORK/bin/awk"
PATH=$WORK/bin:$PATH

# ---- talloc: waf only configures (cross answers checked in); sources compiled directly
cd "$WORK"
curl -fsSL -o talloc.tar.gz "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
echo "$TALLOC_SHA256  talloc.tar.gz" | sha256sum -c -
tar xzf talloc.tar.gz
T=$WORK/talloc-$TALLOC_VERSION
cp "$HERE/talloc-cross-answers.txt" "$WORK/talloc-answers.txt"
(cd "$T" && CC="$CC" CFLAGS="$HARDEN_CFLAGS" ./configure --cross-compile \
    --cross-answers="$WORK/talloc-answers.txt" --disable-python >"$WORK/talloc-configure.log" 2>&1) \
    || { tail -20 "$WORK/talloc-configure.log"; exit 1; }
mkdir -p "$WORK/talloc/lib" "$WORK/talloc/include"
for f in talloc.c lib/replace/replace.c; do
    $CC $HARDEN_CFLAGS -D__STDC_WANT_LIB_EXT1__=1 -I"$T/bin/default" -I"$T/lib/replace" -I"$T" -c "$T/$f" \
        -o "$WORK/talloc/$(basename "$f" .c).o"
done
"$TC/bin/llvm-ar" rcs "$WORK/talloc/lib/libtalloc.a" "$WORK"/talloc/*.o
cp "$T/talloc.h" "$WORK/talloc/include/"

# ---- proot
git init -q "$WORK/proot"
cd "$WORK/proot"
git fetch -q --depth 1 --tags "$PROOT_REPO" "$PROOT_COMMIT"
git checkout -q FETCH_HEAD
[ "$(git rev-parse HEAD)" = "$PROOT_COMMIT" ] || { echo "proot commit mismatch" >&2; exit 1; }
cd src
# LOADER_CFLAGS/LOADER_LDFLAGS from the environment are prepended to the makefile's own (+=).
# The loader runs before any libc or TLS exists: no stack protector, no FORTIFY, no PIE/RELRO.
# extension/ashmem_memfd/ashmem_memfd.c calls strcmp/memset without <string.h>: keep that a
# warning (clang uses the builtin declarations) and fail below on any other undeclared function.
CFLAGS="$HARDEN_CFLAGS -I$WORK/talloc/include -Wno-error=implicit-function-declaration" \
LDFLAGS="$HARDEN_LDFLAGS -L$WORK/talloc/lib" \
LOADER_CFLAGS="-fno-stack-protector -U_FORTIFY_SOURCE" \
LOADER_LDFLAGS="-Wl,-z,max-page-size=$PAGE_SIZE" \
make -j"$(nproc)" V=1 proot \
    CC="$CC" STRIP="$TC/bin/llvm-strip" OBJCOPY="$TC/bin/llvm-objcopy" OBJDUMP="$TC/bin/llvm-objdump" \
    PROOT_UNBUNDLE_LOADER="$NO_LOADER_DIR" HAS_LOADER_32BIT= >"$WORK/proot-make.log" 2>&1 \
    || { tail -30 "$WORK/proot-make.log"; exit 1; }

undeclared=$(sed -n "s/.*call to undeclared .*function '\\([a-z_0-9]*\\)'.*/\\1/p" "$WORK/proot-make.log" | sort -u | tr '\n' ' ')
[ "$undeclared" = "memset strcmp " ] || { echo "unexpected undeclared functions: $undeclared" >&2; exit 1; }

"$TC/bin/llvm-strip" --strip-unneeded -o "$OUT/libproot.so" proot
"$TC/bin/llvm-strip" --strip-unneeded -o "$OUT/libproot-loader.so" loader/loader
chmod 0755 "$OUT/libproot.so" "$OUT/libproot-loader.so"

# ---- checks: fail on alignment or missing hardening; report CPU feature notes
RE=$TC/bin/llvm-readelf
fail=0
for f in "$OUT/libproot.so" "$OUT/libproot-loader.so"; do
    for align in $("$RE" -lW "$f" | awk '$1 == "LOAD" {print $NF}'); do
        [ $((align)) -ge $PAGE_SIZE ] || { echo "FAIL $(basename "$f"): LOAD align $align" >&2; fail=1; }
    done
    echo "$(basename "$f") [$ABI]: $("$RE" -nW "$f" | sed -n 's/.*Properties: *//p' | tr '\n' ' ')"
done
P=$OUT/libproot.so
"$RE" -hW "$P" | grep -q 'DYN ' || { echo "FAIL libproot.so: not PIE" >&2; fail=1; }
"$RE" -lW "$P" | grep -q GNU_RELRO || { echo "FAIL libproot.so: no RELRO" >&2; fail=1; }
"$RE" -dW "$P" | grep -q 'BIND_NOW\|FLAGS_1.*NOW' || { echo "FAIL libproot.so: no BIND_NOW" >&2; fail=1; }
"$RE" -sW "$P" | grep -q __stack_chk_fail || { echo "FAIL libproot.so: no stack protector" >&2; fail=1; }
"$RE" -sW "$P" | grep -q '__[a-z]*_chk' || { echo "FAIL libproot.so: no FORTIFY calls" >&2; fail=1; }
if "$RE" -dW "$P" | grep NEEDED | grep -qv 'lib\(c\|dl\|m\)\.so'; then
    echo "FAIL libproot.so: unexpected shared library dependency" >&2; fail=1
fi
[ $fail -eq 0 ] || exit 1
echo "built $ABI into $OUT"
