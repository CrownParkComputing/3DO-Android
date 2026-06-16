// SPDX-License-Identifier: BSD-2-Clause
//
// Stub for libnativewindow.so used to satisfy Turnip's DT_NEEDED list when
// the classloader-namespace sandbox does not allow apps to dlopen the
// system libnativewindow.so directly.
//
// Turnip uses the AHardwareBuffer C API (not the C++ libandroid NativeWindow
// class), so we only need to provide AHardwareBuffer_allocate and friends.
// These map to AHardwareBuffer instances that the underlying KGSL allocator
// can back with GPU memory. We return NULL/error so Turnip falls back to
// the legacy linear-image path (which works for our 320x240 frame upload
// and the swapchain presents — we don't need a hardware buffer for the
// composited output).
//
// These stubs are co-located with the user-imported Turnip driver at
// getFilesDir()/drivers/libnativewindow.so.

#include <stddef.h>
#include <stdint.h>

extern "C" {

// Forward-declare the opaque AHardwareBuffer from <android/hardware_buffer.h>
struct AHardwareBuffer;

typedef struct AHardwareBuffer AHardwareBuffer_t;

// Mirrors AHardwareBuffer_Desc
struct AHardwareBufferDesc {
    uint32_t width;
    uint32_t height;
    uint32_t layers;
    uint32_t format;
    uint32_t usage;
    uint32_t stride;
    uint32_t rfu0;
    uint64_t rfu1;
};

__attribute__((visibility("default")))
int AHardwareBuffer_isSupported(uint32_t /*format*/, uint64_t /*usage*/) {
    return 0; // false
}

__attribute__((visibility("default")))
int AHardwareBuffer_allocate(const AHardwareBufferDesc* /*desc*/,
                            AHardwareBuffer_t** outBuffer) {
    if (outBuffer) *outBuffer = nullptr;
    return -1; // NO_MEMORY-equivalent
}

__attribute__((visibility("default")))
void AHardwareBuffer_acquire(AHardwareBuffer_t* /*buffer*/) {
    // no-op
}

__attribute__((visibility("default")))
void AHardwareBuffer_release(AHardwareBuffer_t* /*buffer*/) {
    // no-op
}

__attribute__((visibility("default")))
int AHardwareBuffer_describe(const AHardwareBuffer_t* /*buffer*/,
                             AHardwareBufferDesc* /*outDesc*/) {
    return -1;
}

__attribute__((visibility("default")))
const void* AHardwareBuffer_getNativeHandle(const AHardwareBuffer_t* /*buffer*/) {
    return nullptr;
}

} // extern "C"
