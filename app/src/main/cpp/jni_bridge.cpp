#include <jni.h>
#include <android/log.h>
#include "audio_engine.h"
#include "pitch_detector.h"

#define LOG_TAG "JNI_Bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// JNI function naming: Java_<package>_<class>_<method>
// Package: net.tigr.musicsheetflow.audio
// Class: NativeAudioEngine

extern "C" {

static JavaVM* g_jvm = nullptr;
static jobject g_callback = nullptr;
static jmethodID g_onPitchDetected = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGI("Native library loaded");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_net_tigr_musicsheetflow_audio_NativeAudioEngine_nativeStart(
        JNIEnv* env,
        jobject thiz) {
    LOGI("Starting audio engine");
    auto* engine = musicsheetflow::getAudioEngine();
    return engine->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_net_tigr_musicsheetflow_audio_NativeAudioEngine_nativeStop(
        JNIEnv* env,
        jobject thiz) {
    LOGI("Stopping audio engine");
    auto* engine = musicsheetflow::getAudioEngine();
    engine->stop();
}

JNIEXPORT void JNICALL
Java_net_tigr_musicsheetflow_audio_NativeAudioEngine_nativeSetNoiseGate(
        JNIEnv* env,
        jobject thiz,
        jfloat thresholdDb) {
    auto* engine = musicsheetflow::getAudioEngine();
    engine->setNoiseGateThreshold(thresholdDb);
}

JNIEXPORT void JNICALL
Java_net_tigr_musicsheetflow_audio_NativeAudioEngine_nativeSetCallback(
        JNIEnv* env,
        jobject thiz,
        jobject callback) {
    // Store callback reference
    if (g_callback != nullptr) {
        env->DeleteGlobalRef(g_callback);
    }

    if (callback != nullptr) {
        g_callback = env->NewGlobalRef(callback);
        jclass callbackClass = env->GetObjectClass(callback);
        g_onPitchDetected = env->GetMethodID(
                callbackClass,
                "onPitchDetected",
                "(FFIIJ)V"  // float freq, float conf, int midi, int cents, long timestamp
        );

        // Set up native callback
        auto* engine = musicsheetflow::getAudioEngine();
        engine->setPitchCallback([](const musicsheetflow::PitchEvent& event) {
            if (g_jvm == nullptr || g_callback == nullptr) return;

            JNIEnv* env;
            bool attached = false;
            int status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);

            if (status == JNI_EDETACHED) {
                g_jvm->AttachCurrentThread(&env, nullptr);
                attached = true;
            }

            if (env != nullptr && g_callback != nullptr && g_onPitchDetected != nullptr) {
                env->CallVoidMethod(
                        g_callback,
                        g_onPitchDetected,
                        event.frequency,
                        event.confidence,
                        event.midiNote,
                        event.centDeviation,
                        event.timestampNs
                );
            }

            if (attached) {
                g_jvm->DetachCurrentThread();
            }
        });
    } else {
        g_callback = nullptr;
        g_onPitchDetected = nullptr;
    }
}

}  // extern "C"
