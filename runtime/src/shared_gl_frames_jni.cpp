#include "shared_gl_frames.h"
#include <jni.h>
#include <atomic>
#include <cstdint>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <new>

using IOSurfaceReleaseFn = void (*)(void*);
namespace macapk {
bool CocoaPresentSurfaceIOSurfaceFrom(int surfaceKey, int width, int height,
                                      IOSurfaceRef image, IOSurfaceReleaseFn release,
                                      void* context);
}

namespace {
struct PoolEntry {
    macapk::SharedGlFrames* pool;
    std::atomic<bool> closing{false};
    std::mutex destroyMutex;
    bool destroyed = false;  // guarded by destroyMutex
    size_t activeOperations = 0;  // guarded by registryMutex
};

std::mutex registryMutex;
std::map<uint64_t, std::shared_ptr<PoolEntry>> registry;
uint64_t nextId = 1;

std::shared_ptr<PoolEntry> find(jlong id) {
    auto it = registry.find(static_cast<uint64_t>(id));
    return it == registry.end() ? nullptr : it->second;
}

bool tryDestroyEntry(const std::shared_ptr<PoolEntry>& entry) {
    if (!entry) return false;
    std::lock_guard<std::mutex> lock(entry->destroyMutex);
    if (entry->destroyed) return true;
    if (!macapk::SharedGlFramesDestroy(entry->pool)) return false;
    entry->destroyed = true;
    return true;
}

void finishOperation(const std::shared_ptr<PoolEntry>& entry) {
    if (!entry) return;
    bool closeNow = false;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        if (entry->activeOperations) --entry->activeOperations;
        closeNow = entry->activeOperations == 0
            && entry->closing.load(std::memory_order_acquire);
    }
    if (closeNow) (void) tryDestroyEntry(entry);
}

class EntryUse {
public:
    explicit EntryUse(jlong id) {
        std::lock_guard<std::mutex> lock(registryMutex);
        entry_ = find(id);
        if (!entry_ || entry_->closing.load(std::memory_order_acquire)) {
            entry_.reset();
            return;
        }
        ++entry_->activeOperations;
    }
    ~EntryUse() { finishOperation(entry_); }
    EntryUse(const EntryUse&) = delete;
    EntryUse& operator=(const EntryUse&) = delete;

    explicit operator bool() const { return entry_ != nullptr; }
    PoolEntry* operator->() const { return entry_.get(); }
    const std::shared_ptr<PoolEntry>& shared() const { return entry_; }

private:
    std::shared_ptr<PoolEntry> entry_;
};

EntryUse useEntry(jlong id) {
    return EntryUse(id);
}

bool validOutput(JNIEnv* env, jlongArray out) {
    return out && env->GetArrayLength(out) >= 2;
}

struct PresentLease {
    std::shared_ptr<PoolEntry> entry;
    uint64_t token;
};

void releasePresented(void* context) {
    std::unique_ptr<PresentLease> lease(static_cast<PresentLease*>(context));
    if (!lease || !lease->entry || !lease->entry->pool) return;
    macapk::SharedGlFramesReleasePresented(lease->entry->pool, lease->token);
    if (lease->entry->closing.load(std::memory_order_acquire)) {
        // The compositor may outlive Java's pool handle. Keep the shared_ptr in
        // the lease and retry final destruction once this frame returns.
        (void) tryDestroyEntry(lease->entry);
    }
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_macapk_loader_SharedGlFrameBridge_create(
    JNIEnv*, jclass, jlong d, jlong c, jint w, jint h) {
    std::lock_guard<std::mutex> lock(registryMutex);
    if (nextId > static_cast<uint64_t>(std::numeric_limits<jlong>::max())) return 0;
    auto* pool = macapk::SharedGlFramesCreate(
        reinterpret_cast<EGLDisplay>(static_cast<intptr_t>(d)),
        reinterpret_cast<EGLConfig>(static_cast<intptr_t>(c)), w, h);
    if (!pool) return 0;
    const auto id = nextId++;
    try {
        auto entry = std::make_shared<PoolEntry>();
        entry->pool = pool;
        registry.emplace(id, std::move(entry));
    } catch (...) {
        macapk::SharedGlFramesDestroy(pool);
        return 0;
    }
    return static_cast<jlong>(id);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_begin(
    JNIEnv* env, jclass, jlong id, jlongArray out) {
    if (!validOutput(env, out)) return JNI_FALSE;
    EntryUse entry = useEntry(id);
    if (!entry) return JNI_FALSE;
    uint64_t token = 0;
    EGLSurface surface = EGL_NO_SURFACE;
    if (!macapk::SharedGlFramesBegin(entry->pool, &token, &surface)) return JNI_FALSE;
    jlong values[2] = {static_cast<jlong>(token),
                       static_cast<jlong>(reinterpret_cast<intptr_t>(surface))};
    env->SetLongArrayRegion(out, 0, 2, values);
    if (env->ExceptionCheck()) {
        macapk::SharedGlFramesCancel(entry->pool, token);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_publish(
    JNIEnv*, jclass, jlong id, jlong token) {
    EntryUse entry = useEntry(id);
    return entry && macapk::SharedGlFramesPublish(entry->pool, static_cast<uint64_t>(token))
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_cancel(
    JNIEnv*, jclass, jlong id, jlong token) {
    EntryUse entry = useEntry(id);
    return entry && macapk::SharedGlFramesCancel(entry->pool, static_cast<uint64_t>(token))
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_take(
    JNIEnv* env, jclass, jlong id, jlongArray out) {
    if (!validOutput(env, out)) return JNI_FALSE;
    EntryUse entry = useEntry(id);
    if (!entry) return JNI_FALSE;
    uint64_t token = 0;
    IOSurfaceRef image = nullptr;
    if (!macapk::SharedGlFramesTake(entry->pool, &token, &image)) return JNI_FALSE;
    jlong values[2] = {static_cast<jlong>(token),
                       static_cast<jlong>(reinterpret_cast<intptr_t>(image))};
    env->SetLongArrayRegion(out, 0, 2, values);
    if (env->ExceptionCheck()) {
        macapk::SharedGlFramesRelease(entry->pool, token);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jboolean copyArgb(JNIEnv* env, jlong id, jlong token, jintArray out, bool drawing) {
    if (!out) return JNI_FALSE;
    const jsize length = env->GetArrayLength(out);
    EntryUse entry = useEntry(id);
    if (!entry) return JNI_FALSE;
    // GetIntArrayElements copies on HotSpot. The native conversion already
    // writes every element, so pin the Java array for the short copy instead.
    jint* values = static_cast<jint*>(env->GetPrimitiveArrayCritical(out, nullptr));
    if (!values) return JNI_FALSE;
    bool ok = drawing
        ? macapk::SharedGlFramesCopyDrawingArgb(
              entry->pool, static_cast<uint64_t>(token),
              reinterpret_cast<uint32_t*>(values), static_cast<size_t>(length))
        : macapk::SharedGlFramesCopyArgb(
              entry->pool, static_cast<uint64_t>(token),
              reinterpret_cast<uint32_t*>(values), static_cast<size_t>(length));
    env->ReleasePrimitiveArrayCritical(out, values, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_copyArgb(
    JNIEnv* env, jclass, jlong id, jlong token, jintArray out) {
    return copyArgb(env, id, token, out, false);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_copyDrawingArgb(
    JNIEnv* env, jclass, jlong id, jlong token, jintArray out) {
    return copyArgb(env, id, token, out, true);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_release(
    JNIEnv*, jclass, jlong id, jlong token) {
    EntryUse entry = useEntry(id);
    return entry && macapk::SharedGlFramesRelease(entry->pool, static_cast<uint64_t>(token))
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_destroy(
    JNIEnv*, jclass, jlong id) {
    std::shared_ptr<PoolEntry> entry;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        entry = find(id);
        if (!entry || entry->activeOperations != 0
            || entry->closing.exchange(true, std::memory_order_acq_rel))
            return JNI_FALSE;
    }
    if (!tryDestroyEntry(entry)) {
        {
            std::lock_guard<std::mutex> lock(registryMutex);
            if (!entry->destroyed) entry->closing.store(false, std::memory_order_release);
        }
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = registry.find(static_cast<uint64_t>(id));
    if (it != registry.end() && it->second == entry) registry.erase(it);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_presentLayer(
    JNIEnv* env, jclass, jlong id, jlong token, jint surfaceKey, jlongArray out) {
    if (!validOutput(env, out) || surfaceKey <= 0) return JNI_FALSE;
    EntryUse entry = useEntry(id);
    if (!entry) return JNI_FALSE;
    IOSurfaceRef image = nullptr;
    int width = 0, height = 0;
    uint64_t newToken = 0;
    EGLSurface newSurface = EGL_NO_SURFACE;
    if (!macapk::SharedGlFramesPublishAndBegin(
            entry->pool, static_cast<uint64_t>(token), &image, &width, &height,
            &newToken, &newSurface)) return JNI_FALSE;

    jlong values[2] = {static_cast<jlong>(newToken),
                       static_cast<jlong>(reinterpret_cast<intptr_t>(newSurface))};
    env->SetLongArrayRegion(out, 0, 2, values);
    if (env->ExceptionCheck()) {
        macapk::SharedGlFramesRestorePublished(entry->pool, static_cast<uint64_t>(token));
        macapk::SharedGlFramesCancel(entry->pool, newToken);
        return JNI_FALSE;
    }

    auto* lease = new (std::nothrow) PresentLease{entry.shared(), static_cast<uint64_t>(token)};
    if (!lease || !macapk::CocoaPresentSurfaceIOSurfaceFrom(
            surfaceKey, width, height, image, releasePresented, lease)) {
        delete lease;
        macapk::SharedGlFramesRestorePublished(entry->pool, static_cast<uint64_t>(token));
        macapk::SharedGlFramesCancel(entry->pool, newToken);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_destroyDeferred(
    JNIEnv*, jclass, jlong id) {
    std::shared_ptr<PoolEntry> entry;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        auto it = registry.find(static_cast<uint64_t>(id));
        if (it == registry.end()
            || it->second->closing.exchange(true, std::memory_order_acq_rel))
            return JNI_FALSE;
        entry = it->second;
        registry.erase(it);
    }
    // Outstanding operations and compositor callbacks keep this pool alive
    // through entry and retry final destruction when the last lease returns.
    (void) tryDestroyEntry(entry);
    return JNI_TRUE;
}

extern "C" bool MacapkSharedGlOwnsSurface(void* surface) {
    if (!surface) return false;
    std::lock_guard<std::mutex> lock(registryMutex);
    for (const auto& item : registry) {
        if (item.second
            && !item.second->closing.load(std::memory_order_acquire)
            && macapk::SharedGlFramesOwnsSurface(
                item.second->pool, reinterpret_cast<EGLSurface>(surface))) return true;
    }
    return false;
}
