#pragma once
#include <cstdint>
#include <cstddef>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

namespace macapk::pixels {
inline uint32_t SwapRedBlue(uint32_t p) {
    return (p & 0xff00ff00u) | ((p & 0x00ff0000u) >> 16) | ((p & 0x000000ffu) << 16);
}
inline unsigned Mul255(unsigned c, unsigned a) {
    const unsigned product = c * a + 128;
    return (product + (product >> 8)) >> 8;
}
#if defined(__ARM_NEON)
inline uint8x8_t Mul255(uint8x8_t channel, uint8x8_t alpha) {
    const uint16x8_t product = vaddq_u16(vmull_u8(channel, alpha), vdupq_n_u16(128));
    return vshrn_n_u16(vaddq_u16(product, vshrq_n_u16(product, 8)), 8);
}
#endif

// Top-down straight ARGB words -> top-down GL RGBA bytes on the little-endian
// host. Unlike pbuffer readback this direction must NOT flip rows. The caller
// has validated both capacities and owns the input until this copy returns.
inline void ArgbToRgba(const uint32_t* src, uint32_t* dst, int width, int height,
                       int stride, bool premultiply) {
    for (int y = 0; y < height; ++y) {
        const uint32_t* row = src + static_cast<size_t>(y) * stride;
        uint32_t* out = dst + static_cast<size_t>(y) * width;
        int x = 0;
#if defined(__ARM_NEON)
        if (premultiply) {
            for (; x + 8 <= width; x += 8) {
                const uint8x8x4_t bgra = vld4_u8(reinterpret_cast<const uint8_t*>(row + x));
                const uint8x8_t alpha = bgra.val[3];
                const uint8x8x4_t rgba = {{Mul255(bgra.val[2], alpha),
                    Mul255(bgra.val[1], alpha), Mul255(bgra.val[0], alpha), alpha}};
                vst4_u8(reinterpret_cast<uint8_t*>(out + x), rgba);
            }
        } else {
            const uint32x4_t ag = vdupq_n_u32(0xff00ff00u);
            const uint32x4_t red = vdupq_n_u32(0x00ff0000u);
            const uint32x4_t blue = vdupq_n_u32(0x000000ffu);
            for (; x + 4 <= width; x += 4) {
                const uint32x4_t p = vld1q_u32(row + x);
                vst1q_u32(out + x, vorrq_u32(vandq_u32(p, ag),
                    vorrq_u32(vshrq_n_u32(vandq_u32(p, red), 16),
                              vshlq_n_u32(vandq_u32(p, blue), 16))));
            }
        }
#endif
        for (; x < width; ++x) {
            uint32_t p = row[x];
            const unsigned a = p >> 24;
            if (premultiply && a != 255)
                p = (a << 24) | (Mul255((p >> 16) & 255, a) << 16)
                    | (Mul255((p >> 8) & 255, a) << 8) | Mul255(p & 255, a);
            out[x] = SwapRedBlue(p);
        }
    }
}

namespace detail {
inline uint32_t UnpremulBgraWord(uint32_t pixel) {
    const uint32_t a = pixel >> 24;
    if (a == 255u) return pixel;
    if (a == 0u) return 0u;
    uint32_t r = (pixel >> 16) & 255u;
    uint32_t g = (pixel >> 8) & 255u;
    uint32_t b = pixel & 255u;
    r = (r * 255u + (a >> 1)) / a;
    g = (g * 255u + (a >> 1)) / a;
    b = (b * 255u + (a >> 1)) / a;
    return (a << 24) | (r << 16) | ((g & 255u) << 8) | b;
}

inline uint32_t UnpremulRgbaWordToArgb(uint32_t px) {
    const uint32_t a = px >> 24;
    uint32_t r = px & 0xffu;
    uint32_t b = (px >> 16) & 0xffu;
    if (a != 0xffu) {
        if (a == 0u) return 0u;
        uint32_t g = (px >> 8) & 0xffu;
        r = (r * 255u + (a >> 1)) / a;
        g = (g * 255u + (a >> 1)) / a;
        b = (b * 255u + (a >> 1)) / a;
        px = (a << 24) | (g << 8);
    }
    return (px & 0xff00ff00u) | (r << 16) | b;
}
}  // namespace detail

// Bottom-up premultiplied 32BGRA (little-endian host words 0xAARRGGBB) ->
// top-down straight ARGB words.
inline void FlipBgraPremulToArgb(const uint8_t* srcBase, size_t srcStrideBytes,
                                 uint32_t* dst, int width, int height) {
    for (int y = 0; y < height; ++y) {
        const auto* row = reinterpret_cast<const uint32_t*>(
            srcBase + static_cast<size_t>(height - 1 - y) * srcStrideBytes);
        uint32_t* out = dst + static_cast<size_t>(y) * width;
        int x = 0;
#if defined(__ARM_NEON)
        const uint32x4_t zero = vdupq_n_u32(0);
        for (; x + 16 <= width; x += 16) {
            const uint8x16x4_t px = vld4q_u8(reinterpret_cast<const uint8_t*>(row + x));
            const uint8x16_t alpha = px.val[3];
            if (vminvq_u8(alpha) == 255) {
                vst4q_u8(reinterpret_cast<uint8_t*>(out + x), px);
            } else if (vmaxvq_u8(alpha) == 0) {
                vst1q_u32(out + x + 0, zero);
                vst1q_u32(out + x + 4, zero);
                vst1q_u32(out + x + 8, zero);
                vst1q_u32(out + x + 12, zero);
            } else {
                for (int k = 0; k < 16; ++k) out[x + k] = detail::UnpremulBgraWord(row[x + k]);
            }
        }
#endif
        for (; x < width; ++x) out[x] = detail::UnpremulBgraWord(row[x]);
    }
}

// Bottom-up premultiplied GL RGBA bytes -> top-down straight ARGB words.
inline void FlipRgbaPremulToArgb(const uint32_t* src, uint32_t* dst, int width, int height) {
    for (int y = 0; y < height; ++y) {
        const uint32_t* srcRow = src + static_cast<size_t>(height - 1 - y) * width;
        uint32_t* dstRow = dst + static_cast<size_t>(y) * width;
        int x = 0;
#if defined(__ARM_NEON)
        const uint32x4_t zero = vdupq_n_u32(0);
        for (; x + 16 <= width; x += 16) {
            const uint8x16x4_t rgba = vld4q_u8(reinterpret_cast<const uint8_t*>(srcRow + x));
            const uint8x16_t alpha = rgba.val[3];
            if (vminvq_u8(alpha) == 255) {
                const uint8x16x4_t bgra = {{rgba.val[2], rgba.val[1], rgba.val[0], alpha}};
                vst4q_u8(reinterpret_cast<uint8_t*>(dstRow + x), bgra);
            } else if (vmaxvq_u8(alpha) == 0) {
                vst1q_u32(dstRow + x + 0, zero);
                vst1q_u32(dstRow + x + 4, zero);
                vst1q_u32(dstRow + x + 8, zero);
                vst1q_u32(dstRow + x + 12, zero);
            } else {
                for (int k = 0; k < 16; ++k)
                    dstRow[x + k] = detail::UnpremulRgbaWordToArgb(srcRow[x + k]);
            }
        }
#endif
        for (; x < width; ++x) dstRow[x] = detail::UnpremulRgbaWordToArgb(srcRow[x]);
    }
}
}
