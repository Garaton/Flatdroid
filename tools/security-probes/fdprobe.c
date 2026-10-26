/* fdprobe: tries known ways out of a proot root. Usage: fdprobe <host-only path> <host pid>
 * Each line: test name, result, errno. "ESCAPE" means the host was reached. */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/io_uring.h>
#include <linux/openat2.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

/* The kernel's file handle. bionic (Termux) forward-declares struct file_handle but doesn't
 * define it, so embedding it fails to compile; this matches the ABI with an inline buffer and
 * needs no libc type. */
struct probe_file_handle {
    unsigned int handle_bytes;
    int handle_type;
    unsigned char f_handle[128];
};

static void res(const char *t, int ok, const char *okmsg) {
    if (ok) printf("%-22s ESCAPE  %s\n", t, okmsg);
    else printf("%-22s blocked errno=%d (%s)\n", t, errno, strerror(errno));
}

/* Reading another same-UID process's memory is a real exposure but not a filesystem/path escape,
 * so it gets its own label. If the process is Xvnc it is the screen and keystrokes; a helper is
 * readable only until its /proc entry is hidden (Default and Hardened). */
static void memres(const char *t, int ok) {
    if (ok) printf("%-22s EXPOSED read another process's memory (same-UID; Xvnc = screen/keys)\n", t);
    else printf("%-22s blocked errno=%d (%s)\n", t, errno, strerror(errno));
}

/* The system calls fdsec blocks beyond the escapes above. Under fdsec each should return the
 * errno shown (EPERM, or ENOSYS for io_uring/openat2 and the --extra group); "OPEN" means the
 * filter let it through. The extra group is blocked only at Default/Hardened (or --extra). */
static void filter_checks(void) {
    struct chk { const char *name; long nr; int want; };
    static const struct chk list[] = {
#ifdef __NR_pidfd_getfd
        { "pidfd_getfd",         __NR_pidfd_getfd,      EPERM },
#endif
#ifdef __NR_bpf
        { "bpf",                 __NR_bpf,              EPERM },
#endif
#ifdef __NR_perf_event_open
        { "perf_event_open",     __NR_perf_event_open,  EPERM },
#endif
#ifdef __NR_userfaultfd
        { "userfaultfd",         __NR_userfaultfd,      EPERM },
#endif
#ifdef __NR_pidfd_open
        { "pidfd_open+",         __NR_pidfd_open,       ENOSYS },
#endif
#ifdef __NR_process_madvise
        { "process_madvise+",    __NR_process_madvise,  ENOSYS },
#endif
#ifdef __NR_kcmp
        { "kcmp+",               __NR_kcmp,             ENOSYS },
#endif
#ifdef __NR_keyctl
        { "keyctl+",             __NR_keyctl,           ENOSYS },
#endif
#ifdef __NR_add_key
        { "add_key+",            __NR_add_key,          ENOSYS },
#endif
#ifdef __NR_request_key
        { "request_key+",        __NR_request_key,      ENOSYS },
#endif
    };
    for (size_t i = 0; i < sizeof list / sizeof *list; i++) {
        errno = 0;
        long r = syscall(list[i].nr, -1L, 0L, 0L, 0L, 0L, 0L);
        int e = r < 0 ? errno : 0;
        printf("%-22s %s errno=%d (%s)\n", list[i].name,
               e == list[i].want ? "blocked" : "OPEN   ", e, strerror(e));
    }
}

/* Internet sockets: with network off, fdsec --no-inet refuses them (EACCES); Unix sockets stay. */
static void net_checks(void) {
    int fd;
    errno = 0; fd = socket(AF_INET, SOCK_STREAM, 0);
    printf("%-22s %s errno=%d\n", "socket AF_INET",  fd >= 0 ? "OPEN (network on) " : "blocked", fd < 0 ? errno : 0);
    if (fd >= 0) close(fd);
    errno = 0; fd = socket(AF_INET6, SOCK_STREAM, 0);
    printf("%-22s %s errno=%d\n", "socket AF_INET6", fd >= 0 ? "OPEN (network on) " : "blocked", fd < 0 ? errno : 0);
    if (fd >= 0) close(fd);
    errno = 0; fd = socket(AF_UNIX, SOCK_STREAM, 0);
    printf("%-22s %s errno=%d\n", "socket AF_UNIX",  fd >= 0 ? "ok (expected)" : "BLOCKED", fd < 0 ? errno : 0);
    if (fd >= 0) close(fd);
}

static void seccomp_status(void) {
    FILE *f = fopen("/proc/self/status", "r"); char l[256];
    if (!f) { printf("status: unreadable\n"); return; }
    while (fgets(l, sizeof l, f)) if (!strncmp(l, "Seccomp", 7)) printf("  %s", l);
    fclose(f);
}

static int uring_open(const char *path) {
    struct io_uring_params p; memset(&p, 0, sizeof p);
    int fd = syscall(__NR_io_uring_setup, 4, &p);
    if (fd < 0) return -1;
    size_t sl = p.sq_off.array + p.sq_entries * sizeof(unsigned);
    size_t cl = p.cq_off.cqes + p.cq_entries * sizeof(struct io_uring_cqe);
    char *sq = mmap(0, sl, PROT_READ | PROT_WRITE, MAP_SHARED, fd, IORING_OFF_SQ_RING);
    char *cq = mmap(0, cl, PROT_READ | PROT_WRITE, MAP_SHARED, fd, IORING_OFF_CQ_RING);
    struct io_uring_sqe *sqes = mmap(0, p.sq_entries * sizeof *sqes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, IORING_OFF_SQES);
    if (sq == MAP_FAILED || cq == MAP_FAILED || sqes == MAP_FAILED) return -1;
    memset(&sqes[0], 0, sizeof sqes[0]);
    sqes[0].opcode = IORING_OP_OPENAT; sqes[0].fd = AT_FDCWD;
    sqes[0].addr = (unsigned long)path; sqes[0].open_flags = O_RDONLY | O_DIRECTORY;
    unsigned *tail = (unsigned *)(sq + p.sq_off.tail), *arr = (unsigned *)(sq + p.sq_off.array);
    arr[*tail & *(unsigned *)(sq + p.sq_off.ring_mask)] = 0;
    __atomic_store_n(tail, *tail + 1, __ATOMIC_RELEASE);
    if (syscall(__NR_io_uring_enter, fd, 1, 1, IORING_ENTER_GETEVENTS, 0, 0) < 0) return -1;
    unsigned head = *(unsigned *)(cq + p.cq_off.head);
    struct io_uring_cqe *c = (struct io_uring_cqe *)(cq + p.cq_off.cqes) + (head & *(unsigned *)(cq + p.cq_off.ring_mask));
    if (c->res < 0) { errno = -c->res; return -1; }
    return c->res;
}

static void list(const char *t, const char *dir) {
    DIR *d = opendir(dir); struct dirent *e; int sys = 0, app = 0;
    if (!d) { res(t, 0, ""); return; }
    while ((e = readdir(d))) { if (!strcmp(e->d_name, "system") || !strcmp(e->d_name, "apex")) sys = 1; if (!strcmp(e->d_name, "app")) app = 1; }
    closedir(d);
    printf("%-22s %s  (host /system or /apex seen: %s, /app seen: %s)\n", t, sys ? "ESCAPE " : "guest  ", sys ? "yes" : "no", app ? "yes" : "no");
}

static unsigned long first_map(int pid) {
    char b[64]; unsigned long a = 0; snprintf(b, sizeof b, "/proc/%d/maps", pid);
    FILE *m = fopen(b, "r"); if (m) { if (fscanf(m, "%lx", &a) != 1) a = 0; fclose(m); }
    return a;
}

/* /proc/PID/mem opened read-write: same power as ptrace, and not a syscall seccomp can tell apart. Reads only. */
static void memtest(const char *label, int pid) {
    char b[64], t[48], buf[16]; unsigned long a = first_map(pid);
    snprintf(b, sizeof b, "/proc/%d/mem", pid); snprintf(t, sizeof t, "%s mem rw", label);
    errno = 0; int fd = open(b, O_RDWR);
    if (fd < 0) { res(t, 0, ""); return; }
    errno = 0; ssize_t r = a ? pread(fd, buf, 16, a) : -1; close(fd);
    memres(t, r > 0);
}

static void scan(void) {
    DIR *d = opendir("/proc"); struct dirent *e; int found = 0;
    if (!d) return;
    while ((e = readdir(d))) {
        int p = atoi(e->d_name); if (p <= 0 || p == getpid()) continue;
        char b[64], c[32] = ""; snprintf(b, sizeof b, "/proc/%d/comm", p);
        FILE *f = fopen(b, "r"); if (!f) continue;
        if (fgets(c, sizeof c, f)) c[strcspn(c, "\n")] = 0;
        fclose(f);
        /* "libproot.so" is Flatdroid's own proot (the tracer); reading its memory would be the
         * worst escape, so the non-dumpable build must block it. */
        if (strcmp(c, "proot") && strcmp(c, "libproot.so") && strcmp(c, "Xvnc") && strcmp(c, "pulseaudio") && strcmp(c, "openbox")) continue;
        char t[40]; snprintf(t, sizeof t, "%s(%d)", c, p); memtest(t, p); found++;
        unsigned long a = first_map(p); char buf[16]; struct iovec l = { buf, 16 }, rv = { (void *)a, 16 };
        snprintf(t, sizeof t, "%s(%d) pvm", c, p);
        if (!a) { printf("%-22s blocked (maps unreadable: not dumpable)\n", t); continue; }
        errno = 0; long r = syscall(__NR_process_vm_readv, p, &l, 1, &rv, 1, 0); memres(t, r > 0);
    }
    closedir(d);
    if (!found) printf("scan: no proot/Xvnc/pulseaudio/openbox processes visible\n");
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: fdprobe <host-only dir> <host pid>\n"); return 2; }
    const char *hp = argv[1]; int pid = atoi(argv[2]); char b[256]; int fd;
    printf("fdprobe pid=%d target=%s hostpid=%d\n", getpid(), hp, pid); seccomp_status();
    filter_checks();
    net_checks();

    errno = 0; fd = open(hp, O_RDONLY | O_DIRECTORY);           res("open (baseline)", fd >= 0, "proot did not translate open");
    struct open_how h = { .flags = O_RDONLY | O_DIRECTORY };
    errno = 0; fd = syscall(__NR_openat2, AT_FDCWD, hp, &h, sizeof h); res("openat2", fd >= 0, "host path opened");
    errno = 0; fd = uring_open(hp);                              res("io_uring openat", fd >= 0, "host path opened");
    struct probe_file_handle fh = { .handle_bytes = sizeof fh.f_handle }; int mid;
    errno = 0; int r = syscall(__NR_name_to_handle_at, AT_FDCWD, "/", &fh, &mid, 0);
    if (r == 0) { int m = open("/", O_RDONLY); errno = 0; fd = syscall(__NR_open_by_handle_at, m, &fh, O_RDONLY); res("open_by_handle_at", fd >= 0, "handle opened"); }
    else res("name_to_handle_at", 0, "");
    errno = 0; r = ptrace(PTRACE_SEIZE, pid, 0, 0);               res("ptrace host pid", r == 0, "attached to host process");
    if (r == 0) ptrace(PTRACE_DETACH, pid, 0, 0);
    snprintf(b, sizeof b, "/proc/%d/maps", pid); FILE *m = fopen(b, "r"); unsigned long a = 0;
    if (m) { if (fscanf(m, "%lx", &a) != 1) a = 0; fclose(m); }
    char buf[16]; struct iovec l = { buf, 16 }, rv = { (void *)a, 16 };
    errno = 0; r = a ? syscall(__NR_process_vm_readv, pid, &l, 1, &rv, 1, 0) : -1; res("process_vm_readv", r > 0, "read host process memory");
    snprintf(b, sizeof b, "/proc/%d/root", pid); list("/proc/PID/root", b);
    snprintf(b, sizeof b, "/proc/%d/cwd", pid); errno = 0; fd = open(b, O_RDONLY | O_DIRECTORY);
    if (fd >= 0) { char p2[64]; snprintf(p2, sizeof p2, "/proc/self/fd/%d", fd); char t[512]; ssize_t k = readlink(p2, t, sizeof t - 1); t[k > 0 ? k : 0] = 0; printf("%-22s LEAK    readlink shows %s (a host path here is an info leak; proot blocks traversal through it, so not an escape)\n", "/proc/PID/cwd", t); }
    else res("/proc/PID/cwd", 0, "");
    memtest("host pid", pid);
    scan();
    return 0;
}
