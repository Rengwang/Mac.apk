#pragma once

#include <cstdint>

// Returns which narrow instruction shape makes it safe to resume a guest whose
// PC and LR were both changed by exactly +4 GiB. Both accepted shapes overwrite
// x30 before any indirect branch, so the corrupt saved return address is dead.
enum class MacapkReturnAliasShape {
    None = 0,
    RestoreLr = 1,
    AdrpAddBl = 2,
};

static inline MacapkReturnAliasShape macapk_return_alias_shape(
    const uint32_t* insn) {
    if (!insn) return MacapkReturnAliasShape::None;

    // ldr x30, [sp], #imm
    if ((insn[0] & 0xFFC00000u) == 0xF8400000u &&
        (insn[0] & 0x1Fu) == 30u && ((insn[0] >> 5) & 0x1Fu) == 31u) {
        return MacapkReturnAliasShape::RestoreLr;
    }

    // adrp Xd, page; add Xd, Xd, #imm; bl target
    const uint32_t rd = insn[0] & 0x1Fu;
    if ((insn[0] & 0x9F000000u) == 0x90000000u &&
        (insn[1] & 0xFF800000u) == 0x91000000u &&
        (insn[1] & 0x1Fu) == rd && ((insn[1] >> 5) & 0x1Fu) == rd &&
        (insn[2] & 0xFC000000u) == 0x94000000u) {
        return MacapkReturnAliasShape::AdrpAddBl;
    }
    return MacapkReturnAliasShape::None;
}

// Full address gate for the opt-in recovery. Image ownership is checked by the
// caller because the signal path must use the loader's fixed, async-safe mirror.
static inline bool macapk_return_alias_target(
    uintptr_t pc, uintptr_t lr, uintptr_t fault_address,
    const uint32_t* insn, uintptr_t* corrected_out,
    MacapkReturnAliasShape* shape_out) {
    constexpr uintptr_t kFourGiB = 0x100000000ULL;
    if (!insn || pc != lr || pc != fault_address || pc < kFourGiB) return false;
    const MacapkReturnAliasShape shape = macapk_return_alias_shape(insn);
    if (shape == MacapkReturnAliasShape::None) return false;
    if (corrected_out) *corrected_out = pc - kFourGiB;
    if (shape_out) *shape_out = shape;
    return true;
}
