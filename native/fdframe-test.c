/*
 * Checks fdframe.c's turn and RGBA conversion (built in, without JNI): small frames with known
 * results, then random frames of many sizes against a plain per-pixel turn, every angle. On
 * arm64 that covers the NEON path, elsewhere the tiled one. test-fdframe.sh builds and runs it.
 *
 *   fdframe-test
 */
#define FDFRAME_NO_JNI
#include "fdframe.c"

#include <stdio.h>
#include <stdlib.h>

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { printf("FAIL " __VA_ARGS__); printf("\n"); failures++; } } while (0)

/* The turn sample by sample, as the definition reads: (x, y) to its place in the turned plane. */
static void reference(const uint8_t *src, int w, int h, int degrees, uint8_t *dst) {
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) dst[target(x, y, w, h, degrees)] = src[(size_t)y * w + x];
    const uint8_t *c = src + (size_t)w * h;
    uint8_t *oc = dst + (size_t)w * h;
    int cw = w / 2, ch = h / 2;
    for (int y = 0; y < ch; y++)
        for (int x = 0; x < cw; x++) {
            size_t to = target(x, y, cw, ch, degrees) * 2, from = ((size_t)y * cw + x) * 2;
            oc[to] = c[from];
            oc[to + 1] = c[from + 1];
        }
}

static void known_turns(void) {
    /* 4x2: Y rows 1 2 3 4 / 5 6 7 8, one chroma row (V,U) (10,20) (30,40). */
    const uint8_t src[] = {1, 2, 3, 4, 5, 6, 7, 8, 10, 20, 30, 40};
    const uint8_t r90[] = {5, 1, 6, 2, 7, 3, 8, 4, 10, 20, 30, 40};
    const uint8_t r180[] = {8, 7, 6, 5, 4, 3, 2, 1, 30, 40, 10, 20};
    const uint8_t r270[] = {4, 8, 3, 7, 2, 6, 1, 5, 30, 40, 10, 20};
    uint8_t out[12];
    turn_frame(src, 4, 2, 90, 0, out);
    CHECK(!memcmp(out, r90, 12), "4x2 by 90");
    turn_frame(src, 4, 2, 180, 0, out);
    CHECK(!memcmp(out, r180, 12), "4x2 by 180");
    turn_frame(src, 4, 2, 270, 0, out);
    CHECK(!memcmp(out, r270, 12), "4x2 by 270");
    /* Swapped: (U,V) pairs come out. */
    const uint8_t r90s[] = {5, 1, 6, 2, 7, 3, 8, 4, 20, 10, 40, 30};
    turn_frame(src, 4, 2, 90, 1, out);
    CHECK(!memcmp(out, r90s, 12), "4x2 by 90, chroma swapped");
    uint8_t frame[12];
    memcpy(frame, src, 12);
    swap_frame(frame, 4, 2);
    const uint8_t swapped[] = {1, 2, 3, 4, 5, 6, 7, 8, 20, 10, 40, 30};
    CHECK(!memcmp(frame, swapped, 12), "4x2 swapped in place");
}

/* want's chroma pairs with their bytes traded. */
static void swap_reference(uint8_t *frame, int w, int h) {
    for (size_t i = (size_t)w * h; i < (size_t)w * h * 3 / 2; i += 2) {
        uint8_t t = frame[i];
        frame[i] = frame[i + 1];
        frame[i + 1] = t;
    }
}

static void random_turns(void) {
    const int sizes[][2] = {{8, 8}, {16, 8}, {8, 16}, {24, 40}, {64, 48}, {176, 144}, {640, 480}, {1280, 720}, {720, 1440},
                            {1920, 1080}, {2, 2}, {6, 4}, {30, 10}, {1278, 722}, {642, 482}};
    const int angles[] = {90, 180, 270};
    unsigned seed = 1;
    for (size_t s = 0; s < sizeof sizes / sizeof *sizes; s++) {
        int w = sizes[s][0], h = sizes[s][1];
        size_t n = (size_t)w * h * 3 / 2;
        uint8_t *src = malloc(n), *want = malloc(n), *got = malloc(n);
        for (size_t i = 0; i < n; i++) { seed = seed * 1103515245 + 12345; src[i] = (uint8_t)(seed >> 16); }
        for (int a = 0; a < 3; a++) {
            reference(src, w, h, angles[a], want);
            memset(got, 0xAA, n);
            turn_frame(src, w, h, angles[a], 0, got);
            CHECK(!memcmp(want, got, n), "%dx%d by %d (%s)", w, h, angles[a], turns_in_neon(w, h, angles[a]) ? "NEON" : "tiles");
            swap_reference(want, w, h);
            memset(got, 0xAA, n);
            turn_frame(src, w, h, angles[a], 1, got);
            CHECK(!memcmp(want, got, n), "%dx%d by %d, chroma swapped", w, h, angles[a]);
        }
        memcpy(want, src, n);
        swap_reference(want, w, h);
        memcpy(got, src, n);
        swap_frame(got, w, h);
        CHECK(!memcmp(want, got, n), "%dx%d swapped in place", w, h);
        free(src);
        free(want);
        free(got);
    }
}

static void known_rgba(void) {
    /* 4x2 RGBA, rows 20 bytes apart (4 bytes of padding): a white 2x2 block, then a red one. */
    uint8_t rgba[40] = {0};
    for (int r = 0; r < 2; r++)
        for (int x = 0; x < 4; x++) {
            uint8_t *p = rgba + r * 20 + x * 4;
            p[0] = 255;
            p[1] = p[2] = x < 2 ? 255 : 0;
            p[3] = 255;
        }
    const uint8_t nv21[] = {255, 255, 77, 77, 255, 255, 77, 77, 128, 128, 255, 85};
    const uint8_t nv12[] = {255, 255, 77, 77, 255, 255, 77, 77, 128, 128, 85, 255};
    uint8_t out[12];
    rgba_frame(rgba, 20, 4, 2, out, 0);
    CHECK(!memcmp(out, nv21, 12), "RGBA to NV21");
    rgba_frame(rgba, 20, 4, 2, out, 1);
    CHECK(!memcmp(out, nv12, 12), "RGBA to NV12");
}

static void sizes(void) {
    CHECK(fits(1280, 720), "1280x720 fits");
    CHECK(!fits(1279, 720) && !fits(0, 720) && !fits(1280, -2) && !fits(32768, 2), "odd, empty and huge sizes refused");
}

int main(void) {
    known_turns();
    random_turns();
    known_rgba();
    sizes();
#if defined(__aarch64__)
    printf("arm64: NEON turns checked\n");
#endif
    printf(failures ? "%d failed\n" : "all passed\n", failures);
    return failures != 0;
}
