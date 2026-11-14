Environment variables and launch arguments some Flatpaks need under Flatdroid.

Set them per app: turn on Settings > Developer options, open the app's settings, and enter them under Launch > Environment variables (one NAME=value per line), or Launch > Launch arguments (separated by spaces, added after the app's own command). They apply at the app's next launch.

# VLC (org.videolan.VLC)
```
VLC_PLUGIN_PATH=/app/lib/vlc/plugins
VLC_DATA_PATH=/app/share/vlc
```

# Firefox based browsers (org.mozilla.firefox, one.ablaze.floorp, etc)
```
# Needed for normal browsing to work
MOZ_DISABLE_CONTENT_SANDBOX=1

# For videos to work
MOZ_DISABLE_RDD_SANDBOX=1
MOZ_DISABLE_UTILITY_SANDBOX=1
```
