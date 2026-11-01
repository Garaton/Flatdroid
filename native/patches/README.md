# Patches to termux/proot

`build-proot.sh` applies every `proot-*.patch` here, in name order, to the pinned proot commit.
The bug fixes (0001, 0002) should be offered upstream and removed once a pinned commit contains
them; 0003 is Flatdroid's own hardening. Re-check each one whenever `PROOT_COMMIT` changes (last
checked at 4a27ca6, v5.1.107.97: 0001 and 0002 still needed; `src/tracee/seccomp.c` is unchanged
upstream since a179d3e).

| Patch | What it does | Upstream |
|---|---|---|
| `proot-0001-seccomp-fork-as-clone.patch` | On x86_64, Android's app seccomp filter blocks `fork(2)` (bionic only uses `clone`), and proot answers trapped syscalls it doesn't handle with `ENOSYS`, so musl's `fork()` fails in Alpine ("can't fork: Function not implemented"). The patch re-issues a trapped `fork` as `clone(SIGCHLD, 0)`. The child keeps the two rewritten argument registers; musl's `_Fork` (Alpine 3.24) doesn't read them after the syscall. No effect on aarch64, which has no `fork` syscall. | not yet offered |
| `proot-0002-seccomp-faccessat2-as-faccessat.patch` | Android's app seccomp filter blocks `faccessat2(2)` before Android 16, and proot answers it with `ENOSYS`. glibc then emulates the flags itself, but musl fails with `EINVAL` for `AT_SYMLINK_NOFOLLOW`, which GLib's `g_file_query_exists()` reports as a missing file, so in Alpine flatpak saw no installed apps ("No installed refs found"). The patch re-issues a trapped `faccessat2` as `faccessat(dirfd, path, mode)` when the flags are only `AT_EACCESS` and/or `AT_SYMLINK_NOFOLLOW`; other flags still get `ENOSYS`. Results match proot's own untrapped handling of `faccessat2`, which already follows the final symlink. | not yet offered |
| `proot-0003-non-dumpable.patch` | Hardening, not a bug fix. proot runs as the app's user, as everything it traces does, so a traced process could open `/proc/<proot pid>/mem` (a plain `open()`, which no seccomp filter can tell apart) or use `ptrace`/`process_vm_*` on proot and run code outside its path translation. The patch makes proot non-dumpable at start (the kernel then refuses all three to same-user processes, and Android's `/proc` hides proot from them) and sets the first tracee dumpable again before its `execve(2)`: the kernel refuses a tracer access to a non-dumpable tracee's memory, so that first `execve` couldn't be translated. The shm helper is proot run again, so it is non-dumpable too. | not planned (a project-specific choice) |
