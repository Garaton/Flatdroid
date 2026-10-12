# Flatdroid

> Under development and unfinished.

![Flatdroid Logo](./assets/flatdroid_icon.png)

An Android based Flatpak runner which uses proot to provide the needed environment for Flatpaks to run.

This is an almost entirely vibe-coded app in order to train my agentic coding skills. Expect there to be issues, and me (the maintainer) to not have a complete knowledge of the codebase, but if you make a GitHub issue I will respond (given 2 weeks).

## Contents

- [Overview](#overview)
- [Requirements](#requirements)
- [Installation](#installation)
- [Methodology](#methodology)
- [Security](#security)
- [Acknowledgements](#acknowledgements)

## Overview

Flatdroid runs Linux desktop Flatpak apps from Flathub on Android phones, without root. It sets up a small Alpine Linux environment inside the app using proot, uses Flatpak there to download and update apps, and launches each app with its own launcher, as Flatpak's normal sandbox can't run on Android. Apps draw to a virtual X11 display that you view with a VNC viewer such as AVNC.

The app uses Documents/Flatdroid as its home directory, any files it creates and is put within home show up here for you to access. With there being optional settings to automatically see everything in Documents/Flatdroid without needing to grant access for each individual file, and the ability to mount the shared Android storage onto /sdcard.

## Requirements

- Version and Architecture: Android 7+ (minSdk 24), arm64-v8a or x86_64, and only arm64 tested so far.
- Internet: For initial setup, and Flatpak downloading.
- Storage Space: 0.4-1 GB per Flatpak runtime.
- VNC Viewer: AVNC, bVNC, etc.

## Installation

Placeholder to be made when building is done

## Methodology

Alpine rootfs and proot are used to install packages and run the Flatpak CLI for install, update, and uninstall. Flatpaks are launched outside of Alpine with a custom launcher under their own proot.

Flatpak Needs:
- When running: a runtime at /usr, app at /app, app data in ~/.var/app/\<APP-ID\>, and a .flatpak-info file.
- User namespaces (Android doesn't do namespaces).
- X11 or Wayland server.

Issues:
- bwrap and proot have compatibility issues so base `flatpak run` doesn't work (custom launcher used).

What is Done:
- proot is used to mount needed flatpak runtime data, and Documents/Flatdroid on flatpak run.
- proot fakes success on namespaces, but Android doesn't have namespaces, which creates bwrap incompatibility.
- Flatpaks are launched through a custom launcher to get around bwrap and other issues.
- Xvnc and Openbox are used for display management.
- PulseAudio is used for audio.
- Software only rendering.

## Security

Issues:
- Sandboxes are disabled in order for Flatpak to run, but the Android Sandbox remains.
    - Flatpaks are not isolated from each other.
    - This app does not bypass Android's sandbox.
    - Electron and WebKit run without their own sandboxes as well.
- Flatpak permissions are not enforced. Any and all permissions you give to the app, you give to every single Flatpak.
- Due to use of external VNC viewer, and VNC passwords being weak, treat it as if any app has access to the VNC session.

Hardening:
- Every Flatpak launch is ran with a seccomp filter and non-dumpable proot. This isolates Flatpaks from the extent of Flatdroids file permissions (a proccess only has access to files bounded to proot).
- Varrying "Home Modes" to limit accessible files even more.

## Acknowledgements

Flatdroid builds on these projects:

- [PRoot](https://github.com/termux/proot) (Termux fork): GPL-2.0
- [talloc](): LGPL-3.0
- [Alpine Linux](https://alpinelinux.org/): rootfs and packages
- [Flatpak](https://flatpak.org/): LGPL-2.1-or-later
- [Flathub](https://flathub.org/)
- [TigerVNC](https://tigervnc.org/) (Xvnc): GPL-2.0
- [Openbox](http://openbox.org/): GPL-2.0
- [PulseAudio](https://www.freedesktop.org/wiki/Software/PulseAudio/): LGPL-2.1
- [proot-distro](https://github.com/termux/proot-distro) and [RedTerm](https://github.com/GlobalTechInfo/RedTerm): GPL-3.0, references for running proot without Termux

## License
Licensed under the GNU General Public License v3.0 or later (GPL-3.0-or-later). See LICENSE.

## DISCLAIMER

**Not affiliated with Flatpak, Flathub, or any Flatpak app developers.**

Ergo, don't report problems to Flatpak, Flathub, or app developers. Flatdroid runs apps in an unsupported environment, so expect issues. If you are unable to start an app, or it crashes unexpectedly, report the issue here.

