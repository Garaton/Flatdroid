#!/bin/sh
# Checks the V4L2 preload on an x86_64 Linux host: builds the x86_64 libfdv4l2.so with its
# cameras list in a temporary folder, runs fdv4l2-test.c (built with the host's cc) with it
# preloaded, then, where they are installed, GStreamer's v4l2src (Motion-JPEG and NV12) and
# ffmpeg's v4l2 input on a pipe fed real frames.
#
#   test-fdv4l2.sh <ndk dir>
set -eu

[ $# -eq 1 ] || { echo "usage: $0 <ndk dir>" >&2; exit 2; }
[ "$(uname -m)" = x86_64 ] || { echo "needs an x86_64 Linux host" >&2; exit 2; }
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'kill $(jobs -p) 2>/dev/null; rm -rf "$WORK"' EXIT

FDV4L2_DEFINES="-DCONFIG=\"$WORK/cameras\"" "$HERE/build-fdv4l2.sh" x86_64 "$1" "$WORK" >/dev/null
printf '%s/video0 %s/media0 %s/control0 640 480 30 mjpeg,nv12 Back camera\n' "$WORK" "$WORK" "$WORK" > "$WORK/cameras"
cc -O2 -Wall -o "$WORK/fdv4l2-test" "$HERE/fdv4l2-test.c"
L=$WORK/libfdv4l2.so
fail=0

echo "== calls"
LD_PRELOAD=$L timeout 20 "$WORK/fdv4l2-test" "$WORK" || fail=1

# Real frames of one type (image/jpeg or video/nv12, from the file), written as Flatdroid writes them, for real V4L2 readers.
feed() {
    rm -f "$WORK/video0" "$WORK/media0" "$WORK/control0"
    mkfifo "$WORK/video0" "$WORK/media0" "$WORK/control0"
    (
        exec 3<>"$WORK/video0" 4<>"$WORK/control0"
        size=$(wc -c < "$2")
        while :; do
            { printf -- '--flatdroid-frame\r\nContent-Type: %s\r\nContent-Length: %s\r\n\r\n' "$1" "$size"; cat "$2"; printf '\r\n'; } >&3
            sleep 0.03
        done
    ) </dev/null >/dev/null 2>&1 &
}

if command -v ffmpeg >/dev/null; then
    ffmpeg -loglevel error -f lavfi -i testsrc=size=640x480 -frames:v 1 -f mjpeg "$WORK/frame.jpg"
    ffmpeg -loglevel error -f lavfi -i testsrc=size=640x480 -frames:v 1 -pix_fmt nv12 -f rawvideo "$WORK/frame.nv12"
    if command -v gst-launch-1.0 >/dev/null; then
        echo "== GStreamer v4l2src, Motion-JPEG"
        feed image/jpeg "$WORK/frame.jpg"
        if LD_PRELOAD=$L timeout 20 gst-launch-1.0 -q v4l2src device="$WORK/video0" num-buffers=10 ! image/jpeg,width=640,height=480 ! jpegdec ! fakesink; then
            echo "ok"
        else
            echo "FAIL: GStreamer, Motion-JPEG"; fail=1
        fi
        kill $(jobs -p) 2>/dev/null || true
        echo "== GStreamer v4l2src, NV12"
        feed video/nv12 "$WORK/frame.nv12"
        if LD_PRELOAD=$L timeout 20 gst-launch-1.0 -q v4l2src device="$WORK/video0" num-buffers=10 ! video/x-raw,format=NV12,width=640,height=480 ! videoconvert ! fakesink; then
            echo "ok"
        else
            echo "FAIL: GStreamer, NV12"; fail=1
        fi
        kill $(jobs -p) 2>/dev/null || true
    fi
    echo "== ffmpeg v4l2, Motion-JPEG"
    feed image/jpeg "$WORK/frame.jpg"
    if LD_PRELOAD=$L timeout 20 ffmpeg -loglevel error -f v4l2 -input_format mjpeg -i "$WORK/video0" -frames:v 10 -f null -; then
        echo "ok"
    else
        echo "FAIL: ffmpeg"; fail=1
    fi
    kill $(jobs -p) 2>/dev/null || true
fi
[ $fail -eq 0 ] && echo "all passed" || { echo "FAILED"; exit 1; }
