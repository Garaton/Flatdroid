# Security Policy

## Supported versions

Only the latest release gets security fixes.

## Reporting a vulnerability

Please don't open a public issue for security problems. Report them privately instead, **Security** -> **Report a vulnerability**.

Include what you found, how to reproduce it, along with the Flatdroid version, device, and Android version you used.

I'll reply within two weeks. Flatdroid is maintained by one person, so a fix may take time, but I will keep you updated. You'll also be credited in the release notes unless you would prefer otherwise.

## Scope

In scope:
- An app run by Flatdroid getting past proot, the seccomp filter or its per-app settings, to reach files, devices or data it shouldn't.
- Problems in Flatdroid's own code, such as its file handling, exports or the VNC session setup.

Out of scope:
- Bugs in Flatpak apps/Flathub/Alpine packages. Please report those to their own projects.
- Flatpak sandboxing.
- Other accepted risks, that may be found within `README.md`.
