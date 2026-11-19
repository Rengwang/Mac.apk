#pragma once
#include <cstdint>

// AArch64 LDR Xt,[Xn,#0x28], using a thread's TPIDR_EL0 snapshot as its base.
// XNU stores per-CPU/cluster bits in TPIDR_EL0[23:0], while arm64_native sets
// bit 48 (0x1000000000000). A thread that migrates cores/clusters between the
// prologue `mrs TPIDR_EL0` and epilogue `ldr [Xn,#0x28]` keeps the same upper
// invariant bits (`~0xffffffULL`) even though low CPU/cluster bits changed.
// Restrict recovery to the Android TLS stack-guard slot and to a registered
// guest-created executable range; ordinary host/JVM and unrelated faults pass on.
inline bool macapk_guest_jit_canary_load(bool guest_code, uint32_t insn,
                                        uint64_t base, uint64_t tpidr,
                                        uint64_t fault) {
    constexpr uint64_t kTpidrInvariantMask = ~0xffffffULL;
    return guest_code && (insn & 0xffc00000u) == 0xf9400000u
        && ((insn >> 10) & 0xfffu) == 5 && ((insn >> 5) & 31u) != 31
        && (insn & 31u) != 31 && (tpidr >> 48) == 1
        && (base & kTpidrInvariantMask) == (tpidr & kTpidrInvariantMask)
        && fault == base + 0x28;
}
