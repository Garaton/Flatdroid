/*
 * fdv4l2: Android's cameras as V4L2 devices, preloaded (LD_PRELOAD) into apps that open
 * /dev/video* themselves, so their camera needs no PipeWire. Each camera's device in the app's
 * /dev is a pipe Flatdroid writes frames to, each as a part of a multipart stream (a header with
 * its Content-Type and Content-Length, as webcams send over HTTP). Opening the device opens the
 * pipe, so its descriptor is the device's, and poll() and select() need no wrapping: the pipe is
 * readable when a frame is there. This answers the V4L2 calls on it: capabilities, the formats
 * (Motion-JPEG and NV12 at one size and rate per camera; the app picks, and the choice is written
 * to the camera's control pipe for Flatdroid), inputs, stream parameters, buffers (memory-mapped,
 * exported and imported as DMABUF, all backed by memfd), streaming, and no controls. A dequeue
 * reads the newest whole frame of the chosen format into a buffer.
 *
 * For libcamera, each camera also has a media controller device (/dev/media*) that tells the
 * driver ("uvcvideo", as a USB camera) and the one video node; Flatdroid lays out the matching
 * /sys entries. stat() and its relatives report the devices as character devices (video: major
 * 81), as apps check. Devices are also recognized by their inode on their first V4L2 or media
 * call, for callers that open them with a raw system call (libcamera does).
 *
 * The cameras are listed in CONFIG, one per line:
 *   "<video path> <media path> <control path> <width> <height> <rate> <formats> <name>"
 * with formats as "mjpeg,nv12" in the order they are offered.
 *
 * The calls wrapped are those of PipeWire's pw-v4l2 (read for its behavior only) but munmap,
 * plus the stat family, and the C library's syscall() for the same calls (libv4l2, which many
 * camera programs use, makes its calls through it). Other paths, descriptors and system calls go
 * straight to the kernel. Known misses: static binaries, and system call instructions of a
 * program's own.
 *
 * Also socket(): Android refuses apps the kernel's device event socket (NETLINK_KOBJECT_UEVENT,
 * EACCES), and eudev's udev_monitor_new_from_netlink() then gives no monitor, which programs
 * watching for new cameras use unchecked (they crash in udev_monitor_enable_receiving). A
 * refused one becomes a routing netlink socket, which carries no device events: the monitor
 * exists and stays quiet (no camera comes or goes). Loaded for that into every Alpine program.
 *
 * No C library, like fdsec and fdshm: raw system calls, so one library loads into any root's
 * programs. Only errno is the program's own (__errno_location, which glibc and musl export).
 */
#include <asm/unistd.h>
#include <linux/errno.h>
#include <linux/fcntl.h>
#include <linux/media.h>
#include <linux/mman.h>
#include <linux/netlink.h>
#include <linux/stat.h>
#include <linux/videodev2.h>
#include <stdarg.h>
#include <stddef.h>

#ifndef CONFIG
#define CONFIG "/.flatdroid/cameras"
#endif

#define MAX_CAMERAS 8
#define MAX_STREAMS 16
#define MAX_FDS 32
#define MAX_BUFFERS 8
#define MAX_FORMATS 2
#define PATH_LEN 64
#define NAME_LEN 32
#define CONFIG_BYTES 4096
#define VIDEO_MAJOR 81
#define MEDIA_MAJOR 238
#define PAGE 4096UL
/* A frame not complete this long after its start was seen (the writer stopped midway) is dropped. */
#define PARTIAL_WAIT_MS 1000

#define BOUNDARY "--flatdroid-frame\r\n"
#define TYPE_FIELD "Content-Type:"
#define LENGTH_FIELD "Content-Length:"

#define MFD_CLOEXEC 1U

extern int *__errno_location(void);

/* ---------- system calls ---------- */

static long syscall6(long n, long a, long b, long c, long d, long e, long f)
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
    __asm__ volatile("syscall" : "=a"(ret) : "a"(n), "D"(a), "S"(b), "d"(c), "r"(r10), "r"(r8), "r"(r9) : "rcx", "r11", "memory");
    return ret;
#else
#error "unsupported architecture"
#endif
}

#define sys1(n, a) syscall6(n, (long)(a), 0, 0, 0, 0, 0)
#define sys2(n, a, b) syscall6(n, (long)(a), (long)(b), 0, 0, 0, 0)
#define sys3(n, a, b, c) syscall6(n, (long)(a), (long)(b), (long)(c), 0, 0, 0)
#define sys4(n, a, b, c, d) syscall6(n, (long)(a), (long)(b), (long)(c), (long)(d), 0, 0)
#define sys5(n, a, b, c, d, e) syscall6(n, (long)(a), (long)(b), (long)(c), (long)(d), (long)(e), 0)

static int failed(long ret) { return ret < 0 && ret > -4096; }

/* A system call's result as libc returns it: -1 and errno on failure. */
static long result(long ret)
{
    if (failed(ret)) {
        *__errno_location() = (int)-ret;
        return -1;
    }
    return ret;
}

static long fail(int err)
{
    *__errno_location() = err;
    return -1;
}

/* ---------- small helpers (no C library) ---------- */

static void zero(void *p, unsigned long n)
{
    volatile unsigned char *b = p;
    while (n--) *b++ = 0;
}

static void copy(void *to, const void *from, unsigned long n)
{
    unsigned char *t = to;
    const unsigned char *f = from;
    /* Word by word where both line up: raw frames are megabytes. */
    if ((((unsigned long)t | (unsigned long)f) & 7) == 0) {
        for (; n >= 8; n -= 8, t += 8, f += 8) *(unsigned long *)(void *)t = *(const unsigned long *)(const void *)f;
    }
    while (n--) *t++ = *f++;
}

static void move(unsigned char *to, const unsigned char *from, unsigned long n)
{
    while (n--) *to++ = *from++; /* only ever towards the start */
}

static int same(const char *a, const char *b)
{
    while (*a && *a == *b) a++, b++;
    return *a == *b;
}

static int starts(const char *s, unsigned long len, const char *prefix)
{
    unsigned long i = 0;
    for (; prefix[i]; i++) if (i >= len || s[i] != prefix[i]) return 0;
    return 1;
}

static void text(char *to, unsigned long size, const char *from)
{
    unsigned long i = 0;
    for (; i + 1 < size && from[i]; i++) to[i] = from[i];
    for (; i < size; i++) to[i] = 0;
}

/* "<prefix><n>" into to. */
static void numbered(char *to, unsigned long size, const char *prefix, unsigned n)
{
    text(to, size, prefix);
    unsigned long i = 0;
    while (i < size && to[i]) i++;
    char digits[12];
    int d = 0;
    do digits[d++] = (char)('0' + n % 10); while ((n /= 10) && d < 11);
    while (d && i + 1 < size) to[i++] = digits[--d];
    if (i < size) to[i] = 0;
}

/* Index of needle in hay, or -1. */
static long find(const unsigned char *hay, unsigned long len, const char *needle)
{
    unsigned long n = 0;
    while (needle[n]) n++;
    for (unsigned long i = 0; i + n <= len; i++) {
        unsigned long k = 0;
        while (k < n && hay[i + k] == (unsigned char)needle[k]) k++;
        if (k == n) return (long)i;
    }
    return -1;
}

/* ---------- lock (callers may be on several threads) ---------- */

static volatile char lock_flag;

static void lock(void)
{
    while (__atomic_test_and_set(&lock_flag, __ATOMIC_ACQUIRE)) sys3(__NR_sched_yield, 0, 0, 0);
}

static void unlock(void) { __atomic_clear(&lock_flag, __ATOMIC_RELEASE); }

/* ---------- formats ---------- */

enum format { MJPEG, NV12 };

static const struct {
    const char *name;     /* in the cameras list and the control pipe */
    const char *type;     /* a frame's Content-Type */
    unsigned fourcc;
    const char *description;
} FORMATS[] = {
    [MJPEG] = { "mjpeg", "image/jpeg", V4L2_PIX_FMT_MJPEG, "Motion-JPEG" },
    [NV12] = { "nv12", "video/nv12", V4L2_PIX_FMT_NV12, "Y/UV 4:2:0" },
};

/* ---------- the cameras ---------- */

struct camera {
    char path[PATH_LEN], media[PATH_LEN], control[PATH_LEN];
    char name[NAME_LEN];
    unsigned width, height, rate;
    enum format formats[MAX_FORMATS];
    unsigned format_count;
    enum format format;   /* the one the app chose last */
    unsigned long video_dev, video_ino, media_dev, media_ino;
};

struct camera_list {
    int count;
    struct camera at[MAX_CAMERAS];
};

/*
 * Loaded once, then published whole: a thread never sees it half-filled, and loading takes no
 * lock, so a fork while another thread loads it leaves nothing held in the child.
 */
static struct camera_list *camera_list;
/* What a caller gets when there was no memory to load the list into; it is loaded again next time. */
static struct camera_list no_cameras;

static void *map(int fd, unsigned long len);
static unsigned long pages(unsigned long n);

static unsigned number(const char **p)
{
    unsigned v = 0;
    while (**p == ' ') (*p)++;
    while (**p >= '0' && **p <= '9' && v < 100000) v = v * 10 + (unsigned)(*(*p)++ - '0');
    return v;
}

static void word(const char **p, char *to, unsigned long size)
{
    unsigned long i = 0;
    while (**p == ' ') (*p)++;
    while (**p && **p != ' ' && **p != '\n') {
        if (i + 1 < size) to[i++] = **p;
        (*p)++;
    }
    to[i] = 0;
}

/* The device and inode behind path, so a descriptor opened some other way is known by them. */
static void identify(const char *path, unsigned long *dev, unsigned long *ino)
{
    struct statx st;
    if (!failed(sys5(__NR_statx, AT_FDCWD, path, 0, STATX_INO, &st))) {
        *dev = ((unsigned long)st.stx_dev_major << 32) | st.stx_dev_minor;
        *ino = st.stx_ino;
    }
}

/* CONFIG into list, with buf (CONFIG_BYTES) to read it into. */
static void load_cameras(struct camera_list *list, char *buf)
{
    list->count = 0;
    long fd = sys4(__NR_openat, AT_FDCWD, CONFIG, O_RDONLY | O_CLOEXEC, 0);
    if (failed(fd)) return;
    long len = 0;
    for (;;) {
        long n = sys3(__NR_read, fd, buf + len, CONFIG_BYTES - 1 - len);
        if (n == -EINTR) continue;
        if (n <= 0) break;
        len += n;
        if (len == CONFIG_BYTES - 1) break;
    }
    sys1(__NR_close, fd);
    if (len <= 0) return;
    buf[len] = 0;
    const char *p = buf;
    while (*p && list->count < MAX_CAMERAS) {
        struct camera *c = &list->at[list->count];
        zero(c, sizeof(*c));
        word(&p, c->path, sizeof(c->path));
        word(&p, c->media, sizeof(c->media));
        word(&p, c->control, sizeof(c->control));
        c->width = number(&p);
        c->height = number(&p);
        c->rate = number(&p);
        char formats[32];
        word(&p, formats, sizeof(formats));
        for (const char *f = formats; *f && c->format_count < MAX_FORMATS;) {
            unsigned long n = 0;
            while (f[n] && f[n] != ',') n++;
            for (unsigned k = 0; k < sizeof(FORMATS) / sizeof(FORMATS[0]); k++) {
                unsigned long m = 0;
                while (FORMATS[k].name[m]) m++;
                if (m == n && starts(f, n, FORMATS[k].name)) c->formats[c->format_count++] = (enum format)k;
            }
            f += n;
            if (*f == ',') f++;
        }
        while (*p == ' ') p++;
        unsigned i = 0;
        while (*p && *p != '\n') {
            if (i + 1 < NAME_LEN) c->name[i++] = *p;
            p++;
        }
        c->name[i] = 0;
        if (*p == '\n') p++;
        if (c->path[0] != '/' || c->media[0] != '/' || c->control[0] != '/' || !c->width || !c->height || !c->rate || !c->format_count) continue;
        c->format = c->formats[0];
        identify(c->path, &c->video_dev, &c->video_ino);
        identify(c->media, &c->media_dev, &c->media_ino);
        list->count++;
    }
}

/* The cameras, loaded on first use. Threads that load it at once each do; the first to finish is kept. */
static struct camera_list *cameras(void)
{
    struct camera_list *list = __atomic_load_n(&camera_list, __ATOMIC_ACQUIRE);
    if (list) return list;
    unsigned long kept = pages(sizeof(*list));
    list = map(-1, kept + CONFIG_BYTES);
    if (!list) return &no_cameras;
    load_cameras(list, (char *)list + kept);
    sys2(__NR_munmap, (char *)list + kept, CONFIG_BYTES);
    struct camera_list *first = 0;
    if (__atomic_compare_exchange_n(&camera_list, &first, list, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return list;
    sys2(__NR_munmap, list, kept);
    return first;
}

/* A camera a stream was opened on: the list is loaded by then. */
static struct camera *camera(int index) { return &camera_list->at[index]; }

enum kind { NONE, VIDEO, MEDIA };

/* The camera at path, or -1; kind says which of its devices. */
static int camera_at(const char *path, enum kind *kind)
{
    if (!path || path[0] != '/') return -1;
    const struct camera_list *list = cameras();
    for (int i = 0; i < list->count; i++) {
        if (same(path, list->at[i].path)) { *kind = VIDEO; return i; }
        if (same(path, list->at[i].media)) { *kind = MEDIA; return i; }
    }
    return -1;
}

/*
 * Tells Flatdroid what the app does with the camera, a line each (its end keeps the pipe open;
 * none, no harm): the format's name when the app chooses it, ON when it starts streaming, OFF when
 * it stops (stream off, or the device closed while streaming). An app that streams uses the
 * camera, however slowly it reads.
 */
#define ON "on"
#define OFF "off"
static void announce(const struct camera *c, const char *what)
{
    long fd = sys4(__NR_openat, AT_FDCWD, c->control, O_WRONLY | O_NONBLOCK | O_CLOEXEC, 0);
    if (failed(fd)) return;
    char line[16];
    text(line, sizeof(line), what);
    unsigned long n = 0;
    while (line[n]) n++;
    line[n++] = '\n';
    sys3(__NR_write, fd, line, n);
    sys1(__NR_close, fd);
}

/* ---------- open devices ---------- */

struct buffer {
    int memfd;           /* MMAP: the buffer's memory, which mmap and EXPBUF hand out; -1 when none */
    void *own;           /* where this library writes it */
    unsigned long own_length;
    int imported;        /* DMABUF: the app's descriptor last queued */
    int queued;
};

struct stream {
    int used;
    int refs;
    int camera;
    enum kind kind;
    unsigned count;
    unsigned memory;        /* V4L2_MEMORY_MMAP or V4L2_MEMORY_DMABUF */
    unsigned long length;   /* each MMAP buffer's, page-aligned */
    struct buffer buffers[MAX_BUFFERS];
    unsigned queue[MAX_BUFFERS];
    unsigned queue_head, queue_len;
    int streaming;
    unsigned sequence;
    unsigned char *staging; /* what was read from the pipe and not used yet */
    unsigned long staged, staging_size;
};

static struct stream streams[MAX_STREAMS];
static struct { int fd; int stream; } fds[MAX_FDS];
static int fd_count;

/* The stream behind fd, or null. */
static struct stream *stream_of(int fd)
{
    struct stream *s = 0;
    if (fd < 0 || __atomic_load_n(&fd_count, __ATOMIC_ACQUIRE) == 0) return 0;
    lock();
    for (int i = 0; i < fd_count; i++) if (fds[i].fd == fd) { s = &streams[fds[i].stream]; break; }
    unlock();
    return s;
}

static unsigned long sizeimage(const struct camera *c, enum format f)
{
    unsigned long pixels = (unsigned long)c->width * c->height;
    if (f == NV12) return pixels * 3 / 2;
    /* A JPEG frame is far smaller than this; a bigger one is dropped. */
    return pixels * 2 < 65536 ? 65536 : pixels * 2;
}

static unsigned long pages(unsigned long n) { return (n + PAGE - 1) & ~(PAGE - 1); }

static void *map(int fd, unsigned long len)
{
    long p = syscall6(__NR_mmap, 0, (long)len, PROT_READ | PROT_WRITE, fd < 0 ? MAP_PRIVATE | MAP_ANONYMOUS : MAP_SHARED, fd, 0);
    return failed(p) ? 0 : (void *)p;
}

/* Memory to hand out as a descriptor: memfd, else an unlinked temporary file (kernels without memfd). */
static int shared_memory(unsigned long len)
{
    long fd = sys2(__NR_memfd_create, "fdv4l2", MFD_CLOEXEC);
    if (failed(fd)) fd = sys4(__NR_openat, AT_FDCWD, "/tmp", __O_TMPFILE | O_RDWR | O_CLOEXEC, 0600);
    if (failed(fd)) return -1;
    if (failed(sys2(__NR_ftruncate, fd, len))) {
        sys1(__NR_close, fd);
        return -1;
    }
    return (int)fd;
}

/* Under the lock. */
static int add_fd(int fd, int stream)
{
    if (fd_count == MAX_FDS) return -1;
    fds[fd_count].fd = fd;
    fds[fd_count].stream = stream;
    __atomic_store_n(&fd_count, fd_count + 1, __ATOMIC_RELEASE);
    streams[stream].refs++;
    return 0;
}

static struct stream *register_open(int fd, int camera, enum kind kind)
{
    struct stream *found = 0;
    lock();
    for (int i = 0; i < MAX_STREAMS; i++) {
        struct stream *s = &streams[i];
        if (s->used) continue;
        zero(s, sizeof(*s));
        s->used = 1;
        s->camera = camera;
        s->kind = kind;
        for (unsigned k = 0; k < MAX_BUFFERS; k++) s->buffers[k].memfd = s->buffers[k].imported = -1;
        if (add_fd(fd, i) != 0) s->used = 0;
        else found = s;
        break;
    }
    unlock();
    return found;
}

/* Under the lock, or with the stream no longer reachable. */
static void free_buffers(struct stream *s)
{
    for (unsigned i = 0; i < MAX_BUFFERS; i++) {
        struct buffer *b = &s->buffers[i];
        if (b->own) sys2(__NR_munmap, b->own, b->own_length);
        /* Descriptors the app has (mapped or exported) keep the memory as long as it needs it. */
        if (b->memfd >= 0) sys1(__NR_close, b->memfd);
        zero(b, sizeof(*b));
        b->memfd = b->imported = -1;
    }
    s->count = 0;
    s->queue_head = s->queue_len = 0;
}

static void forget_fd(int fd)
{
    if (__atomic_load_n(&fd_count, __ATOMIC_ACQUIRE) == 0) return;
    int stopped = -1;
    lock();
    for (int i = 0; i < fd_count; i++) {
        if (fds[i].fd != fd) continue;
        struct stream *s = &streams[fds[i].stream];
        fds[i] = fds[fd_count - 1];
        __atomic_store_n(&fd_count, fd_count - 1, __ATOMIC_RELEASE);
        if (--s->refs == 0) {
            /* Closed while streaming: the app stopped. */
            if (s->kind == VIDEO && s->streaming) stopped = s->camera;
            s->streaming = 0;
            if (s->staging) sys2(__NR_munmap, s->staging, s->staging_size);
            free_buffers(s);
            s->used = 0;
        }
        break;
    }
    unlock();
    if (stopped >= 0) announce(camera(stopped), OFF);
}

/*
 * The stream behind fd for a request of type ('V' for V4L2, '|' for the media controller): known
 * already, or a camera's device opened some other way, recognized by its inode.
 */
static struct stream *stream_for(int fd, unsigned type)
{
    struct stream *s = stream_of(fd);
    if (s || (type != 'V' && type != '|')) return s;
    const struct camera_list *list = cameras();
    if (list->count <= 0) return 0;
    struct statx st;
    if (failed(sys5(__NR_statx, fd, "", AT_EMPTY_PATH, STATX_INO, &st))) return 0;
    unsigned long dev = ((unsigned long)st.stx_dev_major << 32) | st.stx_dev_minor;
    for (int i = 0; i < list->count; i++) {
        const struct camera *c = &list->at[i];
        if (c->video_ino && dev == c->video_dev && st.stx_ino == c->video_ino) return register_open(fd, i, VIDEO);
        if (c->media_ino && dev == c->media_dev && st.stx_ino == c->media_ino) return register_open(fd, i, MEDIA);
    }
    return 0;
}

/* ---------- frames ---------- */

static long monotonic_ms(void)
{
    struct { long sec, nsec; } ts;
    sys2(__NR_clock_gettime, 1 /* CLOCK_MONOTONIC */, &ts);
    return ts.sec * 1000 + ts.nsec / 1000000;
}

/*
 * Whether a frame whose body ends at e really ends there: its "\r\n", then the next frame's
 * boundary or nothing yet. Flatdroid stops writing a frame larger than the pipe when the app stops
 * reading midway; the next frame then follows the cut one, and its length would take in the next
 * frame's start.
 */
static int frame_ends(const struct stream *s, unsigned long e)
{
    if (s->staging[e] != '\r' || s->staging[e + 1] != '\n') return 0;
    unsigned long rest = s->staged - (e + 2);
    for (unsigned long i = 0; BOUNDARY[i] && i < rest; i++) if (s->staging[e + 2 + i] != (unsigned char)BOUNDARY[i]) return 0;
    return 1;
}

/*
 * Where the newest whole frame of the camera's format in the staging area starts (its body at
 * *data, *len bytes) and where it ends; 0 when there is none. Anything before a frame's boundary,
 * frames cut midway ([frame_ends]) and frames of another format (sent before the app's choice
 * reached Flatdroid) are skipped.
 */
static unsigned long newest_frame(struct stream *s, enum format format, unsigned long *data, unsigned long *len, unsigned long *skipped)
{
    unsigned long at = 0, end = 0;
    *skipped = 0;
    for (;;) {
        long b = find(s->staging + at, s->staged - at, BOUNDARY);
        if (b < 0) break;
        unsigned long start = at + (unsigned long)b;
        long h = find(s->staging + start, s->staged - start, "\r\n\r\n");
        if (h < 0) break;
        unsigned long body = start + (unsigned long)h + 4;
        long f = find(s->staging + start, (unsigned long)h, LENGTH_FIELD);
        if (f < 0) { at = body; continue; }
        const char *p = (const char *)s->staging + start + f + sizeof(LENGTH_FIELD) - 1;
        unsigned long n = 0;
        while (*p == ' ') p++;
        while (*p >= '0' && *p <= '9' && n < (1UL << 30)) n = n * 10 + (unsigned long)(*p++ - '0');
        if (body + n + 2 > s->staged) break;
        /* Cut: what comes after its boundary is looked through for the next one. */
        if (!frame_ends(s, body + n)) { at = start + 1; continue; }
        long t = find(s->staging + start, (unsigned long)h, TYPE_FIELD);
        int match = 1;
        if (t >= 0) {
            const char *q = (const char *)s->staging + start + t + sizeof(TYPE_FIELD) - 1;
            while (*q == ' ') q++;
            unsigned long left = (const char *)s->staging + start + h - q;
            match = starts(q, left, FORMATS[format].type);
        }
        if (match) {
            *data = body;
            *len = n;
            end = body + n + 2;
        } else {
            *skipped = body + n + 2;
        }
        at = body + n + 2;
    }
    if (*skipped < end) *skipped = 0;
    return end;
}

/* Reads what the pipe has; with wait, until something comes. 0, or a negative error. */
static long fill(int fd, struct stream *s, int wait)
{
    if (s->staged == s->staging_size) s->staged = 0; /* never a whole frame in it: start again */
    if (wait) {
        struct { int fd; short events, revents; } p = { fd, 1 /* POLLIN */, 0 };
        long r = sys5(__NR_ppoll, &p, 1, 0, 0, 0);
        if (failed(r)) return r;
    }
    long n = sys3(__NR_read, fd, s->staging + s->staged, s->staging_size - s->staged);
    if (n == -EAGAIN && !wait) return 0;
    if (failed(n)) return n;
    s->staged += (unsigned long)n;
    return 0;
}

/* Where buffer index is written, and how much it holds. */
static void *destination(struct stream *s, unsigned index, unsigned long *room)
{
    struct buffer *b = &s->buffers[index];
    *room = b->own_length;
    return b->own;
}

/* Waits for a whole frame into the first queued buffer and dequeues it into b. */
static long dequeue(int fd, struct stream *s, struct v4l2_buffer *b)
{
    const struct camera *c = camera(s->camera);
    if (!s->staging) {
        s->staging_size = pages(sizeimage(c, MJPEG) + sizeimage(c, NV12) + PAGE);
        s->staging = map(-1, s->staging_size);
        if (!s->staging) return fail(ENOMEM);
    }
    long flags = sys2(__NR_fcntl, fd, F_GETFL);
    int nonblocking = !failed(flags) && (flags & O_NONBLOCK);
    long partial_since = 0;
    unsigned long data = 0, len = 0, end, skipped;
    for (;;) {
        /* Everything there is now, so the frame is the newest. */
        long r = fill(fd, s, 0);
        if (failed(r) && r != -EAGAIN) return result(r);
        end = newest_frame(s, c->format, &data, &len, &skipped);
        if (end) break;
        if (skipped) {
            move(s->staging, s->staging + skipped, s->staged - skipped);
            s->staged -= skipped;
        }
        if (s->staged == 0) {
            if (nonblocking) return fail(EAGAIN);
            partial_since = 0;
        } else if (!partial_since) {
            partial_since = monotonic_ms();
        } else if (monotonic_ms() - partial_since > PARTIAL_WAIT_MS) {
            s->staged = 0;
            partial_since = 0;
            if (nonblocking) return fail(EAGAIN);
        }
        /* Blocking: wait for the frame (the rest of one already begun, even when non-blocking). */
        r = fill(fd, s, 1);
        if (failed(r)) return result(r);
    }
    lock();
    if (!s->streaming || s->queue_len == 0) {
        unlock();
        return fail(EINVAL);
    }
    unsigned index = s->queue[s->queue_head];
    s->queue_head = (s->queue_head + 1) % MAX_BUFFERS;
    s->queue_len--;
    struct buffer *buf = &s->buffers[index];
    buf->queued = 0;
    unsigned long room;
    void *to = destination(s, index, &room);
    unsigned long used = to && len <= room ? len : 0;
    if (used) copy(to, s->staging + data, used);
    unsigned sequence = s->sequence++;
    int imported = buf->imported;
    unlock();
    move(s->staging, s->staging + end, s->staged - end);
    s->staged -= end;

    struct { long sec, nsec; } ts;
    sys2(__NR_clock_gettime, 1 /* CLOCK_MONOTONIC */, &ts);
    zero(b, sizeof(*b));
    b->index = index;
    b->type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    b->bytesused = (unsigned)used;
    b->flags = V4L2_BUF_FLAG_MAPPED | V4L2_BUF_FLAG_TIMESTAMP_MONOTONIC | V4L2_BUF_FLAG_TSTAMP_SRC_EOF |
        (used ? 0 : V4L2_BUF_FLAG_ERROR);
    b->field = V4L2_FIELD_NONE;
    b->timestamp.tv_sec = ts.sec;
    b->timestamp.tv_usec = ts.nsec / 1000;
    b->sequence = sequence;
    b->memory = s->memory;
    if (s->memory == V4L2_MEMORY_DMABUF) {
        b->m.fd = imported;
        b->length = (unsigned)room;
    } else {
        b->m.offset = index * s->length;
        b->length = s->length;
    }
    return 0;
}

/* ---------- V4L2 ---------- */

static void describe_format(const struct camera *c, enum format f, struct v4l2_format *v)
{
    zero(&v->fmt, sizeof(v->fmt));
    v->type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    v->fmt.pix.width = c->width;
    v->fmt.pix.height = c->height;
    v->fmt.pix.pixelformat = FORMATS[f].fourcc;
    v->fmt.pix.field = V4L2_FIELD_NONE;
    v->fmt.pix.bytesperline = f == NV12 ? c->width : 0;
    v->fmt.pix.sizeimage = (unsigned)sizeimage(c, f);
    v->fmt.pix.colorspace = f == MJPEG ? V4L2_COLORSPACE_JPEG : V4L2_COLORSPACE_SRGB;
}

static void describe_buffer(const struct stream *s, unsigned index, struct v4l2_buffer *b)
{
    const struct buffer *buf = &s->buffers[index];
    zero(b, sizeof(*b));
    b->index = index;
    b->type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    b->memory = s->memory;
    if (s->memory == V4L2_MEMORY_DMABUF) {
        b->m.fd = buf->imported;
        b->length = (unsigned)buf->own_length;
    } else {
        b->m.offset = index * s->length;
        b->length = s->length;
    }
    b->field = V4L2_FIELD_NONE;
    b->flags = (buf->own ? V4L2_BUF_FLAG_MAPPED : 0) | (buf->queued ? V4L2_BUF_FLAG_QUEUED : 0) |
        V4L2_BUF_FLAG_TIMESTAMP_MONOTONIC | V4L2_BUF_FLAG_TSTAMP_SRC_EOF;
}

/* The format the app asked for if offered, else the camera's first. */
static enum format pick(const struct camera *c, unsigned fourcc)
{
    for (unsigned i = 0; i < c->format_count; i++) if (FORMATS[c->formats[i]].fourcc == fourcc) return c->formats[i];
    return c->formats[0];
}

/* Queues an imported DMABUF: maps it, or keeps the mapping of the same descriptor. Under the lock. */
static int import(struct buffer *b, int fd, unsigned length)
{
    if (b->imported == fd && b->own) return 0;
    if (b->own) sys2(__NR_munmap, b->own, b->own_length);
    b->own = 0;
    unsigned long len = length;
    if (!len) {
        long end = sys3(__NR_lseek, fd, 0, 2 /* SEEK_END */);
        if (failed(end)) return -EINVAL;
        len = (unsigned long)end;
    }
    void *p = map(fd, len);
    if (!p) return -EINVAL;
    b->own = p;
    b->own_length = len;
    b->imported = fd;
    return 0;
}

static long v4l2(int fd, struct stream *s, unsigned request, void *arg)
{
    struct camera *c = camera(s->camera);
    if (!arg && request != VIDIOC_STREAMON && request != VIDIOC_STREAMOFF) return fail(EFAULT);
    switch (request) {
    case VIDIOC_QUERYCAP: {
        struct v4l2_capability *cap = arg;
        zero(cap, sizeof(*cap));
        text((char *)cap->driver, sizeof(cap->driver), "uvcvideo");
        text((char *)cap->card, sizeof(cap->card), c->name[0] ? c->name : "Camera");
        numbered((char *)cap->bus_info, sizeof(cap->bus_info), "usb-flatdroid-", (unsigned)s->camera + 1);
        cap->version = (5U << 16) | (10U << 8);
        cap->device_caps = V4L2_CAP_VIDEO_CAPTURE | V4L2_CAP_STREAMING;
        cap->capabilities = cap->device_caps | V4L2_CAP_DEVICE_CAPS;
        return 0;
    }
    case VIDIOC_ENUM_FMT: {
        struct v4l2_fmtdesc *d = arg;
        if (d->type != V4L2_BUF_TYPE_VIDEO_CAPTURE || d->index >= c->format_count) return fail(EINVAL);
        unsigned index = d->index;
        enum format f = c->formats[index];
        zero(d, sizeof(*d));
        d->index = index;
        d->type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        d->flags = f == MJPEG ? V4L2_FMT_FLAG_COMPRESSED : 0;
        text((char *)d->description, sizeof(d->description), FORMATS[f].description);
        d->pixelformat = FORMATS[f].fourcc;
        return 0;
    }
    case VIDIOC_ENUM_FRAMESIZES: {
        struct v4l2_frmsizeenum *e = arg;
        if (e->index != 0 || FORMATS[pick(c, e->pixel_format)].fourcc != e->pixel_format) return fail(EINVAL);
        e->type = V4L2_FRMSIZE_TYPE_DISCRETE;
        e->discrete.width = c->width;
        e->discrete.height = c->height;
        zero(e->reserved, sizeof(e->reserved));
        return 0;
    }
    case VIDIOC_ENUM_FRAMEINTERVALS: {
        struct v4l2_frmivalenum *e = arg;
        if (e->index != 0 || FORMATS[pick(c, e->pixel_format)].fourcc != e->pixel_format || e->width != c->width || e->height != c->height)
            return fail(EINVAL);
        e->type = V4L2_FRMIVAL_TYPE_DISCRETE;
        e->discrete.numerator = 1;
        e->discrete.denominator = c->rate;
        zero(e->reserved, sizeof(e->reserved));
        return 0;
    }
    case VIDIOC_G_FMT:
    case VIDIOC_S_FMT:
    case VIDIOC_TRY_FMT: {
        struct v4l2_format *f = arg;
        if (f->type != V4L2_BUF_TYPE_VIDEO_CAPTURE) return fail(EINVAL);
        enum format chosen = request == VIDIOC_G_FMT ? c->format : pick(c, f->fmt.pix.pixelformat);
        if (request == VIDIOC_S_FMT) {
            if (s->count && chosen != c->format) return fail(EBUSY);
            c->format = chosen;
            announce(c, FORMATS[c->format].name);
        }
        describe_format(c, chosen, f);
        return 0;
    }
    case VIDIOC_G_PARM:
    case VIDIOC_S_PARM: {
        struct v4l2_streamparm *p = arg;
        if (p->type != V4L2_BUF_TYPE_VIDEO_CAPTURE) return fail(EINVAL);
        zero(&p->parm, sizeof(p->parm));
        p->parm.capture.capability = V4L2_CAP_TIMEPERFRAME;
        p->parm.capture.timeperframe.numerator = 1;
        p->parm.capture.timeperframe.denominator = c->rate;
        return 0;
    }
    case VIDIOC_ENUMINPUT: {
        struct v4l2_input *in = arg;
        if (in->index != 0) return fail(EINVAL);
        zero(in, sizeof(*in));
        text((char *)in->name, sizeof(in->name), "Camera");
        in->type = V4L2_INPUT_TYPE_CAMERA;
        return 0;
    }
    case VIDIOC_G_INPUT:
        *(int *)arg = 0;
        return 0;
    case VIDIOC_S_INPUT:
        return *(int *)arg == 0 ? 0 : fail(EINVAL);
    case VIDIOC_REQBUFS: {
        struct v4l2_requestbuffers *r = arg;
        if (r->type != V4L2_BUF_TYPE_VIDEO_CAPTURE || (r->memory != V4L2_MEMORY_MMAP && r->memory != V4L2_MEMORY_DMABUF)) return fail(EINVAL);
        lock();
        if (s->streaming) {
            unlock();
            return fail(EBUSY);
        }
        free_buffers(s);
        unsigned count = r->count > MAX_BUFFERS ? MAX_BUFFERS : r->count;
        s->memory = r->memory;
        s->length = pages(sizeimage(c, c->format));
        int ok = 1;
        if (s->memory == V4L2_MEMORY_MMAP) {
            for (unsigned i = 0; i < count && ok; i++) {
                struct buffer *b = &s->buffers[i];
                b->memfd = shared_memory(s->length);
                b->own = b->memfd < 0 ? 0 : map(b->memfd, s->length);
                b->own_length = s->length;
                if (!b->own) ok = 0;
            }
        }
        if (!ok) {
            free_buffers(s);
            unlock();
            return fail(ENOMEM);
        }
        s->count = count;
        unlock();
        r->count = count;
        r->capabilities = V4L2_BUF_CAP_SUPPORTS_MMAP | V4L2_BUF_CAP_SUPPORTS_DMABUF;
        return 0;
    }
    case VIDIOC_QUERYBUF: {
        struct v4l2_buffer *b = arg;
        if (b->type != V4L2_BUF_TYPE_VIDEO_CAPTURE || b->index >= s->count) return fail(EINVAL);
        describe_buffer(s, b->index, b);
        return 0;
    }
    case VIDIOC_EXPBUF: {
        struct v4l2_exportbuffer *e = arg;
        if (e->type != V4L2_BUF_TYPE_VIDEO_CAPTURE || s->memory != V4L2_MEMORY_MMAP || e->index >= s->count || e->plane != 0) return fail(EINVAL);
        long copy_fd = sys3(__NR_fcntl, s->buffers[e->index].memfd, (e->flags & O_CLOEXEC) ? F_DUPFD_CLOEXEC : F_DUPFD, 0);
        if (failed(copy_fd)) return result(copy_fd);
        e->fd = (int)copy_fd;
        return 0;
    }
    case VIDIOC_QBUF: {
        struct v4l2_buffer *b = arg;
        if (b->type != V4L2_BUF_TYPE_VIDEO_CAPTURE || b->memory != s->memory || b->index >= s->count) return fail(EINVAL);
        lock();
        struct buffer *buf = &s->buffers[b->index];
        if (buf->queued) {
            unlock();
            return fail(EINVAL);
        }
        if (s->memory == V4L2_MEMORY_DMABUF) {
            int r = import(buf, b->m.fd, b->length);
            if (r < 0) {
                unlock();
                return fail(-r);
            }
        }
        buf->queued = 1;
        s->queue[(s->queue_head + s->queue_len) % MAX_BUFFERS] = b->index;
        s->queue_len++;
        unlock();
        describe_buffer(s, b->index, b);
        return 0;
    }
    case VIDIOC_DQBUF: {
        struct v4l2_buffer *b = arg;
        if (b->type != V4L2_BUF_TYPE_VIDEO_CAPTURE || b->memory != s->memory) return fail(EINVAL);
        if (!s->streaming || s->queue_len == 0) return fail(EINVAL);
        return dequeue(fd, s, b);
    }
    case VIDIOC_STREAMON:
    case VIDIOC_STREAMOFF: {
        if (arg && *(int *)arg != V4L2_BUF_TYPE_VIDEO_CAPTURE) return fail(EINVAL);
        lock();
        int was = s->streaming;
        if (request == VIDIOC_STREAMON) {
            if (!s->count) {
                unlock();
                return fail(EINVAL);
            }
            s->streaming = 1;
        } else {
            s->streaming = 0;
            for (unsigned i = 0; i < s->count; i++) s->buffers[i].queued = 0;
            s->queue_head = s->queue_len = 0;
            s->staged = 0;
        }
        unlock();
        if (request == VIDIOC_STREAMON) {
            announce(c, FORMATS[c->format].name);
            announce(c, ON);
        } else if (was) {
            announce(c, OFF);
        }
        return 0;
    }
    /* No controls. */
    case VIDIOC_QUERYCTRL:
    case VIDIOC_QUERY_EXT_CTRL:
    case VIDIOC_QUERYMENU:
    case VIDIOC_G_CTRL:
    case VIDIOC_S_CTRL:
    case VIDIOC_G_EXT_CTRLS:
    case VIDIOC_S_EXT_CTRLS:
    case VIDIOC_TRY_EXT_CTRLS:
        return fail(EINVAL);
    default:
        return fail(ENOTTY);
    }
}

/* ---------- the media controller: one USB camera with one video node ---------- */

enum { ENTITY_ID = 1, INTERFACE_ID = 2, PAD_ID = 3, LINK_ID = 4 };

static long media(struct stream *s, unsigned request, void *arg)
{
    const struct camera *c = camera(s->camera);
    if (!arg) return fail(EFAULT);
    switch (request) {
    case MEDIA_IOC_DEVICE_INFO: {
        struct media_device_info *info = arg;
        zero(info, sizeof(*info));
        text(info->driver, sizeof(info->driver), "uvcvideo");
        text(info->model, sizeof(info->model), c->name[0] ? c->name : "Camera");
        numbered(info->bus_info, sizeof(info->bus_info), "usb-flatdroid-", (unsigned)s->camera + 1);
        info->media_version = info->driver_version = (5U << 16) | (10U << 8);
        return 0;
    }
    case MEDIA_IOC_G_TOPOLOGY: {
        struct media_v2_topology *t = arg;
        if ((t->ptr_entities && t->num_entities < 1) || (t->ptr_interfaces && t->num_interfaces < 1) ||
            (t->ptr_pads && t->num_pads < 1) || (t->ptr_links && t->num_links < 1)) return fail(ENOSPC);
        t->topology_version = 1;
        t->num_entities = t->num_interfaces = t->num_pads = t->num_links = 1;
        if (t->ptr_entities) {
            struct media_v2_entity *e = (void *)(unsigned long)t->ptr_entities;
            zero(e, sizeof(*e));
            e->id = ENTITY_ID;
            text(e->name, sizeof(e->name), c->name[0] ? c->name : "Camera");
            e->function = MEDIA_ENT_F_IO_V4L;
            e->flags = MEDIA_ENT_FL_DEFAULT;
        }
        if (t->ptr_interfaces) {
            struct media_v2_interface *i = (void *)(unsigned long)t->ptr_interfaces;
            zero(i, sizeof(*i));
            i->id = INTERFACE_ID;
            i->intf_type = MEDIA_INTF_T_V4L_VIDEO;
            i->devnode.major = VIDEO_MAJOR;
            i->devnode.minor = (unsigned)s->camera;
        }
        if (t->ptr_pads) {
            struct media_v2_pad *p = (void *)(unsigned long)t->ptr_pads;
            zero(p, sizeof(*p));
            p->id = PAD_ID;
            p->entity_id = ENTITY_ID;
            p->flags = MEDIA_PAD_FL_SINK;
        }
        if (t->ptr_links) {
            struct media_v2_link *l = (void *)(unsigned long)t->ptr_links;
            zero(l, sizeof(*l));
            l->id = LINK_ID;
            l->source_id = INTERFACE_ID;
            l->sink_id = ENTITY_ID;
            l->flags = MEDIA_LNK_FL_INTERFACE_LINK | MEDIA_LNK_FL_ENABLED | MEDIA_LNK_FL_IMMUTABLE;
        }
        return 0;
    }
    default:
        return fail(ENOTTY);
    }
}

/* ---------- the wrapped calls ---------- */

#define V __attribute__((visibility("default")))

static int open_at(int dirfd, const char *path, int flags, int mode)
{
    enum kind kind = NONE;
    int camera = camera_at(path, &kind);
    /* The video device is read only: an app can't write into its own frames. */
    if (kind == VIDEO) flags = (flags & ~O_ACCMODE) | O_RDONLY;
    long fd = sys4(__NR_openat, dirfd, path, flags, mode);
    if (failed(fd)) return (int)result(fd);
    if (camera >= 0) register_open((int)fd, camera, kind);
    return (int)fd;
}

static int mode_of(int flags, va_list ap)
{
    return (flags & O_CREAT) || (flags & __O_TMPFILE) == __O_TMPFILE ? va_arg(ap, int) : 0;
}

V int open(const char *path, int flags, ...)
{
    va_list ap;
    va_start(ap, flags);
    int mode = mode_of(flags, ap);
    va_end(ap);
    return open_at(AT_FDCWD, path, flags, mode);
}

V int open64(const char *path, int flags, ...)
{
    va_list ap;
    va_start(ap, flags);
    int mode = mode_of(flags, ap);
    va_end(ap);
    return open_at(AT_FDCWD, path, flags, mode);
}

V int __open_2(const char *path, int flags) { return open_at(AT_FDCWD, path, flags, 0); }
V int __open64_2(const char *path, int flags) { return open_at(AT_FDCWD, path, flags, 0); }

V int openat(int dirfd, const char *path, int flags, ...)
{
    va_list ap;
    va_start(ap, flags);
    int mode = mode_of(flags, ap);
    va_end(ap);
    return open_at(dirfd, path, flags, mode);
}

V int openat64(int dirfd, const char *path, int flags, ...)
{
    va_list ap;
    va_start(ap, flags);
    int mode = mode_of(flags, ap);
    va_end(ap);
    return open_at(dirfd, path, flags, mode);
}

V int __openat_2(int dirfd, const char *path, int flags) { return open_at(dirfd, path, flags, 0); }
V int __openat64_2(int dirfd, const char *path, int flags) { return open_at(dirfd, path, flags, 0); }

V int dup(int fd)
{
    long copy_fd = sys1(__NR_dup, fd);
    if (failed(copy_fd)) return (int)result(copy_fd);
    if (stream_of(fd)) {
        lock();
        for (int i = 0; i < fd_count; i++) {
            if (fds[i].fd == fd) {
                add_fd((int)copy_fd, fds[i].stream);
                break;
            }
        }
        unlock();
    }
    return (int)copy_fd;
}

V int close(int fd)
{
    forget_fd(fd);
    return (int)result(sys1(__NR_close, fd));
}

V int ioctl(int fd, unsigned long request, ...)
{
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    /* The kernel reads the request as 32 bits; callers passing an int sign-extend it. */
    unsigned r = (unsigned)request;
    struct stream *s = stream_for(fd, _IOC_TYPE(r));
    if (s) return (int)(s->kind == MEDIA ? media(s, r, arg) : v4l2(fd, s, r, arg));
    return (int)result(sys3(__NR_ioctl, fd, request, arg));
}

static void *map_call(void *addr, unsigned long len, int prot, int flags, int fd, long offset)
{
    struct stream *s = stream_of(fd);
    if (s && s->kind == VIDEO) {
        int memfd = -1;
        lock();
        if (s->memory == V4L2_MEMORY_MMAP && s->length && offset >= 0 && (unsigned long)offset % s->length == 0 &&
            (unsigned long)offset / s->length < s->count && len <= s->length) {
            memfd = s->buffers[(unsigned long)offset / s->length].memfd;
        }
        unlock();
        if (memfd < 0) return (void *)fail(EINVAL);
        /* The app's own view of the buffer's memory. */
        return (void *)result(syscall6(__NR_mmap, (long)addr, (long)len, prot, (flags & ~MAP_PRIVATE) | MAP_SHARED, memfd, 0));
    }
    return (void *)result(syscall6(__NR_mmap, (long)addr, (long)len, prot, flags, fd, offset));
}

#ifndef AF_NETLINK
#define AF_NETLINK 16
#endif

static int socket_call(int domain, int type, int protocol)
{
    long fd = sys3(__NR_socket, domain, type, protocol);
    if (fd == -EACCES && domain == AF_NETLINK && protocol == NETLINK_KOBJECT_UEVENT) fd = sys3(__NR_socket, domain, type, NETLINK_ROUTE);
    return (int)result(fd);
}

V int socket(int domain, int type, int protocol) { return socket_call(domain, type, protocol); }

V void *mmap(void *addr, unsigned long len, int prot, int flags, int fd, long offset) { return map_call(addr, len, prot, flags, fd, offset); }
V void *mmap64(void *addr, unsigned long len, int prot, int flags, int fd, long offset) { return map_call(addr, len, prot, flags, fd, offset); }

/* ---------- stat: the cameras' devices are character devices ---------- */

/* The kernel's struct stat, which is glibc's (and musl's) on these 64-bit architectures. */
#if defined(__x86_64__)
#include <asm/stat.h>
#else
#include <asm-generic/stat.h>
#endif

static void as_device(struct stat *st, int camera, enum kind kind)
{
    st->st_mode = S_IFCHR | 0660;
    st->st_rdev = ((unsigned long)(kind == MEDIA ? MEDIA_MAJOR : VIDEO_MAJOR) << 8) | (unsigned)camera;
    st->st_size = 0;
}

static int stat_at(int dirfd, const char *path, struct stat *st, int flags)
{
    long r = sys4(__NR_newfstatat, dirfd, path, st, flags);
    if (failed(r)) return (int)result(r);
    enum kind kind = NONE;
    int camera = camera_at(path, &kind);
    if (camera >= 0) as_device(st, camera, kind);
    return 0;
}

static int stat_fd(int fd, struct stat *st)
{
    long r = sys2(__NR_fstat, fd, st);
    if (failed(r)) return (int)result(r);
    struct stream *s = stream_of(fd);
    if (s) as_device(st, s->camera, s->kind);
    return 0;
}

V int stat(const char *path, struct stat *st) { return stat_at(AT_FDCWD, path, st, 0); }
V int stat64(const char *path, struct stat *st) { return stat_at(AT_FDCWD, path, st, 0); }
V int lstat(const char *path, struct stat *st) { return stat_at(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW); }
V int lstat64(const char *path, struct stat *st) { return stat_at(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW); }
V int fstat(int fd, struct stat *st) { return stat_fd(fd, st); }
V int fstat64(int fd, struct stat *st) { return stat_fd(fd, st); }
V int fstatat(int dirfd, const char *path, struct stat *st, int flags) { return stat_at(dirfd, path, st, flags); }
V int fstatat64(int dirfd, const char *path, struct stat *st, int flags) { return stat_at(dirfd, path, st, flags); }
/* glibc before 2.33: the version argument names the layout, which is the kernel's here. */
V int __xstat(int ver, const char *path, struct stat *st) { (void)ver; return stat_at(AT_FDCWD, path, st, 0); }
V int __xstat64(int ver, const char *path, struct stat *st) { (void)ver; return stat_at(AT_FDCWD, path, st, 0); }
V int __lxstat(int ver, const char *path, struct stat *st) { (void)ver; return stat_at(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW); }
V int __lxstat64(int ver, const char *path, struct stat *st) { (void)ver; return stat_at(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW); }
V int __fxstat(int ver, int fd, struct stat *st) { (void)ver; return stat_fd(fd, st); }
V int __fxstat64(int ver, int fd, struct stat *st) { (void)ver; return stat_fd(fd, st); }
V int __fxstatat(int ver, int dirfd, const char *path, struct stat *st, int flags) { (void)ver; return stat_at(dirfd, path, st, flags); }
V int __fxstatat64(int ver, int dirfd, const char *path, struct stat *st, int flags) { (void)ver; return stat_at(dirfd, path, st, flags); }

V int statx(int dirfd, const char *path, int flags, unsigned mask, struct statx *st)
{
    long r = sys5(__NR_statx, dirfd, path, flags, mask, st);
    if (failed(r)) return (int)result(r);
    enum kind kind = NONE;
    int camera = -1;
    if (path && path[0] == '/') {
        camera = camera_at(path, &kind);
    } else if (path && !path[0] && (flags & AT_EMPTY_PATH)) {
        struct stream *s = stream_of(dirfd);
        if (s) {
            camera = s->camera;
            kind = s->kind;
        }
    }
    if (camera >= 0) {
        st->stx_mode = S_IFCHR | 0660;
        st->stx_rdev_major = kind == MEDIA ? MEDIA_MAJOR : VIDEO_MAJOR;
        st->stx_rdev_minor = (unsigned)camera;
        st->stx_size = 0;
    }
    return 0;
}

/*
 * The C library's syscall(), which libv4l2 calls instead of open(), ioctl() and the others: the
 * calls wrapped above (socket too) get the same answers, every other number goes to the kernel. Six arguments
 * are read whatever the call takes, as the C library's own does; errors as -1 and errno, as there.
 */
V long syscall(long n, ...)
{
    va_list ap;
    va_start(ap, n);
    long a = va_arg(ap, long), b = va_arg(ap, long), c = va_arg(ap, long);
    long d = va_arg(ap, long), e = va_arg(ap, long), f = va_arg(ap, long);
    va_end(ap);
    switch (n) {
    case __NR_openat: return open_at((int)a, (const char *)b, (int)c, (int)d);
#ifdef __NR_open
    case __NR_open: return open_at(AT_FDCWD, (const char *)a, (int)b, (int)c);
#endif
    case __NR_close: return close((int)a);
    case __NR_dup: return dup((int)a);
    case __NR_ioctl: return ioctl((int)a, (unsigned long)b, (void *)c);
    case __NR_mmap: return (long)map_call((void *)a, (unsigned long)b, (int)c, (int)d, (int)e, f);
    case __NR_newfstatat: return stat_at((int)a, (const char *)b, (struct stat *)c, (int)d);
    case __NR_fstat: return stat_fd((int)a, (struct stat *)b);
    case __NR_socket: return socket_call((int)a, (int)b, (int)c);
    default: return result(syscall6(n, a, b, c, d, e, f));
    }
}
