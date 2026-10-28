#!/usr/bin/env python3
"""Exercise actual ANativeWindow shim code with counted JNI/Cocoa test doubles.

No JVM, Android app, display, or installed runtime is used. Extracting the real
functions keeps this test on the ownership boundary rather than duplicating
the reference-count algorithm in a separate model.
"""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / "runtime/translator/src/bionic_shim.cpp").read_text()
records = source.split("struct MacApkNativeWindow {", 1)[1].split(
    "// v1.0.162: Surface->CAMetalLayer bridge", 1
)[0]
functions = source.split(
    "alignas(16) static uint8_t g_macapk_dummy_ANativeWindow[4096]", 1
)[1].split("// ANativeWindow_lock(window", 1)[0]

prefix = r'''
#include <atomic>
#include <cerrno>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>
struct MockSurface { int key; std::atomic<int> globals{0}; int width = 0, height = 0; };
using jobject = MockSurface*;
using jclass = MockSurface*;
using jmethodID = const char*;
using jint = int;
using jlong = long long;
bool fail_attach = false, fail_global = false, layers_ready = true;
bool keyed_geometry_available = true;
std::atomic<int> key_queries{0};
int keyed_geometry_calls = 0, global_geometry_calls = 0;
struct FakeJNI {
    bool ExceptionCheck() { return false; }
    void ExceptionClear() {}
    jobject NewGlobalRef(jobject s) {
        if (!s || fail_global) return nullptr;
        ++s->globals; return s;
    }
    void DeleteGlobalRef(jobject s) { assert(s && s->globals.fetch_sub(1) > 0); }
    jobject NewLocalRef(jobject s) { return s; }
    bool IsSameObject(jobject a, jobject b) { return a == b; }
    jclass GetObjectClass(jobject s) { return s; }
    jmethodID GetMethodID(jclass, const char* name, const char*) { return name; }
    jint CallIntMethod(jobject s, jmethodID) { ++key_queries; return s->key; }
    jlong CallLongMethod(jobject s, jmethodID) { return (jlong(s->width) << 32) | uint32_t(s->height); }
    void CallVoidMethod(jobject, jmethodID) {}
    void DeleteLocalRef(jclass) {}
} fake_jni;
using JNIEnv = FakeJNI;
struct AttachGuard { FakeJNI* env = fail_attach ? nullptr : &fake_jni; };
void* fake_layer(int* w, int* h) {
    *w = layers_ready ? 640 : 0; *h = layers_ready ? 480 : 0;
    return layers_ready ? reinterpret_cast<void*>(0x1000) : nullptr;
}
void* fake_key_layer(int key, int* w, int* h) {
    return fake_layer(w, h) ? reinterpret_cast<void*>(uintptr_t(0x1000 + key)) : nullptr;
}
void fake_geometry(int, int) { ++global_geometry_calls; }
int last_geometry_key = 0, last_geometry_w = -1, last_geometry_h = -1;
int fake_key_geometry(int key, int w, int h) {
    ++keyed_geometry_calls;
    last_geometry_key = key; last_geometry_w = w; last_geometry_h = h;
    return 0;
}
void* test_dlsym(void*, const char* name) {
    if (!std::strcmp(name, "CocoaEnsureNativeEglLayerForSurfaceKey"))
        return reinterpret_cast<void*>(fake_key_layer);
    if (!std::strcmp(name, "CocoaSetNativeEglBufferGeometryForSurfaceKey"))
        return keyed_geometry_available
            ? reinterpret_cast<void*>(fake_key_geometry) : nullptr;
    if (!std::strcmp(name, "CocoaEnsureNativeEglLayer") ||
        !std::strcmp(name, "CocoaGetUnityRenderLayer"))
        return reinterpret_cast<void*>(fake_layer);
    if (!std::strcmp(name, "CocoaSetNativeEglBufferGeometry"))
        return reinterpret_cast<void*>(fake_geometry);
    return nullptr;
}
#define dlsym test_dlsym
#define RTLD_DEFAULT nullptr
'''

tests = r'''
int main(int argc, char** argv) {
    const char* mode = argc > 1 ? argv[1] : "legacy";
    const bool keyed = !std::strcmp(mode, "keyed")
        || !std::strcmp(mode, "keyed-missing-bridge");
    if (!std::strcmp(mode, "keyed-missing-bridge")) keyed_geometry_available = false;
    MockSurface a{7}, b{0}, c{12}, other{9};
    if (!keyed) {
        void* first = __macapk_arm64_ANativeWindow_fromSurface(nullptr, &a);
        assert(first == &g_macapk_unity_window && a.globals == 1);
        void* second = __macapk_arm64_ANativeWindow_fromSurface(nullptr, &b);
        assert(first == second && a.globals == 0 && b.globals == 1);
        assert(__macapk_arm64_ANativeWindow_toSurface(nullptr, first) == &b);
        __macapk_arm64_ANativeWindow_acquire(first);
        __macapk_arm64_ANativeWindow_release(first);
        assert(b.globals == 1 && g_macapk_nw_records.empty() && key_queries == 0);
        std::puts("legacy: singleton identity, reference replacement, unchanged no-op acquire/release PASS");
        return 0;
    }
    void* first = __macapk_arm64_ANativeWindow_fromSurface(nullptr, &a);
    assert(first != &g_macapk_unity_window && first != g_macapk_dummy_ANativeWindow);
    assert(g_macapk_nw_records.size() == 1 && a.globals == 1);
    assert(__macapk_arm64_ANativeWindow_getWidth(first) == 640);
    assert(__macapk_arm64_ANativeWindow_toSurface(nullptr, first) == &a);
    // setBuffersGeometry changes only the producer buffer request. It must not
    // rewrite the window's default size queried by the guest.
    if (!keyed_geometry_available) {
        assert(__macapk_arm64_ANativeWindow_setBuffersGeometry(first, 32, 16, 1) == -ENOSYS);
        assert(global_geometry_calls == 0 && keyed_geometry_calls == 0);
        __macapk_arm64_ANativeWindow_release(first);
        assert(a.globals == 0 && g_macapk_nw_records.empty());
        std::puts("keyed-missing-bridge: keyed failure never mutates the shared layer PASS");
        return 0;
    }
    assert(__macapk_arm64_ANativeWindow_fromSurface(nullptr, &a) == first);
    assert(__macapk_arm64_ANativeWindow_setBuffersGeometry(first, 0, 0, 0) == 0);
    assert(last_geometry_key == 7 && last_geometry_w == 0 && last_geometry_h == 0);
    assert(keyed_geometry_calls == 1 && global_geometry_calls == 0);
    assert(__macapk_arm64_ANativeWindow_getWidth(first) == 640);
    assert(__macapk_arm64_ANativeWindow_getHeight(first) == 480);
    assert(__macapk_arm64_ANativeWindow_setBuffersGeometry(first, 1280, 720, 3) == 0);
    assert(last_geometry_key == 7 && last_geometry_w == 1280 && last_geometry_h == 720);
    assert(__macapk_arm64_ANativeWindow_getFormat(first) == 3);
    assert(__macapk_arm64_ANativeWindow_setBuffersGeometry(first, 16, 9, 0) == 0);
    assert(last_geometry_key == 7 && last_geometry_w == 16 && last_geometry_h == 9);
    assert(__macapk_arm64_ANativeWindow_getFormat(first) == 1);
    assert(keyed_geometry_calls == 3 && global_geometry_calls == 0);
    assert(__macapk_arm64_ANativeWindow_getWidth(first) == 640);
    assert(__macapk_arm64_ANativeWindow_getHeight(first) == 480);
    assert(__macapk_arm64_ANativeWindow_setBuffersGeometry(first, -1, 0, 1) == -EINVAL);
    assert(__macapk_arm64_ANativeWindow_setBuffersGeometry(first, 0, 1, 1) == -EINVAL);
    assert(__macapk_arm64_ANativeWindow_setBuffersGeometry(nullptr, 1, 1, 1) == -EINVAL);
    assert(keyed_geometry_calls == 3 && global_geometry_calls == 0);
    void* second = __macapk_arm64_ANativeWindow_fromSurface(nullptr, &other);
    assert(second && second != first);
    assert(__macapk_arm64_ANativeWindow_setBuffersGeometry(second, 320, 180, 1) == 0);
    assert(last_geometry_key == 9 && last_geometry_w == 320 && last_geometry_h == 180);
    assert(keyed_geometry_calls == 4 && global_geometry_calls == 0);
    __macapk_arm64_ANativeWindow_release(second);
    assert(other.globals == 0);
    __macapk_arm64_ANativeWindow_acquire(first);
    __macapk_arm64_ANativeWindow_release(first);
    __macapk_arm64_ANativeWindow_release(first);
    assert(a.globals == 1 && g_macapk_nw_records[0]->references == 1);
    std::vector<std::thread> workers;
    for (int n = 0; n < 8; ++n) workers.emplace_back([&] {
        for (int k = 0; k < 200; ++k) {
            void* acquired = __macapk_arm64_ANativeWindow_fromSurface(nullptr, &a);
            assert(acquired == first);
            assert(__macapk_arm64_ANativeWindow_toSurface(nullptr, acquired) == &a);
            __macapk_arm64_ANativeWindow_acquire(acquired);
            __macapk_arm64_ANativeWindow_release(acquired);
            __macapk_arm64_ANativeWindow_release(acquired);
        }
    });
    for (auto& worker : workers) worker.join();
    assert(g_macapk_nw_records.size() == 1 && g_macapk_nw_records[0]->references == 1);
    fail_attach = true;
    __macapk_arm64_ANativeWindow_release(first);
    fail_attach = false;
    assert(a.globals == 1 && g_macapk_nw_records.size() == 1);
    __macapk_arm64_ANativeWindow_release(first);
    assert(a.globals == 0 && g_macapk_nw_records.empty());
    assert(__macapk_arm64_ANativeWindow_toSurface(nullptr, first) == nullptr);
    __macapk_arm64_ANativeWindow_release(first); // unknown/stale handle: no dereference
    __macapk_arm64_ANativeWindow_acquire(first);
    assert(__macapk_arm64_ANativeWindow_setBuffersGeometry(first, 1, 1, 1) == -EINVAL);
    assert(keyed_geometry_calls == 4 && global_geometry_calls == 0);
    void* rebuilt = __macapk_arm64_ANativeWindow_fromSurface(nullptr, &a);
    assert(rebuilt != g_macapk_dummy_ANativeWindow);
    assert(rebuilt != &g_macapk_unity_window);
    assert(__macapk_arm64_ANativeWindow_toSurface(nullptr, rebuilt) == &a);
    assert(__macapk_arm64_ANativeWindow_setBuffersGeometry(rebuilt, 0, 0, 0) == 0);
    assert(last_geometry_key == 7 && last_geometry_w == 0 && last_geometry_h == 0);
    assert(keyed_geometry_calls == 5 && global_geometry_calls == 0);
    __macapk_arm64_ANativeWindow_release(rebuilt);
    assert(a.globals == 0 && g_macapk_nw_records.empty());
    b.width = 320; b.height = 180;
    void* fallback = __macapk_arm64_ANativeWindow_fromSurface(nullptr, &b);
    assert(fallback != &g_macapk_unity_window);
    assert(__macapk_arm64_ANativeWindow_getWidth(fallback) == 320);
    assert(__macapk_arm64_ANativeWindow_getHeight(fallback) == 180);
    b.width = 800; b.height = 450;
    assert(__macapk_arm64_ANativeWindow_getWidth(fallback) == 800);
    assert(__macapk_arm64_ANativeWindow_getHeight(fallback) == 450);
    assert(__macapk_arm64_ANativeWindow_toSurface(nullptr, fallback) == &b);
    assert(__macapk_native_window_layer(fallback) == reinterpret_cast<void*>(0x1000));
    assert(__macapk_arm64_ANativeWindow_setBuffersGeometry(fallback, 0, 0, 0) == 0);
    assert(global_geometry_calls == 1);
    __macapk_arm64_ANativeWindow_release(fallback);
    assert(b.globals == 0 && g_macapk_nw_records.empty());
    layers_ready = false;
    assert(__macapk_arm64_ANativeWindow_fromSurface(nullptr, &c) == g_macapk_dummy_ANativeWindow);
    assert(c.globals == 0 && g_macapk_nw_records.empty());
    assert(__macapk_arm64_ANativeWindow_toSurface(nullptr, g_macapk_dummy_ANativeWindow) == nullptr);
    layers_ready = true; fail_global = true;
    assert(__macapk_arm64_ANativeWindow_fromSurface(nullptr, &c) == g_macapk_dummy_ANativeWindow);
    assert(c.globals == 0 && g_macapk_nw_records.empty());
    std::puts("keyed: identity, concurrent owners, final JNI release, fallback/failure cleanup PASS");
}
'''

with tempfile.TemporaryDirectory(prefix="macapk-native-window-") as temp:
    harness = Path(temp) / "test.cpp"
    binary = Path(temp) / "test"
    harness.write_text(prefix + "\nstruct MacApkNativeWindow {" + records +
                       "\nalignas(16) static uint8_t g_macapk_dummy_ANativeWindow[4096]" +
                       functions + tests)
    subprocess.run(["clang++", "-std=c++20", "-pthread", "-Wall", "-Wextra",
                    str(harness), "-o", str(binary)], check=True)
    for test_mode in ("legacy", "keyed", "keyed-missing-bridge"):
        env = dict(os.environ, MACAPK_EXPERIMENTAL_NATIVE_SURFACE_LAYERS=(
            "1" if test_mode.startswith("keyed") else "0"))
        subprocess.run([str(binary), test_mode], env=env, check=True)
