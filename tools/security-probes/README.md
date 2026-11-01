# Security probes

Manual tools that check, from inside a launched Flatpak, that the milestone 6 hardening holds. They are not part of the app and are not built or run by Gradle; run them by hand on a device against a session Flatdroid started. There is no way to automate them (they need a live Flatpak session with a chosen security level), but they are kept here so the checks can be re-run and extended.

## fdprobe.c - system-call and /proc escape probe

Probes the ways a Flatpak could leave its proot root or reach another process: path opens, `openat2`, `io_uring`, file handles, `ptrace`, `process_vm_*`, `/proc/<pid>/{mem,root,cwd}`, and the rest of what `fdsec` blocks (plus the Internet-socket checks). It also checks the path/mount system calls proot does not translate — `statmount`, `listmount`, the four `*xattrat` calls, `file_getattr` and `file_setattr` — which `fdsec` forces to `ENOSYS` (as it does for every syscall number above `open_tree_attr`) so they can never reach the host's files; `OPEN` on one of these means the filter let an untranslated path call through. Each line is labelled:

- `ESCAPE`  — reached the real filesystem / broke out of the root (should never happen).
- `EXPOSED` — read another same-UID process's memory (real at Performant where the display and audio helpers are visible; blocked once they are hidden at Default/Hardened).
- `LEAK`    — revealed a real path string but granted no access (proot still blocks traversal).
- `blocked` / `OPEN` — the call was refused / allowed, with the errno.

Build a static binary (Termux's `-static` is unsupported — use the NDK or Alpine's musl):

```
# NDK, on a PC
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang \
  --target=aarch64-linux-android24 -O2 -static -o fdprobe fdprobe.c
# or, in Flatdroid's Alpine terminal (musl, fully static)
apk add build-base && gcc -O2 -static -o fdprobe fdprobe.c
```

Get it into a Flatpak and run it. `/app` is exec-capable under proot and writable, so from a shell Flatpak (e.g. Konsole) copy it there and run it with a host-only path and a helper pid:

```
cp /path/to/fdprobe /app/bin/fdprobe && chmod +x /app/bin/fdprobe
/app/bin/fdprobe /system <helper-pid>     # helper pid e.g. from: adb shell pidof Xvnc
```

Under milestone 6, at Hardened every escape reads `blocked` and the helpers are not even visible; at Performant the helpers' memory reads as `EXPOSED` (the documented trade-off) and `/proc/root` reads as the guest root (not an escape).

## fdprobe.sh - config-level checks (no compiler)

Checks what a security level sets, and that the runtime environment is what Flatpak expects, using only common runtime binaries. Run it in a shell Flatpak:

```
sh fdprobe.sh
```

Sections:

- **seccomp / network / hidden `/proc` / `/sys` / `/dev` / dot-files** — the level's binds and permissions (seccomp active, network off => no DNS or Internet sockets, helpers hidden at Default/Hardened, `/sys` reduced and no Binder at Hardened, per-app hidden dot-files).
- **PulseAudio module loading** — with Sound on, loading a module must be refused (`--disallow-module-loading`); a loaded module would run the app's code among the helpers. Needs `pactl` (present in KDE runtimes); skipped otherwise.
- **processes started after launch** — `/proc` hiding is a snapshot taken when the app starts, so helpers that run later (`xkbcomp` on a keymap request, a terminal command, an install alongside the session) are visible and dumpable. Lists same-UID processes whose memory is readable; hidden helpers should not appear. The launcher ends escaped processes and refuses Alpine commands while an app runs, so in a plain session only the app's own processes should be listed.
- **escaped processes** — an app can kill its own proot, leaving its children untraced and reparented to init. Flatdroid sweeps these on session and task end and before a file check, so a fresh session should list none.
- **environment accuracy and privacy** — `LANG` (a real locale means the language is shared, `C.UTF-8` means it isn't), the time zone (`UTC` means it isn't shared), `container=flatpak`, and the `/run/user/<uid>` layout (`app/` present), i.e. the per-app time-zone and language privacy settings and the Flatpak-layout fixes.

## flatpakprobe.sh - the biggest threat of a malicious Flatpak

Within `org.kde.konsole`, or any other Flatpak terminal emulator, run this with either `org.xfce.mousepad` installed or another other app id which you pass in as the first parameter. After some waiting it will find a file belonging to the provided app id, and ask you if you want to save it (saves to `./hit.txt`) and/or tamper with it. If you choose to tamper with the file, and open that Flatpak with "Check app files before launch" as true (and there's a pre-existing Flatdroid check), it will tell you that the app was tampered with.

Usage:
```
sh flatpakprobe.sh  # probes org.xfce.mousepad
sh flatpakprobe.sh org.mozilla.firefox  # probes Firefox
```
