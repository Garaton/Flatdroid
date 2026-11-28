/*
 * fdstatx: the C library's syscall(2) function, preloaded (LD_PRELOAD) into Alpine's commands on
 * kernels before 5.8, where statx(2) has no mount ID (STATX_MNT_ID). Flatpak resolves paths
 * through libglnx's glnx_chase, which calls statx through syscall() for STATX_MNT_ID or
 * STATX_MNT_ID_UNIQUE and fails ("statx didn't return all required fields") when the kernel
 * returns neither: no app could be installed or updated there.
 *
 * Every system call goes through unchanged. After a statx that asked for a mount ID and got none,
 * the ID is read from /proc/self/fdinfo/<fd> ("mnt_id:", kernel 3.15 and later), the same number a
 * newer kernel's statx gives, for the descriptor itself (an empty path with AT_EMPTY_PATH) or for
 * the path opened with O_PATH. Kernels before 3.15 have no such line: there the device number
 * stands in (libglnx only compares the IDs, to notice a path crossing into another mount, and
 * a mount's files share its device). If neither is there the result stays as the kernel gave it.
 *
 * fstatfs(2) too: kernels before 3.12 refuse it on an O_PATH descriptor (EBADF), which libglnx's
 * open_tree fallback (kernels before 5.2) opens and then checks for an automount. Then the same
 * file's statfs(2) through /proc/self/fd/<fd> answers instead.
 *
 * No C library, like fdshm: raw system calls, so it loads into musl programs. Only errno is the
 * program's own (__errno_location, which musl exports).
 */
#include <asm/unistd.h>
#include <linux/fcntl.h>
#include <stdarg.h>

#define STATX_MNT_ID 0x00001000U
#define STATX_MNT_ID_UNIQUE 0x00004000U
/* struct statx: stx_mask at 0, stx_dev_major and stx_dev_minor at 136 and 140, stx_mnt_id at 144. */
#define STX_DEV_MAJOR_OFFSET 136
#define STX_DEV_MINOR_OFFSET 140
#define STX_MNT_ID_OFFSET 144
#ifndef O_PATH
#define O_PATH 010000000
#endif

extern int *__errno_location(void);

static long raw6(long n, long a, long b, long c, long d, long e, long f)
{
#if defined(__aarch64__)
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory");
    return x0;
#elif defined(__x86_64__)
    long ret;
    register long r10 __asm__("r10") = d;
    register long r8 __asm__("r8") = e;
    register long r9 __asm__("r9") = f;
    __asm__ volatile("syscall" : "=a"(ret) : "a"(n), "D"(a), "S"(b), "d"(c), "r"(r10), "r"(r8), "r"(r9)
                     : "rcx", "r11", "memory");
    return ret;
#else
#error "unsupported architecture"
#endif
}

/* The "mnt_id:" line of /proc/self/fdinfo/<fd>; 0 when it can't be read. */
static unsigned long mount_id(int fd)
{
    char path[48] = "/proc/self/fdinfo/";
    char digits[12];
    int n = 0, len = 18;
    unsigned v = (unsigned)fd;
    do digits[n++] = (char)('0' + v % 10); while ((v /= 10) && n < 11);
    while (n) path[len++] = digits[--n];
    path[len] = 0;
    long info = raw6(__NR_openat, AT_FDCWD, (long)path, O_RDONLY | O_CLOEXEC, 0, 0, 0);
    if (info < 0) return 0;
    char buf[512];
    long got = raw6(__NR_read, info, (long)buf, sizeof buf - 1, 0, 0, 0);
    raw6(__NR_close, info, 0, 0, 0, 0, 0);
    if (got <= 0) return 0;
    buf[got] = 0;
    static const char key[] = "mnt_id:";
    for (long i = 0; i + (long)sizeof key - 1 <= got; i++) {
        if (i > 0 && buf[i - 1] != '\n') continue;
        int k = 0;
        while (key[k] && buf[i + k] == key[k]) k++;
        if (key[k]) continue;
        const char *p = buf + i + k;
        while (*p == ' ' || *p == '\t') p++;
        unsigned long id = 0;
        while (*p >= '0' && *p <= '9') id = id * 10 + (unsigned long)(*p++ - '0');
        return id;
    }
    return 0;
}

/* "/proc/self/fd/<fd>" in [path] (at least 32 bytes). */
static void fd_path(int fd, char *path)
{
    static const char prefix[] = "/proc/self/fd/";
    int len = 0;
    while (prefix[len]) { path[len] = prefix[len]; len++; }
    char digits[12];
    int n = 0;
    unsigned v = (unsigned)fd;
    do digits[n++] = (char)('0' + v % 10); while ((v /= 10) && n < 11);
    while (n) path[len++] = digits[--n];
    path[len] = 0;
}

/* Gives a successful statx's [out] the mount ID it asked for in [mask] when the kernel left it out. */
static void add_mount_id(int dirfd, const char *path, unsigned flags, unsigned mask, unsigned char *out)
{
    if (!(mask & (STATX_MNT_ID | STATX_MNT_ID_UNIQUE)) || !out) return;
    unsigned *stx_mask = (unsigned *)out;
    if (*stx_mask & (STATX_MNT_ID | STATX_MNT_ID_UNIQUE)) return;
    int fd = dirfd;
    long opened = -1;
    if (!path || !path[0]) {
        if (!(flags & AT_EMPTY_PATH)) return;
        if (dirfd == AT_FDCWD) opened = raw6(__NR_openat, AT_FDCWD, (long)".", O_PATH | O_CLOEXEC, 0, 0, 0);
    } else {
        int nofollow = (flags & AT_SYMLINK_NOFOLLOW) ? O_NOFOLLOW : 0;
        opened = raw6(__NR_openat, dirfd, (long)path, O_PATH | O_CLOEXEC | nofollow, 0, 0, 0);
    }
    if (opened >= 0) fd = (int)opened;
    else if (path && path[0]) return;
    else if (dirfd == AT_FDCWD) return;
    unsigned long id = mount_id(fd);
    if (opened >= 0) raw6(__NR_close, opened, 0, 0, 0, 0, 0);
    if (!id) {
        unsigned major = *(unsigned *)(out + STX_DEV_MAJOR_OFFSET), minor = *(unsigned *)(out + STX_DEV_MINOR_OFFSET);
        /* Above any real mount ID's range, and never 0. */
        id = (1UL << 63) | ((unsigned long)major << 32) | minor;
    }
    *(unsigned long *)(out + STX_MNT_ID_OFFSET) = id;
    *stx_mask |= STATX_MNT_ID;
}

/* struct statfs is the kernel's on both 64-bit architectures, as in musl. */
__attribute__((visibility("default"))) int fstatfs(int fd, void *buf)
{
    unsigned char *bytes = buf;
    for (int i = 0; i < 120; i++) bytes[i] = 0;
    long r = raw6(__NR_fstatfs, fd, (long)buf, 0, 0, 0, 0);
    if (r == -9 /* EBADF */ && fd >= 0) {
        char path[32];
        fd_path(fd, path);
        long again = raw6(__NR_statfs, (long)path, (long)buf, 0, 0, 0, 0);
        if (again == 0) return 0;
    }
    if (r < 0) {
        *__errno_location() = (int)-r;
        return -1;
    }
    return 0;
}

__attribute__((visibility("default"))) long syscall(long n, ...)
{
    va_list ap;
    va_start(ap, n);
    long a = va_arg(ap, long), b = va_arg(ap, long), c = va_arg(ap, long);
    long d = va_arg(ap, long), e = va_arg(ap, long), f = va_arg(ap, long);
    va_end(ap);
    long r = raw6(n, a, b, c, d, e, f);
    if (n == __NR_statx && r == 0) add_mount_id((int)a, (const char *)b, (unsigned)c, (unsigned)d, (unsigned char *)e);
    if (r < 0 && r > -4096) {
        *__errno_location() = (int)-r;
        return -1;
    }
    return r;
}
