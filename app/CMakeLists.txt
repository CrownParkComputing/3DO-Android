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

# Find Vulkan library
find_library(
    vulkan-lib
    vulkan
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

add_library(
    wavpack
    STATIC
    cpp/third_party/wavpack/src/common_utils.c
    cpp/third_party/wavpack/src/decorr_utils.c
    cpp/third_party/wavpack/src/entropy_utils.c
    cpp/third_party/wavpack/src/extra1.c
    cpp/third_party/wavpack/src/extra2.c
    cpp/third_party/wavpack/src/open_utils.c
    cpp/third_party/wavpack/src/open_filename.c
    cpp/third_party/wavpack/src/open_legacy.c
    cpp/third_party/wavpack/src/open_raw.c
    cpp/third_party/wavpack/src/pack.c
    cpp/third_party/wavpack/src/pack_dns.c
    cpp/third_party/wavpack/src/pack_floats.c
    cpp/third_party/wavpack/src/pack_utils.c
    cpp/third_party/wavpack/src/read_words.c
    cpp/third_party/wavpack/src/tags.c
    cpp/third_party/wavpack/src/tag_utils.c
    cpp/third_party/wavpack/src/unpack.c
    cpp/third_party/wavpack/src/unpack_floats.c
    cpp/third_party/wavpack/src/unpack_seek.c
    cpp/third_party/wavpack/src/unpack_utils.c
    cpp/third_party/wavpack/src/write_words.c
)

target_include_directories(wavpack PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp/third_party/wavpack/include
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp/third_party/wavpack/src
)

set_target_properties(wavpack PROPERTIES POSITION_INDEPENDENT_CODE ON)

# ---- SPIR-V shaders for the Vulkan renderer ----
# Sourced from app/cpp/renderers/shaders/*.vert / *.frag, compiled by
# glslangValidator, and embedded as a uint32_t array by spv_to_cpp.py.
# The committed vulkan_shaders.gen.cpp is used as a fallback so CI builds
# without glslangValidator still work.
find_package(Python3 COMPONENTS Interpreter)
find_program(GLSLANG_VALIDATOR glslangValidator)
set(VK_SHADERS_DIR    ${CMAKE_CURRENT_SOURCE_DIR}/cpp/renderers/shaders)
set(VK_SHADERS_OUTDIR ${CMAKE_CURRENT_BINARY_DIR}/shaders)
set(VK_VERT_SPV       ${VK_SHADERS_OUTDIR}/vulkan.vert.spv)
set(VK_FRAG_SPV       ${VK_SHADERS_OUTDIR}/vulkan.frag.spv)
set(VK_SHADERS_GEN    ${VK_SHADERS_DIR}/vulkan_shaders.gen.cpp)

if(GLSLANG_VALIDATOR)
  file(MAKE_DIRECTORY ${VK_SHADERS_OUTDIR})
  add_custom_command(
    OUTPUT  ${VK_VERT_SPV} ${VK_FRAG_SPV}
    COMMAND ${GLSLANG_VALIDATOR} -V -S vert ${VK_SHADERS_DIR}/vulkan.vert -o ${VK_VERT_SPV}
    COMMAND ${GLSLANG_VALIDATOR} -V -S frag ${VK_SHADERS_DIR}/vulkan.frag -o ${VK_FRAG_SPV}
    DEPENDS ${VK_SHADERS_DIR}/vulkan.vert ${VK_SHADERS_DIR}/vulkan.frag
    COMMENT "Building Vulkan SPIR-V shaders with glslangValidator"
    VERBATIM)
  add_custom_command(
    OUTPUT  ${VK_SHADERS_DIR}/vulkan_shaders.gen.cpp
    COMMAND ${CMAKE_COMMAND} -E make_directory ${VK_SHADERS_DIR}
    COMMAND ${Python3_EXECUTABLE} ${VK_SHADERS_DIR}/spv_to_cpp.py
            ${VK_VERT_SPV} vert_spv
            ${VK_FRAG_SPV} frag_spv
            > ${VK_SHADERS_DIR}/vulkan_shaders.gen.cpp
    DEPENDS ${VK_VERT_SPV} ${VK_FRAG_SPV} ${VK_SHADERS_DIR}/spv_to_cpp.py
    COMMENT "Regenerating vulkan_shaders.gen.cpp"
    VERBATIM)
endif()

# Add all source files
add_library(
    native-lib
    SHARED
    cpp/jni_bridge.cpp
    cpp/input_handler.cpp
    cpp/unified_renderer.cpp

    # Renderer files
    cpp/renderers/software_renderer.cpp
    cpp/renderers/vulkan_renderer.cpp
    cpp/renderers/shaders/vulkan_shaders.gen.cpp

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
    ${vulkan-lib}
    chdr
    wavpack
    ${zlib-lib}
)

target_link_options(native-lib PRIVATE "-Wl,-z,max-page-size=16384")

# ---- Performance: always optimize the software 3DO core ----
# The Opera core is a pure software emulator (ARM60 interpreter + MADAM CEL
# rasterizer + DSP). At -O0 (the Android "Debug" default) it runs several times
# too slow and games stutter. Force aggressive optimization on the emulator and
# its codec helper libraries in EVERY build type. These compile options are
# appended after the build-type flags, so the trailing -O3 overrides the
# Debug -O0 (last -O wins for Clang). The app/Java layer stays fully debuggable.
set(CORE_OPT_FLAGS -O3 -fno-math-errno -funroll-loops)
target_compile_options(native-lib PRIVATE ${CORE_OPT_FLAGS})
target_compile_definitions(native-lib PRIVATE NDEBUG)
target_compile_options(chdr      PRIVATE ${CORE_OPT_FLAGS})
target_compile_options(chdr-lzma PRIVATE ${CORE_OPT_FLAGS})
target_compile_options(chdr-zstd PRIVATE ${CORE_OPT_FLAGS})
target_compile_options(wavpack   PRIVATE ${CORE_OPT_FLAGS})
