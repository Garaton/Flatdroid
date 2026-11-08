/*
 * The embedded viewer's frames: RFB pixels read from the socket straight into one frame in memory,
 * and each update's changed area copied from it into the next buffer of a surface's queue, which
 * Android's compositor shows as it is (placed and scaled by the compositor, nothing drawn by the
 * app). Called from viewer/NativeFrames.kt, on Android 10 and later only.
 */
#include <android/hardware_buffer.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define FN(name) Java_com_garathon_flatdroid_viewer_NativeFrames_##name
#define BYTES_PER_PIXEL 4

/* All of the connection's reading comes here (one reading thread): few system calls for many small rows. */
static uint8_t input[256 * 1024];
static size_t input_at, input_end;

/* A new connection. */
JNIEXPORT void JNICALL FN(reset)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    input_at = input_end = 0;
}

static int fill(int fd) {
    for (;;) {
        ssize_t got = read(fd, input, sizeof input);
        if (got < 0 && errno == EINTR) continue;
        if (got <= 0) return -1;
        input_at = 0;
        input_end = (size_t)got;
        return 0;
    }
}

static int read_fully(int fd, uint8_t *to, size_t count) {
    while (count > 0) {
        if (input_at == input_end) {
            /* Large reads skip the buffer: straight from the socket into the pixels. */
            if (count >= sizeof input) {
                ssize_t got = read(fd, to, count);
                if (got < 0 && errno == EINTR) continue;
                if (got <= 0) return -1;
                to += got;
                count -= (size_t)got;
                continue;
            }
            if (fill(fd) != 0) return -1;
        }
        size_t part = input_end - input_at;
        if (part > count) part = count;
        memcpy(to, input + input_at, part);
        input_at += part;
        to += part;
        count -= part;
    }
    return 0;
}

/* The next byte, or -1 when the connection ended. */
JNIEXPORT jint JNICALL FN(readByte)(JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    if (input_at == input_end && fill(fd) != 0) return -1;
    return input[input_at++];
}

/* Up to length bytes into a Java array: how many, or -1 when the connection ended. */
JNIEXPORT jint JNICALL FN(readBytes)(JNIEnv *env, jclass cls, jint fd, jbyteArray to, jint offset, jint length) {
    (void)cls;
    if (length == 0) return 0;
    if (input_at == input_end && fill(fd) != 0) return -1;
    size_t part = input_end - input_at;
    if (part > (size_t)length) part = (size_t)length;
    (*env)->SetByteArrayRegion(env, to, offset, (jsize)part, (const jbyte *)(input + input_at));
    input_at += part;
    return (jint)part;
}

/*
 * The frame, and the window it is shown through. The pixels are written by the reading thread
 * only. The window is attached and detached from the main thread under [lock], which is never
 * held while posting: a post holds its own reference, so letting go of the window never waits
 * for a post that waits for the compositor. Posts (reading thread, and the main thread once on
 * attaching) take turns under [posting].
 */
struct frame {
    pthread_mutex_t lock;
    pthread_mutex_t posting;
    uint8_t *pixels; /* width x height, no padding */
    int width, height;
    ANativeWindow *window;
    int window_width, window_height; /* the size its buffers were set to */
};

#define FRAME(handle) ((struct frame *)(intptr_t)(handle))

JNIEXPORT jlong JNICALL FN(create)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    struct frame *f = calloc(1, sizeof *f);
    if (f == NULL) return 0;
    pthread_mutex_init(&f->lock, NULL);
    pthread_mutex_init(&f->posting, NULL);
    return (jlong)(intptr_t)f;
}

/* The connection ended and the window is gone. */
JNIEXPORT void JNICALL FN(destroy)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    struct frame *f = FRAME(handle);
    if (f->window != NULL) ANativeWindow_release(f->window);
    pthread_mutex_destroy(&f->lock);
    pthread_mutex_destroy(&f->posting);
    free(f->pixels);
    free(f);
}

/* A new size, all black; false when there isn't the memory for it. */
JNIEXPORT jboolean JNICALL FN(resize)(JNIEnv *env, jclass cls, jlong handle, jint width, jint height) {
    (void)env; (void)cls;
    struct frame *f = FRAME(handle);
    uint8_t *pixels = calloc((size_t)width * (size_t)height, BYTES_PER_PIXEL);
    if (pixels == NULL) return JNI_FALSE;
    free(f->pixels);
    f->pixels = pixels;
    f->width = width;
    f->height = height;
    return JNI_TRUE;
}

/* Reads a Raw rectangle from fd into the frame; 0, or -1 when the connection ended. */
JNIEXPORT jint JNICALL FN(readRect)(JNIEnv *env, jclass cls, jint fd, jlong handle, jint x, jint y, jint width, jint height) {
    (void)env; (void)cls;
    struct frame *f = FRAME(handle);
    size_t pitch = (size_t)f->width * BYTES_PER_PIXEL, row = (size_t)width * BYTES_PER_PIXEL;
    uint8_t *to = f->pixels + (size_t)y * pitch + (size_t)x * BYTES_PER_PIXEL;
    if (row == pitch) return read_fully(fd, to, row * (size_t)height);
    for (jint r = 0; r < height; r++, to += pitch) {
        if (read_fully(fd, to, row) != 0) return -1;
    }
    return 0;
}

/* CopyRect within the frame, in the row order that never overwrites a source row first. */
JNIEXPORT void JNICALL FN(moveRect)(JNIEnv *env, jclass cls, jlong handle, jint sx, jint sy,
                                    jint x, jint y, jint width, jint height) {
    (void)env; (void)cls;
    struct frame *f = FRAME(handle);
    size_t pitch = (size_t)f->width * BYTES_PER_PIXEL, row = (size_t)width * BYTES_PER_PIXEL;
    for (jint i = 0; i < height; i++) {
        jint r = sy < y ? height - 1 - i : i;
        memmove(f->pixels + (size_t)(y + r) * pitch + (size_t)x * BYTES_PER_PIXEL,
                f->pixels + (size_t)(sy + r) * pitch + (size_t)sx * BYTES_PER_PIXEL, row);
    }
}

/* As ScrollMeter.hashRows (the green of every fourth pixel, one hash per row and band). */
JNIEXPORT void JNICALL FN(hashRows)(JNIEnv *env, jclass cls, jlong handle, jint bands, jintArray out) {
    (void)cls;
    struct frame *f = FRAME(handle);
    jint *hashes = (*env)->GetIntArrayElements(env, out, NULL);
    if (hashes == NULL) return;
    size_t pitch = (size_t)f->width * BYTES_PER_PIXEL;
    for (jint band = 0; band < bands; band++) {
        size_t from = (size_t)(f->width * band / bands) * BYTES_PER_PIXEL;
        size_t to = (size_t)(f->width * (band + 1) / bands) * BYTES_PER_PIXEL;
        for (jint y = 0; y < f->height; y++) {
            uint32_t h = 0x811c9dc5u;
            const uint8_t *row = f->pixels + (size_t)y * pitch;
            for (size_t i = from + 1; i < to; i += 16) h = (h ^ row[i]) * 0x01000193u;
            hashes[band * f->height + y] = (jint)h;
        }
    }
    (*env)->ReleaseIntArrayElements(env, out, hashes, 0);
}

/* Frames are shown through [surface] from now on (null: nowhere). False when it can't be used. */
JNIEXPORT jboolean JNICALL FN(attach)(JNIEnv *env, jclass cls, jlong handle, jobject surface) {
    (void)cls;
    struct frame *f = FRAME(handle);
    ANativeWindow *window = surface != NULL ? ANativeWindow_fromSurface(env, surface) : NULL;
    pthread_mutex_lock(&f->lock);
    ANativeWindow *old = f->window;
    f->window = window;
    f->window_width = f->window_height = 0;
    pthread_mutex_unlock(&f->lock);
    if (old != NULL) ANativeWindow_release(old); /* a post still holding it keeps its own reference */
    return surface == NULL || window != NULL;
}

/*
 * Shows the frame, which changed within the rectangle given. The window's next buffer gets that
 * area from the frame and the rest from its last buffer; where it has none (a new window or
 * size), it asks for more and gets all of the frame. Waits while the compositor holds all of the
 * window's buffers.
 */
JNIEXPORT void JNICALL FN(post)(JNIEnv *env, jclass cls, jlong handle, jint left, jint top, jint right, jint bottom) {
    (void)env; (void)cls;
    struct frame *f = FRAME(handle);
    pthread_mutex_lock(&f->posting);
    pthread_mutex_lock(&f->lock);
    ANativeWindow *window = f->window;
    int resized = 0;
    if (window != NULL) {
        ANativeWindow_acquire(window);
        resized = f->width != f->window_width || f->height != f->window_height;
        f->window_width = f->width;
        f->window_height = f->height;
    }
    pthread_mutex_unlock(&f->lock);
    if (window == NULL || f->pixels == NULL) {
        if (window != NULL) ANativeWindow_release(window);
        pthread_mutex_unlock(&f->posting);
        return;
    }
    if (resized) ANativeWindow_setBuffersGeometry(window, f->width, f->height, AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
    ANativeWindow_Buffer out;
    ARect dirty = { left, top, right, bottom };
    if (ANativeWindow_lock(window, &out, &dirty) == 0) {
        if (out.width == f->width && out.height == f->height) {
            size_t pitch = (size_t)f->width * BYTES_PER_PIXEL, out_pitch = (size_t)out.stride * BYTES_PER_PIXEL;
            size_t row = (size_t)(dirty.right - dirty.left) * BYTES_PER_PIXEL;
            const uint8_t *s = f->pixels + (size_t)dirty.top * pitch + (size_t)dirty.left * BYTES_PER_PIXEL;
            uint8_t *d = (uint8_t *)out.bits + (size_t)dirty.top * out_pitch + (size_t)dirty.left * BYTES_PER_PIXEL;
            for (int y = dirty.top; y < dirty.bottom; y++, s += pitch, d += out_pitch) memcpy(d, s, row);
        }
        ANativeWindow_unlockAndPost(window);
    }
    ANativeWindow_release(window);
    pthread_mutex_unlock(&f->posting);
}
