cmake_minimum_required(VERSION 3.22.1)
project(musicsheetflow_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Oboe - from third_party/oboe
set(OBOE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/oboe)
if(EXISTS ${OBOE_DIR}/CMakeLists.txt)
    add_subdirectory(${OBOE_DIR} oboe_build)
    set(OBOE_AVAILABLE TRUE)
else()
    message(FATAL_ERROR "Oboe not found in ${OBOE_DIR}. Run scripts/download_dependencies.sh")
endif()

# aubio - built from third_party/aubio
set(AUBIO_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/aubio)
if(EXISTS ${AUBIO_DIR}/src/aubio.h)
    add_library(aubio STATIC
        # Core
        ${AUBIO_DIR}/src/cvec.c
        ${AUBIO_DIR}/src/fvec.c
        ${AUBIO_DIR}/src/fmat.c
        ${AUBIO_DIR}/src/lvec.c
        ${AUBIO_DIR}/src/mathutils.c
        ${AUBIO_DIR}/src/musicutils.c
        ${AUBIO_DIR}/src/vecutils.c
        # Pitch detection - all methods needed by pitch.c
        ${AUBIO_DIR}/src/pitch/pitch.c
        ${AUBIO_DIR}/src/pitch/pitchyin.c
        ${AUBIO_DIR}/src/pitch/pitchyinfast.c
        ${AUBIO_DIR}/src/pitch/pitchyinfft.c
        ${AUBIO_DIR}/src/pitch/pitchmcomb.c
        ${AUBIO_DIR}/src/pitch/pitchfcomb.c
        ${AUBIO_DIR}/src/pitch/pitchschmitt.c
        ${AUBIO_DIR}/src/pitch/pitchspecacf.c
        # Spectral
        ${AUBIO_DIR}/src/spectral/fft.c
        ${AUBIO_DIR}/src/spectral/ooura_fft8g.c
        ${AUBIO_DIR}/src/spectral/phasevoc.c
        ${AUBIO_DIR}/src/spectral/specdesc.c
        ${AUBIO_DIR}/src/spectral/statistics.c
        ${AUBIO_DIR}/src/spectral/filterbank.c
        ${AUBIO_DIR}/src/spectral/filterbank_mel.c
        # Temporal
        ${AUBIO_DIR}/src/temporal/filter.c
        ${AUBIO_DIR}/src/temporal/biquad.c
        ${AUBIO_DIR}/src/temporal/a_weighting.c
        ${AUBIO_DIR}/src/temporal/c_weighting.c
        ${AUBIO_DIR}/src/temporal/resampler.c
        # Utils
        ${AUBIO_DIR}/src/utils/log.c
        ${AUBIO_DIR}/src/utils/parameter.c
        ${AUBIO_DIR}/src/utils/hist.c
        ${AUBIO_DIR}/src/utils/scale.c
    )
    target_include_directories(aubio PUBLIC ${AUBIO_DIR}/src)
    target_compile_definitions(aubio PRIVATE
        HAVE_STDLIB_H=1
        HAVE_STDIO_H=1
        HAVE_MATH_H=1
        HAVE_STRING_H=1
        HAVE_LIMITS_H=1
        HAVE_STDARG_H=1
        HAVE_C99_VARARGS_MACROS=1
        HAVE_MEMCPY_HACKS=0
    )
    target_link_libraries(aubio m)
    set(AUBIO_AVAILABLE TRUE)
else()
    message(WARNING "aubio source not found in ${AUBIO_DIR}")
    set(AUBIO_AVAILABLE FALSE)
endif()

# TinySoundFont - header-only SoundFont synthesizer (replaces FluidSynth)
# Much simpler, no dependencies, MIT license
set(TSF_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
if(EXISTS ${TSF_DIR}/tsf.h)
    message(STATUS "TinySoundFont found")
    set(TSF_AVAILABLE TRUE)
else()
    message(WARNING "TinySoundFont not found in ${TSF_DIR}")
    set(TSF_AVAILABLE FALSE)
endif()

# Native library sources
set(NATIVE_SOURCES
    audio_engine.cpp
    pitch_detector.cpp
    midi_engine.cpp
    jni_bridge.cpp
)

# Stub sources if libraries not available
if(NOT AUBIO_AVAILABLE)
    list(APPEND NATIVE_SOURCES stubs/aubio_stub.cpp)
endif()

add_library(musicsheetflow_native SHARED ${NATIVE_SOURCES})

target_include_directories(musicsheetflow_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${TSF_DIR}
)

target_link_libraries(musicsheetflow_native
    oboe
    android
    log
)

if(AUBIO_AVAILABLE)
    target_link_libraries(musicsheetflow_native aubio)
    target_compile_definitions(musicsheetflow_native PRIVATE HAVE_AUBIO=1)
endif()

if(TSF_AVAILABLE)
    target_compile_definitions(musicsheetflow_native PRIVATE HAVE_TSF=1)
endif()
