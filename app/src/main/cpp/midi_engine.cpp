#include "midi_engine.h"
#include <oboe/Oboe.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <mutex>
#include <vector>
#include <unistd.h>

#define TSF_IMPLEMENTATION
#include "third_party/tsf.h"

#define LOG_TAG "MidiEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace musicsheetflow {

class MidiEngineImpl : public MidiEngine, public oboe::AudioStreamDataCallback {
public:
    MidiEngineImpl() = default;

    ~MidiEngineImpl() override {
        stop();
        if (tsf_) {
            tsf_close(tsf_);
            tsf_ = nullptr;
        }
    }

    bool loadSoundFont(const std::string& path) override {
        std::lock_guard<std::mutex> lock(mutex_);

        if (tsf_) {
            tsf_close(tsf_);
            tsf_ = nullptr;
        }

        tsf_ = tsf_load_filename(path.c_str());
        if (!tsf_) {
            LOGE("Failed to load SoundFont: %s", path.c_str());
            return false;
        }

        // Set output mode to stereo interleaved, 44100 Hz
        tsf_set_output(tsf_, TSF_STEREO_INTERLEAVED, sampleRate_, 0.0f);

        tsf_set_volume(tsf_, 1.0f);

        // Set up channel 0 for piano (General MIDI preset 0)
        tsf_channel_set_presetnumber(tsf_, 0, 0, 0);
        tsf_channel_set_volume(tsf_, 0, 1.0f);

        // Set up channel 9 for percussion (GM drum kit, bank 128)
        tsf_channel_set_presetnumber(tsf_, 9, 0, 1);  // Preset 0, bank 1 for percussion
        tsf_channel_set_volume(tsf_, 9, 1.0f);

        LOGI("SoundFont loaded: %s (%d presets)", path.c_str(), tsf_get_presetcount(tsf_));
        return true;
    }

    bool loadSoundFontFromMemory(const void* data, int size) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (tsf_) {
            tsf_close(tsf_);
            tsf_ = nullptr;
        }

        tsf_ = tsf_load_memory(data, size);
        if (!tsf_) {
            LOGE("Failed to load SoundFont from memory");
            return false;
        }

        tsf_set_output(tsf_, TSF_STEREO_INTERLEAVED, sampleRate_, 0.0f);

        LOGI("SoundFont loaded from memory (%d bytes)", size);
        return true;
    }

    bool isLoaded() const override {
        return tsf_ != nullptr;
    }

    void noteOn(int note, float velocity) override {
        noteOnChannel(0, note, velocity);
    }

    void noteOff(int note) override {
        noteOffChannel(0, note);
    }

    void noteOnChannel(int channel, int note, float velocity) override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (tsf_) {
            tsf_channel_note_on(tsf_, channel, note, velocity);
        }
    }

    void noteOffChannel(int channel, int note) override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (tsf_) {
            tsf_channel_note_off(tsf_, channel, note);
        }
    }

    void setChannelPreset(int channel, int preset, int bank) override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (tsf_) {
            tsf_channel_set_presetnumber(tsf_, channel, preset, bank);
            LOGI("Channel %d set to preset %d, bank %d", channel, preset, bank);
        }
    }

    void allNotesOff() override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (tsf_) {
            tsf_note_off_all(tsf_);
        }
    }

    void batchNoteOn(const int* notes, const float* velocities, int count) override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (tsf_) {
            for (int i = 0; i < count; i++) {
                tsf_channel_note_on(tsf_, 0, notes[i], velocities[i]);
            }
        }
    }

    void setVolume(float volume) override {
        std::lock_guard<std::mutex> lock(mutex_);
        volume_ = volume;
        if (tsf_) {
            tsf_set_volume(tsf_, volume);
        }
    }

    bool start() override {
        if (stream_) {
            return true;  // Already running
        }

        oboe::Result result = oboe::Result::ErrorInternal;

        // Retry loop for emulator compatibility (audio service may take time)
        for (int attempt = 1; attempt <= 5; attempt++) {
            oboe::AudioStreamBuilder builder;
            builder.setDirection(oboe::Direction::Output)
                   ->setPerformanceMode(oboe::PerformanceMode::None)
                   ->setSharingMode(oboe::SharingMode::Shared)
                   ->setFormat(oboe::AudioFormat::Float)
                   ->setChannelCount(oboe::ChannelCount::Stereo)
                   ->setDataCallback(this);

            // Don't specify sample rate, let system choose
            result = builder.openStream(stream_);
            if (result == oboe::Result::OK) {
                break;
            }

            LOGE("Failed to open audio stream (attempt %d): %s", attempt, oboe::convertToText(result));

            if (attempt < 5) {
                // Wait before retry
                usleep(500000);  // 500ms
            } else {
                LOGE("All attempts to open audio stream failed");
                return false;
            }
        }

        // Update sample rate to match stream
        sampleRate_ = stream_->getSampleRate();
        if (tsf_) {
            tsf_set_output(tsf_, TSF_STEREO_INTERLEAVED, sampleRate_, 0.0f);
            tsf_set_volume(tsf_, 1.0f);
            LOGI("TSF output configured: sampleRate=%d, stereo interleaved", sampleRate_);
        }

        result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start output stream: %s", oboe::convertToText(result));
            return false;
        }

        LOGI("MIDI engine started: sampleRate=%d", sampleRate_);
        return true;
    }

    void stop() override {
        if (stream_) {
            stream_->requestStop();
            stream_->close();
            stream_.reset();
        }
        allNotesOff();
    }

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* stream,
            void* audioData,
            int32_t numFrames) override {

        auto* output = static_cast<float*>(audioData);

        // Try to acquire mutex - if blocked, output silence to avoid audio glitches
        std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
        if (lock.owns_lock() && tsf_) {
            tsf_render_float(tsf_, output, numFrames, 0);
        } else {
            memset(output, 0, numFrames * 2 * sizeof(float));
        }

        return oboe::DataCallbackResult::Continue;
    }

private:
    tsf* tsf_ = nullptr;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex mutex_;
    int sampleRate_ = 44100;
    float volume_ = 0.8f;
};

std::unique_ptr<MidiEngine> createMidiEngine() {
    return std::make_unique<MidiEngineImpl>();
}

// Global instance for JNI access
static std::unique_ptr<MidiEngineImpl> g_midiEngine;

MidiEngine* getMidiEngine() {
    if (!g_midiEngine) {
        g_midiEngine = std::make_unique<MidiEngineImpl>();
    }
    return g_midiEngine.get();
}

}  // namespace musicsheetflow

// JNI functions
extern "C" {

JNIEXPORT jboolean JNICALL
Java_net_tigr_musicsheetflow_audio_NativeMidiEngine_nativeLoadSoundFont(
        JNIEnv* env,
        jobject thiz,
        jstring path) {
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    bool result = musicsheetflow::getMidiEngine()->loadSoundFont(pathStr);
    env->ReleaseStringUTFChars(path, pathStr);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_net_tigr_musicsheetflow_audio_NativeMidiEngine_nativeStart(
        JNIEnv* env,
        jobject thiz) {
    return musicsheetflow::getMidiEngine()->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_net_tigr_musicsheetflow_audio_NativeMidiEngine_nativeStop(
        JNIEnv* env,
        jobject thiz) {
    musicsheetflow::getMidiEngine()->stop();
}

JNIEXPORT void JNICALL
Java_net_tigr_musicsheetflow_audio_NativeMidiEngine_nativeNoteOn(
        JNIEnv* env,
        jobject thiz,
        jint note,
        jfloat velocity) {
    musicsheetflow::getMidiEngine()->noteOn(note, velocity);
}

JNIEXPORT void JNICALL
Java_net_tigr_musicsheetflow_audio_NativeMidiEngine_nativeNoteOff(
        JNIEnv* env,
        jobject thiz,
        jint note) {
    musicsheetflow::getMidiEngine()->noteOff(note);
}

JNIEXPORT void JNICALL
Java_net_tigr_musicsheetflow_audio_NativeMidiEngine_nativeAllNotesOff(
        JNIEnv* env,
        jobject thiz) {
    musicsheetflow::getMidiEngine()->allNotesOff();
}

JNIEXPORT void JNICALL
Java_net_tigr_musicsheetflow_audio_NativeMidiEngine_nativeSetVolume(
        JNIEnv* env,
        jobject thiz,
        jfloat volume) {
    musicsheetflow::getMidiEngine()->setVolume(volume);
}

JNIEXPORT void JNICALL
Java_net_tigr_musicsheetflow_audio_NativeMidiEngine_nativeNoteOnChannel(
        JNIEnv* env,
        jobject thiz,
        jint channel,
        jint note,
        jfloat velocity) {
    musicsheetflow::getMidiEngine()->noteOnChannel(channel, note, velocity);
}

JNIEXPORT void JNICALL
Java_net_tigr_musicsheetflow_audio_NativeMidiEngine_nativeNoteOffChannel(
        JNIEnv* env,
        jobject thiz,
        jint channel,
        jint note) {
    musicsheetflow::getMidiEngine()->noteOffChannel(channel, note);
}

JNIEXPORT void JNICALL
Java_net_tigr_musicsheetflow_audio_NativeMidiEngine_nativeSetChannelPreset(
        JNIEnv* env,
        jobject thiz,
        jint channel,
        jint preset,
        jint bank) {
    musicsheetflow::getMidiEngine()->setChannelPreset(channel, preset, bank);
}

JNIEXPORT void JNICALL
Java_net_tigr_musicsheetflow_audio_NativeMidiEngine_nativeBatchNoteOn(
        JNIEnv* env,
        jobject thiz,
        jintArray notes,
        jfloatArray velocities) {
    jint* noteArr = env->GetIntArrayElements(notes, nullptr);
    jfloat* velArr = env->GetFloatArrayElements(velocities, nullptr);
    jsize count = env->GetArrayLength(notes);

    auto* engine = dynamic_cast<musicsheetflow::MidiEngineImpl*>(musicsheetflow::getMidiEngine());
    if (engine) {
        engine->batchNoteOn(noteArr, velArr, count);
    }

    env->ReleaseIntArrayElements(notes, noteArr, 0);
    env->ReleaseFloatArrayElements(velocities, velArr, 0);
}

}  // extern "C"
