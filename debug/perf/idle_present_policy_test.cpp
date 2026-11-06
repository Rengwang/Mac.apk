#include "idle_present_policy.h"

#include <cstdio>

using macapk::idle_present::Decide;
using macapk::idle_present::Inputs;
using macapk::idle_present::WakeReason;

static int failures = 0;

static void expect(bool condition, const char* message) {
    if (condition) return;
    std::fprintf(stderr, "FAIL: %s\n", message);
    ++failures;
}

static Inputs enabled_idle() {
    return Inputs{true, false, false, false, false, false, false, false};
}

int main() {
    auto d = Decide(Inputs{false, false, false, false, false, false, false, false});
    expect(d.run && d.reason == WakeReason::kGateDisabled,
           "disabled gate preserves the historical continuous path");

    Inputs continuous = enabled_idle();
    continuous.continuous_display = true;
    d = Decide(continuous);
    expect(d.run && d.reason == WakeReason::kContinuous,
           "GL/native callbacks that request continuous display always run");

    Inputs decoupled = enabled_idle();
    decoupled.decoupled_present = true;
    d = Decide(decoupled);
    expect(d.run && d.reason == WakeReason::kDecoupled,
           "decoupled presents keep their consumer tick");

    Inputs native_egl = enabled_idle();
    native_egl.native_egl_live = true;
    d = Decide(native_egl);
    expect(d.run && d.reason == WakeReason::kNativeEgl,
           "live native EGL keeps its historical callback cadence");

    Inputs initial = enabled_idle();
    initial.initial_or_resize = true;
    d = Decide(initial);
    expect(d.run && d.reason == WakeReason::kInitialOrResize,
           "first frame and resize always run");

    Inputs ui = enabled_idle();
    ui.ui_request = true;
    d = Decide(ui);
    expect(d.run && d.reason == WakeReason::kUiRequest,
           "a scheduled UI traversal wakes the Canvas path");

    Inputs surface = enabled_idle();
    surface.surface_frame = true;
    d = Decide(surface);
    expect(d.run && d.reason == WakeReason::kSurfaceFrame,
           "a new Surface frame wakes the Canvas path");

    Inputs heartbeat = enabled_idle();
    heartbeat.heartbeat_due = true;
    d = Decide(heartbeat);
    expect(d.run && d.reason == WakeReason::kHeartbeat,
           "the low-frequency safety heartbeat always runs");

    d = Decide(enabled_idle());
    expect(!d.run && d.reason == WakeReason::kIdle,
           "a clean Canvas window skips identical present work");

    Inputs both = enabled_idle();
    both.ui_request = true;
    both.surface_frame = true;
    d = Decide(both);
    expect(d.run && d.reason == WakeReason::kUiRequest,
           "coalesced UI and Surface work is serviced in one callback");

    if (failures) return 1;
    std::printf("PASS: idle present gate decisions and wake priorities\n");
    return 0;
}
