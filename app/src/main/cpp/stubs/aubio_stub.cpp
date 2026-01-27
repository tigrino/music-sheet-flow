// aubio stub - pitch detection fallback
// This stub is only used if aubio source is not available

#include <android/log.h>

#define LOG_TAG "AubioStub"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace musicsheetflow {

class PitchDetectorStub {
public:
    PitchDetectorStub(int sampleRate, int bufferSize) {
        LOGW("Pitch detection not available - aubio not found");
    }

    float detectPitch(const float* samples, int numSamples, float* confidence) {
        *confidence = 0.0f;
        return 0.0f;  // No pitch detected
    }
};

}  // namespace musicsheetflow
