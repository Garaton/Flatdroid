#!/bin/sh
# Generates the launcher icon resources from three square source images (same size, same
# framing, e.g. 512x512):
#
#   icon        the coloured artwork on a transparent background   (adaptive foreground)
#   background  an opaque background                               (adaptive background)
#   monochrome  black artwork on transparent; only its alpha is used (themed icons, Android 13+)
#
#   tools/make-launcher-icons.sh [icon] [background] [monochrome]
#
# Defaults: assets/flatdroid_icon.png, assets/flatdroid_background.png, assets/flatdroid_icon_bw.png.
# Writes app/src/main/res/mipmap-*/ic_launcher*.webp and mipmap-anydpi-v26/ic_launcher*.xml.
# Needs ffmpeg with libwebp.
#
# Framing: adaptive icon layers are 108dp; launchers mask them to shapes and only the centre
# 66dp circle is guaranteed visible. SCALE is the source image's size as a fraction of the layer,
# chosen so the artwork's farthest point stays inside that circle; SHIFT_X/SHIFT_Y move the
# source (in source pixels) before scaling, to centre the artwork. The background gets the same
# framing and is padded with PAD_COLOR, so it stays aligned with the artwork.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ICON=${1:-$ROOT/assets/flatdroid_icon.png}
BACKGROUND=${2:-$ROOT/assets/flatdroid_background.png}
MONO=${3:-$ROOT/assets/flatdroid_icon_bw.png}
RES=$ROOT/app/src/main/res

SCALE=0.69      # source size / layer size
SHIFT_X=4       # artwork centre is at (252, 279) in the 512px sources
SHIFT_Y=-23
SOURCE=512      # source width in pixels, for SHIFT_X/SHIFT_Y
PAD_COLOR=white # colour at the background image's edges

command -v ffmpeg >/dev/null || { echo "ffmpeg is required" >&2; exit 1; }

# layer <input> <size> <pad colour> <output>: the source framed in a size x size layer.
layer() {
    t=$(awk "BEGIN { printf \"%d\", $2 * $SCALE + 0.5 }")
    x=$(awk "BEGIN { printf \"%d\", ($2 - $t) / 2 + $SHIFT_X * $t / $SOURCE }")
    y=$(awk "BEGIN { printf \"%d\", ($2 - $t) / 2 + $SHIFT_Y * $t / $SOURCE }")
    ffmpeg -v error -y -i "$1" -vf "format=rgba,scale=$t:$t:flags=lanczos,pad=$2:$2:$x:$y:color=$3" \
        -c:v libwebp -lossless 1 "$4"
}

# legacy <size> <shape: square|round> <output>: background and icon composited, cropped to the
# visible 72dp of the 108dp layer, then masked (Android 7.x, which has no adaptive icons).
legacy() {
    full=$(awk "BEGIN { printf \"%d\", $1 * 108 / 72 + 0.5 }")
    off=$(( (full - $1) / 2 ))
    if [ "$2" = round ]; then r=$(awk "BEGIN { print $1 / 2 }"); else r=$(awk "BEGIN { print $1 * 0.18 }"); fi
    # Alpha 255 inside a rounded square with corner radius r (a circle when r is half the size).
    mask="if(lte(pow(max(max($r-X,X-(W-1-$r)),0),2)+pow(max(max($r-Y,Y-(H-1-$r)),0),2),$r*$r),255,0)"
    layer "$BACKGROUND" "$full" "$PAD_COLOR" "$TMP/bg.webp"
    layer "$ICON" "$full" "0x00000000" "$TMP/fg.webp"
    ffmpeg -v error -y -i "$TMP/bg.webp" -i "$TMP/fg.webp" -filter_complex \
        "[0][1]overlay=format=auto,crop=$1:$1:$off:$off,format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='$mask'" \
        -c:v libwebp -lossless 1 "$3"
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

for density in mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4; do
    name=${density%:*} factor=${density#*:}
    dir=$RES/mipmap-$name
    mkdir -p "$dir"
    size=$(awk "BEGIN { printf \"%d\", 108 * $factor }")
    layer "$ICON" "$size" "0x00000000" "$dir/ic_launcher_foreground.webp"
    layer "$BACKGROUND" "$size" "$PAD_COLOR" "$dir/ic_launcher_background.webp"
    layer "$MONO" "$size" "0x00000000" "$dir/ic_launcher_monochrome.webp"
    legacy_size=$(awk "BEGIN { printf \"%d\", 48 * $factor }")
    legacy "$legacy_size" square "$dir/ic_launcher.webp"
    legacy "$legacy_size" round "$dir/ic_launcher_round.webp"
done

mkdir -p "$RES/mipmap-anydpi-v26"
for name in ic_launcher ic_launcher_round; do
    cat > "$RES/mipmap-anydpi-v26/$name.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
EOF
done
echo "Launcher icons written to $RES"
