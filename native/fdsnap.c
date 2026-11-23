/*
 * Alpine safe launch's walk (part of libfdwalk.so), called through JNI by NativeSnapshot.kt: one
 * call stamps every entry under Alpine's folders and every store entry Alpine's links lead
 * through, as AlpineChanges.Snapshot holds them, on the calling thread.
 *
 *   take(rootfs, store, dirs, count) -> the snapshot, or null when memory runs out
 *
 * rootfs: Alpine's root. store: link2symlink's store. dirs: count NUL-separated folders under the
 * root. The snapshot, in native byte order: the stamp count and the bind count (8 bytes each),
 * per stamp its change time in nanoseconds, inode, size and mode (8 bytes each), then per stamp
 * its path and NUL (relative to the root, or the store entry's own path), then per bind the path
 * Alpine's links name and the store file bound there, each with a NUL.
 *
 * As AlpineChanges' walk was: folders in name order, depth first, links never followed but into
 * the store, where an entry is followed through at most MAX_STORE_HOPS links to a regular file.
 */
#include <dirent.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

enum { MAX_STORE_HOPS = 4 };

typedef struct { char *data; size_t len, cap; int failed; } bytes_t;

static void put(bytes_t *b, const void *bytes, size_t n) {
    if (b->failed) return;
    if (b->len + n > b->cap) {
        size_t cap = b->cap ? b->cap : 256 * 1024;
        while (cap < b->len + n) cap *= 2;
        char *grown = realloc(b->data, cap);
        if (!grown) { b->failed = 1; return; }
        b->data = grown;
        b->cap = cap;
    }
    memcpy(b->data + b->len, bytes, n);
    b->len += n;
}

typedef struct {
    int root;               /* Alpine's root, open */
    const char *store;      /* the store's path with a trailing slash */
    size_t store_len;
    bytes_t numbers, paths, binds;
    size_t stamps, bound;
    char **seen;            /* store entries bound already: open addressing */
    size_t seen_cap;
} snap_t;

static size_t hash(const char *s) {
    size_t h = 1469598103934665603ULL;
    for (; *s; s++) h = (h ^ (unsigned char)*s) * 1099511628211ULL;
    return h;
}

static int is_bound(const snap_t *s, const char *entry) {
    for (size_t i = hash(entry) & (s->seen_cap - 1); s->seen[i]; i = (i + 1) & (s->seen_cap - 1))
        if (!strcmp(s->seen[i], entry)) return 1;
    return 0;
}

static int add_bound(snap_t *s, const char *entry) {
    if ((s->bound + 1) * 2 > s->seen_cap) {
        size_t cap = s->seen_cap * 2;
        char **grown = calloc(cap, sizeof(char *));
        if (!grown) return 0;
        for (size_t i = 0; i < s->seen_cap; i++) {
            if (!s->seen[i]) continue;
            size_t at = hash(s->seen[i]) & (cap - 1);
            while (grown[at]) at = (at + 1) & (cap - 1);
            grown[at] = s->seen[i];
        }
        free(s->seen);
        s->seen = grown;
        s->seen_cap = cap;
    }
    char *copy = strdup(entry);
    if (!copy) return 0;
    size_t at = hash(entry) & (s->seen_cap - 1);
    while (s->seen[at]) at = (at + 1) & (s->seen_cap - 1);
    s->seen[at] = copy;
    s->bound++;
    return 1;
}

static void stamp(snap_t *s, const char *path, const struct stat *st) {
    int64_t n[4] = {(int64_t)st->st_ctim.tv_sec * 1000000000LL + st->st_ctim.tv_nsec, (int64_t)st->st_ino, (int64_t)st->st_size,
                    (int64_t)st->st_mode};
    put(&s->numbers, n, sizeof n);
    put(&s->paths, path, strlen(path) + 1);
    s->stamps++;
}

/* A direct entry of the store, named by its path (no ':', which link2symlink's names never hold). */
static int in_store(const snap_t *s, const char *path) {
    return !strncmp(path, s->store, s->store_len) && path[s->store_len] && !strchr(path + s->store_len, '/') && !strchr(path, ':');
}

/* An entry Alpine's links name, followed through the store to the file bound at its path. */
static void store_file(snap_t *s, const char *entry) {
    char path[PATH_MAX], target[PATH_MAX];
    if (strlen(entry) >= sizeof path) return;
    strcpy(path, entry);
    for (int hop = 0; hop < MAX_STORE_HOPS; hop++) {
        struct stat st;
        if (lstat(path, &st)) return;
        stamp(s, path, &st);
        if (!S_ISLNK(st.st_mode)) {
            if (S_ISREG(st.st_mode)) {
                if (!add_bound(s, entry)) { s->binds.failed = 1; return; }
                put(&s->binds, entry, strlen(entry) + 1);
                put(&s->binds, path, strlen(path) + 1);
            }
            return;
        }
        ssize_t n = readlink(path, target, sizeof target);
        if (n < 0 || (size_t)n >= sizeof target) return;
        target[n] = 0;
        if (!in_store(s, target)) return;
        strcpy(path, target);
    }
}

static int by_name(const void *a, const void *b) { return strcmp(*(char *const *)a, *(char *const *)b); }

/* Every entry of the folder rel (relative to the root), its folders after each in turn. */
static void visit(snap_t *s, const char *rel) {
    int fd = openat(s->root, rel, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return;
    DIR *d = fdopendir(fd);
    if (!d) { close(fd); return; }
    char **names = NULL;
    size_t count = 0, cap = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.' && (!e->d_name[1] || (e->d_name[1] == '.' && !e->d_name[2]))) continue;
        if (count == cap) {
            size_t n = cap ? cap * 2 : 64;
            char **grown = realloc(names, n * sizeof(char *));
            if (!grown) { s->paths.failed = 1; break; }
            names = grown;
            cap = n;
        }
        if (!(names[count] = strdup(e->d_name))) { s->paths.failed = 1; break; }
        count++;
    }
    if (count > 1) qsort(names, count, sizeof(char *), by_name);
    size_t rel_len = strlen(rel);
    for (size_t i = 0; i < count && !s->paths.failed; i++) {
        struct stat st;
        if (fstatat(dirfd(d), names[i], &st, AT_SYMLINK_NOFOLLOW)) continue;
        size_t name_len = strlen(names[i]);
        char *path = malloc(rel_len + name_len + 2);
        if (!path) { s->paths.failed = 1; break; }
        memcpy(path, rel, rel_len);
        path[rel_len] = '/';
        memcpy(path + rel_len + 1, names[i], name_len + 1);
        stamp(s, path, &st);
        if (S_ISDIR(st.st_mode)) {
            visit(s, path);
        } else if (S_ISLNK(st.st_mode)) {
            char target[PATH_MAX];
            ssize_t n = readlinkat(dirfd(d), names[i], target, sizeof target);
            if (n >= 0 && (size_t)n < sizeof target) {
                target[n] = 0;
                if (in_store(s, target) && !is_bound(s, target)) store_file(s, target);
            }
        }
        free(path);
    }
    for (size_t i = 0; i < count; i++) free(names[i]);
    free(names);
    closedir(d);
}

/* A Java byte array with a NUL after it; with [whole], NULL when it holds a NUL itself (a path). */
static char *c_bytes(JNIEnv *env, jbyteArray bytes, int whole) {
    jsize n = (*env)->GetArrayLength(env, bytes);
    char *s = malloc((size_t)n + 1);
    if (!s) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte *)s);
    s[n] = 0;
    if (whole && strlen(s) != (size_t)n) { free(s); return NULL; }
    return s;
}

JNIEXPORT jbyteArray JNICALL
Java_com_garathon_flatdroid_alpine_programs_NativeSnapshot_take(JNIEnv *env, jclass cls, jbyteArray rootfs, jbyteArray store,
                                                                jbyteArray dirs, jint count) {
    (void)cls;
    char *root = c_bytes(env, rootfs, 1), *store_dir = c_bytes(env, store, 1), *folders = c_bytes(env, dirs, 0);
    jsize folders_len = (*env)->GetArrayLength(env, dirs);
    snap_t s = {0};
    s.seen_cap = 1024;
    s.seen = calloc(s.seen_cap, sizeof(char *));
    char *store_slash = store_dir ? malloc(strlen(store_dir) + 2) : NULL;
    jbyteArray out = NULL;
    if (!root || !store_dir || !folders || !s.seen || !store_slash || count < 0) goto done;
    /* The store's path as AlpineChanges names it: without trailing slashes, then one. */
    strcpy(store_slash, store_dir);
    size_t len = strlen(store_slash);
    while (len > 0 && store_slash[len - 1] == '/') len--;
    store_slash[len] = '/';
    store_slash[len + 1] = 0;
    s.store = store_slash;
    s.store_len = len + 1;
    s.root = open(root, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (s.root >= 0) {
        size_t at = 0;
        for (jint i = 0; i < count && at < (size_t)folders_len; i++) {
            const char *dir = folders + at;
            at += strlen(dir) + 1;
            struct stat st;
            if (!*dir || fstatat(s.root, dir, &st, AT_SYMLINK_NOFOLLOW)) continue;
            stamp(&s, dir, &st);
            if (S_ISDIR(st.st_mode)) visit(&s, dir);
        }
        close(s.root);
    }
    if (s.numbers.failed || s.paths.failed || s.binds.failed) goto done;
    {
        int64_t header[2] = {(int64_t)s.stamps, (int64_t)s.bound};
        size_t total = sizeof header + s.numbers.len + s.paths.len + s.binds.len;
        if (total > INT32_MAX) goto done;
        out = (*env)->NewByteArray(env, (jsize)total);
        if (!out) goto done;
        jsize at = 0;
        (*env)->SetByteArrayRegion(env, out, at, sizeof header, (const jbyte *)header);
        at += sizeof header;
        if (s.numbers.len) (*env)->SetByteArrayRegion(env, out, at, (jsize)s.numbers.len, (const jbyte *)s.numbers.data);
        at += (jsize)s.numbers.len;
        if (s.paths.len) (*env)->SetByteArrayRegion(env, out, at, (jsize)s.paths.len, (const jbyte *)s.paths.data);
        at += (jsize)s.paths.len;
        if (s.binds.len) (*env)->SetByteArrayRegion(env, out, at, (jsize)s.binds.len, (const jbyte *)s.binds.data);
    }
done:
    for (size_t i = 0; s.seen && i < s.seen_cap; i++) free(s.seen[i]);
    free(s.seen);
    free(s.numbers.data);
    free(s.paths.data);
    free(s.binds.data);
    free(root);
    free(store_dir);
    free(folders);
    free(store_slash);
    return out;
}
