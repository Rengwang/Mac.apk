#include "app_fps_meter.h"

#include <cmath>
#include <cstdio>

using macapk::app_fps::Meter;

static int failures = 0;

static void expect_near(const char* name, double got, double want, double eps = 1e-9) {
    if (std::fabs(got - want) > eps) {
        std::fprintf(stderr, "FAIL %s: got %.12f want %.12f\n", name, got, want);
        ++failures;
    }
}

int main() {
    {
        Meter m;
        bool flushed = false;
        for (int frame = 0; frame <= 60; ++frame)
            flushed = m.Tick(frame / 60.0, frame != 0, 0.5);
        expect_near("60fps", m.fps, 60.0);
        expect_near("60fps window", m.last_window_seconds, 0.5);
        if (!flushed) {
            std::fprintf(stderr, "FAIL 60fps did not flush\n");
            ++failures;
        }
    }
    {
        Meter m;
        for (int frame = 0; frame <= 30; ++frame)
            m.Tick(frame / 30.0, frame != 0, 0.5);
        expect_near("30fps", m.fps, 30.0);
    }
    {
        Meter m;
        m.Tick(10.0, false, 0.5);
        m.Tick(10.6, false, 0.5);
        expect_near("idle", m.fps, 0.0);
    }
    {
        Meter m;
        for (int i = 0; i <= 10; ++i) m.Tick(1.0 + i / 60.0, i == 3, 0.1);
        expect_near("coalesced", m.fps, 10.0);
        m.Reset();
        expect_near("reset fps", m.fps, 0.0);
        expect_near("reset start", m.window_start, -1.0);
    }
    if (failures) return 1;
    std::puts("PASS app_fps_meter_test (4 scenarios)");
    return 0;
}
