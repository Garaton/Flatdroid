/*
 * The embedded viewer's frames: RFB pixels read from the socket straight into a hardware buffer
 * the GPU draws from, instead of a Java array copied into a bitmap and uploaded. Called from
 * viewer/NativeFrames.kt, on Android 10 and later only.
 */
#include <android/hardware_buffer_jni.h>
#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

#define FN(name) Java_com_garathon_flatdroid_viewer_NativeFrames_##name
#define BYTES_PER_PIXEL 4

/* The buffer's pixels, for the CPU, until unlock; 0 on failure. */
JNIEXPORT jlong JNICALL FN(lock)(JNIEnv *env, jclass cls, jobject buffer) {
    (void)cls;
    void *pixels = NULL;
    AHardwareBuffer *native = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    uint64_t usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
    if (AHardwareBuffer_lock(native, usage, -1, NULL, &pixels) != 0) return 0;
    return (jlong)(intptr_t)pixels;
}

JNIEXPORT void JNICALL FN(unlock)(JNIEnv *env, jclass cls, jobject buffer) {
    (void)cls;
    AHardwareBuffer_unlock(AHardwareBuffer_fromHardwareBuffer(env, buffer), NULL);
}

/* Pixels from one row's start to the next's. */
JNIEXPORT jint JNICALL FN(stride)(JNIEnv *env, jclass cls, jobject buffer) {
    (void)cls;
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(AHardwareBuffer_fromHardwareBuffer(env, buffer), &desc);
    return (jint)desc.stride;
}

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

/* Reads a Raw rectangle from fd into the buffer; 0, or -1 when the connection ended. */
JNIEXPORT jint JNICALL FN(readRect)(JNIEnv *env, jclass cls, jint fd, jlong pixels, jint stride,
                                    jint x, jint y, jint width, jint height) {
    (void)env; (void)cls;
    size_t pitch = (size_t)stride * BYTES_PER_PIXEL, row = (size_t)width * BYTES_PER_PIXEL;
    uint8_t *to = (uint8_t *)(intptr_t)pixels + (size_t)y * pitch + (size_t)x * BYTES_PER_PIXEL;
    if (row == pitch) return read_fully(fd, to, row * (size_t)height);
    for (jint r = 0; r < height; r++, to += pitch) {
        if (read_fully(fd, to, row) != 0) return -1;
    }
    return 0;
}

/* Copies a rectangle between two buffers of the same layout. */
JNIEXPORT void JNICALL FN(copyRect)(JNIEnv *env, jclass cls, jlong to, jlong from, jint stride,
                                    jint x, jint y, jint width, jint height) {
    (void)env; (void)cls;
    size_t pitch = (size_t)stride * BYTES_PER_PIXEL, row = (size_t)width * BYTES_PER_PIXEL;
    size_t start = (size_t)y * pitch + (size_t)x * BYTES_PER_PIXEL;
    uint8_t *d = (uint8_t *)(intptr_t)to + start;
    const uint8_t *s = (const uint8_t *)(intptr_t)from + start;
    for (jint r = 0; r < height; r++, d += pitch, s += pitch) memcpy(d, s, row);
}

/* CopyRect within one buffer, in the row order that never overwrites a source row first. */
JNIEXPORT void JNICALL FN(moveRect)(JNIEnv *env, jclass cls, jlong pixels, jint stride, jint sx, jint sy,
                                    jint x, jint y, jint width, jint height) {
    (void)env; (void)cls;
    size_t pitch = (size_t)stride * BYTES_PER_PIXEL, row = (size_t)width * BYTES_PER_PIXEL;
    uint8_t *base = (uint8_t *)(intptr_t)pixels;
    for (jint i = 0; i < height; i++) {
        jint r = sy < y ? height - 1 - i : i;
        memmove(base + (size_t)(y + r) * pitch + (size_t)x * BYTES_PER_PIXEL,
                base + (size_t)(sy + r) * pitch + (size_t)sx * BYTES_PER_PIXEL, row);
    }
}

/* As ScrollMeter.hashRows (the green of every fourth pixel, one hash per row and band). */
JNIEXPORT void JNICALL FN(hashRows)(JNIEnv *env, jclass cls, jlong pixels, jint stride, jint width, jint height,
                                    jint bands, jintArray out) {
    (void)cls;
    jint *hashes = (*env)->GetIntArrayElements(env, out, NULL);
    const uint8_t *base = (const uint8_t *)(intptr_t)pixels;
    size_t pitch = (size_t)stride * BYTES_PER_PIXEL;
    for (jint band = 0; band < bands; band++) {
        size_t from = (size_t)(width * band / bands) * BYTES_PER_PIXEL;
        size_t to = (size_t)(width * (band + 1) / bands) * BYTES_PER_PIXEL;
        for (jint y = 0; y < height; y++) {
            uint32_t h = 0x811c9dc5u;
            const uint8_t *row = base + (size_t)y * pitch;
            for (size_t i = from + 1; i < to; i += 16) h = (h ^ row[i]) * 0x01000193u;
            hashes[band * height + y] = (jint)h;
        }
    }
    (*env)->ReleaseIntArrayElements(env, out, hashes, 0);
}
