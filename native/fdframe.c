/*
 * libfdframe.so: the camera's per-frame work for Flatdroid, called through JNI by NativeFrames.kt.
 *
 *   turn(src, width, height, degrees, swap, dst)
 *       an NV21 or NV12 frame (Y, then chroma pairs at half size) turned clockwise by 90, 180 or
 *       270 degrees; dst is height x width for 90 and 270. Chroma pairs move whole, so either
 *       order works; with swap, each pair's two bytes trade places on the way (NV21 to NV12 or
 *       back).
 *   swap(frame, width, height)
 *       the same trade in place, for a frame that isn't turned.
 *   convert(rgba, rowStride, width, height, out, uvFirst)
 *       RGBA pixels (a direct buffer, from its start) as NV21, or NV12 with uvFirst, in JPEG's
 *       full range (BT.601), as Android's cameras give: each pixel's Y, and each 2x2 block's U and
 *       V from its average.
 *
 * On arm64 the 90 and 270 degree turns move 8x8 blocks of luma and 4x4 blocks of chroma pairs
 * through NEON registers (a frame whose sides are multiples of 8); everything else is plain C in
 * tiles, so a block's rows stay in the cache. Both return false for arguments that don't fit.
 * fdframe-test.c checks both paths against a plain per-pixel turn.
 */
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

enum { TILE = 32 };

static inline uint16_t load16(const uint8_t *p) { uint16_t v; memcpy(&v, p, 2); return v; }
static inline void store16(uint8_t *p, uint16_t v) { memcpy(p, &v, 2); }
static inline uint16_t swapped16(uint16_t v) { return (uint16_t)(v << 8 | v >> 8); }

/* Where sample (x, y) of a w x h plane lands in the turned plane, as a sample index. */
static inline size_t target(int x, int y, int w, int h, int degrees) {
    switch (degrees) {
    case 90: return (size_t)x * h + (h - 1 - y);
    case 180: return (size_t)(h - 1 - y) * w + (w - 1 - x);
    default: return (size_t)(w - 1 - x) * h + y; /* 270 */
    }
}

/* One plane of [size]-byte samples (1 for luma, 2 for chroma pairs, their bytes traded with swap) turned in tiles. */
static void turn_plane(const uint8_t *src, int w, int h, int size, int degrees, int swap, uint8_t *dst) {
    for (int by = 0; by < h; by += TILE) {
        int ey = by + TILE < h ? by + TILE : h;
        for (int bx = 0; bx < w; bx += TILE) {
            int ex = bx + TILE < w ? bx + TILE : w;
            for (int y = by; y < ey; y++) {
                const uint8_t *row = src + (size_t)y * w * size;
                /* Along a source row the target moves by a fixed step. */
                ptrdiff_t step = degrees == 90 ? h : degrees == 180 ? -1 : -(ptrdiff_t)h;
                uint8_t *to = dst + target(bx, y, w, h, degrees) * size;
                if (size == 1) {
                    for (int x = bx; x < ex; x++, to += step) *to = row[x];
                } else {
                    for (int x = bx; x < ex; x++, to += step * 2) {
                        uint16_t pair = load16(row + 2 * x);
                        store16(to, swap ? swapped16(pair) : pair);
                    }
                }
            }
        }
    }
}

#if defined(__aarch64__)
/* q[0..7] transposed: out[c] holds lane c of each q, q[0]'s first. */
static inline void transpose8(const uint8x8_t q[8], uint8x8_t out[8]) {
    uint8x8x2_t a = vtrn_u8(q[0], q[1]), b = vtrn_u8(q[2], q[3]), c = vtrn_u8(q[4], q[5]), e = vtrn_u8(q[6], q[7]);
    uint16x4x2_t f = vtrn_u16(vreinterpret_u16_u8(a.val[0]), vreinterpret_u16_u8(b.val[0]));
    uint16x4x2_t g = vtrn_u16(vreinterpret_u16_u8(a.val[1]), vreinterpret_u16_u8(b.val[1]));
    uint16x4x2_t h = vtrn_u16(vreinterpret_u16_u8(c.val[0]), vreinterpret_u16_u8(e.val[0]));
    uint16x4x2_t k = vtrn_u16(vreinterpret_u16_u8(c.val[1]), vreinterpret_u16_u8(e.val[1]));
    uint32x2x2_t m = vtrn_u32(vreinterpret_u32_u16(f.val[0]), vreinterpret_u32_u16(h.val[0]));
    uint32x2x2_t n = vtrn_u32(vreinterpret_u32_u16(g.val[0]), vreinterpret_u32_u16(k.val[0]));
    uint32x2x2_t o = vtrn_u32(vreinterpret_u32_u16(f.val[1]), vreinterpret_u32_u16(h.val[1]));
    uint32x2x2_t p = vtrn_u32(vreinterpret_u32_u16(g.val[1]), vreinterpret_u32_u16(k.val[1]));
    out[0] = vreinterpret_u8_u32(m.val[0]);
    out[1] = vreinterpret_u8_u32(n.val[0]);
    out[2] = vreinterpret_u8_u32(o.val[0]);
    out[3] = vreinterpret_u8_u32(p.val[0]);
    out[4] = vreinterpret_u8_u32(m.val[1]);
    out[5] = vreinterpret_u8_u32(n.val[1]);
    out[6] = vreinterpret_u8_u32(o.val[1]);
    out[7] = vreinterpret_u8_u32(p.val[1]);
}

/* The same for 4x4 chroma pairs. */
static inline void transpose4(const uint16x4_t q[4], uint16x4_t out[4]) {
    uint16x4x2_t a = vtrn_u16(q[0], q[1]), b = vtrn_u16(q[2], q[3]);
    uint32x2x2_t c = vtrn_u32(vreinterpret_u32_u16(a.val[0]), vreinterpret_u32_u16(b.val[0]));
    uint32x2x2_t e = vtrn_u32(vreinterpret_u32_u16(a.val[1]), vreinterpret_u32_u16(b.val[1]));
    out[0] = vreinterpret_u16_u32(c.val[0]);
    out[1] = vreinterpret_u16_u32(e.val[0]);
    out[2] = vreinterpret_u16_u32(c.val[1]);
    out[3] = vreinterpret_u16_u32(e.val[1]);
}

/*
 * A 90 or 270 degree turn of a plane whose sides are multiples of 8 (luma) or 4 (chroma pairs).
 * Clockwise (90), target row x is source column x read bottom to top: the block's rows go in
 * reversed. Counter-clockwise (270), target row w-1-x is source column x read top to bottom.
 */
static void turn_luma_neon(const uint8_t *src, int w, int h, int degrees, uint8_t *dst) {
    for (int by = 0; by < h; by += 8)
        for (int bx = 0; bx < w; bx += 8) {
            uint8x8_t q[8], out[8];
            for (int r = 0; r < 8; r++) {
                const uint8_t *row = src + (size_t)(by + r) * w + bx;
                q[degrees == 90 ? 7 - r : r] = vld1_u8(row);
            }
            transpose8(q, out);
            for (int c = 0; c < 8; c++) {
                size_t at = degrees == 90 ? (size_t)(bx + c) * h + (h - 8 - by) : (size_t)(w - 1 - bx - c) * h + by;
                vst1_u8(dst + at, out[c]);
            }
        }
}

static void turn_chroma_neon(const uint8_t *src, int w, int h, int degrees, int swap, uint8_t *dst) {
    for (int by = 0; by < h; by += 4)
        for (int bx = 0; bx < w; bx += 4) {
            uint16x4_t q[4], out[4];
            for (int r = 0; r < 4; r++) {
                const uint8_t *row = src + ((size_t)(by + r) * w + bx) * 2;
                uint8x8_t pairs = vld1_u8(row);
                q[degrees == 90 ? 3 - r : r] = vreinterpret_u16_u8(swap ? vrev16_u8(pairs) : pairs);
            }
            transpose4(q, out);
            for (int c = 0; c < 4; c++) {
                size_t at = degrees == 90 ? (size_t)(bx + c) * h + (h - 4 - by) : (size_t)(w - 1 - bx - c) * h + by;
                vst1_u8(dst + at * 2, vreinterpret_u8_u16(out[c]));
            }
        }
}
#endif

/* Whether the NEON path takes this turn. */
static int turns_in_neon(int w, int h, int degrees) {
#if defined(__aarch64__)
    return degrees != 180 && w % 8 == 0 && h % 8 == 0;
#else
    (void)w; (void)h; (void)degrees;
    return 0;
#endif
}

/* An NV21/NV12 frame (w and h even) turned clockwise by degrees (90, 180, 270) into dst, its chroma's bytes traded with swap. */
static void turn_frame(const uint8_t *src, int w, int h, int degrees, int swap, uint8_t *dst) {
    const uint8_t *chroma = src + (size_t)w * h;
    uint8_t *out_chroma = dst + (size_t)w * h;
    if (turns_in_neon(w, h, degrees)) {
#if defined(__aarch64__)
        turn_luma_neon(src, w, h, degrees, dst);
        turn_chroma_neon(chroma, w / 2, h / 2, degrees, swap, out_chroma);
        return;
#endif
    }
    turn_plane(src, w, h, 1, degrees, 0, dst);
    turn_plane(chroma, w / 2, h / 2, 2, degrees, swap, out_chroma);
}

/* The chroma pairs of an NV21/NV12 frame (w and h even) with their bytes traded, in place. */
static void swap_frame(uint8_t *frame, int w, int h) {
    uint8_t *c = frame + (size_t)w * h;
    size_t n = (size_t)w * h / 2, i = 0;
#if defined(__aarch64__)
    for (; i + 16 <= n; i += 16) vst1q_u8(c + i, vrev16q_u8(vld1q_u8(c + i)));
#endif
    for (; i < n; i += 2) {
        uint8_t first = c[i];
        c[i] = c[i + 1];
        c[i + 1] = first;
    }
}

static inline uint8_t clamp(int v) { return (uint8_t)(v > 255 ? 255 : v < 0 ? 0 : v); }
static inline uint8_t luma(uint32_t p) {
    return (uint8_t)((77 * (int)(p & 0xFF) + 150 * (int)(p >> 8 & 0xFF) + 29 * (int)(p >> 16 & 0xFF) + 128) >> 8);
}

/* RGBA (R in the lowest byte of each little-endian pixel; w and h even) as NV21, or NV12 with uv_first. */
static void rgba_frame(const uint8_t *rgba, int stride, int w, int h, uint8_t *out, int uv_first) {
    uint8_t *chroma = out + (size_t)w * h;
    int v_at = uv_first ? 1 : 0, u_at = 1 - v_at;
    for (int r = 0; r < h; r += 2) {
        const uint8_t *top = rgba + (size_t)r * stride, *bottom = top + stride;
        uint8_t *yt = out + (size_t)r * w, *yb = yt + w, *c = chroma + (size_t)(r / 2) * w;
        for (int x = 0; x < w; x += 2) {
            uint32_t a, b, d, e;
            memcpy(&a, top + 4 * x, 4);
            memcpy(&b, top + 4 * x + 4, 4);
            memcpy(&d, bottom + 4 * x, 4);
            memcpy(&e, bottom + 4 * x + 4, 4);
            yt[x] = luma(a);
            yt[x + 1] = luma(b);
            yb[x] = luma(d);
            yb[x + 1] = luma(e);
            int red = (int)((a & 0xFF) + (b & 0xFF) + (d & 0xFF) + (e & 0xFF));
            int green = (int)((a >> 8 & 0xFF) + (b >> 8 & 0xFF) + (d >> 8 & 0xFF) + (e >> 8 & 0xFF));
            int blue = (int)((a >> 16 & 0xFF) + (b >> 16 & 0xFF) + (d >> 16 & 0xFF) + (e >> 16 & 0xFF));
            c[x + u_at] = clamp(((-43 * red - 85 * green + 128 * blue + 512) >> 10) + 128);
            c[x + v_at] = clamp(((128 * red - 107 * green - 21 * blue + 512) >> 10) + 128);
        }
    }
}

/* Whether w x h is a frame these take: even sides, at most 16384 each. */
static int fits(int w, int h) { return w > 0 && h > 0 && w % 2 == 0 && h % 2 == 0 && w <= 16384 && h <= 16384; }

#ifndef FDFRAME_NO_JNI
#include <jni.h>

JNIEXPORT jboolean JNICALL
Java_com_garathon_flatdroid_camera_NativeFrames_turn(JNIEnv *env, jclass cls, jbyteArray src, jint width, jint height,
                                                     jint degrees, jboolean swap, jbyteArray dst) {
    (void)cls;
    size_t bytes = (size_t)width * height * 3 / 2;
    if (!fits(width, height) || (degrees != 90 && degrees != 180 && degrees != 270)) return JNI_FALSE;
    if ((size_t)(*env)->GetArrayLength(env, src) < bytes || (size_t)(*env)->GetArrayLength(env, dst) < bytes) return JNI_FALSE;
    if ((*env)->IsSameObject(env, src, dst)) return JNI_FALSE;
    uint8_t *in = (*env)->GetPrimitiveArrayCritical(env, src, NULL);
    if (!in) return JNI_FALSE;
    uint8_t *out = (*env)->GetPrimitiveArrayCritical(env, dst, NULL);
    if (!out) { (*env)->ReleasePrimitiveArrayCritical(env, src, in, JNI_ABORT); return JNI_FALSE; }
    turn_frame(in, width, height, degrees, swap, out);
    (*env)->ReleasePrimitiveArrayCritical(env, dst, out, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, src, in, JNI_ABORT);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_garathon_flatdroid_camera_NativeFrames_swap(JNIEnv *env, jclass cls, jbyteArray frame, jint width, jint height) {
    (void)cls;
    if (!fits(width, height) || (size_t)(*env)->GetArrayLength(env, frame) < (size_t)width * height * 3 / 2) return JNI_FALSE;
    uint8_t *bytes = (*env)->GetPrimitiveArrayCritical(env, frame, NULL);
    if (!bytes) return JNI_FALSE;
    swap_frame(bytes, width, height);
    (*env)->ReleasePrimitiveArrayCritical(env, frame, bytes, 0);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_garathon_flatdroid_camera_NativeFrames_convert(JNIEnv *env, jclass cls, jobject rgba, jint rowStride, jint width,
                                                       jint height, jbyteArray dst, jboolean uvFirst) {
    (void)cls;
    if (!fits(width, height) || rowStride < width * 4) return JNI_FALSE;
    const uint8_t *pixels = (*env)->GetDirectBufferAddress(env, rgba);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, rgba);
    if (!pixels || capacity < (jlong)(height - 1) * rowStride + (jlong)width * 4) return JNI_FALSE;
    if ((size_t)(*env)->GetArrayLength(env, dst) < (size_t)width * height * 3 / 2) return JNI_FALSE;
    uint8_t *out = (*env)->GetPrimitiveArrayCritical(env, dst, NULL);
    if (!out) return JNI_FALSE;
    rgba_frame(pixels, rowStride, width, height, out, uvFirst);
    (*env)->ReleasePrimitiveArrayCritical(env, dst, out, 0);
    return JNI_TRUE;
}
#endif
