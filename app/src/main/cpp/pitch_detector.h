#pragma once

#include <memory>

namespace musicsheetflow {

struct PitchResult {
    float frequency;      // Hz (0 if no pitch detected)
    float confidence;     // 0.0-1.0
    int midiNote;         // 0-127, -1 if no pitch
    int centDeviation;    // -50 to +50 cents from nearest note
};

class PitchDetector {
public:
    virtual ~PitchDetector() = default;

    // Detect pitch from audio samples
    // Returns PitchResult with frequency=0 if no pitch detected
    virtual PitchResult detect(const float* samples, int numSamples) = 0;

    // Set minimum confidence threshold (0.0-1.0, default 0.3)
    virtual void setConfidenceThreshold(float threshold) = 0;

    // Set silence threshold in dB (e.g., -50.0, default -50)
    virtual void setSilenceThreshold(float thresholdDb) = 0;
};

// Factory function to create pitch detector
// sampleRate: audio sample rate (typically 44100)
// bufferSize: number of samples per detection (typically 2048)
std::unique_ptr<PitchDetector> createPitchDetector(int sampleRate, int bufferSize);

}  // namespace musicsheetflow
