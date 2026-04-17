cmake_minimum_required(VERSION 3.22.1)

project("4DO-Android")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find required libraries
find_library(
    log-lib
    log
)

find_library(
    android-lib
    android
)

# Find EGL and OpenGL ES libraries
find_library(
    egl-lib
    EGL
)

find_library(
    gles-lib
    GLESv3
)

# Find zlib for CHD support
find_library(
    zlib-lib
    z
)

add_library(
    chdr-lzma
    STATIC
    cpp/third_party/libchdr/deps/lzma-25.01/src/LzmaDec.c
)

target_include_directories(chdr-lzma PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp/third_party/libchdr/deps/lzma-25.01/include
)

set_target_properties(chdr-lzma PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(
    chdr-zstd
    STATIC
    cpp/third_party/libchdr/deps/zstd-1.5.7/zstddeclib.c
)

target_include_directories(chdr-zstd PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp/third_party/libchdr/deps/zstd-1.5.7
)

set_target_properties(chdr-zstd PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(
    chdr
    STATIC
    cpp/third_party/libchdr/src/libchdr_bitstream.c
    cpp/third_party/libchdr/src/libchdr_cdrom.c
    cpp/third_party/libchdr/src/libchdr_chd.c
    cpp/third_party/libchdr/src/libchdr_codec_cdfl.c
    cpp/third_party/libchdr/src/libchdr_codec_cdlz.c
    cpp/third_party/libchdr/src/libchdr_codec_cdzl.c
    cpp/third_party/libchdr/src/libchdr_codec_cdzs.c
    cpp/third_party/libchdr/src/libchdr_codec_flac.c
    cpp/third_party/libchdr/src/libchdr_codec_huff.c
    cpp/third_party/libchdr/src/libchdr_codec_lzma.c
    cpp/third_party/libchdr/src/libchdr_codec_zlib.c
    cpp/third_party/libchdr/src/libchdr_codec_zstd.c
    cpp/third_party/libchdr/src/libchdr_flac.c
    cpp/third_party/libchdr/src/libchdr_huffman.c
)

target_include_directories(chdr PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp/third_party/libchdr/include
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp/third_party/libchdr/include/dr_libs
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp/third_party/libchdr/deps/lzma-25.01/include
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp/third_party/libchdr/deps/zstd-1.5.7
)

target_compile_definitions(chdr PRIVATE
    CHDR_SYSTEM_ZLIB
    WANT_RAW_DATA_SECTOR=1
    WANT_SUBCODE=1
    VERIFY_BLOCK_CRC=1
)

target_link_libraries(
    chdr
    PRIVATE
    chdr-lzma
    chdr-zstd
    ${zlib-lib}
)

set_target_properties(chdr PROPERTIES POSITION_INDEPENDENT_CODE ON)

# Add all source files
add_library(
    native-lib
    SHARED
    cpp/jni_bridge.cpp
    cpp/input_handler.cpp
    cpp/unified_renderer.cpp
    
    # GPU renderer files
    cpp/renderers/renderer_factory.cpp
    cpp/renderers/gl_renderer.cpp
    cpp/renderers/software_renderer.cpp
    cpp/renderers/vulkan_renderer.cpp

    # Native hardware-emulation backend (ported from legacy opera modules)
    # (individual files will be removed as native C++ replacements are completed)
    cpp/native_core/native_3do.c
    cpp/native_core/native_arm.c
    # opera_bios.c removed – BIOS loading handled by native_bios.h
    cpp/native_core/native_bitop.c
    # migrated build units (now compiled from native_core wrappers)
    cpp/native_core/native_cdrom.c
    cpp/native_core/native_clio.c
    # opera_clock.c removed – replaced by native implementation in native_core.cpp
    # opera_diag_port.c removed – replaced by native implementation in native_core.cpp
    cpp/native_core/native_dsp.c
    # opera_fixedpoint_math.c removed – replaced by native implementation in native_core.cpp
    cpp/native_core/native_log.c
    cpp/native_core/native_madam.c
    cpp/native_core/native_mem.c
    # opera_nvram.c removed – NVRAM format functions implemented in native_core.cpp
    cpp/native_core/native_pbus.c
    # opera_region.c removed – replaced by native implementation in native_core.cpp
    cpp/native_core/native_sport.c
    cpp/native_core/native_state.c
    cpp/native_core/native_vdlp.c
    cpp/native_core/native_xbus.c
    cpp/native_core/native_xbus_cdrom_plugin.c
    # prng16.c / prng32.c removed – replaced by native implementation in native_core.cpp
    
    # Native core (modern C++ coordinator – replaces emulator_core.cpp)
    cpp/native_core/native_core.cpp
    cpp/native_core/native_types.h
    cpp/native_core/native_log.h
    cpp/native_core/native_memory.h
    cpp/native_core/native_nvram.h
    cpp/native_core/native_bios.h
    cpp/native_core/native_cdrom.h
    cpp/native_core/native_chd_backend.h
    cpp/native_core/native_audio.h
    cpp/native_core/native_input.h
)

# Include directories
target_include_directories(native-lib PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp/third_party/libchdr/include
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp/third_party/libchdr/include/dr_libs
)

# Link all libraries
target_link_libraries(
    native-lib
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${gles-lib}
    chdr
    ${zlib-lib}
)

target_link_options(native-lib PRIVATE "-Wl,-z,max-page-size=16384")
