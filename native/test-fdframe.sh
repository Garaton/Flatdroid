#!/bin/sh
# Checks fdframe.c: fdframe-test.c built with the host's cc and run; with an NDK directory, also
# built for arm64 and run on the phone adb reaches (ADB from the environment), which checks the
# NEON turns. With --sanitize, the host run is also built with AddressSanitizer and
# UndefinedBehaviorSanitizer (needs gcc with libasan and libubsan).
#
#   test-fdframe.sh [--sanitize] [ndk dir]
set -eu

SANITIZE=0
[ "${1:-}" = --sanitize ] && { SANITIZE=1; shift; }

HERE=$(cd "$(dirname "$0")" && pwd)
ADB=${ADB:-adb}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

cc -O2 -Wall -Wextra -Werror -o "$WORK/fdframe-test" "$HERE/fdframe-test.c"
echo "== host"
"$WORK/fdframe-test"
if [ $SANITIZE -eq 1 ]; then
    echo "== host, AddressSanitizer and UndefinedBehaviorSanitizer"
    cc -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-sanitize-recover=all -Wall -Wextra -Werror \
        -o "$WORK/fdframe-test-san" "$HERE/fdframe-test.c"
    "$WORK/fdframe-test-san"
fi
[ $# -ge 1 ] || exit 0
echo "== arm64 (adb)"
"$1/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --target=aarch64-linux-android24 -O2 -Wall -Wextra -Werror \
    -o "$WORK/fdframe-test-arm64" "$HERE/fdframe-test.c"
$ADB push "$WORK/fdframe-test-arm64" /data/local/tmp/fdframe-test >/dev/null
$ADB shell 'cd /data/local/tmp && ./fdframe-test; s=$?; rm -f fdframe-test; exit $s'
