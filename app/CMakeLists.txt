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
    cpp/native_core/native_audio.h
    cpp/native_core/native_input.h
)

# Include directories
target_include_directories(native-lib PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp
)

# Link all libraries
target_link_libraries(
    native-lib
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${gles-lib}
)
