// Minimal JNI bridge for debug/MediaCodecEncoderTest.java (GAP-MED-002).
// Registers NativeMedia audio/video methods from media_decode.mm + video_toolbox.mm
// and stubs Cocoa presentation hooks so encoder/decoder round-trips run headlessly
// via scripts/run_tests.py without booting Cocoa or touching /Applications/Mac.apk.app.

#include <jni.h>
#include <cstdio>

namespace macapk {
namespace media { void RegisterNativeMediaMethods(JNIEnv* env); }
namespace video { void RegisterNativeVideoMethods(JNIEnv* env); }
}

extern "C" {

void* macapk_vd_present_create(void*) {
    return nullptr;
}

int macapk_vd_present_to_layer(void*, void*, void*) {
    return 0;
}

void CocoaPresentVideoFrame(void*, int, int) {
}

void CocoaPresentVideoFrameToSurface(void*, int, int, jobject) {
}

void* macapk_vorbis_dec_open(const void*, int, const void*, int, int*, int*) {
    return nullptr;
}

int macapk_vorbis_dec_decode(void*, const void*, int, int, void*, int) {
    return -1;
}

int macapk_vorbis_dec_out_sample_rate(void*) {
    return 0;
}

int macapk_vorbis_dec_out_channels(void*) {
    return 0;
}

void macapk_vorbis_dec_close(void*) {
}

}  // extern "C"

static void test_nativelog_print(JNIEnv*, jclass, jint, jstring, jstring) {
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (!vm || vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21) != JNI_OK || !env) {
        return JNI_ERR;
    }

    jclass nl = env->FindClass("com/macapk/loader/NativeLog");
    if (!nl) {
        std::fprintf(stderr, "[media_encoder_jni_test] FindClass(NativeLog) failed\n");
        env->ExceptionClear();
        return JNI_ERR;
    }
    const JNINativeMethod nlMethods[] = {
        {const_cast<char*>("print"),
         const_cast<char*>("(ILjava/lang/String;Ljava/lang/String;)V"),
         reinterpret_cast<void*>(test_nativelog_print)},
    };
    if (env->RegisterNatives(nl, nlMethods, 1) != JNI_OK) {
        std::fprintf(stderr, "[media_encoder_jni_test] RegisterNatives(NativeLog) failed\n");
        env->ExceptionClear();
        env->DeleteLocalRef(nl);
        return JNI_ERR;
    }
    env->DeleteLocalRef(nl);

    macapk::media::RegisterNativeMediaMethods(env);
    macapk::video::RegisterNativeVideoMethods(env);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return JNI_ERR;
    }
    return JNI_VERSION_21;
}
