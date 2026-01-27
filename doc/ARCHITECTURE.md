# Music Sheet Flow - Architecture Document

Version 1.0 | January 2026

## 1. Executive Summary

This document describes the technical architecture for Music Sheet Flow, an Android piano training application with real-time pitch detection and score following. The architecture prioritizes:

1. **Low latency** (<100ms audio-to-visual feedback)
2. **Accuracy** (≥90% pitch detection for piano)
3. **Maintainability** (clean separation of concerns)
4. **Performance** (native C/C++ for audio-critical paths)

## 2. Architecture Decision Records

### ADR-1: Native Android over Cross-Platform

**Decision**: Native Kotlin/Android with C++ audio layer via NDK

**Alternatives Considered**:
- Flutter: Adds ~20-40ms latency through platform channels for audio
- React Native: JavaScript bridge introduces unpredictable latency
- Kotlin Multiplatform: Android-only for v1.0, no benefit

**Rationale**: The 100ms latency requirement is critical. Native Android with Oboe library provides the lowest achievable latency (as low as 10-20ms on supported devices). Cross-platform frameworks add abstraction layers that make meeting this requirement unreliable.

### ADR-2: Oboe for Audio I/O

**Decision**: Use Google's Oboe library for audio capture

**Alternatives Considered**:
- AudioRecord (Java): Higher latency, runs on Java thread
- OpenSL ES directly: Complex, Oboe wraps it better
- AAudio directly: Not available on older devices, Oboe handles fallback

**Rationale**: Oboe automatically selects the best available audio API (AAudio on Android 8.1+, OpenSL ES on older). It's maintained by Google, handles device quirks, and provides consistent low-latency audio across the Android ecosystem.

### ADR-3: C++ Audio Processing Pipeline

**Decision**: Implement audio capture, buffering, and pitch detection in C++ via JNI

**Alternatives Considered**:
- Pure Kotlin with TarsosDSP: Simpler but higher latency due to JNI crossings
- Hybrid (capture in C++, processing in Kotlin): Adds JNI overhead per buffer

**Rationale**: Keeping the entire audio pipeline in C++ eliminates JNI overhead on the critical path. Data only crosses to Kotlin when a note is detected (infrequent event vs. continuous audio stream).

### ADR-4: YIN Algorithm Implementation

**Decision**: Use aubio library (C) for pitch detection

**Alternatives Considered**:
- Custom YIN implementation: Reinventing well-tested code
- TarsosDSP (Java): Works but slower, more GC pressure
- Essentia: Heavier library, more dependencies
- WORLD vocoder: Overkill for monophonic pitch detection

**Rationale**: aubio is battle-tested, available as Debian package (`libaubio-dev`), lightweight, and specifically designed for real-time audio analysis. It implements YIN with optimizations and provides onset detection as a bonus.

### ADR-5: MusicXML Parsing Strategy

**Decision**: Lightweight custom parser using Android's XmlPullParser

**Alternatives Considered**:
- proxymusic (JAXB): Heavy, generates many objects, slow on Android
- Full DOM parsing: Memory-intensive for large scores
- Third-party libraries: Most are JavaScript or Python

**Rationale**: MusicXML is verbose but structurally simple. We need only a subset (notes, rests, measures, time/key signatures, ties). A streaming parser (XmlPullParser) is memory-efficient and fast. The parser can be implemented in ~1500 lines of Kotlin with full control over the internal data model.

### ADR-6: Score Rendering Approach

**Decision**: Custom Canvas rendering via Jetpack Compose

**Alternatives Considered**:
- WebView + VexFlow/OSMD: Adds 50-100ms latency, complex JS-native bridge
- alphaTab: Large dependency, limited customization for our highlighting needs
- Pre-rendered images: Inflexible, large APK size

**Rationale**: Music notation rendering is complex, but our requirements are bounded:
- Standard 5-line staff, treble/bass clef
- Notes, rests, accidentals, ties
- No complex features (tuplets, grace notes, lyrics) in v1.0

Custom rendering provides full control over highlighting animations and integrates seamlessly with Compose. The SMuFL font standard provides music symbols.

### ADR-7: MIDI Synthesis

**Decision**: FluidSynth library with bundled SoundFont

**Alternatives Considered**:
- Android MediaPlayer with MIDI: Poor quality, limited control
- Pre-rendered audio samples: Large APK, inflexible tempo
- Sonivox (Android built-in): Deprecated, poor quality
- Timidity++: Less actively maintained than FluidSynth

**Rationale**: FluidSynth is the industry standard for SoundFont-based synthesis. Available as Debian package (`libfluidsynth-dev`), actively maintained, supports real-time tempo changes, and produces high-quality piano sound. A small (~5MB) piano SoundFont provides good quality without bloating the APK.

### ADR-8: UI Framework

**Decision**: Jetpack Compose with Material 3

**Alternatives Considered**:
- Traditional Views: More boilerplate, harder animations
- Compose + Views hybrid: Unnecessary complexity

**Rationale**: Compose is the modern Android UI standard. Benefits:
- Declarative UI matches our state-driven design
- Efficient recomposition for real-time updates
- Built-in animation APIs for note highlighting
- Canvas API for custom score rendering

## 3. System Architecture

### 3.1 High-Level Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                         Android Application                         │
├────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    UI Layer (Jetpack Compose)                 │  │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────────┐ │  │
│  │  │ ScoreScreen │ │KeyboardView │ │ ControlBar/StatsPanel   │ │  │
│  │  └─────────────┘ └─────────────┘ └─────────────────────────┘ │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                       │
│                              ▼                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │              Presentation Layer (ViewModels)                  │  │
│  │  ┌────────────────┐ ┌────────────────┐ ┌──────────────────┐  │  │
│  │  │PracticeViewModel│ │LibraryViewModel│ │SettingsViewModel│  │  │
│  │  └────────────────┘ └────────────────┘ └──────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                       │
│                              ▼                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                     Domain Layer                              │  │
│  │  ┌───────────────┐ ┌────────────┐ ┌─────────────────────┐    │  │
│  │  │PositionTracker│ │ NoteMatcher│ │ BeatClock           │    │  │
│  │  └───────────────┘ └────────────┘ └─────────────────────┘    │  │
│  │  ┌───────────────┐ ┌────────────┐ ┌─────────────────────┐    │  │
│  │  │ ScoreManager  │ │TimingCalc  │ │ SessionStats        │    │  │
│  │  └───────────────┘ └────────────┘ └─────────────────────┘    │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                       │
│                              ▼                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                      Data Layer                               │  │
│  │  ┌─────────────────┐ ┌─────────────────┐ ┌────────────────┐  │  │
│  │  │ ScoreRepository │ │ StatsRepository │ │ PrefsRepository│  │  │
│  │  └─────────────────┘ └─────────────────┘ └────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────┘  │
│         │                      │                      │              │
│         ▼                      ▼                      ▼              │
│  ┌────────────┐        ┌────────────┐         ┌────────────┐        │
│  │MusicXML    │        │  Room DB   │         │ DataStore  │        │
│  │Parser      │        │            │         │            │        │
│  └────────────┘        └────────────┘         └────────────┘        │
│                                                                      │
├────────────────────────────────────────────────────────────────────┤
│                         JNI Bridge                                  │
├────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                   Native Layer (C++)                          │  │
│  │                                                                │  │
│  │  ┌─────────────────────────────────────────────────────────┐  │  │
│  │  │                  AudioEngine (Oboe)                      │  │  │
│  │  │  ┌──────────┐ ┌───────────┐ ┌──────────────────────┐    │  │  │
│  │  │  │AudioInput│ │RingBuffer │ │ PitchDetector (aubio)│    │  │  │
│  │  │  └──────────┘ └───────────┘ └──────────────────────┘    │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  │                                                                │  │
│  │  ┌─────────────────────────────────────────────────────────┐  │  │
│  │  │              MidiEngine (FluidSynth)                     │  │  │
│  │  │  ┌──────────┐ ┌───────────┐ ┌──────────────────────┐    │  │  │
│  │  │  │Synthesizer│ │SoundFont  │ │ AudioOutput (Oboe)   │    │  │  │
│  │  │  └──────────┘ └───────────┘ └──────────────────────┘    │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  │                                                                │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
└────────────────────────────────────────────────────────────────────┘
```

### 3.2 Audio Pipeline Detail

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Microphone │────▶│ Oboe Input  │────▶│ Ring Buffer │────▶│ Noise Gate  │
│             │     │  Stream     │     │ (2048 samp) │     │             │
└─────────────┘     └─────────────┘     └─────────────┘     └─────────────┘
                                                                   │
                                                                   ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  JNI Call   │◀────│ Pitch Event │◀────│ Note Class- │◀────│ YIN (aubio) │
│ to Kotlin   │     │ (freq, conf)│     │   ifier     │     │             │
└─────────────┘     └─────────────┘     └─────────────┘     └─────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         Kotlin/JVM Layer                                 │
│  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐                │
│  │ Note Matcher│────▶│  Position   │────▶│  UI State   │                │
│  │             │     │  Tracker    │     │  Update     │                │
│  └─────────────┘     └─────────────┘     └─────────────┘                │
└─────────────────────────────────────────────────────────────────────────┘

Latency Budget:
┌─────────────────────────────────────────────────────────────────────────┐
│ Audio Buffer: ~23ms │ Processing: ~10ms │ JNI + UI: ~15ms │ Total: ~48ms│
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.3 Data Flow for Practice Session

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           Practice Session Flow                           │
└──────────────────────────────────────────────────────────────────────────┘

1. Session Start
   User selects score ──▶ MusicXML Parser ──▶ NoteSequence ──▶ PositionTracker
                                                                    │
                                                     Initialize at note 0
                                                                    │
2. Practice Loop                                                    ▼
   ┌────────────────────────────────────────────────────────────────────┐
   │                                                                      │
   │  AudioEngine ──▶ PitchDetected(freq, confidence)                    │
   │       │                                                              │
   │       ▼                                                              │
   │  NoteMatcher.match(detectedPitch, expectedNote)                     │
   │       │                                                              │
   │       ├──▶ MATCH ──▶ PositionTracker.advance()                      │
   │       │                    │                                         │
   │       │                    ├──▶ TimingCalculator.computeOffset()    │
   │       │                    │            │                            │
   │       │                    │            ▼                            │
   │       │                    │    PerformanceEvent(CORRECT, offset)   │
   │       │                    │            │                            │
   │       │                    │            ▼                            │
   │       │                    └──▶ UI: Highlight next note              │
   │       │                              Update keyboard                 │
   │       │                              Update stats                    │
   │       │                                                              │
   │       ├──▶ LOOKAHEAD_MATCH ──▶ Tentative advance, await confirm     │
   │       │                                                              │
   │       └──▶ NO_MATCH ──▶ UI: Flash red (wrong note)                  │
   │                              Position unchanged                      │
   │                                                                      │
   └────────────────────────────────────────────────────────────────────┘

3. Session End
   User stops ──▶ SessionStats.finalize() ──▶ StatsRepository.save()
                                                      │
                                                      ▼
                                              SessionSummaryScreen
```

## 4. Component Specifications

### 4.1 Native Audio Engine (C++)

**Responsibilities**:
- Capture audio from microphone via Oboe
- Maintain low-latency ring buffer
- Apply noise gate
- Detect pitch using aubio's YIN implementation
- Classify pitch to MIDI note number
- Notify Kotlin layer of detected notes

**Key Classes**:
```cpp
class AudioEngine {
    void start();
    void stop();
    void setNoiseGateThreshold(float db);
    void setOnPitchDetected(std::function<void(PitchEvent)> callback);
};

struct PitchEvent {
    float frequency;      // Hz
    float confidence;     // 0.0-1.0
    int midiNote;         // 0-127
    int centDeviation;    // -50 to +50
    int64_t timestampNs;
};
```

**Thread Model**:
- Audio callback runs on high-priority audio thread
- Pitch detection runs on dedicated worker thread
- Events posted to Kotlin via JNI on worker thread

### 4.2 MIDI Engine (C++)

**Responsibilities**:
- Load and manage SoundFont
- Synthesize MIDI notes in real-time
- Support tempo changes without audio artifacts
- Mix with metronome clicks

**Key Classes**:
```cpp
class MidiEngine {
    void loadSoundFont(const char* path);
    void noteOn(int channel, int note, int velocity);
    void noteOff(int channel, int note);
    void setTempo(float bpm);
    void playSequence(const NoteSequence& seq);
    void stop();
};
```

### 4.3 Position Tracker (Kotlin)

**Responsibilities**:
- Maintain current position in score
- Implement 2-note lookahead matching algorithm
- Handle tentative advances and confirmations
- Track skipped notes

**State Machine**:
```
┌─────────────┐    pitch matches N    ┌─────────────┐
│  WAITING_N  │──────────────────────▶│ WAITING_N+1 │
└─────────────┘                       └─────────────┘
       │                                     │
       │ pitch matches N+1                   │
       ▼                                     │
┌─────────────────┐                         │
│ TENTATIVE_N+1   │                         │
│ (N marked skip) │                         │
└─────────────────┘                         │
       │                                     │
       ├── pitch matches N+2 ──▶ Confirm, go to WAITING_N+2
       ├── pitch matches N ────▶ Undo skip, go to WAITING_N+1
       └── pitch matches other ─▶ Undo skip, go to WAITING_N
```

### 4.4 Score Renderer (Compose)

**Responsibilities**:
- Render staff lines, clefs, key/time signatures
- Render notes with appropriate colors based on state
- Handle automatic scrolling (continuous mode)
- Handle page turns (page mode)
- Support zoom and pan gestures

**Rendering Strategy**:
- Pre-render static elements (staff lines, clefs) to bitmap
- Dynamic elements (notes, highlights) drawn each frame
- Use Compose's `drawWithCache` for efficient caching
- SMuFL font (Bravura) for music symbols

### 4.5 Virtual Keyboard (Compose)

**Responsibilities**:
- Display 1.5-3 octave piano keyboard
- Auto-scroll to keep expected note visible
- Highlight expected key (blue)
- Flash feedback on played notes (green/red)
- Animate transitions smoothly

## 5. Data Models

### 5.1 Score Data Model

```kotlin
data class Score(
    val id: String,
    val title: String,
    val composer: String?,
    val parts: List<Part>,
    val metadata: ScoreMetadata
)

data class Part(
    val id: String,
    val name: String,          // "Piano Right Hand", "Piano Left Hand"
    val measures: List<Measure>,
    val clef: Clef,
    val keySignature: KeySignature,
    val timeSignature: TimeSignature
)

data class Measure(
    val number: Int,
    val notes: List<NoteEvent>,
    val tempoChange: Float?    // BPM if tempo marking present
)

data class NoteEvent(
    val id: String,
    val pitch: Pitch,          // null for rests
    val startBeat: Float,      // Position within measure
    val durationBeats: Float,
    val voice: Int,
    val isTied: Boolean,
    val articulation: Articulation?
)

data class Pitch(
    val step: PitchStep,       // C, D, E, F, G, A, B
    val octave: Int,           // 0-8
    val alter: Int,            // -1 (flat), 0 (natural), 1 (sharp)
    val midiNumber: Int        // Computed: 12 * (octave + 1) + stepOffset + alter
)
```

### 5.2 Performance Data Model

```kotlin
data class PerformanceEvent(
    val timestamp: Long,
    val expectedNoteId: String,
    val detectedPitch: Pitch?,
    val confidence: Float,
    val result: PerformanceResult,
    val timingOffsetMs: Int
)

enum class PerformanceResult {
    CORRECT_ON_TIME,    // Within ±100ms
    CORRECT_EARLY,      // Correct pitch, >100ms early
    CORRECT_LATE,       // Correct pitch, >100ms late
    WRONG_PITCH,        // Wrong pitch played
    SKIPPED             // Note was skipped (manual or lookahead)
}

data class SessionStats(
    val scoreId: String,
    val startTime: Long,
    val endTime: Long,
    val totalNotes: Int,
    val correctNotes: Int,
    val wrongNotes: Int,
    val skippedNotes: Int,
    val onTimePercent: Float,
    val earlyPercent: Float,
    val latePercent: Float,
    val problemNotes: List<String>  // Note IDs with frequent errors
)
```

## 6. Technology Stack Summary

| Layer | Technology | Justification |
|-------|------------|---------------|
| UI | Jetpack Compose + Material 3 | Modern, declarative, efficient animations |
| Architecture | MVVM + Clean Architecture | Testable, maintainable, standard Android |
| DI | Hilt | Standard for Android, compile-time safe |
| Async | Kotlin Coroutines + Flow | Modern, structured concurrency |
| Local DB | Room | Standard Android persistence |
| Preferences | DataStore | Type-safe, async, replaces SharedPreferences |
| Audio I/O | Oboe (C++) | Lowest latency audio on Android |
| Pitch Detection | aubio (C) | Battle-tested YIN implementation |
| MIDI Synthesis | FluidSynth (C) | High-quality SoundFont synthesis |
| Score Parsing | XmlPullParser | Lightweight, streaming, built-in |
| Score Rendering | Custom Compose Canvas | Full control, no WebView latency |
| Music Font | Bravura (SMuFL) | Standard, comprehensive music symbols |

## 7. Performance Considerations

### 7.1 Latency Optimization

1. **Audio buffer size**: Use smallest stable buffer (typically 256-512 samples)
2. **Exclusive mode**: Request exclusive audio device access via Oboe
3. **Thread priority**: Audio thread at `SCHED_FIFO` priority
4. **Avoid allocations**: Pre-allocate buffers, use object pools in hot paths
5. **JNI minimization**: Only cross JNI boundary for detected notes, not raw audio

### 7.2 Memory Management

1. **Score loading**: Stream-parse large MusicXML, don't load entire DOM
2. **Render caching**: Cache staff bitmaps, only redraw dynamic elements
3. **SoundFont**: Use compressed SF3 format (~5MB vs ~50MB uncompressed)
4. **Session data**: Write to Room incrementally, don't accumulate in memory

### 7.3 Battery Optimization

1. **Audio session**: Release audio resources when paused
2. **Screen**: Allow screen dimming except score area
3. **Background**: No background processing when app not visible
4. **Sensors**: Only microphone active, no unnecessary sensors

## 8. Testing Strategy

### 8.1 Unit Tests

| Component | Coverage Target | Focus Areas |
|-----------|-----------------|-------------|
| MusicXML Parser | 90% | Edge cases, malformed input |
| Position Tracker | 95% | State transitions, lookahead logic |
| Note Matcher | 95% | Pitch tolerance, timing windows |
| Timing Calculator | 90% | Edge cases, overflow |

### 8.2 Integration Tests

- Audio pipeline: Recorded audio files → pitch detection → expected MIDI notes
- Score loading: MusicXML files → internal model → correct note sequence
- Full practice loop: Simulated pitch events → UI state changes

### 8.3 Performance Tests

- Latency measurement: Timestamp at audio callback → timestamp at UI update
- Memory profiling: No leaks during extended practice sessions
- Battery drain: 2-hour practice session < 20% battery on reference device

## 9. Security Considerations

1. **Audio privacy**: All audio processed locally, never transmitted
2. **File access**: Scoped storage for imported scores
3. **No network**: Core functionality works offline
4. **Permissions**: Only RECORD_AUDIO required; storage optional

## 10. Future Extensibility

The architecture supports future enhancements:

1. **Polyphonic detection**: Replace aubio with ML-based detector (e.g., Basic Pitch)
2. **MIDI input**: Add MIDI input source alongside microphone
3. **Cloud sync**: Add cloud repository implementation behind existing interfaces
4. **iOS port**: Native layer (C++) is portable; UI would need SwiftUI rewrite
5. **Additional instruments**: Pitch detection is instrument-agnostic; add instrument profiles

---

*Document prepared by Albert 'Tigr' Zenkoff*
