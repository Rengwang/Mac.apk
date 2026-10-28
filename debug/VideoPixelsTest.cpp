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
    std::puts("PASS: SIMD/scalar color conversion, alpha rounding, stride, odd widths, row order, buffer guards");
}
