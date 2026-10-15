# Patches to termux/proot

`build-proot.sh` applies every `proot-*.patch` here, in name order, to the pinned proot commit.
Each patch should be offered upstream and removed once a pinned commit contains it. Re-check
each one whenever `PROOT_COMMIT` changes.

| Patch | What it does | Upstream |
|---|---|---|
| `proot-0001-seccomp-fork-as-clone.patch` | On x86_64, Android's app seccomp filter blocks `fork(2)` (bionic only uses `clone`), and proot answers trapped syscalls it doesn't handle with `ENOSYS`, so musl's `fork()` fails in Alpine ("can't fork: Function not implemented"). The patch re-issues a trapped `fork` as `clone(SIGCHLD, 0)`. The child keeps the two rewritten argument registers; musl's `_Fork` (Alpine 3.24) doesn't read them after the syscall. No effect on aarch64, which has no `fork` syscall. | not yet offered |
