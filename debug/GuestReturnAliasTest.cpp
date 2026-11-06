#include "../runtime/translator/src/guest_return_alias.h"

#include <cassert>
#include <cstdio>

int main() {
    const uint32_t restore_lr[] = {0xF84107FEu, 0xD65F03C0u, 0};  // ldr x30,[sp],#16; ret
    const uint32_t adrp_add_bl[] = {0xB0FFF5A3u, 0x91389863u, 0x97FF9747u};
    assert(macapk_return_alias_shape(restore_lr) == MacapkReturnAliasShape::RestoreLr);
    assert(macapk_return_alias_shape(adrp_add_bl) == MacapkReturnAliasShape::AdrpAddBl);

    const uint32_t wrong_load_register[] = {0xF84107E0u, 0xD65F03C0u, 0};
    const uint32_t wrong_base_register[] = {0xF84107DEu, 0xD65F03C0u, 0};
    const uint32_t add_other_destination[] = {0xB0FFF5A3u, 0x91389844u, 0x97FF9747u};
    const uint32_t no_call[] = {0xB0FFF5A3u, 0x91389863u, 0xD65F03C0u};
    assert(macapk_return_alias_shape(wrong_load_register) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(wrong_base_register) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(add_other_destination) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(no_call) == MacapkReturnAliasShape::None);
    assert(macapk_return_alias_shape(nullptr) == MacapkReturnAliasShape::None);

    constexpr uintptr_t kBad = 0x100000000ULL + 0x12345678ULL;
    uintptr_t corrected = 0;
    MacapkReturnAliasShape shape = MacapkReturnAliasShape::None;
    assert(macapk_return_alias_target(kBad, kBad, kBad, restore_lr,
                                      &corrected, &shape));
    assert(corrected == 0x12345678ULL && shape == MacapkReturnAliasShape::RestoreLr);
    assert(macapk_return_alias_target(kBad, kBad, kBad, adrp_add_bl,
                                      &corrected, &shape));
    assert(corrected == 0x12345678ULL && shape == MacapkReturnAliasShape::AdrpAddBl);
    assert(!macapk_return_alias_target(kBad, kBad + 4, kBad, restore_lr,
                                       &corrected, &shape));
    assert(!macapk_return_alias_target(kBad, kBad, kBad + 4, restore_lr,
                                       &corrected, &shape));
    assert(!macapk_return_alias_target(0x12345678ULL, 0x12345678ULL,
                                       0x12345678ULL, restore_lr,
                                       &corrected, &shape));
    assert(!macapk_return_alias_target(kBad, kBad, kBad, wrong_load_register,
                                       &corrected, &shape));
    assert(!macapk_return_alias_target(kBad, kBad, kBad, nullptr,
                                       &corrected, &shape));

    std::puts("4 GiB return-alias address gate and shape classification PASS");
}
