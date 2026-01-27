#include "pitch_detector.h"
#include <android/log.h>
#include <cmath>

#define LOG_TAG "PitchDetector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#ifdef HAVE_AUBIO
#include <aubio.h>
#endif

namespace musicsheetflow {

class PitchDetectorImpl : public PitchDetector {
public:
    PitchDetectorImpl(int sampleRate, int bufferSize)
        : sampleRate_(sampleRate), bufferSize_(bufferSize) {
#ifdef HAVE_AUBIO
        input_ = new_fvec(bufferSize);
        output_ = new_fvec(1);
        pitch_ = new_aubio_pitch("yinfast", bufferSize, bufferSize / 2, sampleRate);
        aubio_pitch_set_unit(pitch_, "Hz");
        aubio_pitch_set_tolerance(pitch_, 0.7f);
        aubio_pitch_set_silence(pitch_, silenceThresholdDb_);
        LOGI("aubio pitch detector initialized: confidence=%.2f, silence=%.1fdB",
             confidenceThreshold_, silenceThresholdDb_);
#else
        LOGI("aubio not available, using stub pitch detector");
#endif
    }

    void setConfidenceThreshold(float threshold) override {
        confidenceThreshold_ = threshold;
        LOGI("Confidence threshold set to %.2f", threshold);
    }

    void setSilenceThreshold(float thresholdDb) override {
        silenceThresholdDb_ = thresholdDb;
#ifdef HAVE_AUBIO
        if (pitch_) {
            aubio_pitch_set_silence(pitch_, thresholdDb);
        }
#endif
        LOGI("Silence threshold set to %.1f dB", thresholdDb);
    }

    ~PitchDetectorImpl() override {
#ifdef HAVE_AUBIO
        if (pitch_) del_aubio_pitch(pitch_);
        if (input_) del_fvec(input_);
        if (output_) del_fvec(output_);
#endif
    }

    PitchResult detect(const float* samples, int numSamples) override {
        PitchResult result = {0.0f, 0.0f, -1, 0};

#ifdef HAVE_AUBIO
        if (numSamples != bufferSize_) {
            return result;
        }

        // Copy samples to aubio input vector
        for (int i = 0; i < numSamples; ++i) {
            fvec_set_sample(input_, samples[i], i);
        }

        // Run pitch detection
        aubio_pitch_do(pitch_, input_, output_);

        float freq = fvec_get_sample(output_, 0);
        float confidence = aubio_pitch_get_confidence(pitch_);

        // Use configurable confidence threshold
        if (freq > 20.0f && confidence > confidenceThreshold_) {
            result.frequency = freq;
            result.confidence = confidence;
            result.midiNote = frequencyToMidi(freq);
            result.centDeviation = calculateCentDeviation(freq, result.midiNote);
        }
#else
        // Stub: no pitch detection without aubio
        (void)samples;
        (void)numSamples;
#endif
        return result;
    }

private:
    int frequencyToMidi(float freq) {
        // MIDI note = 69 + 12 * log2(freq / 440)
        return static_cast<int>(std::round(69.0f + 12.0f * std::log2(freq / 440.0f)));
    }

    int calculateCentDeviation(float freq, int midiNote) {
        // Expected frequency for MIDI note
        float expected = 440.0f * std::pow(2.0f, (midiNote - 69) / 12.0f);
        // Cents = 1200 * log2(actual / expected)
        return static_cast<int>(std::round(1200.0f * std::log2(freq / expected)));
    }

    int sampleRate_;
    int bufferSize_;
    float confidenceThreshold_ = 0.3f;
    float silenceThresholdDb_ = -50.0f;

#ifdef HAVE_AUBIO
    fvec_t* input_ = nullptr;
    fvec_t* output_ = nullptr;
    aubio_pitch_t* pitch_ = nullptr;
#endif
};

std::unique_ptr<PitchDetector> createPitchDetector(int sampleRate, int bufferSize) {
    return std::make_unique<PitchDetectorImpl>(sampleRate, bufferSize);
}

}  // namespace musicsheetflow
