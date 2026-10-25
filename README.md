# Flatdroid

> Under development and unfinished.

![Flatdroid Logo](./assets/flatdroid_icon.png)

An Android based Flatpak runner which uses proot to provide the needed environment for Flatpaks to run.

This is an almost entirely vibe-coded app in order to train my agentic coding skills. Expect there to be issues, and me (the maintainer) to not have a complete knowledge of the codebase, but if you make a GitHub issue I will respond (given 2 weeks).

## Contents

- [Overview](#overview)
- [Requirements](#requirements)
- [Installation](#installation)
- [Features](#features)
- [Building](#building)
- [Methodology](#methodology)
- [Security](#security)
- [Acknowledgments](#acknowledgments)

## Overview

Flatdroid runs Linux desktop Flatpak apps from Flathub on Android phones, without root. It sets up a small Alpine Linux environment inside the app with proot, and uses the `flatpak` CLI to download and update apps, and launches each app with its own launcher, as Flatpak's normal sandbox can't run on Android (bwrap needs namespaces). Apps draw to a virtual X11 display that you view with a VNC viewer such as AVNC.

The app uses `Documents/Flatdroid` as its home directory, any files it creates and is put within home show up there for you to access. With there also being optional settings to automatically see everything in `Documents/Flatdroid` without needing to grant individual access, and the ability to bind shared Android storage to `/sdcard`.

## Requirements

- Version and Architecture: Android 7+ (minSdk 24), arm64-v8a or x86_64.
- Internet: For initial setup, and Flatpak downloading.
- Storage Space: ~400 MB for initial required packages, and 0.4-1 GB per Flatpak runtime.
- VNC Viewer App: AVNC, bVNC, etc.

## Installation

Placeholder to be made when hardening milestone is done, with apk download information, and setup option information

## Features

Placeholder

### Placeholder more sub-sections

### Export/Import Flatdroid Data

### flatdroid-sync

`tools/flatdroid-sync` is a shell utility that can transfer Flatpak settings between your PC and Flatdroid. It works by taking a Flatdroid compliant zip and pushing it's correlated Flatpak settings to your PC, or taking your PC's Flatpak settings and forming them into Flatdroid compliant zips.

Example Usage:
```
# Imports the individual app settings the specified app
flatdroid-sync -i "UnofficialHomestuckCollection_settings.zip"

# Imports while taking a backup of app current settings
flatdroid-sync -b backup_hs.zip -i "UnofficialHomestuckCollection_settings.zip"

# Imports all app settings found within this zip that are tracked by flatpak on
# this system, whilst backuping up the current settings
flatdroid-sync -b -i "Flatdroid Flatpak settings 2026-10-24 22-34.zip"

# Exports all Flatpak settings to the specified zip
flatdroid-sync -o all_settings.zip -x

# Exports the Flatpak settings for org.ex.app1 and com.ex.app2
flatdroid-sync -o app1_app2_settings.zip -x org.ex.app1 com.ex.app2

# Exports the Flatpak settings for only com.example.flat
flatdroid-sync -x com.example.flat
```

Dependencies: `zip`, `unzip`.

## Building

- Requirements: Android SDK platform-tools, platforms;android-35, build-tools 35, NDK 30, JDK 21, CMake 3.22.1, make, git, curl, gawk, readelf.
- Clone the repository: `git clone https://github.com/Garaton/flatdroid.git`.
- Build: In the base directory: `./gradlew assemble[architecture]["Release" or "Debug"]`.
  - ex: `./gradlew assembleArm64Debug`
  - Built APKs are found in `app/build/outputs/apk`.
- Architectures: Arm64, X86_64, Universal (only 64-bit systems are supported).

## Methodology

Alpine rootfs and proot are used to install packages and run the Flatpak CLI for install, update, and uninstall. Flatpaks are launched outside of Alpine with a custom launcher under their own proot.

Flatpak Needs:
- When running: a runtime at /usr, app at /app, app data in ~/.var/app/\<APP-ID\>, and a .flatpak-info file.
- User namespaces (Android doesn't do namespaces).
- X11 or Wayland server.

Issues:
- bwrap and proot have compatibility issues so base `flatpak run` doesn't work (custom launcher used).

How it Works:
- proot is used to bind needed flatpak runtime data, and Documents/Flatdroid on flatpak launch.
- proot fakes success on namespaces, but Android doesn't have namespaces, which creates bwrap incompatibility.
- Flatpaks are launched through a custom launcher to get around bwrap and other issues.
- Xvnc and Openbox are used for display management.
- PulseAudio is used for audio.
- Software only rendering.

## Security

Issues:
- Sandboxes are disabled in order for Flatpak to run.
    - This does not bypass Android's own sandbox.
    - Flatpaks are not isolated from each other.
    - Electron and WebKit run without their own sandboxes as well.
- Due to use of external VNC viewer, and VNC passwords being weak, treat it as if any app has access to the VNC session.
- If a Flatpak escapes proot, it will have access to everything Flatdroid has access to.

Hardening:
- Every Flatpak launch runs with a seccomp filter and non-dumpable proot. This prevents Flatpaks from reaching the extent of Flatdroid's permissions (a process only has access to what is bound to proot).
- Different "Home Modes" to limit accessible files even more.
- Per-Flatpak permissions, with the ability to also disable permissions.
- Other miscellaneous low cost hardening.

## Acknowledgments

Flatdroid builds on and uses these projects:

- [PRoot](https://github.com/termux/proot): GPL-2.0, built from a pinned commit with small patches in `native/patches/`
- [talloc](https://talloc.samba.org/talloc/doc/html/index.html): LGPL-3.0-or-later
- [Alpine Linux](https://alpinelinux.org/): rootfs and packages
- [Flatpak](https://flatpak.org/): LGPL-2.1-or-later
- [Flathub](https://flathub.org/)
- [TigerVNC](https://tigervnc.org/) (Xvnc): GPL-2.0
- [Openbox](http://openbox.org/): GPL-2.0
- [PulseAudio](https://www.freedesktop.org/wiki/Software/PulseAudio/): LGPL-2.1

## License

Licensed under the GNU General Public License v3.0 or later (GPL-3.0-or-later). See LICENSE.

## DISCLAIMER

**Not affiliated with Flatpak, Flathub, or any Flatpak app developers.**

Ergo, don't report problems to Flatpak, Flathub, or app developers. Flatdroid runs apps in an unsupported environment, so expect issues. If you are unable to start an app, or it crashes unexpectedly, report the issue here.

