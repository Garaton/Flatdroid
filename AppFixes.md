Settings, environment variables, and launch arguments some Flatpaks need under Flatdroid.

Settings are on the app's settings page. Environment variables and launch arguments need Settings > Developer options turned on. Then open the app's settings and enter them under Launch > Environment variables (one NAME=value per line) or Launch > Launch arguments (separated by spaces, added after the app's own command). Changes apply at the app's next launch.

# VLC (org.videolan.VLC)
Environment variables:
```
VLC_PLUGIN_PATH=/app/lib/vlc/plugins
VLC_DATA_PATH=/app/share/vlc
```

# Firefox based browsers (org.mozilla.firefox, one.ablaze.floorp, etc)
Environment variables:
```
# Needed for normal browsing to work
MOZ_DISABLE_CONTENT_SANDBOX=1

# For videos to work
MOZ_DISABLE_RDD_SANDBOX=1
MOZ_DISABLE_UTILITY_SANDBOX=1
```

# Prism Launcher (org.prismlauncher.PrismLauncher)
Settings:
- Force X11/Xwayland
- Rendering: OpenGL

# Gear Lever (it.mijorus.gearlever)
Settings:
- App home
- Force X11, for apps that need X11. Xwayland, for supporting both.

Environment variables:
```
# Android has no FUSE, so an AppImage can't mount itself. Unpacks into Gear Lever's /tmp each launch.
APPIMAGE_EXTRACT_AND_RUN=1
```
