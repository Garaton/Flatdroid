Environment variables some Flatpaks need under Flatdroid.

Set them per app: turn on Settings > Developer options, open the app's settings, and enter
them under Launch > Environment variables.

# VLC (org.videolan.VLC)
```
VLC_PLUGIN_PATH=/app/lib/vlc/plugins
VLC_DATA_PATH=/app/share/vlc
```

# Firefox (org.mozilla.firefox)
```
MOZ_DISABLE_CONTENT_SANDBOX=1
```
