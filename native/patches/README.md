# Patches to termux/proot

`build-proot.sh` applies every `proot-*.patch` here, in name order, to the pinned proot commit.
Each patch should be offered upstream and removed once a pinned commit contains it. Re-check
each one whenever `PROOT_COMMIT` changes (last checked at 4a27ca6, v5.1.107.97: both still needed;
`src/tracee/seccomp.c` is unchanged upstream since a179d3e).

| Patch | What it does | Upstream |
|---|---|---|
| `proot-0001-seccomp-fork-as-clone.patch` | On x86_64, Android's app seccomp filter blocks `fork(2)` (bionic only uses `clone`), and proot answers trapped syscalls it doesn't handle with `ENOSYS`, so musl's `fork()` fails in Alpine ("can't fork: Function not implemented"). The patch re-issues a trapped `fork` as `clone(SIGCHLD, 0)`. The child keeps the two rewritten argument registers; musl's `_Fork` (Alpine 3.24) doesn't read them after the syscall. No effect on aarch64, which has no `fork` syscall. | not yet offered |
| `proot-0002-seccomp-faccessat2-as-faccessat.patch` | Android's app seccomp filter blocks `faccessat2(2)` before Android 16, and proot answers it with `ENOSYS`. glibc then emulates the flags itself, but musl fails with `EINVAL` for `AT_SYMLINK_NOFOLLOW`, which GLib's `g_file_query_exists()` reports as a missing file, so in Alpine flatpak saw no installed apps ("No installed refs found"). The patch re-issues a trapped `faccessat2` as `faccessat(dirfd, path, mode)` when the flags are only `AT_EACCESS` and/or `AT_SYMLINK_NOFOLLOW`; other flags still get `ENOSYS`. Results match proot's own untrapped handling of `faccessat2`, which already follows the final symlink. | not yet offered |
