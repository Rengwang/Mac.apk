#pragma once
#include <jni.h>
extern "C" {
JNIEXPORT jlong JNICALL Java_com_macapk_loader_SharedGlFrameBridge_create(JNIEnv*,jclass,jlong,jlong,jint,jint);
JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_begin(JNIEnv*,jclass,jlong,jlongArray);
JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_publish(JNIEnv*,jclass,jlong,jlong);
JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_cancel(JNIEnv*,jclass,jlong,jlong);
JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_take(JNIEnv*,jclass,jlong,jlongArray);
JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_copyArgb(JNIEnv*,jclass,jlong,jlong,jintArray);
JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_copyDrawingArgb(JNIEnv*,jclass,jlong,jlong,jintArray);
JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_presentLayer(JNIEnv*,jclass,jlong,jlong,jint,jlongArray);
JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_release(JNIEnv*,jclass,jlong,jlong);
JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_destroy(JNIEnv*,jclass,jlong);
JNIEXPORT jboolean JNICALL Java_com_macapk_loader_SharedGlFrameBridge_destroyDeferred(JNIEnv*,jclass,jlong);
bool MacapkSharedGlOwnsSurface(void*);
}
