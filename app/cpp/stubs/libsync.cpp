// SPDX-License-Identifier: BSD-2-Clause
//
// Stub for libsync.so used to satisfy Turnip's DT_NEEDED list when the
// classloader-namespace sandbox does not allow apps to dlopen the system
// libsync.so directly.
//
// Turnip uses sync_wait / sync_merge for KGSL fence synchronization. We
// stub them to return success with an invalid fence fd so Turnip can
// proceed without actually waiting on anything. This matches the
// behavior of an immediate-completion fence: the GPU work Turnip submits
// will complete synchronously inside the next vkQueueSubmit anyway, so
// the fence being "always-signaled" is functionally equivalent for our
// use-case (the renderer's per-image fence in vulkan_renderer.cpp
// already provides the actual GPU/CPU sync — these Turnip-level fences
// were only used for internal scheduling that we don't need).
//
// These stubs are co-located with the user-imported Turnip driver at
// getFilesDir()/drivers/libsync.so.

#include <stddef.h>
#include <stdint.h>

extern "C" {

// struct sync_fence_info / sync_pt_info are opaque; we never construct them.
struct sync_fence_info;
struct sync_pt_info;

// Returns 0 on success. We return success immediately, which is correct for
// the "fence is already signaled" case.
__attribute__((visibility("default")))
int sync_wait(int /*fd*/, int /*timeout*/) {
    return 0;
}

// Returns the merged fence fd, or -1 on error. We return -1 to indicate
// "no fence created"; callers should handle the error gracefully.
__attribute__((visibility("default")))
int sync_merge(const char* /*name*/, int /*fd1*/, int /*fd2*/) {
    return -1;
}

__attribute__((visibility("default")))
struct sync_fence_info* sync_file_info(int /*fd*/) {
    return nullptr;
}

__attribute__((visibility("default")))
void sync_fence_info_free(struct sync_fence_info* /*info*/) {
    // no-op
}

__attribute__((visibility("default")))
struct sync_pt_info* sync_pt_info(struct sync_fence_info* /*info*/,
                                  void** /*obj*/) {
    return nullptr;
}

} // extern "C"
