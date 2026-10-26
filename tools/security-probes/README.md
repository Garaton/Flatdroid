# Security probes

Manual tools that check, from inside a launched Flatpak, that the milestone 6 hardening holds.
They are not part of the app and are not built or run by Gradle; run them by hand on a device
against a session Flatdroid started. There is no way to automate them (they need a live Flatpak
session with a chosen security level), but they are kept here so the checks can be re-run and
extended.

## fdprobe.c — system-call and /proc escape probe

Probes the ways a Flatpak could leave its proot root or reach another process: path opens,
`openat2`, `io_uring`, file handles, `ptrace`, `process_vm_*`, `/proc/<pid>/{mem,root,cwd}`, and
the rest of what `fdsec` blocks (plus the Internet-socket checks). Each line is labelled:

- `ESCAPE`  — reached the real filesystem / broke out of the root (should never happen).
- `EXPOSED` — read another same-UID process's memory (real at Performant where the display and
  audio helpers are visible; blocked once they are hidden at Default/Hardened).
- `LEAK`    — revealed a real path string but granted no access (proot still blocks traversal).
- `blocked` / `OPEN` — the call was refused / allowed, with the errno.

Build a static binary (Termux's `-static` is unsupported — use the NDK or Alpine's musl):

    # NDK, on a PC
    $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang \
      --target=aarch64-linux-android24 -O2 -static -o fdprobe fdprobe.c
    # or, in Flatdroid's Alpine terminal (musl, fully static)
    apk add build-base && gcc -O2 -static -o fdprobe fdprobe.c

Get it into a Flatpak and run it. `/app` is exec-capable under proot and writable, so from a
shell Flatpak (e.g. Konsole) copy it there and run it with a host-only path and a helper pid:

    cp /path/to/fdprobe /app/bin/fdprobe && chmod +x /app/bin/fdprobe
    /app/bin/fdprobe /system <helper-pid>     # helper pid e.g. from: adb shell pidof Xvnc

Under milestone 6, at Hardened every escape reads `blocked` and the helpers are not even visible;
at Performant the helpers' memory reads as `EXPOSED` (the documented trade-off) and `/proc/root`
reads as the guest root (not an escape).

## fdprobe.sh — config-level checks (no compiler)

Checks what a security level sets, using only common runtime binaries: seccomp status, network
reachability, hidden helper `/proc` entries, reduced `/sys`, `/dev` (no Binder) and the per-app
hidden dot-files. Run it in a shell Flatpak:

    sh fdprobe.sh      # paste it in, or curl it from an adb-reverse'd local server
