#pragma once

#include <string>
#include <memory>

namespace musicsheetflow {

class MidiEngine {
public:
    virtual ~MidiEngine() = default;

    virtual bool loadSoundFont(const std::string& path) = 0;
    virtual bool isLoaded() const = 0;

    virtual void noteOn(int note, float velocity = 0.8f) = 0;
    virtual void noteOff(int note) = 0;
    virtual void allNotesOff() = 0;
    virtual void batchNoteOn(const int* notes, const float* velocities, int count) = 0;

    virtual void noteOnChannel(int channel, int note, float velocity = 0.8f) = 0;
    virtual void noteOffChannel(int channel, int note) = 0;
    virtual void setChannelPreset(int channel, int preset, int bank = 0) = 0;

    virtual void setVolume(float volume) = 0;  // 0.0 - 1.0

    virtual bool start() = 0;
    virtual void stop() = 0;
};

// Factory function
std::unique_ptr<MidiEngine> createMidiEngine();

}  // namespace musicsheetflow
