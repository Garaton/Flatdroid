#!/bin/sh
# Checks libfdwalk.so's C on a Linux host: builds fdwalk.c, fdcheck.c and fdsnap.c with the host's
# cc against the JDK's JNI headers, then runs fdwalk-test.java (a real folder with a link, a
# dangling link, a folder and a name with an emoji, an accent and a tab), fdcheck-test.java (the
# file check against manifests, and its threads on a wide tree) and fdsnap-test.java (Alpine safe
# launch's walk) against it.
#
# With --sanitize, the three run again on a build with AddressSanitizer, UndefinedBehaviorSanitizer
# and LeakSanitizer (the JVM's own allocations left out), and fdcheck-test.java on a build with
# ThreadSanitizer. Needs gcc with libasan, libubsan and libtsan.
#
#   test-fdwalk.sh [--sanitize]        (JAVA_HOME set)
set -eu

[ -n "${JAVA_HOME:-}" ] || { echo "JAVA_HOME is not set" >&2; exit 2; }
SANITIZE=0
[ "${1:-}" = --sanitize ] && SANITIZE=1
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir "$WORK/tmp"

build() { # output, extra cc flags...
    out=$1; shift
    cc -Wall -Wextra -Werror -fPIC -shared -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -pthread "$@" \
        -o "$out" "$HERE/fdwalk.c" "$HERE/fdcheck.c" "$HERE/fdsnap.c"
}

# test file, package folder, class
TESTS="fdwalk util/io FdwalkTest
fdcheck flatpak/integrity FdcheckTest
fdsnap alpine/programs FdsnapTest"
echo "$TESTS" | while read -r name dir class; do
    mkdir -p "$WORK/src/com/garathon/flatdroid/$dir"
    cp "$HERE/$name-test.java" "$WORK/src/com/garathon/flatdroid/$dir/$class.java"
    "$JAVA_HOME/bin/javac" -d "$WORK/classes" "$WORK/src/com/garathon/flatdroid/$dir/$class.java"
done

run() { # library, class (dotted, under com.garathon.flatdroid), then the JVM's extra environment
    lib=$1 class=$2; shift 2
    env "$@" "$JAVA_HOME/bin/java" -Djava.io.tmpdir="$WORK/tmp" -cp "$WORK/classes" "com.garathon.flatdroid.$class" "$lib"
}

build "$WORK/libfdwalk.so" -O2
for class in util.io.FdwalkTest flatpak.integrity.FdcheckTest alpine.programs.FdsnapTest; do run "$WORK/libfdwalk.so" "$class"; done
[ $SANITIZE -eq 1 ] || exit 0

runtime() { path=$(cc -print-file-name="$1"); [ -f "$path" ] || { echo "$1 not found: install it" >&2; exit 2; }; echo "$path"; }
ASAN=$(runtime libasan.so.8)
UBSAN=$(runtime libubsan.so.1)
TSAN=$(runtime libtsan.so.2)
# A report fails the run whatever the exit status (the JVM's own exit can hide one).
checked() { # as run
    status=0
    run "$@" > "$WORK/log" 2>&1 || status=$?
    cat "$WORK/log"
    if [ $status -ne 0 ] || grep -qE 'ERROR: (Address|Leak)Sanitizer|runtime error:|WARNING: ThreadSanitizer' "$WORK/log"; then
        echo "FAIL: sanitizer report or exit $status" >&2
        exit 1
    fi
}
printf 'leak:libjvm.so\nleak:libjli.so\nleak:libjava.so\nleak:libzip.so\nleak:libnio.so\nleak:libnet.so\n' > "$WORK/lsan.supp"

echo "== AddressSanitizer, UndefinedBehaviorSanitizer, LeakSanitizer"
build "$WORK/libfdwalk-asan.so" -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-sanitize-recover=all
for class in util.io.FdwalkTest flatpak.integrity.FdcheckTest alpine.programs.FdsnapTest; do
    checked "$WORK/libfdwalk-asan.so" "$class" LD_PRELOAD="$ASAN $UBSAN" \
        ASAN_OPTIONS=handle_segv=0:allow_user_segv_handler=1:detect_leaks=1 \
        LSAN_OPTIONS=suppressions="$WORK/lsan.supp":print_suppressions=0 UBSAN_OPTIONS=print_stacktrace=1
done

echo "== ThreadSanitizer"
build "$WORK/libfdwalk-tsan.so" -O1 -g -fsanitize=thread
checked "$WORK/libfdwalk-tsan.so" flatpak.integrity.FdcheckTest LD_PRELOAD="$TSAN" \
    TSAN_OPTIONS=report_signal_unsafe=0:ignore_noninstrumented_modules=1
echo "sanitizers: nothing found"
