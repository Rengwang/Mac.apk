#pragma once

namespace macapk::app_fps {

// Counts host display ticks that carried at least one new guest frame. Reports
// from multiple Android surfaces are coalesced by the caller before Tick(), so
// one composed display frame is counted once rather than once per surface.
struct Meter {
    double window_start = -1.0;
    double fps = 0.0;
    double last_window_seconds = 0.0;
    int frames = 0;

    // Returns true when this call closes a reporting window. interval is in
    // seconds; an idle window reports 0 FPS instead of retaining an old value.
    bool Tick(double now, bool guest_frame, double interval = 0.5) {
        if (window_start < 0.0) window_start = now;
        if (guest_frame) ++frames;
        const double elapsed = now - window_start;
        if (elapsed < interval) return false;
        last_window_seconds = elapsed;
        fps = elapsed > 0.0 ? static_cast<double>(frames) / elapsed : 0.0;
        frames = 0;
        window_start = now;
        return true;
    }

    void Reset() {
        window_start = -1.0;
        fps = 0.0;
        last_window_seconds = 0.0;
        frames = 0;
    }
};

}  // namespace macapk::app_fps
