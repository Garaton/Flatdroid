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

echo "== PulseAudio module loading (needs Sound on and pactl; must be refused)"
# A module loaded by the app runs code among the helpers (it can dlopen any .so by path), so
# PulseAudio is started with --disallow-module-loading. Loading a harmless null sink must fail.
if command -v pactl >/dev/null 2>&1 && [ -n "$PULSE_SERVER" ]; then
  if out=$(pactl load-module module-null-sink sink_name=fdprobe_test 2>&1); then
    echo "LOADED module (id $out) -- module loading is ALLOWED (should be refused); unloading"
    pactl unload-module "$out" >/dev/null 2>&1
  else
    echo "refused: $out (good)"
  fi
else
  echo "skipped (no pactl or Sound off): run inside a Sound-enabled app that has pactl"
fi

echo "== processes started after launch (not hidden; their memory is the app's to read)"
# /proc hiding is a snapshot taken when the app starts. Helpers that run later (xkbcomp when a
# client asks for a keymap, a terminal command, an install alongside the session) are visible and
# dumpable. List same-UID processes whose maps are readable, excluding this shell's own tree.
me=$(id -u 2>/dev/null)
mine=$$
readable=0
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  [ "$p" = "$mine" ] && continue
  [ -r "$d/status" ] || continue
  uid=$(awk '/^Uid:/{print $2; exit}' "$d/status" 2>/dev/null)
  [ "$uid" = "$me" ] || continue
  # Readable maps == the process is dumpable, so /proc/<pid>/mem could be opened.
  if head -c1 "$d/maps" >/dev/null 2>&1; then
    comm=$(cat "$d/comm" 2>/dev/null)
    echo "  readable: pid $p ($comm)"
    readable=$((readable + 1))
  fi
done
echo "$readable same-UID process(es) with readable memory (hidden helpers should not appear)"

echo "== escaped processes (left proot; Flatdroid sweeps these on session/task end)"
# An app can kill its own proot; its children then run untraced, reparented to init. A clean
# session should show none. Same-UID, TracerPid 0, parent init(1).
escaped=0
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  [ -r "$d/status" ] || continue
  uid=$(awk '/^Uid:/{print $2; exit}' "$d/status" 2>/dev/null)
  [ "$uid" = "$me" ] || continue
  ppid=$(awk '/^PPid:/{print $2; exit}' "$d/status" 2>/dev/null)
  tracer=$(awk '/^TracerPid:/{print $2; exit}' "$d/status" 2>/dev/null)
  if [ "$ppid" = "1" ] && [ "$tracer" = "0" ]; then
    echo "  ESCAPED: pid $p ($(cat "$d/comm" 2>/dev/null)) -- untraced, reparented to init"
    escaped=$((escaped + 1))
  fi
done
echo "$escaped untraced same-UID orphan(s) (should be 0 in a fresh session)"

echo "== environment accuracy and privacy (time zone, language, container, runtime dir)"
echo "LANG=${LANG:-unset} (C.UTF-8 => language not shared; a real locale => shared)"
echo "timezone: $(cat /etc/timezone 2>/dev/null || echo unset) (UTC => time zone not shared)"
echo "container=${container:-unset} (should be 'flatpak' when /.flatpak-info is present)"
echo "XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-unset}"
[ -d "$XDG_RUNTIME_DIR/app" ] && echo "  $XDG_RUNTIME_DIR/app present (Flatpak layout)" || echo "  no app/ dir under XDG_RUNTIME_DIR"

echo "== done"
