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

    # libopera hardware-emulation backend
    # (individual files will be removed as native C++ replacements are completed)
    cpp/libopera/opera_3do.c
    cpp/libopera/opera_arm.c
    # opera_bios.c removed – BIOS loading handled by native_bios.h
    cpp/libopera/opera_bitop.c
    cpp/libopera/opera_cdrom.c
    cpp/libopera/opera_clio.c
    cpp/libopera/opera_clock.c
    cpp/libopera/opera_diag_port.c
    cpp/libopera/opera_dsp.c
    cpp/libopera/opera_fixedpoint_math.c
    cpp/libopera/opera_log.c
    cpp/libopera/opera_madam.c
    cpp/libopera/opera_mem.c
    # opera_nvram.c removed – NVRAM format functions implemented in native_core.cpp
    cpp/libopera/opera_pbus.c
    cpp/libopera/opera_region.c
    cpp/libopera/opera_sport.c
    cpp/libopera/opera_state.c
    cpp/libopera/opera_vdlp.c
    cpp/libopera/opera_xbus.c
    cpp/libopera/opera_xbus_cdrom_plugin.c
    cpp/libopera/prng16.c
    cpp/libopera/prng32.c
    
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
