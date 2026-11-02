/*
 * fdshm: shm_open(3) and shm_unlink(3) backed by memfd, preloaded (LD_PRELOAD) into the Wayland
 * compositor and its VNC server, which allocate their buffers with shm_open. Android has no
 * /dev/shm, and a folder in its place would put every frame on flash.
 *
 * A name stays known from shm_open with O_CREAT until shm_unlink, as with /dev/shm: opening it
 * again in that time reopens the same memory through /proc/self/fd (with the new access mode),
 * which is how a read-only copy of a writable buffer is made. Names are per process, which is
 * all these programs need: they unlink each name right after creating it.
 *
 * fchmod(2) is wrapped too: wlroots takes write permission off a writable buffer after making
 * its read-only copy (so a client can't reopen the copy writable), and Android refuses fchmod on
 * memfd with EACCES. Only that case is passed as done: the client could then reopen its copy
 * writable, which changes nothing anyone else reads (the keymap, in a one-app session).
 *
 * No C library, like fdsec: raw system calls, so the one library loads into musl programs. Only
 * errno is the program's own (__errno_location, which musl exports).
 */
#include <asm/unistd.h>
#include <linux/errno.h>
#include <linux/fcntl.h>
#include <stddef.h>

#define MFD_CLOEXEC 1U
#define MFD_ALLOW_SEALING 2U
#define MAX_NAMES 64
#define NAME_MAX_LEN 255

extern int *__errno_location(void);

static long syscall4(long n, long a, long b, long c, long d)
{
#if defined(__aarch64__)
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory");
    return x0;
#elif defined(__x86_64__)
    long ret;
    register long r10 __asm__("r10") = d;
    __asm__ volatile("syscall" : "=a"(ret) : "a"(n), "D"(a), "S"(b), "d"(c), "r"(r10) : "rcx", "r11", "memory");
    return ret;
#else
#error "unsupported architecture"
#endif
}

#define syscall3(n, a, b, c) syscall4(n, a, b, c, 0)

static long fail(long ret)
{
    *__errno_location() = (int)-ret;
    return -1;
}

static struct {
    char name[NAME_MAX_LEN + 1];
    int fd;
} names[MAX_NAMES];
static int lock;

static void take(void) { while (__atomic_exchange_n(&lock, 1, __ATOMIC_ACQUIRE)) { } }
static void give(void) { __atomic_store_n(&lock, 0, __ATOMIC_RELEASE); }

static int same(const char *a, const char *b)
{
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}

/* "/x" and "x" name the same object, as in musl; "" and names with another '/' are invalid. */
static const char *strip(const char *name)
{
    if (*name == '/') name++;
    size_t len = 0;
    for (const char *p = name; *p; p++, len++) {
        if (*p == '/') return NULL;
    }
    return len == 0 || len > NAME_MAX_LEN ? NULL : name;
}

static int find(const char *name)
{
    for (int i = 0; i < MAX_NAMES; i++) {
        if (names[i].name[0] && same(names[i].name, name)) return i;
    }
    return -1;
}

/* /proc/self/fd/<fd> */
static void fd_path(int fd, char *out)
{
    const char *prefix = "/proc/self/fd/";
    char digits[12];
    int n = 0;
    do { digits[n++] = (char)('0' + fd % 10); fd /= 10; } while (fd);
    while (*prefix) *out++ = *prefix++;
    while (n) *out++ = digits[--n];
    *out = 0;
}

int shm_open(const char *name, int flags, unsigned mode)
{
    (void)mode;
    const char *key = strip(name);
    if (!key) return (int)fail(-EINVAL);
    take();
    int slot = find(key);
    if (slot >= 0) {
        if ((flags & O_CREAT) && (flags & O_EXCL)) { give(); return (int)fail(-EEXIST); }
        char path[32];
        fd_path(names[slot].fd, path);
        give();
        long fd = syscall3(__NR_openat, AT_FDCWD, (long)path, (flags & ~(O_CREAT | O_EXCL | O_TRUNC)) | O_CLOEXEC);
        if (fd < 0) return (int)fail(fd);
        if (flags & O_TRUNC) syscall3(__NR_ftruncate, fd, 0, 0);
        return (int)fd;
    }
    if (!(flags & O_CREAT)) { give(); return (int)fail(-ENOENT); }
    int free_slot = -1;
    for (int i = 0; i < MAX_NAMES && free_slot < 0; i++) {
        if (!names[i].name[0]) free_slot = i;
    }
    if (free_slot < 0) { give(); return (int)fail(-ENFILE); }
    long fd = syscall3(__NR_memfd_create, (long)key, MFD_CLOEXEC | MFD_ALLOW_SEALING, 0);
    if (fd < 0) { give(); return (int)fail(fd); }
    size_t i = 0;
    for (; key[i]; i++) names[free_slot].name[i] = key[i];
    names[free_slot].name[i] = 0;
    names[free_slot].fd = (int)fd;
    give();
    return (int)fd;
}

/* Whether fd is a memfd: /proc/self/fd/<fd> reads "/memfd:<name> (deleted)". */
static int is_memfd(int fd)
{
    char path[32], target[16];
    fd_path(fd, path);
    long n = syscall4(__NR_readlinkat, AT_FDCWD, (long)path, (long)target, sizeof(target));
    const char *prefix = "/memfd:";
    for (int i = 0; prefix[i]; i++) {
        if (i >= n || target[i] != prefix[i]) return 0;
    }
    return 1;
}

int fchmod(int fd, unsigned mode)
{
    long ret = syscall3(__NR_fchmod, fd, mode, 0);
    if (ret == -EACCES && is_memfd(fd)) return 0;
    return ret < 0 ? (int)fail(ret) : 0;
}

/* Forgets the name; the memory lives on while any descriptor or mapping holds it. */
int shm_unlink(const char *name)
{
    const char *key = strip(name);
    if (!key) return (int)fail(-EINVAL);
    take();
    int slot = find(key);
    if (slot < 0) { give(); return (int)fail(-ENOENT); }
    names[slot].name[0] = 0;
    give();
    return 0;
}
