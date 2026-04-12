#include <stdint.h>
#include <string.h>
#include <arm_neon.h>

/*
 * C replacements for pixman NEON assembly functions.
 * The original .S files use GAS macros incompatible with Clang's
 * integrated assembler on newer NDKs / macOS toolchains.
 */

void pixman_composite_src_n_8888_asm_neon(int32_t w, int32_t h,
                                          uint32_t *dst,
                                          int32_t dst_stride,
                                          uint32_t src) {
    for (int32_t y = 0; y < h; y++) {
        int32_t x = 0;
        uint32x4_t vsrc = vdupq_n_u32(src);
        for (; x <= w - 4; x += 4) {
            vst1q_u32(dst + x, vsrc);
        }
        for (; x < w; x++) {
            dst[x] = src;
        }
        dst += dst_stride;
    }
}

void pixman_composite_over_n_8888_asm_neon(int32_t w, int32_t h,
                                           uint32_t *dst,
                                           int32_t dst_stride,
                                           uint32_t src) {
    uint32_t sa = src >> 24;
    if (sa == 0) return;
    if (sa == 255 && (src & 0x00FFFFFF) == 0x00FFFFFF) {
        pixman_composite_src_n_8888_asm_neon(w, h, dst, dst_stride, src);
        return;
    }

    uint8x8_t vsrc = vreinterpret_u8_u32(vdup_n_u32(src));
    uint8x8_t valpha = vdup_n_u8(~(src >> 24));

    for (int32_t y = 0; y < h; y++) {
        int32_t x = 0;
        for (; x <= w - 2; x += 2) {
            uint8x8_t vdst = vreinterpret_u8_u32(vld1_u32(dst + x));
            uint16x8_t tmp = vmull_u8(vdst, valpha);
            vdst = vshrn_n_u16(tmp, 8);
            vdst = vadd_u8(vdst, vsrc);
            vst1_u32(dst + x, vreinterpret_u32_u8(vdst));
        }
        for (; x < w; x++) {
            uint32_t d = dst[x];
            uint32_t ia = ~(src >> 24) & 0xFF;
            uint32_t rb = ((d & 0x00FF00FF) * ia) >> 8;
            uint32_t ag = ((d >> 8) & 0x00FF00FF) * ia >> 8;
            dst[x] = src + ((rb & 0x00FF00FF) | ((ag & 0x00FF00FF) << 8));
        }
        dst += dst_stride;
    }
}
