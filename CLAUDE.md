# Music Sheet Flow - Project CLAUDE.md

Android piano training application with real-time pitch detection and score following.

## Project Overview

**Application Name**: Music Sheet Flow
**Platform**: Android (API 26+, Android 8.0 Oreo and above)
**Primary Language**: Kotlin (native Android)
**Requirements**: See `work/Requirements/SheetFlow_Technical_Requirements.md`
**Mock-ups**: See `work/mock-up/` directory

## Directory Structure

```
/app                    - Android application source code
/work                   - Working files (NOT part of the repo)
  /Requirements         - Technical requirements documents
  /mock-up              - UI mock-ups (SVG)
  /reports              - Analysis reports and planning documents
```

## Core Components

| Module                  | Responsibility                                                |
|-------------------------|---------------------------------------------------------------|
| Audio Input Module      | Captures audio from device microphone, applies noise gate     |
| Pitch Detection Engine  | YIN algorithm for fundamental frequency (F0) detection        |
| Score Parser            | Parses MusicXML files into internal note representation       |
| Position Tracker        | Player-driven score position with 2-note lookahead            |
| Beat Clock              | Independent timer at reference tempo for timing feedback      |
| Note Matcher            | Compares detected pitch against expected note(s)              |
| MIDI Playback Engine    | Synthesizes and plays back scores                             |
| Score Renderer          | Renders musical notation with current position highlighting   |
| Feedback Engine         | Computes timing offset, generates visual feedback             |
| Library Manager         | Bundled scores, local file import                             |

## Technical Specifications

### Audio Processing
- Sample Rate: 44,100 Hz
- Bit Depth: 16-bit PCM
- Buffer Size: 1024 samples (~23ms)
- FFT Window Size: 2048 samples with Hann window
- Pitch Algorithm: YIN with threshold 0.15

### Key Constraints
- Audio latency < 100ms from input to visual feedback
- Pitch detection accuracy >= 90% for piano in quiet environment
- Monophonic detection only (v1.0) - chords not supported
- Player-driven score advancement (Wait Mode default)

## UI Color Scheme

| State             | Color Code     | Usage                              |
|-------------------|----------------|-----------------------------------|
| Current Note      | #2196F3 (Blue) | Note currently expected            |
| Correct On-Time   | #4CAF50 (Green)| Correct pitch, good timing         |
| Correct Early/Late| #FFEB3B (Yellow)| Correct pitch, timing off         |
| Wrong Pitch       | #F44336 (Red)  | Wrong pitch attempted              |
| Skipped           | #9E9E9E (Gray) | Note manually skipped              |
| Upcoming          | #000000 (Black)| Notes not yet reached              |
| Played            | #81C784 (Lt Grn)| Previously played correctly       |
| Page Turn Zone    | #FF9800 (Orange)| Indicates upcoming page turn      |

## Development Guidelines

### Code Standards
- Follow Kotlin coding conventions
- Use Android Jetpack components where appropriate
- Prefer Compose for UI when practical
- All audio processing on dedicated thread, not main UI thread

### Security
- Audio processed locally only, never transmitted
- No hardcoded keys or secrets
- Follow Android permission best practices (microphone, storage)

### Testing
- Unit tests >= 80% coverage for pitch detection, score parsing, position tracking
- Integration tests for audio-to-feedback pipeline
- Performance benchmarks for latency measurement

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest

# Build release APK
./gradlew assembleRelease
```

## Task Log

All task progress is tracked in `.claude_task_log.txt` in the project root.
