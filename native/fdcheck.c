/*
 * The Hardened file check's walk and compare (part of libfdwalk.so), called through JNI by
 * NativeCheck.kt. One check walks a deployment's tree, reads every leaf's link and followed stat
 * on a few threads, reads the deployment's manifest and compares the two, and gives back only
 * what differs: the leaves whose shape (link target, inode, ctime, size) isn't the manifest's,
 * with the manifest's entry beside each, and the manifest's paths the tree no longer has.
 * Kotlin hashes those leaves and decides (FileIntegrity.diff), so the rules stay in one place.
 *
 *   start(root, manifest, store, ignore, ignoreCount, threads) -> handle (0 when it can't start)
 *   await(handle, millis, progress[2]) -> -1 still running, else the status below;
 *                                         progress: leaves read, leaves in all (0 while walking)
 *   cancel(handle)                      the check stops between folders and between chunks
 *   strings(handle), numbers(handle)    the result, once done (see put_record)
 *   free(handle)                        waits for the check's threads, then frees it
 *
 * root: the deployment's files folder. manifest: its manifest file (FileIntegrity.write's format:
 * per line SHA-256, inode, ctime, size, link, path, tab-separated, "-" for none, paths and links
 * escaped). store: link2symlink's store as NUL-separated paths that name it as text (the second
 * form Android reaches app data by, then its canonical path); a link into it is followed, any
 * other is recorded by its target alone. ignore: NUL-separated paths left out on both sides.
 * Everything crosses as UTF-8 bytes.
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

enum { CHECK_DONE = 0, CHECK_NO_BASELINE = 1, CHECK_CANCELLED = 2, CHECK_FAILED = 3 };
enum { CHUNK = 64, MAX_THREADS = 16, RECORD_NUMBERS = 10, HEADER_NUMBERS = 3 };
static const char STORE_PREFIX[] = ".l2s.";

typedef struct { char *data; size_t len, cap; int failed; } buf_t;

static void buf_add(buf_t *b, const void *bytes, size_t n) {
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

static void buf_str(buf_t *b, const char *s) { buf_add(b, s ? s : "", (s ? strlen(s) : 0) + 1); }

/* A growable array of fixed-size items; 0 on failure. */
static int grow(void **items, size_t *cap, size_t need, size_t size) {
    if (need <= *cap) return 1;
    size_t n = *cap ? *cap * 2 : 1024;
    while (n < need) n *= 2;
    void *grown = realloc(*items, n * size);
    if (!grown) return 0;
    *items = grown;
    *cap = n;
    return 1;
}

typedef struct {
    char *rel;          /* relative to the root; owns the name */
    const char *name;   /* within rel */
    size_t dir;
    char *link;         /* the link target, NULL when not a link */
    int64_t ino, ctime, size;
    const struct entry *base; /* its manifest line, set by the compare */
    int differs;
} leaf_t;

typedef struct entry { char *path, *link, *sha; int64_t ino, ctime, size; int seen; } entry_t;

typedef struct { size_t dir, from, to; } item_t;

typedef struct {
    char *root, *manifest;
    char *store[3];
    char **ignore;
    size_t ignored;
    int threads;

    char **dirs;        /* relative paths, "" for the root */
    size_t ndirs, capdirs;
    leaf_t *leaves;
    size_t nleaves, capleaves;
    item_t *items;
    size_t nitems, capitems;
    atomic_size_t next;

    char *text;         /* the manifest's bytes, its entries point into them */
    entry_t *entries;
    size_t nentries, capentries;
    size_t *table;      /* open addressing: entry index + 1, 0 empty */
    size_t tablecap;

    atomic_int done, total, cancelled;
    pthread_mutex_t lock;
    pthread_cond_t cond;
    int finished, status;
    pthread_t thread;
    int started;

    buf_t strings;
    int64_t *numbers;
    size_t nnumbers;
} check_t;

static size_t hash(const char *s) {
    size_t h = 1469598103934665603ULL;
    for (; *s; s++) h = (h ^ (unsigned char)*s) * 1099511628211ULL;
    return h;
}

static int ignored(const check_t *c, const char *rel) {
    for (size_t i = 0; i < c->ignored; i++) if (!strcmp(c->ignore[i], rel)) return 1;
    return 0;
}

/* ---- the manifest */

/* FileIntegrity.unescape in place: \\, \t, \n and \r; any other backslash pair stays as it is. */
static void unescape(char *s) {
    char *out = s;
    while (*s) {
        if (s[0] == '\\' && s[1]) {
            char c = s[1] == '\\' ? '\\' : s[1] == 't' ? '\t' : s[1] == 'n' ? '\n' : s[1] == 'r' ? '\r' : 0;
            if (c) { *out++ = c; s += 2; continue; }
        }
        *out++ = *s++;
    }
    *out = 0;
}

/* A decimal long as Kotlin's toLongOrNull reads it; 0 when it isn't one. */
static int parse_long(const char *s, int64_t *out) {
    if (!*s) return 0;
    errno = 0;
    char *end;
    long long v = strtoll(s, &end, 10);
    if (*end || errno || s[0] == ' ' || s[0] == '\t') return 0;
    *out = v;
    return 1;
}

static entry_t *find(check_t *c, const char *path) {
    if (!c->tablecap) return NULL;
    for (size_t i = hash(path) & (c->tablecap - 1);; i = (i + 1) & (c->tablecap - 1)) {
        size_t at = c->table[i];
        if (!at) return NULL;
        if (!strcmp(c->entries[at - 1].path, path)) return &c->entries[at - 1];
    }
}

/* Reads the manifest as FileIntegrity.read does: a line of other than 6 fields, or with a bad number, is skipped; the last line of a path counts. */
static int read_manifest(check_t *c) {
    int fd = open(c->manifest, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return CHECK_NO_BASELINE;
    struct stat st;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode)) { close(fd); return CHECK_NO_BASELINE; }
    size_t n = (size_t)st.st_size, got = 0;
    c->text = malloc(n + 1);
    if (!c->text) { close(fd); return CHECK_FAILED; }
    while (got < n) {
        ssize_t r = read(fd, c->text + got, n - got);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) break;
        got += (size_t)r;
    }
    close(fd);
    c->text[got] = 0;
    for (char *line = c->text; *line;) {
        char *end = strchr(line, '\n');
        if (end) *end = 0;
        char *f[6];
        int k = 0;
        char *p = line;
        f[k++] = p;
        for (; *p; p++) if (*p == '\t') { *p = 0; if (k < 6) f[k] = p + 1; k++; }
        entry_t e = {0};
        if (k == 6 && parse_long(f[1], &e.ino) && parse_long(f[2], &e.ctime) && parse_long(f[3], &e.size)) {
            e.sha = strcmp(f[0], "-") ? f[0] : NULL;
            e.link = strcmp(f[4], "-") ? f[4] : NULL;
            if (e.link) unescape(e.link);
            unescape(f[5]);
            e.path = f[5];
            if (!grow((void **)&c->entries, &c->capentries, c->nentries + 1, sizeof(entry_t))) return CHECK_FAILED;
            c->entries[c->nentries++] = e;
        }
        if (!end) break;
        line = end + 1;
    }
    c->tablecap = 16;
    while (c->tablecap < c->nentries * 2) c->tablecap *= 2;
    c->table = calloc(c->tablecap, sizeof(size_t));
    if (!c->table) return CHECK_FAILED;
    for (size_t i = 0; i < c->nentries; i++) {
        size_t at = hash(c->entries[i].path) & (c->tablecap - 1);
        for (;; at = (at + 1) & (c->tablecap - 1)) {
            size_t was = c->table[at];
            if (!was) { c->table[at] = i + 1; break; }
            if (!strcmp(c->entries[was - 1].path, c->entries[i].path)) {
                c->entries[was - 1].path = NULL; /* replaced by the later line */
                c->table[at] = i + 1;
                break;
            }
        }
    }
    return CHECK_DONE;
}

/* ---- the walk */

static char *joined(const char *a, const char *b) {
    size_t la = strlen(a), lb = strlen(b);
    char *s = malloc(la + lb + 2);
    if (!s) return NULL;
    memcpy(s, a, la);
    size_t at = la;
    if (la && lb) s[at++] = '/';
    memcpy(s + at, b, lb + 1);
    return s;
}

static int open_dir(const check_t *c, size_t dir) {
    char *path = joined(c->root, c->dirs[dir]);
    if (!path) return -1;
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    free(path);
    return fd;
}

/*
 * FileIntegrity.walk: every leaf (anything but a real directory) under the root, recursing only
 * real directories, so a link to a directory is a leaf. A folder that can't be listed adds nothing.
 */
static int walk(check_t *c) {
    c->dirs = malloc(sizeof(char *) * 1024);
    if (!c->dirs || !(c->dirs[0] = strdup(""))) return CHECK_FAILED;
    c->capdirs = 1024;
    c->ndirs = 1;
    for (size_t d = 0; d < c->ndirs; d++) {
        if (atomic_load(&c->cancelled)) return CHECK_CANCELLED;
        int fd = open_dir(c, d);
        if (fd < 0) continue;
        DIR *dp = fdopendir(fd);
        if (!dp) { close(fd); continue; }
        size_t first = c->nleaves;
        struct dirent *e;
        while ((e = readdir(dp)) != NULL) {
            const char *name = e->d_name;
            if (name[0] == '.' && (!name[1] || (name[1] == '.' && !name[2]))) continue;
            int is_dir = e->d_type == DT_DIR;
            if (e->d_type == DT_UNKNOWN) {
                struct stat st;
                is_dir = !fstatat(dirfd(dp), name, &st, AT_SYMLINK_NOFOLLOW) && S_ISDIR(st.st_mode);
            }
            char *rel = joined(c->dirs[d], name);
            if (!rel) { closedir(dp); return CHECK_FAILED; }
            if (is_dir) {
                if (!grow((void **)&c->dirs, &c->capdirs, c->ndirs + 1, sizeof(char *))) { free(rel); closedir(dp); return CHECK_FAILED; }
                c->dirs[c->ndirs++] = rel;
                continue;
            }
            if (ignored(c, rel)) { free(rel); continue; }
            if (!grow((void **)&c->leaves, &c->capleaves, c->nleaves + 1, sizeof(leaf_t))) { free(rel); closedir(dp); return CHECK_FAILED; }
            leaf_t *l = &c->leaves[c->nleaves++];
            memset(l, 0, sizeof *l);
            l->rel = rel;
            l->name = rel + strlen(rel) - strlen(name);
            l->dir = d;
        }
        closedir(dp);
        for (size_t at = first; at < c->nleaves; at += CHUNK) {
            if (!grow((void **)&c->items, &c->capitems, c->nitems + 1, sizeof(item_t))) return CHECK_FAILED;
            c->items[c->nitems++] = (item_t){d, at, at + CHUNK < c->nleaves ? at + CHUNK : c->nleaves};
        }
    }
    return CHECK_DONE;
}

/* L2s.Store.storedName(target) != null: a store entry's name, in a folder that is the store. */
static int is_store_link(const check_t *c, const char *target) {
    const char *slash = strrchr(target, '/');
    if (!slash || slash == target) return 0;
    if (strncmp(slash + 1, STORE_PREFIX, sizeof STORE_PREFIX - 1)) return 0;
    size_t n = (size_t)(slash - target);
    for (int i = 0; i < 2; i++) if (c->store[i] && strlen(c->store[i]) == n && !strncmp(c->store[i], target, n)) return 1;
    if (!c->store[2] || !*c->store[2]) return 0;
    char parent[PATH_MAX], real[PATH_MAX];
    if (n >= sizeof parent) return 0;
    memcpy(parent, target, n);
    parent[n] = 0;
    return realpath(parent, real) && !strcmp(real, c->store[2]);
}

/* FileIntegrity.shape for each leaf of the items taken: the link, then (unless it names a guest path) the followed stat. */
static void *shape_items(void *arg) {
    check_t *c = arg;
    char target[4096];
    for (;;) {
        if (atomic_load(&c->cancelled)) break;
        size_t i = atomic_fetch_add(&c->next, 1);
        if (i >= c->nitems) break;
        item_t it = c->items[i];
        int fd = open_dir(c, it.dir);
        for (size_t k = it.from; k < it.to; k++) {
            leaf_t *l = &c->leaves[k];
            if (fd < 0) continue;
            ssize_t n = readlinkat(fd, l->name, target, sizeof target);
            if (n >= 0 && (size_t)n < sizeof target) {
                target[n] = 0;
                l->link = strdup(target);
                if (l->link && !is_store_link(c, l->link)) continue; /* recorded by its target alone */
            }
            struct stat st;
            if (!fstatat(fd, l->name, &st, 0)) {
                l->ino = (int64_t)st.st_ino;
                l->ctime = (int64_t)st.st_ctim.tv_sec;
                l->size = (int64_t)st.st_size;
            }
        }
        if (fd >= 0) close(fd);
        atomic_fetch_add(&c->done, (int)(it.to - it.from));
    }
    return NULL;
}

/* ---- the compare */

static int same_text(const char *a, const char *b) { return (!a && !b) || (a && b && !strcmp(a, b)); }

/*
 * One record: numbers kind (0 added, 1 changed), the leaf's has-link, inode, ctime, size, then
 * the manifest's has-link, has-hash, inode, ctime, size; strings path, the leaf's link, the
 * manifest's link and hash ("" for none).
 */
static void put_record(check_t *c, const leaf_t *l, const entry_t *e, int64_t *n) {
    n[0] = e != NULL;
    n[1] = l->link != NULL;
    n[2] = l->ino;
    n[3] = l->ctime;
    n[4] = l->size;
    n[5] = e && e->link;
    n[6] = e && e->sha;
    n[7] = e ? e->ino : 0;
    n[8] = e ? e->ctime : 0;
    n[9] = e ? e->size : 0;
    buf_str(&c->strings, l->rel);
    buf_str(&c->strings, l->link);
    buf_str(&c->strings, e ? e->link : NULL);
    buf_str(&c->strings, e ? e->sha : NULL);
}

/* The result: numbers records, removed paths, leaves; then the records; strings likewise, the removed paths after the records. */
static int compare(check_t *c) {
    size_t records = 0;
    for (size_t i = 0; i < c->nleaves; i++) {
        leaf_t *l = &c->leaves[i];
        entry_t *e = find(c, l->rel);
        if (e) e->seen = 1;
        l->base = e;
        l->differs = !e || !same_text(l->link, e->link) || l->ino != e->ino || l->ctime != e->ctime || l->size != e->size;
        records += l->differs;
    }
    c->numbers = malloc((HEADER_NUMBERS + records * RECORD_NUMBERS) * sizeof(int64_t));
    if (!c->numbers) return CHECK_FAILED;
    c->nnumbers = HEADER_NUMBERS + records * RECORD_NUMBERS;
    int64_t *n = c->numbers + HEADER_NUMBERS;
    for (size_t i = 0; i < c->nleaves; i++) {
        const leaf_t *l = &c->leaves[i];
        if (!l->differs) continue;
        put_record(c, l, l->base, n);
        n += RECORD_NUMBERS;
    }
    size_t removed = 0;
    for (size_t i = 0; i < c->nentries; i++) {
        const entry_t *e = &c->entries[i];
        if (!e->path || e->seen || ignored(c, e->path)) continue;
        buf_str(&c->strings, e->path);
        removed++;
    }
    c->numbers[0] = (int64_t)records;
    c->numbers[1] = (int64_t)removed;
    c->numbers[2] = (int64_t)c->nleaves;
    return c->strings.failed ? CHECK_FAILED : CHECK_DONE;
}

static void *run(void *arg) {
    check_t *c = arg;
    int status = read_manifest(c);
    if (status == CHECK_DONE) status = walk(c);
    if (status == CHECK_DONE) {
        atomic_store(&c->total, (int)c->nleaves);
        pthread_t helpers[MAX_THREADS];
        int started = 0;
        for (int i = 1; i < c->threads; i++) if (!pthread_create(&helpers[started], NULL, shape_items, c)) started++;
        shape_items(c);
        for (int i = 0; i < started; i++) pthread_join(helpers[i], NULL);
        status = atomic_load(&c->cancelled) ? CHECK_CANCELLED : compare(c);
    }
    pthread_mutex_lock(&c->lock);
    c->status = status;
    c->finished = 1;
    pthread_cond_broadcast(&c->cond);
    pthread_mutex_unlock(&c->lock);
    return NULL;
}

static void check_free(check_t *c) {
    for (size_t i = 0; i < c->ndirs; i++) free(c->dirs[i]);
    for (size_t i = 0; i < c->nleaves; i++) { free(c->leaves[i].rel); free(c->leaves[i].link); }
    for (size_t i = 0; i < c->ignored; i++) free(c->ignore[i]);
    for (int i = 0; i < 3; i++) free(c->store[i]);
    free(c->dirs);
    free(c->leaves);
    free(c->items);
    free(c->ignore);
    free(c->root);
    free(c->manifest);
    free(c->text);
    free(c->entries);
    free(c->table);
    free(c->strings.data);
    free(c->numbers);
    pthread_mutex_destroy(&c->lock);
    pthread_cond_destroy(&c->cond);
    free(c);
}

/* ---- JNI */

static char *c_string(JNIEnv *env, jbyteArray bytes) {
    jsize n = (*env)->GetArrayLength(env, bytes);
    char *s = malloc((size_t)n + 1);
    if (!s) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte *)s);
    s[n] = 0;
    if (memchr(s, 0, (size_t)n)) { free(s); return NULL; }
    return s;
}

/* count NUL-terminated strings from bytes, each copied; NULL on failure. */
static char **c_strings(JNIEnv *env, jbyteArray bytes, int count) {
    jsize n = (*env)->GetArrayLength(env, bytes);
    char *all = malloc((size_t)n + 1);
    char **out = calloc((size_t)count + 1, sizeof(char *));
    if (!all || !out) { free(all); free(out); return NULL; }
    (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte *)all);
    all[n] = 0;
    size_t at = 0;
    for (int i = 0; i < count; i++) {
        if (at > (size_t)n) { for (int k = 0; k < i; k++) free(out[k]); free(out); free(all); return NULL; }
        out[i] = strdup(all + at);
        if (!out[i]) { for (int k = 0; k < i; k++) free(out[k]); free(out); free(all); return NULL; }
        at += strlen(all + at) + 1;
    }
    free(all);
    return out;
}

#define FN(name) Java_com_garathon_flatdroid_flatpak_integrity_NativeCheck_##name

JNIEXPORT jlong JNICALL FN(start)(JNIEnv *env, jclass cls, jbyteArray root, jbyteArray manifest, jbyteArray store,
                                  jbyteArray ignore, jint ignoreCount, jint threads) {
    (void)cls;
    if (ignoreCount < 0 || threads < 1) return 0;
    check_t *c = calloc(1, sizeof(check_t));
    if (!c) return 0;
    pthread_mutex_init(&c->lock, NULL);
    pthread_cond_init(&c->cond, NULL);
    c->threads = threads > MAX_THREADS ? MAX_THREADS : threads;
    c->root = c_string(env, root);
    c->manifest = c_string(env, manifest);
    char **stores = c_strings(env, store, 3);
    if (stores) { for (int i = 0; i < 3; i++) c->store[i] = stores[i]; free(stores); }
    c->ignore = c_strings(env, ignore, ignoreCount);
    c->ignored = c->ignore ? (size_t)ignoreCount : 0;
    if (!c->root || !c->manifest || !stores || !c->ignore || pthread_create(&c->thread, NULL, run, c)) {
        check_free(c);
        return 0;
    }
    c->started = 1;
    return (jlong)(intptr_t)c;
}

JNIEXPORT jint JNICALL FN(await)(JNIEnv *env, jclass cls, jlong handle, jint millis, jintArray progress) {
    (void)cls;
    check_t *c = (check_t *)(intptr_t)handle;
    struct timespec until;
    clock_gettime(CLOCK_REALTIME, &until);
    until.tv_sec += millis / 1000;
    until.tv_nsec += (long)(millis % 1000) * 1000000L;
    if (until.tv_nsec >= 1000000000L) { until.tv_sec++; until.tv_nsec -= 1000000000L; }
    pthread_mutex_lock(&c->lock);
    while (!c->finished && pthread_cond_timedwait(&c->cond, &c->lock, &until) == 0) {}
    int status = c->finished ? c->status : -1;
    pthread_mutex_unlock(&c->lock);
    if ((*env)->GetArrayLength(env, progress) >= 2) {
        jint p[2] = {atomic_load(&c->done), atomic_load(&c->total)};
        (*env)->SetIntArrayRegion(env, progress, 0, 2, p);
    }
    return status;
}

JNIEXPORT void JNICALL FN(cancel)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    atomic_store(&((check_t *)(intptr_t)handle)->cancelled, 1);
}

JNIEXPORT jbyteArray JNICALL FN(strings)(JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    check_t *c = (check_t *)(intptr_t)handle;
    if (!c->finished || c->status != CHECK_DONE) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, (jsize)c->strings.len);
    if (out && c->strings.len) (*env)->SetByteArrayRegion(env, out, 0, (jsize)c->strings.len, (const jbyte *)c->strings.data);
    return out;
}

JNIEXPORT jlongArray JNICALL FN(numbers)(JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    check_t *c = (check_t *)(intptr_t)handle;
    if (!c->finished || c->status != CHECK_DONE) return NULL;
    jlongArray out = (*env)->NewLongArray(env, (jsize)c->nnumbers);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, (jsize)c->nnumbers, (const jlong *)c->numbers);
    return out;
}

JNIEXPORT void JNICALL FN(free)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    check_t *c = (check_t *)(intptr_t)handle;
    if (!c) return;
    atomic_store(&c->cancelled, 1);
    if (c->started) pthread_join(c->thread, NULL);
    check_free(c);
}
