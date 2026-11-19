#pragma once

#include <cstdint>

// Returns which narrow instruction shape makes it safe to resume a guest whose
// PC and LR were both changed by exactly +4 GiB. Every accepted shape overwrites
// x30 before any indirect branch or x30 read, so the corrupt saved return address
// cannot escape. Callers must pass a 4-word array insn[0..3].
enum class MacapkReturnAliasShape {
    None = 0,
    RestoreLr = 1,
    AdrpAddBl = 2,
    SetupBl = 3,
};

static inline bool macapk_is_bl_insn(uint32_t w) {
    return (w & 0xFC000000u) == 0x94000000u;
}

static inline bool macapk_is_restore_lr_insn(uint32_t w) {
    const uint32_t rt = w & 0x1Fu;
    const uint32_t rn = (w >> 5) & 0x1Fu;
    // ldr x30, [sp], #simm9 (post/pre/unscaled from sp)
    if ((w & 0xFFC00000u) == 0xF8400000u && rt == 30u && rn == 31u) {
        return true;
    }
    // ldr x30, [sp, #uimm12] (unsigned offset from sp)
    if ((w & 0xFFC003FFu) == 0xF94003FEu) {
        return true;
    }
    // 64-bit ldp/ldnp from sp restoring x30 in exactly one lane
    if ((w & 0xFE400000u) == 0xA8400000u && rn == 31u) {
        const uint32_t rt2 = (w >> 10) & 0x1Fu;
        return (rt == 30u) != (rt2 == 30u);
    }
    return false;
}

static inline bool macapk_is_pure_setup_insn(uint32_t w) {
    const uint32_t rd = w & 0x1Fu;
    if (rd == 30u || rd == 31u) return false;  // never mutate x30 or sp/xzr

    // add/sub (immediate, non-flag, 32/64-bit): rn == 31 is SP (e.g. mov x0, sp),
    // while rn == 30 would read the corrupt x30.
    if ((w & 0x3F800000u) == 0x11000000u) {
        const uint32_t rn = (w >> 5) & 0x1Fu;
        return rn != 30u;
    }

    // orr (shifted register, non-flag, 32/64-bit), including mov Xd, Xm (rn == 31 == XZR):
    // rn/rm == 31 is XZR/WZR (safe), while rn/rm == 30 reads x30 (forbidden).
    // Reject unallocated 32-bit shifts >= 32 (sf == 0 && imm6[5] == 1).
    if ((w & 0x7F200000u) == 0x2A000000u) {
        if ((w & 0x80008000u) == 0x00008000u) return false;
        const uint32_t rn = (w >> 5) & 0x1Fu;
        const uint32_t rm = (w >> 16) & 0x1Fu;
        return rn != 30u && rm != 30u;
    }

    // movn/movz/movk (move wide immediate, 32/64-bit; opc == 01 and 32-bit hw >= 2 unallocated)
    if ((w & 0x1F800000u) == 0x12800000u) {
        const uint32_t opc = (w >> 29) & 0x3u;
        if (opc == 1u) return false;
        if ((w & 0x80400000u) == 0x00400000u) return false;
        return true;
    }

    // adr/adrp (PC-relative address, 64-bit)
    if ((w & 0x1F000000u) == 0x10000000u) {
        return true;
    }

    return false;
}

static inline MacapkReturnAliasShape macapk_return_alias_shape(
    const uint32_t* insn) {
    if (!insn) return MacapkReturnAliasShape::None;

    if (macapk_is_restore_lr_insn(insn[0])) {
        return MacapkReturnAliasShape::RestoreLr;
    }

    // adrp Xd, page; add Xd, Xd, #imm; bl target
    const uint32_t rd = insn[0] & 0x1Fu;
    if (rd != 30u && rd != 31u &&
        (insn[0] & 0x9F000000u) == 0x90000000u &&
        (insn[1] & 0xFF800000u) == 0x91000000u &&
        (insn[1] & 0x1Fu) == rd && ((insn[1] >> 5) & 0x1Fu) == rd &&
        macapk_is_bl_insn(insn[2])) {
        return MacapkReturnAliasShape::AdrpAddBl;
    }

    // 0..3 straight-line pure setup instructions followed immediately by direct bl
    for (int k = 0; k <= 3; ++k) {
        if (macapk_is_bl_insn(insn[k])) {
            return MacapkReturnAliasShape::SetupBl;
        }
        if (k == 3 || !macapk_is_pure_setup_insn(insn[k])) {
            return MacapkReturnAliasShape::None;
        }
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
