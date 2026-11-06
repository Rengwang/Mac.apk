#include "../runtime/translator/src/guest_jit_canary.h"
#include <cassert>
#include <cstdio>
int main() {
    constexpr uint32_t ldr_x8_x20_28 = 0xf9401688u;
    constexpr uint64_t tp = 0x1000000001006ull;
    assert(macapk_guest_jit_canary_load(true, ldr_x8_x20_28, tp, tp, tp + 0x28));
    assert(!macapk_guest_jit_canary_load(false, ldr_x8_x20_28, tp, tp, tp + 0x28));
    assert(!macapk_guest_jit_canary_load(true, 0xf9001688u, tp, tp, tp + 0x28));
    assert(!macapk_guest_jit_canary_load(true, 0xf9401288u, tp, tp, tp + 0x28));
    assert(!macapk_guest_jit_canary_load(true, ldr_x8_x20_28, tp + 1, tp, tp + 0x28));
    assert(!macapk_guest_jit_canary_load(true, ldr_x8_x20_28, tp, tp, tp + 0x29));
    assert(!macapk_guest_jit_canary_load(true, ldr_x8_x20_28, 0x1006, 0x1006, 0x102e));
    std::puts("guest JIT canary classification PASS");
}
