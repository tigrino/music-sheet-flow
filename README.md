# Music Sheet Flow

An Android piano training application with real-time pitch detection and score following. Designed for beginners learning to read music and play piano.

## Overview

Music Sheet Flow displays musical scores and uses microphone-based pitch detection to follow the player's performance in real-time. The app operates in "Wait Mode" by default, meaning the score advances only when the player plays the correct notes - there's no time pressure, allowing learners to practice at their own pace.

## Features

- **Real-time Pitch Detection**: Native YIN algorithm detects piano notes with ≥90% accuracy
- **Player-driven Score Following**: Score waits for correct notes (Wait Mode)
- **Visual Feedback**: Color-coded notes show accuracy and timing
- **Interactive Virtual Keyboard**: 3-octave piano (C3-C6) with MIDI playback
- **Metronome**: Audio and visual beat indicators with adjustable tempo (40-240 BPM)
- **Score Playback**: MIDI synthesis for listening to pieces before practicing
- **Score Library**: Bundled starter library (~25 public domain pieces) plus import support
- **Session Statistics**: Track accuracy, timing, and progress
- **Localization**: English and Russian note naming systems

## User Interface

### Screen Layout

```
+------------------------------------------+
|  [Library]  Score Title     ♩=BPM  [EN]  |  <- Header Bar
+------------------------------------------+
|                                          |
|         Musical Score Display            |  <- Score Area (scrolls automatically)
|         (current note highlighted)       |
|                                          |
+------------------------------------------+
|  [Piano Keyboard - 3 octaves]            |  <- Virtual Keyboard
+------------------------------------------+
|  [⏮][🎵][▶][⏭]  [----Tempo----]  [⚙]   |  <- Control Bar
+------------------------------------------+
```

### Controls Reference

| Button | Name | Function |
|--------|------|----------|
| ⏮ | Restart | Reset score position to beginning |
| 🎵 / ⏸ | Playback | Play/pause score playback (MIDI) |
| ▶ | Practice | Start/stop practice session with pitch detection |
| ⏭ | Skip | Skip current note (marks as gray, enabled only during practice) |
| 🔊 / 🔇 | Metronome | Toggle metronome audio clicks and visual beat indicator |
| 0-4 | Count-In | Cycle through count-in measures (0=off, 1-4 beats before start) |
| ABC | Note Names | Toggle note name labels on score and keyboard |
| ℹ | Statistics | View session stats (accuracy %, note counts, timing breakdown) |
| ⚙ | Settings | Open pitch detection settings (thresholds) |

### Tempo Slider

Adjustable from 40 to 240 BPM. Affects both metronome and timing feedback. Disabled during active practice or playback.

### Language Switcher

Located in the header. Switches between note naming systems:
- **EN**: A, B, C, D, E, F, G
- **RU**: Ля, Си, До, Ре, Ми, Фа, Соль

## How It Works

### Practice Flow

1. **Select a Score**: Tap the Library button to browse and select a piece
2. **Set Tempo**: Adjust the tempo slider to your preferred speed
3. **Optional Setup**: Enable metronome, set count-in measures, toggle note names
4. **Start Practice**: Tap the Practice button (large play icon)
5. **Play Notes**: Play the highlighted note on your piano/keyboard
6. **Get Feedback**: Notes change color based on accuracy:
   - **Blue**: Currently expected note
   - **Green**: Correct pitch, good timing
   - **Yellow**: Correct pitch, early or late timing
   - **Red**: Wrong pitch (brief flash)
   - **Gray**: Skipped note
7. **Continue**: Score advances automatically when you play correct notes
8. **View Stats**: Tap the stats button to see your accuracy and timing breakdown

### Score Following Algorithm

The app uses a 2-note lookahead algorithm:
- Waits for the currently expected note
- If you play the next note instead, it tentatively advances (detecting intentional skips)
- If you return to the earlier note, it undoes the skip
- This allows natural playing without getting stuck on difficult passages

### Pitch Detection Pipeline

1. Microphone captures audio at 44.1 kHz
2. Native YIN algorithm detects fundamental frequency
3. Note Matcher validates pitch stability (30ms) and confidence
4. Position Tracker updates score position
5. Visual feedback rendered within 100ms of input

## Settings

### Pitch Detection Settings

Accessible via the gear icon:

| Setting | Range | Description |
|---------|-------|-------------|
| Confidence Threshold | 0.1-0.8 | Lower = more detections (may increase false positives) |
| Silence Threshold | -70 to -30 dB | Lower = more sensitive to quiet playing |
| Noise Gate | -60 to -30 dB | Lower = less ambient noise filtering |

## Supported Formats

- **MusicXML** (.xml, .musicxml, .mxl) for score import
- Bundled scores in MusicXML format

## Technical Requirements

- **Android**: 8.0 (Oreo) or higher (API 26+)
- **Permissions**: Microphone access required
- **Hardware**: Device microphone required
- **Storage**: Optional for importing custom scores

## Building from Source

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

## Architecture

| Component | Responsibility |
|-----------|----------------|
| Audio Input Module | Captures microphone audio, applies noise gate |
| Pitch Detection Engine | YIN algorithm for frequency detection |
| Score Parser | Parses MusicXML into internal representation |
| Position Tracker | Player-driven score position with lookahead |
| Beat Clock | Independent timer for timing feedback |
| Note Matcher | Compares detected pitch against expected notes |
| MIDI Playback Engine | Synthesizes and plays scores |
| Score Renderer | Renders notation with position highlighting |
| Feedback Engine | Generates visual feedback based on accuracy |

## Performance Specifications

- Audio latency: < 100ms from input to visual feedback
- Pitch accuracy: ±25 cents (quarter semitone)
- Pitch range: A0 (27.5 Hz) to C8 (4186 Hz)
- Detection accuracy: ≥90% for piano in quiet environment

## License

GNU General Public License v3.0

## Author

Albert 'Tigr' Zenkoff
