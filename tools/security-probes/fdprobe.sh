#!/bin/sh
# Config-level hardening checks, run inside a Flatpak (e.g. Konsole's shell). Complements
# fdprobe.c, which probes syscall escapes; this checks the binds and permissions a security
# level sets, using only common runtime binaries. Read each line against the level you set.

echo "== seccomp (fdsec installs the filter; a second line/number means it is active)"
grep -E '^(NoNewPrivs|Seccomp)' /proc/self/status

echo "== network (off => no resolv.conf link target, lookups and Internet sockets fail)"
ls -l /etc/resolv.conf 2>&1
if getent hosts example.com >/dev/null 2>&1; then echo "name lookup: WORKS (network on)"; else echo "name lookup: fails (off, or no DNS)"; fi
if (exec 3<>/dev/tcp/1.1.1.1/80) 2>/dev/null; then echo "tcp connect: WORKS (network on)"; exec 3<&- 2>/dev/null; else echo "tcp connect: refused (network off)"; fi

echo "== hidden /proc (Default/Hardened => the display and audio helpers show as empty dirs)"
empty=0; total=0
for d in /proc/[0-9]*; do
  total=$((total + 1))
  [ -z "$(ls "$d" 2>/dev/null)" ] && empty=$((empty + 1))
done
echo "$empty of $total /proc/<pid> entries are empty (hidden helpers)"

echo "== /sys (Hardened => only cpu and node under devices/system; no /sys/class)"
ls /sys 2>&1
ls /sys/devices/system 2>&1
if ls /sys/class >/dev/null 2>&1; then echo "/sys/class: present"; else echo "/sys/class: absent (limited)"; fi

echo "== /dev (never Binder; Hardened devices=all => base set plus dri only)"
ls /dev
if cat /dev/binder >/dev/null 2>&1; then echo "/dev/binder: PRESENT (should never happen)"; else echo "/dev/binder: absent (good)"; fi

echo "== shared-home dot-files (should be this app's own, not shared across apps)"
for f in .bashrc .bash_history .gitconfig .netrc .ssh .gnupg; do
  [ -e "$HOME/$f" ] && ls -ld "$HOME/$f" 2>/dev/null
done

echo "== done"
