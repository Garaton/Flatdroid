#!/bin/sh
# Checks fdsec's filter on an x86_64 Linux host: builds the x86_64 libfdsec.so and fdsec-test.c
# (with the host's cc), then runs the test with each combination of options.
#
#   test-fdsec.sh <ndk dir>
set -eu

[ $# -eq 1 ] || { echo "usage: $0 <ndk dir>" >&2; exit 2; }
[ "$(uname -m)" = x86_64 ] || { echo "needs an x86_64 Linux host" >&2; exit 2; }
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

"$HERE/build-fdsec.sh" x86_64 "$1" "$WORK" >/dev/null
cc -O2 -Wall -o "$WORK/fdsec-test" "$HERE/fdsec-test.c"
F=$WORK/libfdsec.so T=$WORK/fdsec-test
fail=0
run() { echo "== $*"; timeout 10 "$@" || fail=1; }
run "$T" none
run "$F" "$T" base
run "$F" --extra "$T" extra
run "$F" --no-inet "$T" noinet
run "$F" --extra --no-inet -- "$T" extra noinet
run "$F" --no-connect "$T" noconnect
echo "== refusals"
timeout 5 "$F" relative 2>&1 | grep -q '^fdsec: usage' || { echo "FAIL: relative command accepted"; fail=1; }
timeout 5 "$F" --unknown /bin/true 2>&1 | grep -q '^fdsec: unknown option' || { echo "FAIL: unknown option accepted"; fail=1; }
[ $fail -eq 0 ] && echo "all passed" || { echo "FAILED"; exit 1; }
