#include "../runtime/src/video_pixels.h"
#include <vector>
#include <random>
#include <cassert>
#include <cstdio>
int main() {
    std::mt19937 rng(12345);
    for (int w : {1, 3, 4, 5, 16, 1919, 1920}) for (int h : {1, 3, 7})
    for (int stride : {w, w + 5}) for (bool premul : {false, true}) {
        std::vector<uint32_t> input(stride * h), output(w * h + 8, 0xcafebabe);
        for (auto& p : input) p = rng();
        input[0] = 0x00112233;
        if (w > 1) input[1] = 0x80112233;
        macapk::pixels::ArgbToRgba(input.data(), output.data() + 4, w, h, stride, premul);
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
            uint32_t p = input[y * stride + x], a = p >> 24;
            unsigned r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            if (premul) { r = (r * a + 127) / 255; g = (g * a + 127) / 255; b = (b * a + 127) / 255; }
            uint32_t expected = (a << 24) | (b << 16) | (g << 8) | r;
            assert(output[4 + y * w + x] == expected);
        }
        for (int i = 0; i < 4; ++i) { assert(output[i] == 0xcafebabe); assert(output[4+w*h+i] == 0xcafebabe); }
    }
    for (unsigned c = 0; c < 256; ++c) for (unsigned a = 0; a < 256; ++a)
        assert(macapk::pixels::Mul255(c, a) == (c * a + 127) / 255);

    for (int w : {1, 3, 4, 5, 16, 17, 31, 32, 64, 1919, 1920}) for (int h : {1, 3, 7}) {
        const size_t srcStrideBytes = static_cast<size_t>(w + 5) * 4;
        std::vector<uint8_t> bgraBase(srcStrideBytes * h);
        std::vector<uint32_t> rgbaIn(static_cast<size_t>(w) * h);
        std::vector<uint32_t> bgraOut(static_cast<size_t>(w) * h + 8, 0xcafebabe);
        std::vector<uint32_t> rgbaOut(static_cast<size_t>(w) * h + 8, 0xcafebabe);
        for (int y = 0; y < h; ++y) {
            auto* row = reinterpret_cast<uint32_t*>(bgraBase.data() + static_cast<size_t>(y) * srcStrideBytes);
            for (int x = 0; x < w; ++x) {
                uint32_t alpha = (y == 0) ? 0xffu : (y == 1 ? 0x00u : static_cast<uint32_t>((rng() % 256)));
                uint32_t r = alpha == 0 ? (rng() & 0xffu) : (rng() % (alpha + 1));
                uint32_t g = alpha == 0 ? (rng() & 0xffu) : (rng() % (alpha + 1));
                uint32_t b = alpha == 0 ? (rng() & 0xffu) : (rng() % (alpha + 1));
                row[x] = (alpha << 24) | (r << 16) | (g << 8) | b;
                rgbaIn[static_cast<size_t>(y) * w + x] = (alpha << 24) | (b << 16) | (g << 8) | r;
            }
        }
        macapk::pixels::FlipBgraPremulToArgb(bgraBase.data(), srcStrideBytes, bgraOut.data() + 4, w, h);
        macapk::pixels::FlipRgbaPremulToArgb(rgbaIn.data(), rgbaOut.data() + 4, w, h);
        for (int y = 0; y < h; ++y) {
            const auto* bgraRow = reinterpret_cast<const uint32_t*>(
                bgraBase.data() + static_cast<size_t>(h - 1 - y) * srcStrideBytes);
            const uint32_t* rgbaRow = rgbaIn.data() + static_cast<size_t>(h - 1 - y) * w;
            for (int x = 0; x < w; ++x) {
                const uint32_t bp = bgraRow[x];
                const uint32_t ba = bp >> 24;
                uint32_t expectedBgra;
                if (ba == 255u) expectedBgra = bp;
                else if (ba == 0u) expectedBgra = 0u;
                else {
                    uint32_t r = (((bp >> 16) & 255u) * 255u + (ba >> 1)) / ba;
                    uint32_t g = (((bp >> 8) & 255u) * 255u + (ba >> 1)) / ba;
                    uint32_t b = ((bp & 255u) * 255u + (ba >> 1)) / ba;
                    expectedBgra = (ba << 24) | (r << 16) | ((g & 255u) << 8) | b;
                }
                assert(bgraOut[4 + static_cast<size_t>(y) * w + x] == expectedBgra);

                const uint32_t rp = rgbaRow[x];
                const uint32_t ra = rp >> 24;
                uint32_t rr = rp & 255u, rg = (rp >> 8) & 255u, rb = (rp >> 16) & 255u;
                uint32_t expectedRgba;
                if (ra == 0u) expectedRgba = 0u;
                else {
                    if (ra != 255u) {
                        rr = (rr * 255u + (ra >> 1)) / ra;
                        rg = (rg * 255u + (ra >> 1)) / ra;
                        rb = (rb * 255u + (ra >> 1)) / ra;
                    }
                    expectedRgba = (ra << 24) | (rr << 16) | (rg << 8) | rb;
                }
                assert(rgbaOut[4 + static_cast<size_t>(y) * w + x] == expectedRgba);
            }
        }
        for (int i = 0; i < 4; ++i) {
            assert(bgraOut[i] == 0xcafebabe && bgraOut[4 + static_cast<size_t>(w) * h + i] == 0xcafebabe);
            assert(rgbaOut[i] == 0xcafebabe && rgbaOut[4 + static_cast<size_t>(w) * h + i] == 0xcafebabe);
        }
    }
    std::puts("PASS: SIMD/scalar color conversion, alpha rounding, stride, odd widths, row order, buffer guards");
}
