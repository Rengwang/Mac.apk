#include "../runtime/translator/src/guest_return_alias.h"

#include <cassert>
#include <cstdio>

int main() {
    const uint32_t restore_lr[4] = {0xF84107FEu, 0xD65F03C0u, 0, 0};  // ldr x30,[sp],#16; ret
    const uint32_t restore_lr_uimm[4] = {0xF94023FEu, 0xD65F03C0u, 0, 0};  // ldr x30,[sp,#0x40]; ret
    const uint32_t restore_ldp_off[4] = {0xA94457FEu, 0xD65F03C0u, 0, 0};  // ldp x30,x21,[sp,#0x40]; ret
    const uint32_t restore_ldp_post[4] = {0xA8C17BFDu, 0xD65F03C0u, 0, 0};  // ldp x29,x30,[sp],#0x10; ret
    const uint32_t restore_ldp_pre[4] = {0xA9FF7BFDu, 0xD65F03C0u, 0, 0};  // ldp x29,x30,[sp,#-0x10]!; ret
    const uint32_t adrp_add_bl[4] = {0xB0FFF5A3u, 0x91389863u, 0x97FF9747u, 0};
    assert(macapk_return_alias_shape(restore_lr) == MacapkReturnAliasShape::RestoreLr);
    assert(macapk_return_alias_shape(restore_lr_uimm) == MacapkReturnAliasShape::RestoreLr);
    assert(macapk_return_alias_shape(restore_ldp_off) == MacapkReturnAliasShape::RestoreLr);
    assert(macapk_return_alias_shape(restore_ldp_post) == MacapkReturnAliasShape::RestoreLr);
    assert(macapk_return_alias_shape(restore_ldp_pre) == MacapkReturnAliasShape::RestoreLr);
    assert(macapk_return_alias_shape(adrp_add_bl) == MacapkReturnAliasShape::AdrpAddBl);

    // Exact libchronos.so+0x1bd76c portrait-fullscreen sequence:
    // mov x0, sp; bl 0x1b8508; mov x8, #0; ldr x9, [sp, #0x38]
    const uint32_t chronos_setup_bl[4] = {0x910003E0u, 0x97FFEB66u, 0xD2800008u, 0xF9401FE9u};
    const uint32_t bare_bl[4] = {0x94000080u, 0, 0, 0};
    const uint32_t two_setup_bl[4] = {0xAA1303E0u, 0x528000E1u, 0x94000040u, 0};  // mov x0,x19; mov w1,#7; bl
    const uint32_t three_setup_bl[4] = {0xB0000002u, 0xF2A24688u, 0xD1004020u, 0x94000040u};  // adrp x2; movk x8; sub x0,x1,#0x10; bl
    const uint32_t add_other_destination[4] = {0xB0FFF5A3u, 0x91389844u, 0x97FF9747u, 0};  // adrp x3; add x4,x2,#imm; bl (SetupBl, not AdrpAddBl)
    assert(macapk_return_alias_shape(chronos_setup_bl) == MacapkReturnAliasShape::SetupBl);
    assert(macapk_return_alias_shape(bare_bl) == MacapkReturnAliasShape::SetupBl);
    assert(macapk_return_alias_shape(two_setup_bl) == MacapkReturnAliasShape::SetupBl);
    assert(macapk_return_alias_shape(three_setup_bl) == MacapkReturnAliasShape::SetupBl);
    assert(macapk_return_alias_shape(add_other_destination) == MacapkReturnAliasShape::SetupBl);

    const uint32_t wrong_load_register[4] = {0xF84107E0u, 0xD65F03C0u, 0, 0};
    const uint32_t wrong_base_register[4] = {0xF84107DEu, 0xD65F03C0u, 0, 0};
    const uint32_t store_pair[4] = {0xA90457FEu, 0xD65F03C0u, 0, 0};  // stp x30,x21,[sp,#0x40]
    const uint32_t ldpsw_pair[4] = {0x694857FEu, 0xD65F03C0u, 0, 0};  // ldpsw x30,x21,[sp,#0x40]
    const uint32_t simd_ldp[4] = {0xAD4257FEu, 0xD65F03C0u, 0, 0};    // ldp q30,q21,[sp,#0x40]
    const uint32_t same_lane_ldp[4] = {0xA9447BFEu, 0xD65F03C0u, 0, 0};  // ldp x30,x30,[sp,#0x40]
    const uint32_t no_call[4] = {0xB0FFF5A3u, 0x91389863u, 0xD65F03C0u, 0};
    const uint32_t adrp_x30_bl[4] = {0xB000001Eu, 0x910043DEu, 0x94000040u, 0};  // adrp x30; add x30,x30,#0x10; bl
    const uint32_t reads_x30_mov[4] = {0xAA1E03E0u, 0x94000040u, 0, 0};  // mov x0, x30; bl
    const uint32_t reads_x30_add[4] = {0x910023C0u, 0x94000040u, 0, 0};  // add x0, x30, #8; bl
    const uint32_t reads_x30_orr[4] = {0xAA0103C0u, 0x94000040u, 0, 0};  // orr x0, x30, x1; bl
    const uint32_t mutates_sp[4] = {0x910043FFu, 0x94000040u, 0, 0};     // add sp, sp, #0x10; bl
    const uint32_t writes_x30[4] = {0x910003FEu, 0x94000040u, 0, 0};     // mov x30, sp; bl
    const uint32_t loads_mem_before_bl[4] = {0xF94003E0u, 0x94000040u, 0, 0};  // ldr x0, [sp]; bl
    const uint32_t indirect_blr[4] = {0x910003E0u, 0xD63F0100u, 0, 0};   // mov x0, sp; blr x8
    const uint32_t four_setups[4] = {0xD2800020u, 0xD2800041u, 0xD2800062u, 0xD2800083u};  // 4 setups before bl
    const uint32_t flag_adds[4] = {0xB1000420u, 0x94000040u, 0, 0};      // adds x0, x1, #1; bl
    assert(macapk_return_alias_shape(wrong_load_register) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(wrong_base_register) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(store_pair) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(ldpsw_pair) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(simd_ldp) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(same_lane_ldp) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(no_call) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(adrp_x30_bl) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(reads_x30_mov) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(reads_x30_add) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(reads_x30_orr) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(mutates_sp) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(writes_x30) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(loads_mem_before_bl) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(indirect_blr) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(four_setups) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(flag_adds) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(nullptr) == MacapkReturnAliasShape::None);

    constexpr uintptr_t kBad = 0x100000000ULL + 0x12345678ULL;
    uintptr_t corrected = 0;
    MacapkReturnAliasShape shape = MacapkReturnAliasShape::None;
    assert(macapk_return_alias_target(kBad, kBad, kBad, restore_lr,
                                      &corrected, &shape));
    assert(corrected == 0x12345678ULL && shape == MacapkReturnAliasShape::RestoreLr);
    assert(macapk_return_alias_target(kBad, kBad, kBad, restore_ldp_off,
                                      &corrected, &shape));
    assert(corrected == 0x12345678ULL && shape == MacapkReturnAliasShape::RestoreLr);
    assert(macapk_return_alias_target(kBad, kBad, kBad, adrp_add_bl,
                                      &corrected, &shape));
    assert(corrected == 0x12345678ULL && shape == MacapkReturnAliasShape::AdrpAddBl);
    assert(macapk_return_alias_target(kBad, kBad, kBad, chronos_setup_bl,
                                      &corrected, &shape));
    assert(corrected == 0x12345678ULL && shape == MacapkReturnAliasShape::SetupBl);
    assert(!macapk_return_alias_target(kBad, kBad + 4, kBad, restore_lr,
                                       &corrected, &shape));
    assert(!macapk_return_alias_target(kBad, kBad, kBad + 4, restore_lr,
                                       &corrected, &shape));
    assert(!macapk_return_alias_target(0x12345678ULL, 0x12345678ULL,
                                       0x12345678ULL, restore_lr,
                                       &corrected, &shape));
    assert(!macapk_return_alias_target(kBad, kBad, kBad, wrong_load_register,
                                       &corrected, &shape));
    assert(!macapk_return_alias_target(kBad, kBad, kBad, reads_x30_mov,
                                       &corrected, &shape));
    assert(!macapk_return_alias_target(kBad, kBad, kBad, nullptr,
                                       &corrected, &shape));

    std::puts("4 GiB return-alias address gate and shape classification PASS");
}
