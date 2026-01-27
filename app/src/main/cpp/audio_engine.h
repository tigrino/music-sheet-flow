#pragma once

#include <functional>
#include <cstdint>
#include <memory>

namespace musicsheetflow {

struct PitchEvent {
    float frequency;       // Hz
    float confidence;      // 0.0-1.0
    int midiNote;          // 0-127
    int centDeviation;     // -50 to +50
    int64_t timestampNs;   // System timestamp
};

using PitchCallback = std::function<void(const PitchEvent&)>;

class AudioEngine {
public:
    virtual ~AudioEngine() = default;

    virtual bool start() = 0;
    virtual void stop() = 0;
    virtual void setNoiseGateThreshold(float thresholdDb) = 0;
    virtual void setPitchCallback(PitchCallback callback) = 0;
};

// Factory function - returns the singleton instance
AudioEngine* getAudioEngine();

}  // namespace musicsheetflow
