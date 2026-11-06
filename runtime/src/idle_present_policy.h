#pragma once

namespace macapk::idle_present {

enum class WakeReason {
    kGateDisabled,
    kContinuous,
    kDecoupled,
    kNativeEgl,
    kInitialOrResize,
    kUiRequest,
    kSurfaceFrame,
    kHeartbeat,
    kIdle,
};

struct Inputs {
    bool gate_enabled;
    bool continuous_display;
    bool decoupled_present;
    bool native_egl_live;
    bool initial_or_resize;
    bool ui_request;
    bool surface_frame;
    bool heartbeat_due;
};

struct Decision {
    bool run;
    WakeReason reason;
};

// Pure decision used by drawInMTKView. A skipped tick must retain the last
// composited contents; this gate only avoids producing another identical one.
inline Decision Decide(const Inputs& in) {
    if (!in.gate_enabled) return {true, WakeReason::kGateDisabled};
    if (in.continuous_display) return {true, WakeReason::kContinuous};
    if (in.decoupled_present) return {true, WakeReason::kDecoupled};
    if (in.native_egl_live) return {true, WakeReason::kNativeEgl};
    if (in.initial_or_resize) return {true, WakeReason::kInitialOrResize};
    if (in.ui_request) return {true, WakeReason::kUiRequest};
    if (in.surface_frame) return {true, WakeReason::kSurfaceFrame};
    if (in.heartbeat_due) return {true, WakeReason::kHeartbeat};
    return {false, WakeReason::kIdle};
}

inline const char* Name(WakeReason reason) {
    switch (reason) {
        case WakeReason::kGateDisabled: return "disabled";
        case WakeReason::kContinuous: return "continuous";
        case WakeReason::kDecoupled: return "decoupled";
        case WakeReason::kNativeEgl: return "native-egl";
        case WakeReason::kInitialOrResize: return "initial-resize";
        case WakeReason::kUiRequest: return "ui";
        case WakeReason::kSurfaceFrame: return "surface";
        case WakeReason::kHeartbeat: return "heartbeat";
        case WakeReason::kIdle: return "idle";
    }
    return "unknown";
}

}  // namespace macapk::idle_present
