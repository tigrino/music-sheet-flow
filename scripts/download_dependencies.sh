#!/bin/bash
# Download third-party dependencies for Music Sheet Flow
# Run from project root: ./scripts/download_dependencies.sh

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
THIRD_PARTY="$PROJECT_ROOT/app/src/main/cpp/third_party"
ASSETS="$PROJECT_ROOT/app/src/main/assets"
FONTS="$PROJECT_ROOT/app/src/main/res/font"

echo "Project root: $PROJECT_ROOT"

# Create directories
mkdir -p "$THIRD_PARTY"/aubio
mkdir -p "$THIRD_PARTY"/oboe
mkdir -p "$ASSETS"/soundfonts
mkdir -p "$FONTS"

# Download Oboe
echo ""
echo "=== Downloading Oboe 1.8.0 ==="
if [ ! -f "$THIRD_PARTY/oboe/CMakeLists.txt" ]; then
    wget -q --show-progress https://github.com/google/oboe/archive/refs/tags/1.8.0.tar.gz -O /tmp/oboe.tar.gz
    tar -xzf /tmp/oboe.tar.gz -C /tmp
    cp -r /tmp/oboe-1.8.0/* "$THIRD_PARTY/oboe/"
    rm -rf /tmp/oboe-1.8.0 /tmp/oboe.tar.gz
    echo "Oboe downloaded to $THIRD_PARTY/oboe/"
else
    echo "Oboe already present"
fi

# Download aubio
echo ""
echo "=== Downloading aubio 0.4.9 ==="
if [ ! -f "$THIRD_PARTY/aubio/src/aubio.h" ]; then
    wget -q --show-progress https://github.com/aubio/aubio/archive/refs/tags/0.4.9.tar.gz -O /tmp/aubio.tar.gz
    tar -xzf /tmp/aubio.tar.gz -C /tmp
    cp -r /tmp/aubio-0.4.9/* "$THIRD_PARTY/aubio/"
    rm -rf /tmp/aubio-0.4.9 /tmp/aubio.tar.gz
    echo "aubio downloaded to $THIRD_PARTY/aubio/"
else
    echo "aubio already present"
fi

# Download TinySoundFont (header-only MIDI synthesizer)
echo ""
echo "=== Downloading TinySoundFont ==="
if [ ! -f "$THIRD_PARTY/tsf.h" ]; then
    wget -q --show-progress https://raw.githubusercontent.com/schellingb/TinySoundFont/master/tsf.h -O "$THIRD_PARTY/tsf.h"
    wget -q --show-progress https://raw.githubusercontent.com/schellingb/TinySoundFont/master/tml.h -O "$THIRD_PARTY/tml.h"
    echo "TinySoundFont downloaded to $THIRD_PARTY/"
else
    echo "TinySoundFont already present"
fi

# Download Bravura font
echo ""
echo "=== Downloading Bravura font 1.380 (stable) ==="
if [ ! -f "$FONTS/bravura.otf" ]; then
    wget -q --show-progress https://github.com/steinbergmedia/bravura/archive/refs/tags/bravura-1.380.tar.gz -O /tmp/bravura.tar.gz
    tar -xzf /tmp/bravura.tar.gz -C /tmp
    cp /tmp/bravura-bravura-1.380/redist/otf/Bravura.otf "$FONTS/bravura.otf"
    rm -rf /tmp/bravura-bravura-1.380 /tmp/bravura.tar.gz
    echo "Bravura font downloaded to $FONTS/bravura.otf"
else
    echo "Bravura font already present"
fi

# Copy SoundFont from system (if available) or download
echo ""
echo "=== Setting up SoundFont ==="
if [ ! -f "$ASSETS/soundfonts/TimGM6mb.sf2" ]; then
    if [ -f "/usr/share/sounds/sf2/TimGM6mb.sf2" ]; then
        cp /usr/share/sounds/sf2/TimGM6mb.sf2 "$ASSETS/soundfonts/"
        echo "Copied TimGM6mb.sf2 from system"
    else
        echo "Downloading TimGM6mb.sf2..."
        wget -q --show-progress https://archive.org/download/TimGM6mb/TimGM6mb.sf2 -O "$ASSETS/soundfonts/TimGM6mb.sf2"
        echo "Downloaded TimGM6mb.sf2"
    fi
else
    echo "SoundFont already present"
fi

echo ""
echo "=== Dependencies downloaded ==="
echo ""
echo "Directory structure:"
echo "  $THIRD_PARTY/oboe/          - Oboe audio library"
echo "  $THIRD_PARTY/aubio/         - aubio pitch detection library"
echo "  $THIRD_PARTY/tsf.h          - TinySoundFont MIDI synthesizer"
echo "  $THIRD_PARTY/tml.h          - TinyMidiLoader (optional)"
echo "  $FONTS/bravura.otf          - Bravura music notation font"
echo "  $ASSETS/soundfonts/         - SoundFont files"
