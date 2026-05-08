#include <jni.h>

// Minimal JNI template module. Replace this file with your own exported natives.

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    return JNI_VERSION_1_8;
}
