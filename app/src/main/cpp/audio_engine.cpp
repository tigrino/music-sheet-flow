#include "audio_engine.h"
#include "pitch_detector.h"
#include <oboe/Oboe.h>
#include <android/log.h>
#include <cmath>
#include <memory>
#include <vector>
#include <chrono>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace musicsheetflow {

// Buffer size for pitch detection (must match aubio initialization)
static constexpr int PITCH_BUFFER_SIZE = 2048;

class AudioEngineImpl : public AudioEngine, public oboe::AudioStreamDataCallback {
public:
    AudioEngineImpl() = default;

    ~AudioEngineImpl() override {
        stop();
    }

    bool start() override {
        if (stream_) {
            return true;  // Already running
        }

        // Use settings compatible with emulator
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input)
               ->setPerformanceMode(oboe::PerformanceMode::None)
               ->setSharingMode(oboe::SharingMode::Shared)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(oboe::ChannelCount::Mono)
               ->setDataCallback(this);

        oboe::Result result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open input stream: %s", oboe::convertToText(result));
            return false;
        }

        // Get actual sample rate
        sampleRate_ = stream_->getSampleRate();

        // Create pitch detector
        pitchDetector_ = createPitchDetector(sampleRate_, PITCH_BUFFER_SIZE);

        // Reserve buffer space
        audioBuffer_.reserve(PITCH_BUFFER_SIZE);

        result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start input stream: %s", oboe::convertToText(result));
            return false;
        }

        LOGI("Audio input started: sampleRate=%d", sampleRate_);
        return true;
    }

    void stop() override {
        if (stream_) {
            stream_->requestStop();
            stream_->close();
            stream_.reset();
        }
        pitchDetector_.reset();
        audioBuffer_.clear();
    }

    void setNoiseGateThreshold(float thresholdDb) override {
        noiseGateThreshold_ = std::pow(10.0f, thresholdDb / 20.0f);
    }

    void setPitchCallback(PitchCallback callback) override {
        pitchCallback_ = callback;
    }

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* stream,
            void* audioData,
            int32_t numFrames) override {

        auto* data = static_cast<float*>(audioData);

        // Add samples to buffer
        for (int i = 0; i < numFrames; ++i) {
            audioBuffer_.push_back(data[i]);
        }

        // Process when we have enough samples
        while (audioBuffer_.size() >= PITCH_BUFFER_SIZE) {
            // Calculate RMS for noise gate
            float rms = 0.0f;
            for (int i = 0; i < PITCH_BUFFER_SIZE; ++i) {
                rms += audioBuffer_[i] * audioBuffer_[i];
            }
            rms = std::sqrt(rms / PITCH_BUFFER_SIZE);

            // Only process if above noise gate
            if (rms >= noiseGateThreshold_ && pitchDetector_ && pitchCallback_) {
                PitchResult result = pitchDetector_->detect(audioBuffer_.data(), PITCH_BUFFER_SIZE);

                if (result.midiNote >= 0) {
                    auto now = std::chrono::steady_clock::now();
                    auto timestampNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                        now.time_since_epoch()).count();

                    PitchEvent event{
                        result.frequency,
                        result.confidence,
                        result.midiNote,
                        result.centDeviation,
                        timestampNs
                    };
                    pitchCallback_(event);
                }
            }

            // Remove processed samples (with 50% overlap for better detection)
            audioBuffer_.erase(audioBuffer_.begin(), audioBuffer_.begin() + PITCH_BUFFER_SIZE / 2);
        }

        return oboe::DataCallbackResult::Continue;
    }

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<PitchDetector> pitchDetector_;
    std::vector<float> audioBuffer_;
    int sampleRate_ = 44100;
    float noiseGateThreshold_ = 0.01f;  // -40dB default
    PitchCallback pitchCallback_ = nullptr;
};

// Singleton instance
static std::unique_ptr<AudioEngineImpl> g_audioEngine;

AudioEngine* getAudioEngine() {
    if (!g_audioEngine) {
        g_audioEngine = std::make_unique<AudioEngineImpl>();
    }
    return g_audioEngine.get();
}

}  // namespace musicsheetflow
