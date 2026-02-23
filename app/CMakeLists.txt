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
    cpp/android_main.cpp
    cpp/jni_bridge.cpp
    cpp/input_handler.cpp
    cpp/emulator_core.cpp
    cpp/unified_renderer.cpp
    
    # New GPU renderer files
    cpp/renderers/renderer_factory.cpp
    cpp/renderers/gl_renderer.cpp
    cpp/renderers/software_renderer.cpp
    cpp/renderers/vulkan_renderer.cpp
    cpp/libopera/opera_3do.c
    cpp/libopera/opera_arm.c
    cpp/libopera/opera_bios.c
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
    cpp/libopera/opera_nvram.c
    cpp/libopera/opera_pbus.c
    cpp/libopera/opera_region.c
    cpp/libopera/opera_sport.c
    cpp/libopera/opera_state.c
    cpp/libopera/opera_vdlp.c
    cpp/libopera/opera_xbus.c
    cpp/libopera/opera_xbus_cdrom_plugin.c
    cpp/libopera/prng16.c
    cpp/libopera/prng32.c
    
    # New native core (modern C++ rewrite in progress)
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

# Set native_app_glue path and include
set(NATIVE_APP_GLUE_DIR "${CMAKE_ANDROID_NDK}/sources/android/native_app_glue")
if(NOT EXISTS "${NATIVE_APP_GLUE_DIR}" AND DEFINED ANDROID_NDK)
    set(NATIVE_APP_GLUE_DIR "${ANDROID_NDK}/sources/android/native_app_glue")
endif()

# Include directories - include native_app_glue source
target_include_directories(native-lib PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp
    ${NATIVE_APP_GLUE_DIR}
)

# Add native_app_glue as a prebuilt library
if(EXISTS "${NATIVE_APP_GLUE_DIR}/android_native_app_glue.c")
    add_library(native_app_glue STATIC
        ${NATIVE_APP_GLUE_DIR}/android_native_app_glue.c
    )
    set_target_properties(native_app_glue PROPERTIES
        POSITION_INDEPENDENT_CODE ON
    )
    set(NATIVE_APP_GLUE_LIB native_app_glue)
endif()

# Link all libraries
target_link_libraries(
    native-lib
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${gles-lib}
    ${NATIVE_APP_GLUE_LIB}
)
