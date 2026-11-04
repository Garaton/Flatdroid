Environment variables and launch arguments some Flatpaks need under Flatdroid.

Set them per app: turn on Settings > Developer options, open the app's settings, and enter them under Launch > Environment variables (one NAME=value per line) or Launch > Launch arguments (separated by spaces, added after the app's own command). They apply at the app's next launch.

# VLC (org.videolan.VLC)
```
VLC_PLUGIN_PATH=/app/lib/vlc/plugins
VLC_DATA_PATH=/app/share/vlc
```

# Firefox (org.mozilla.firefox)
```
MOZ_DISABLE_CONTENT_SANDBOX=1
```
For video (YouTube and other sites), also the media decoder's and utility processes' sandboxes:
```
MOZ_DISABLE_RDD_SANDBOX=1
MOZ_DISABLE_UTILITY_SANDBOX=1
```

# foot (page.codeberg.dnkl.foot)
Launch arguments, to keep the window open: foot starts its shell through `host-spawn`, which needs Flatpak's portal on a host, so the shell exits at once ("The name org.freedesktop.Flatpak was not provided by any .service files") and foot closes with it. With this the window stays and shows that message; foot can't run a shell under Flatdroid.
```
--hold
```
