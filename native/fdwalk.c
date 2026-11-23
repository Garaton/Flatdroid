/*
 * libfdwalk.so: batched file system reads for Flatdroid's walks (sizes, the file check, Alpine
 * safe launch, the repository clean-up), called through JNI by NativeFs.kt. One call lists a
 * folder with each entry's type, or reads the links or stats of many entries relative to one open
 * folder, so a walk pays one JNI call per folder instead of one per entry, and no path is looked
 * up from / again.
 *
 * Paths and names cross as UTF-8 bytes, never as Java strings: Java's modified UTF-8 can't carry
 * every name a file system holds. Names in and out are NUL-separated blobs.
 *
 *   list(dir)                         -> per entry: type byte, name, NUL; null if dir can't be opened
 *   readlinks(dir, names, starts, count)
 *                                     -> per name: 1 and the target, or 0 (not a link, or gone); NUL
 *   stats(dir, names, starts, count, follow, out[count * STAT_FIELDS])
 *                                     -> per name: inode, ctime (ns), size, mode, allocated bytes;
 *                                        mode -1 when it can't be stat'ed
 *
 * The names are read from a blob at the given start offsets, each up to its NUL: a list() result
 * given back as it came (so names a walk lists aren't encoded again), or names joined by NativeFs.
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* The entry types list() gives, as NativeFs knows them. */
enum { TYPE_DIR = 1, TYPE_LINK = 2, TYPE_FILE = 3, TYPE_OTHER = 4 };
enum { STAT_FIELDS = 5 };

typedef struct { char *data; size_t len, cap; int failed; } blob_t;

static void blob_add(blob_t *b, const void *bytes, size_t n) {
    if (b->failed) return;
    if (b->len + n > b->cap) {
        size_t cap = b->cap ? b->cap : 64 * 1024;
        while (cap < b->len + n) cap *= 2;
        char *grown = realloc(b->data, cap);
        if (!grown) { b->failed = 1; return; }
        b->data = grown;
        b->cap = cap;
    }
    memcpy(b->data + b->len, bytes, n);
    b->len += n;
}

static jbyteArray blob_array(JNIEnv *env, blob_t *b) {
    jbyteArray out = NULL;
    if (!b->failed) {
        out = (*env)->NewByteArray(env, (jsize)b->len);
        if (out && b->len) (*env)->SetByteArrayRegion(env, out, 0, (jsize)b->len, (const jbyte *)b->data);
    }
    free(b->data);
    return out;
}

/* A Java byte array as a NUL-terminated C string (the caller frees it); NULL on failure. */
static char *c_path(JNIEnv *env, jbyteArray bytes) {
    jsize n = (*env)->GetArrayLength(env, bytes);
    char *s = malloc((size_t)n + 1);
    if (!s) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte *)s);
    s[n] = 0;
    if (memchr(s, 0, (size_t)n)) { free(s); return NULL; }
    return s;
}

static int open_dir(const char *path) {
    return open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
}

static unsigned char type_of(int dirfd, const struct dirent *d) {
    switch (d->d_type) {
    case DT_DIR: return TYPE_DIR;
    case DT_LNK: return TYPE_LINK;
    case DT_REG: return TYPE_FILE;
    case DT_UNKNOWN: break;
    default: return TYPE_OTHER;
    }
    /* Some file systems don't say: ask. */
    struct stat st;
    if (fstatat(dirfd, d->d_name, &st, AT_SYMLINK_NOFOLLOW)) return TYPE_OTHER;
    if (S_ISDIR(st.st_mode)) return TYPE_DIR;
    if (S_ISLNK(st.st_mode)) return TYPE_LINK;
    if (S_ISREG(st.st_mode)) return TYPE_FILE;
    return TYPE_OTHER;
}

JNIEXPORT jbyteArray JNICALL
Java_com_garathon_flatdroid_util_io_NativeFs_list(JNIEnv *env, jclass cls, jbyteArray dir) {
    (void)cls;
    char *path = c_path(env, dir);
    if (!path) return NULL;
    int fd = open_dir(path);
    free(path);
    if (fd < 0) return NULL;
    DIR *d = fdopendir(fd);
    if (!d) { close(fd); return NULL; }
    blob_t b = {0};
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.' && (e->d_name[1] == 0 || (e->d_name[1] == '.' && e->d_name[2] == 0))) continue;
        unsigned char type = type_of(dirfd(d), e);
        blob_add(&b, &type, 1);
        blob_add(&b, e->d_name, strlen(e->d_name) + 1);
    }
    closedir(d);
    return blob_array(env, &b);
}

/*
 * Calls each(dirfd, name, index, arg) for count names, each at its start offset in names (up to
 * its NUL); -1 if dir can't be opened or the offsets don't fit the blob.
 */
static int each_name(JNIEnv *env, jbyteArray dir, jbyteArray names, jintArray starts, jint count,
                     void (*each)(int, const char *, jint, void *), void *arg) {
    if (count < 0 || (*env)->GetArrayLength(env, starts) < count) return -1;
    char *path = c_path(env, dir);
    if (!path) return -1;
    int fd = open_dir(path);
    free(path);
    if (fd < 0) return -1;
    jsize n = (*env)->GetArrayLength(env, names);
    char *all = malloc((size_t)n + 1);
    jint *at = malloc((size_t)count * sizeof(jint) + 1);
    int failed = !all || !at;
    if (!failed) {
        (*env)->GetByteArrayRegion(env, names, 0, n, (jbyte *)all);
        all[n] = 0;
        (*env)->GetIntArrayRegion(env, starts, 0, count, at);
        for (jint i = 0; i < count; i++) if (at[i] < 0 || at[i] >= n) failed = 1;
    }
    for (jint i = 0; !failed && i < count; i++) each(fd, all + at[i], i, arg);
    free(all);
    free(at);
    close(fd);
    return failed ? -1 : 0;
}

static void readlink_one(int fd, const char *name, jint i, void *arg) {
    (void)i;
    blob_t *b = arg;
    char target[4096];
    ssize_t n = readlinkat(fd, name, target, sizeof target);
    unsigned char ok = n >= 0 && (size_t)n < sizeof target;
    blob_add(b, &ok, 1);
    if (ok) blob_add(b, target, (size_t)n);
    blob_add(b, "", 1);
}

JNIEXPORT jbyteArray JNICALL
Java_com_garathon_flatdroid_util_io_NativeFs_readlinks(JNIEnv *env, jclass cls, jbyteArray dir, jbyteArray names, jintArray starts,
                                                       jint count) {
    (void)cls;
    blob_t b = {0};
    if (each_name(env, dir, names, starts, count, readlink_one, &b)) { free(b.data); return NULL; }
    return blob_array(env, &b);
}

typedef struct { jlong *out; int flags; } stat_arg_t;

static void stat_one(int fd, const char *name, jint i, void *arg) {
    stat_arg_t *a = arg;
    jlong *o = a->out + (size_t)i * STAT_FIELDS;
    struct stat st;
    if (fstatat(fd, name, &st, a->flags)) {
        o[0] = o[1] = o[2] = o[4] = 0;
        o[3] = -1;
        return;
    }
    o[0] = (jlong)st.st_ino;
    o[1] = (jlong)st.st_ctim.tv_sec * 1000000000LL + st.st_ctim.tv_nsec;
    o[2] = (jlong)st.st_size;
    o[3] = (jlong)st.st_mode;
    o[4] = (jlong)st.st_blocks * 512;
}

JNIEXPORT jboolean JNICALL
Java_com_garathon_flatdroid_util_io_NativeFs_stats(JNIEnv *env, jclass cls, jbyteArray dir, jbyteArray names, jintArray starts,
                                                   jint count, jboolean follow, jlongArray out) {
    (void)cls;
    if (count < 0 || (*env)->GetArrayLength(env, out) < (jsize)count * STAT_FIELDS) return JNI_FALSE;
    jlong *values = malloc((size_t)count * STAT_FIELDS * sizeof(jlong) + 1);
    if (!values) return JNI_FALSE;
    for (jint i = 0; i < count; i++) values[(size_t)i * STAT_FIELDS + 3] = -1;
    stat_arg_t a = {values, follow ? 0 : AT_SYMLINK_NOFOLLOW};
    int failed = each_name(env, dir, names, starts, count, stat_one, &a);
    if (!failed) (*env)->SetLongArrayRegion(env, out, 0, (jsize)count * STAT_FIELDS, values);
    free(values);
    return failed ? JNI_FALSE : JNI_TRUE;
}
