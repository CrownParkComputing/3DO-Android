// SPDX-License-Identifier: BSD-2-Clause
//
// Stub for libhardware.so used to satisfy Turnip's DT_NEEDED list when the
// classloader-namespace sandbox does not allow apps to dlopen the system
// libhardware.so directly.
//
// Turnip's only required symbol from libhardware.so is hw_get_module. We
// provide a no-op stub that returns -ENOSYS / NULL. Turnip only calls this
// on the legacy GRALLOC/VULKAN HAL probe path; the modern path it actually
// uses (kgsl ioctls + AHardwareBuffer) does not need this. If Turnip's
// own fallback path also doesn't depend on hw_get_module, this is a
// no-op entirely. If a runtime code path does call hw_get_module, the
// returned NULL will cause Turnip to fall back to its built-in defaults.
//
// These stubs are co-located with the user-imported Turnip driver at
// getFilesDir()/drivers/libhardware.so so the dynamic loader's
// "DT_NEEDED same-directory search" finds them.

#include <stddef.h>
#include <stdint.h>

extern "C" {

// libhardware.so exports:
//   hw_get_module(const char* id, const struct hw_module_t** module)
//
// Signature per system/hardware.h. We return 0 with *module = nullptr so the
// caller treats the HAL as unavailable and falls back to defaults.
struct hw_module_t;

__attribute__((visibility("default")))
int hw_get_module(const char* /*id*/, const struct hw_module_t** module) {
    if (module) *module = nullptr;
    return -1; // ENXIO-equivalent
}

} // extern "C"
