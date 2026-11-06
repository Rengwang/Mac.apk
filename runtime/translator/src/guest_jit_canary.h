#pragma once
#include <cstdint>

// AArch64 LDR Xt,[Xn,#0x28], using this thread's TPIDR_EL0 as its base.
// Restrict recovery to the Android TLS stack-guard slot and to a registered
// guest-created executable range; ordinary host/JVM and unrelated faults pass on.
inline bool macapk_guest_jit_canary_load(bool guest_code, uint32_t insn,
                                        uint64_t base, uint64_t tpidr,
                                        uint64_t fault) {
    return guest_code && (insn & 0xffc00000u) == 0xf9400000u
        && ((insn >> 10) & 0xfffu) == 5 && ((insn >> 5) & 31u) != 31
        && (insn & 31u) != 31 && (tpidr >> 48) == 1
        && base == tpidr && fault == tpidr + 0x28;
}
