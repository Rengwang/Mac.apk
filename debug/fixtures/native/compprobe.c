/*
 * Minimal freestanding ARM64 JNI/ABI probe library for COMP-001 synthetic fixture.
 * Built offline via:
 *   clang -target aarch64-linux-android29 -fPIC -O2 -ffreestanding -nostdlib -c
 * and packed into an ET_DYN ELF64 shared library by debug/fixtures/build_comp_fixtures.py.
 */
#include <stdint.h>

int compprobe_add(int a, int b) {
    return a + b;
}

int JNI_OnLoad(void* vm, void* reserved) {
    (void)vm;
    (void)reserved;
    return 0x00010006; /* JNI_VERSION_1_6 */
}

int Java_org_macapk_comp_ipcabi_MainActivity_nativeProbe(
    void* env,
    void* thiz,
    int a,
    int b
) {
    (void)env;
    (void)thiz;
    return compprobe_add(a, b) ^ 0x2a;
}
