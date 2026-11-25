/*
 * Checks libfdv4l2.so on a Linux host (run with it preloaded, see test-fdv4l2.sh): the first
 * camera in the cameras file has its video device, media device and control pipe in <dir>; this
 * program writes frames into the video pipe as Flatdroid does, and makes the calls an app makes.
 *
 *   fdv4l2-test <dir>
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/media.h>
#include <linux/videodev2.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/netlink.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <unistd.h>

static int failures;
#define CHECK(cond) do { if (!(cond)) { printf("FAIL %s:%d: %s (errno %d)\n", __FILE__, __LINE__, #cond, errno); failures++; } } while (0)

static void frame(int fd, const char *type, const char *body)
{
    char all[512];
    int len = snprintf(all, sizeof(all), "--flatdroid-frame\r\nContent-Type: %s\r\nContent-Length: %zu\r\n\r\n%s\r\n", type, strlen(body), body);
    CHECK(write(fd, all, len) == len);
}

/* The C library's own syscall(), past the preload's (which answers libv4l2's calls). */
static long kernel(long n, long a, long b, long c, long d)
{
    static long (*real)(long, ...);
    if (!real) real = (long (*)(long, ...))dlsym(dlopen("libc.so.6", RTLD_NOW | RTLD_NOLOAD), "syscall");
    return real(n, a, b, c, d);
}

/* Flatdroid's ends: read and write, so neither side waits for the other to open; not through the preload. */
static int pipe_at(const char *dir, const char *name)
{
    char path[512];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    unlink(path);
    CHECK(mkfifo(path, 0600) == 0);
    return (int)kernel(SYS_openat, AT_FDCWD, (long)path, O_RDWR | O_NONBLOCK, 0);
}

/* Threads that look at the camera at once, the first calls of all: each sees it whole. */
#define RACERS 4
static pthread_barrier_t start_line;
static const char *race_path;

static void *race(void *ok)
{
    struct stat st;
    pthread_barrier_wait(&start_line);
    *(int *)ok = stat(race_path, &st) == 0 && S_ISCHR(st.st_mode);
    return NULL;
}

/* A blocking dequeue on waiting_fd in its own thread. */
static int waiting_fd;
static int waited, waited_errno;
static void *wait_frame(void *unused)
{
    (void)unused;
    struct v4l2_buffer b = { .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
    waited = ioctl(waiting_fd, VIDIOC_DQBUF, &b);
    waited_errno = errno;
    return NULL;
}

static int format_request(int fd, unsigned fourcc, struct v4l2_format *f)
{
    memset(f, 0, sizeof(*f));
    f->type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    f->fmt.pix.width = 1920;
    f->fmt.pix.pixelformat = fourcc;
    return ioctl(fd, VIDIOC_S_FMT, f);
}

int main(int argc, char **argv)
{
    if (argc != 2) return 2;
    setvbuf(stdout, NULL, _IONBF, 0);
    const char *dir = argv[1];
    char path[512], media_path[512];
    snprintf(path, sizeof(path), "%s/video0", dir);
    snprintf(media_path, sizeof(media_path), "%s/media0", dir);
    /* Before the library reads the list, which notes the devices' inodes. */
    int writer = pipe_at(dir, "video0");
    int media_end = pipe_at(dir, "media0");
    int control = pipe_at(dir, "control0");
    CHECK(writer >= 0 && media_end >= 0 && control >= 0);

    race_path = path;
    pthread_t racers[RACERS];
    int seen[RACERS] = { 0 };
    pthread_barrier_init(&start_line, NULL, RACERS);
    for (int i = 0; i < RACERS; i++) CHECK(pthread_create(&racers[i], NULL, race, &seen[i]) == 0);
    for (int i = 0; i < RACERS; i++) {
        pthread_join(racers[i], NULL);
        CHECK(seen[i]);
    }

    struct stat st;
    CHECK(stat(path, &st) == 0 && S_ISCHR(st.st_mode) && major(st.st_rdev) == 81 && minor(st.st_rdev) == 0);
    CHECK(lstat(path, &st) == 0 && S_ISCHR(st.st_mode));
    CHECK(stat(media_path, &st) == 0 && S_ISCHR(st.st_mode) && major(st.st_rdev) == 238);

    int fd = open(path, O_RDWR);
    CHECK(fd >= 0);
    CHECK(fstat(fd, &st) == 0 && S_ISCHR(st.st_mode));
    /* The app can't write into its own frames. */
    CHECK(write(fd, "x", 1) < 0 && errno == EBADF);

    struct v4l2_capability cap;
    CHECK(ioctl(fd, VIDIOC_QUERYCAP, &cap) == 0);
    CHECK(strcmp((char *)cap.driver, "uvcvideo") == 0 && strcmp((char *)cap.card, "Back camera") == 0);
    CHECK((cap.device_caps & (V4L2_CAP_VIDEO_CAPTURE | V4L2_CAP_STREAMING)) == (V4L2_CAP_VIDEO_CAPTURE | V4L2_CAP_STREAMING));
    /* The request passed as an int, sign-extended (Chromium does). */
    int request = (int)VIDIOC_QUERYCAP;
    CHECK(ioctl(fd, (unsigned long)(long)request, &cap) == 0);

    /* Both formats, in the list's order. */
    struct v4l2_fmtdesc d = { .index = 0, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE };
    CHECK(ioctl(fd, VIDIOC_ENUM_FMT, &d) == 0 && d.pixelformat == V4L2_PIX_FMT_MJPEG && d.index == 0);
    d.index = 1;
    CHECK(ioctl(fd, VIDIOC_ENUM_FMT, &d) == 0 && d.pixelformat == V4L2_PIX_FMT_NV12);
    d.index = 2;
    CHECK(ioctl(fd, VIDIOC_ENUM_FMT, &d) < 0 && errno == EINVAL);

    struct v4l2_frmsizeenum fs = { .index = 0, .pixel_format = V4L2_PIX_FMT_NV12 };
    CHECK(ioctl(fd, VIDIOC_ENUM_FRAMESIZES, &fs) == 0 && fs.discrete.width == 640 && fs.discrete.height == 480);
    fs.pixel_format = V4L2_PIX_FMT_YUYV;
    CHECK(ioctl(fd, VIDIOC_ENUM_FRAMESIZES, &fs) < 0 && errno == EINVAL);
    struct v4l2_frmivalenum fi = { .index = 0, .pixel_format = V4L2_PIX_FMT_MJPEG, .width = 640, .height = 480 };
    CHECK(ioctl(fd, VIDIOC_ENUM_FRAMEINTERVALS, &fi) == 0 && fi.discrete.denominator == 30);

    /* A format not offered gets the first; the choice goes to Flatdroid's control pipe. */
    struct v4l2_format f;
    CHECK(format_request(fd, V4L2_PIX_FMT_YUYV, &f) == 0 && f.fmt.pix.width == 640 && f.fmt.pix.pixelformat == V4L2_PIX_FMT_MJPEG);
    CHECK(format_request(fd, V4L2_PIX_FMT_NV12, &f) == 0 && f.fmt.pix.pixelformat == V4L2_PIX_FMT_NV12);
    CHECK(f.fmt.pix.sizeimage == 640 * 480 * 3 / 2 && f.fmt.pix.bytesperline == 640);
    char said[64] = { 0 };
    CHECK(read(control, said, sizeof(said) - 1) > 0 && strcmp(said, "mjpeg\nnv12\n") == 0);

    struct v4l2_queryctrl qc = { .id = V4L2_CTRL_FLAG_NEXT_CTRL };
    CHECK(ioctl(fd, VIDIOC_QUERYCTRL, &qc) < 0 && errno == EINVAL);
    CHECK(ioctl(fd, VIDIOC_G_STD, &qc) < 0 && errno == ENOTTY);

    struct v4l2_requestbuffers rb = { .count = 4, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
    CHECK(ioctl(fd, VIDIOC_REQBUFS, &rb) == 0 && rb.count == 4);
    CHECK(format_request(fd, V4L2_PIX_FMT_MJPEG, &f) < 0 && errno == EBUSY);
    void *maps[4];
    unsigned length = 0;
    for (unsigned i = 0; i < 4; i++) {
        struct v4l2_buffer b = { .index = i, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
        CHECK(ioctl(fd, VIDIOC_QUERYBUF, &b) == 0);
        length = b.length;
        maps[i] = mmap(NULL, b.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, b.m.offset);
        CHECK(maps[i] != MAP_FAILED);
        CHECK(ioctl(fd, VIDIOC_QBUF, &b) == 0);
    }
    int type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    CHECK(ioctl(fd, VIDIOC_STREAMON, &type) == 0);
    memset(said, 0, sizeof(said));
    /* Streaming in the chosen format: what Flatdroid takes as the camera's use. */
    CHECK(read(control, said, sizeof(said) - 1) > 0 && strcmp(said, "nv12\non\n") == 0);

    /* Frames waiting: the newest of the chosen format comes, one of the other format is skipped. */
    frame(writer, "video/nv12", "first");
    frame(writer, "video/nv12", "newest");
    frame(writer, "image/jpeg", "jpeg");
    struct pollfd p = { .fd = fd, .events = POLLIN };
    CHECK(poll(&p, 1, 1000) == 1);
    struct v4l2_buffer b = { .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
    CHECK(ioctl(fd, VIDIOC_DQBUF, &b) == 0);
    CHECK(b.bytesused == 6 && memcmp(maps[b.index], "newest", 6) == 0);
    CHECK(b.index == 0 && b.sequence == 0);
    /* The pipe was emptied: that is how Flatdroid sees the camera is used. */
    struct pollfd w = { .fd = writer, .events = POLLIN };
    CHECK(poll(&w, 1, 0) == 0);

    /* Non-blocking with nothing there. */
    int flags = fcntl(fd, F_GETFL);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    CHECK(ioctl(fd, VIDIOC_DQBUF, &b) < 0 && errno == EAGAIN);
    /* A frame written in two parts, the second while the app waits. */
    const char *part = "--flatdroid-frame\r\nContent-Type: video/nv12\r\nContent-Length: 5\r\n\r\nhel";
    CHECK(write(writer, part, strlen(part)) == (ssize_t)strlen(part));
    if (fork() == 0) {
        usleep(100000);
        write(writer, "lo\r\n", 4);
        _exit(0);
    }
    CHECK(ioctl(fd, VIDIOC_DQBUF, &b) == 0 && b.bytesused == 5 && memcmp(maps[b.index], "hello", 5) == 0 && b.sequence == 1);
    fcntl(fd, F_SETFL, flags);

    /* Junk before a boundary is skipped. */
    CHECK(write(writer, "junk", 4) == 4);
    frame(writer, "video/nv12", "after");
    CHECK(ioctl(fd, VIDIOC_DQBUF, &b) == 0 && b.bytesused == 5 && memcmp(maps[b.index], "after", 5) == 0);

    /* A frame cut midway (the writer stopped while the app didn't read) is dropped, not joined to the next. */
    const char *cut = "--flatdroid-frame\r\nContent-Type: video/nv12\r\nContent-Length: 40\r\n\r\ncut";
    CHECK(write(writer, cut, strlen(cut)) == (ssize_t)strlen(cut));
    frame(writer, "video/nv12", "whole frame after a cut one, longer than its length");
    CHECK(ioctl(fd, VIDIOC_DQBUF, &b) == 0 && b.bytesused == 51 && memcmp(maps[b.index], "whole frame after a cut one", 27) == 0);

    /* A buffer exported as a descriptor (as libcamera does) is the same memory. */
    struct v4l2_exportbuffer e = { .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .index = 1, .flags = O_CLOEXEC };
    CHECK(ioctl(fd, VIDIOC_EXPBUF, &e) == 0 && e.fd >= 0);
    void *exported = mmap(NULL, length, PROT_READ, MAP_SHARED, e.fd, 0);
    CHECK(exported != MAP_FAILED && memcmp(exported, "hello", 5) == 0);

    CHECK(ioctl(fd, VIDIOC_STREAMOFF, &type) == 0);
    CHECK(ioctl(fd, VIDIOC_DQBUF, &b) < 0 && errno == EINVAL);
    /* Stopping says so; a second stop doesn't. */
    memset(said, 0, sizeof(said));
    CHECK(read(control, said, sizeof(said) - 1) > 0 && strcmp(said, "off\n") == 0);
    CHECK(ioctl(fd, VIDIOC_STREAMOFF, &type) == 0);
    CHECK(read(control, said, sizeof(said) - 1) < 0 && errno == EAGAIN);
    for (unsigned i = 0; i < 4; i++) munmap(maps[i], length);

    /* The exported descriptors imported again (libcamera's way after exporting). */
    struct v4l2_requestbuffers none = { .count = 0, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
    CHECK(ioctl(fd, VIDIOC_REQBUFS, &none) == 0);
    struct v4l2_requestbuffers imports = { .count = 1, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_DMABUF };
    CHECK(ioctl(fd, VIDIOC_REQBUFS, &imports) == 0 && imports.count == 1);
    struct v4l2_buffer ib = { .index = 0, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_DMABUF, .length = length };
    ib.m.fd = e.fd;
    CHECK(ioctl(fd, VIDIOC_QBUF, &ib) == 0);
    CHECK(ioctl(fd, VIDIOC_STREAMON, &type) == 0);
    frame(writer, "video/nv12", "dmabuf");
    struct v4l2_buffer ob = { .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_DMABUF };
    CHECK(ioctl(fd, VIDIOC_DQBUF, &ob) == 0 && ob.m.fd == e.fd && ob.bytesused == 6 && memcmp(exported, "dmabuf", 6) == 0);
    CHECK(ioctl(fd, VIDIOC_STREAMOFF, &type) == 0);
    munmap(exported, length);
    close(e.fd);

    /* A copy of the descriptor is the same device. */
    int copy = dup(fd);
    CHECK(ioctl(copy, VIDIOC_QUERYCAP, &cap) == 0);
    close(copy);
    CHECK(ioctl(fd, VIDIOC_QUERYCAP, &cap) == 0);
    close(fd);
    /* The DMABUF stream started and stopped. */
    memset(said, 0, sizeof(said));
    CHECK(read(control, said, sizeof(said) - 1) > 0 && strcmp(said, "nv12\non\noff\n") == 0);

    /* Closed while streaming: stopped too. */
    int again = open(path, O_RDWR);
    struct v4l2_requestbuffers one = { .count = 1, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
    CHECK(again >= 0 && ioctl(again, VIDIOC_REQBUFS, &one) == 0);
    struct v4l2_buffer qb = { .index = 0, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
    CHECK(ioctl(again, VIDIOC_QBUF, &qb) == 0 && ioctl(again, VIDIOC_STREAMON, &type) == 0);
    close(again);
    memset(said, 0, sizeof(said));
    CHECK(read(control, said, sizeof(said) - 1) > 0 && strcmp(said, "nv12\non\noff\n") == 0);

    /* Copies made by dup2, dup3 and fcntl are the device; a number replaced or closed by them, or by close_range, no longer is. */
    int cam = open(path, O_RDWR);
    CHECK(cam >= 0 && dup2(cam, 60) == 60 && ioctl(60, VIDIOC_QUERYCAP, &cap) == 0);
    CHECK(dup3(cam, 61, O_CLOEXEC) == 61 && ioctl(61, VIDIOC_QUERYCAP, &cap) == 0);
    int dupfd = fcntl(cam, F_DUPFD_CLOEXEC, 62);
    CHECK(dupfd >= 62 && ioctl(dupfd, VIDIOC_QUERYCAP, &cap) == 0);
    int null = open("/dev/null", O_RDONLY);
    CHECK(dup2(null, 60) == 60 && ioctl(60, VIDIOC_QUERYCAP, &cap) < 0 && errno == ENOTTY);
    CHECK(fstat(60, &st) == 0 && major(st.st_rdev) == 1);
    CHECK(close_range(61, 61, 0) == 0 && dup2(null, 61) == 61 && ioctl(61, VIDIOC_QUERYCAP, &cap) < 0 && errno == ENOTTY);
    CHECK(dup2(cam, cam) == cam && ioctl(cam, VIDIOC_QUERYCAP, &cap) == 0);
    close(60);
    close(61);
    close(dupfd);
    close(null);

    /* Stopped and closed while another thread waits for a frame: the waiting dequeue fails, nothing breaks, the old frame isn't kept. */
    struct v4l2_requestbuffers two = { .count = 2, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
    CHECK(ioctl(cam, VIDIOC_REQBUFS, &two) == 0);
    for (unsigned i = 0; i < 2; i++) {
        struct v4l2_buffer q = { .index = i, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
        CHECK(ioctl(cam, VIDIOC_QBUF, &q) == 0);
    }
    CHECK(ioctl(cam, VIDIOC_STREAMON, &type) == 0);
    waiting_fd = cam;
    pthread_t waiter;
    CHECK(pthread_create(&waiter, NULL, wait_frame, NULL) == 0);
    usleep(100000);
    CHECK(ioctl(cam, VIDIOC_STREAMOFF, &type) == 0);
    frame(writer, "video/nv12", "stale");
    pthread_join(waiter, NULL);
    CHECK(waited < 0 && waited_errno == EINVAL);
    struct v4l2_buffer q0 = { .index = 0, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
    CHECK(ioctl(cam, VIDIOC_QBUF, &q0) == 0 && ioctl(cam, VIDIOC_STREAMON, &type) == 0);
    frame(writer, "video/nv12", "fresh");
    struct v4l2_buffer got = { .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
    void *m0 = mmap(NULL, 4096, PROT_READ, MAP_SHARED, cam, 0);
    CHECK(ioctl(cam, VIDIOC_DQBUF, &got) == 0 && got.bytesused == 5 && m0 != MAP_FAILED && memcmp(m0, "fresh", 5) == 0);
    munmap(m0, 4096);
    CHECK(ioctl(cam, VIDIOC_QBUF, &got) == 0);
    CHECK(pthread_create(&waiter, NULL, wait_frame, NULL) == 0);
    usleep(100000);
    close(cam);
    frame(writer, "video/nv12", "after close");
    pthread_join(waiter, NULL);
    CHECK(waited < 0);
    memset(said, 0, sizeof(said));
    CHECK(read(control, said, sizeof(said) - 1) > 0 && strcmp(said, "nv12\non\noff\nnv12\non\noff\n") == 0);
    /* Its stream was released: every stream can be opened again. */
    int many[16];
    for (int i = 0; i < 16; i++) many[i] = open(path, O_RDWR);
    CHECK(ioctl(many[15], VIDIOC_QUERYCAP, &cap) == 0);
    for (int i = 0; i < 16; i++) close(many[i]);
    /* Drain what the writer left. */
    char drain[4096];
    while (kernel(SYS_read, writer, (long)drain, sizeof(drain), 0) > 0) {}

    /* Opened past the C library's open() (libcamera does): known by its inode at the first V4L2 call. */
    int raw = (int)kernel(SYS_openat, AT_FDCWD, (long)path, O_RDWR | O_NONBLOCK, 0);
    CHECK(raw >= 0 && ioctl(raw, VIDIOC_QUERYCAP, &cap) == 0 && strcmp((char *)cap.driver, "uvcvideo") == 0);
    close(raw);

    /* Through the C library's syscall(), as libv4l2 makes its calls (OBS): the same answers. */
    int v4l = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDWR | O_NONBLOCK, 0);
    memset(&cap, 0, sizeof(cap));
    CHECK(v4l >= 0 && syscall(SYS_ioctl, v4l, VIDIOC_QUERYCAP, &cap) == 0 && strcmp((char *)cap.driver, "uvcvideo") == 0);
    struct v4l2_fmtdesc desc = { .index = 0, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE };
    CHECK(syscall(SYS_ioctl, v4l, VIDIOC_ENUM_FMT, &desc) == 0 && desc.pixelformat == V4L2_PIX_FMT_MJPEG);
    CHECK(syscall(SYS_fstat, v4l, &st) == 0 && major(st.st_rdev) == 81);
    CHECK(syscall(SYS_close, v4l) == 0);
    /* A device event socket the kernel allows stays one (the stand-in is only for Android's refusal). */
    int ue = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_KOBJECT_UEVENT);
    int proto = -1;
    socklen_t plen = sizeof(proto);
    CHECK(ue >= 0 && getsockopt(ue, SOL_SOCKET, SO_PROTOCOL, &proto, &plen) == 0 && proto == NETLINK_KOBJECT_UEVENT);
    close(ue);
    /* Every other call reaches the kernel. */
    CHECK(syscall(SYS_getpid) == getpid());
    CHECK(syscall(SYS_ioctl, 12345, VIDIOC_QUERYCAP, &cap) == -1 && errno == EBADF);

    /* The media device: one USB camera whose default entity is the video node 81:0. */
    int m = open(media_path, O_RDWR);
    CHECK(m >= 0);
    struct media_device_info info;
    CHECK(ioctl(m, MEDIA_IOC_DEVICE_INFO, &info) == 0 && strcmp(info.driver, "uvcvideo") == 0 && strcmp(info.model, "Back camera") == 0);
    struct media_v2_topology t = { 0 };
    CHECK(ioctl(m, MEDIA_IOC_G_TOPOLOGY, &t) == 0 && t.num_entities == 1 && t.num_interfaces == 1 && t.num_pads == 1 && t.num_links == 1);
    struct media_v2_entity ent;
    struct media_v2_interface intf;
    struct media_v2_pad pad;
    struct media_v2_link link;
    t.ptr_entities = (unsigned long)&ent;
    t.ptr_interfaces = (unsigned long)&intf;
    t.ptr_pads = (unsigned long)&pad;
    t.ptr_links = (unsigned long)&link;
    CHECK(ioctl(m, MEDIA_IOC_G_TOPOLOGY, &t) == 0);
    CHECK(ent.function == MEDIA_ENT_F_IO_V4L && (ent.flags & MEDIA_ENT_FL_DEFAULT) && strcmp(ent.name, "Back camera") == 0);
    CHECK(intf.intf_type == MEDIA_INTF_T_V4L_VIDEO && intf.devnode.major == 81 && intf.devnode.minor == 0);
    CHECK(link.source_id == intf.id && link.sink_id == ent.id && (link.flags & MEDIA_LNK_FL_INTERFACE_LINK));
    CHECK(pad.entity_id == ent.id);
    close(m);

    /* Closed: an ordinary descriptor again. */
    int other = open("/dev/null", O_RDONLY);
    CHECK(other >= 0 && ioctl(other, VIDIOC_QUERYCAP, &cap) < 0 && errno == ENOTTY);
    CHECK(fstat(other, &st) == 0 && major(st.st_rdev) == 1);
    close(other);

    printf(failures ? "FAILED\n" : "all passed\n");
    return failures != 0;
}
