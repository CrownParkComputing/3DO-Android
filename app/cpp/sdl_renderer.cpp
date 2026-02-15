#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <algorithm>
#include <cstring>
#include <mutex>

#define LOG_TAG "4DO-Renderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static bool g_renderer_initialized = false;
static int g_screen_width = 320;  // 3DO original resolution
static int g_screen_height = 240;
static ANativeWindow* g_native_window = nullptr;
static std::mutex g_render_mutex;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdo_android_EmulatorActivity_initRenderer(JNIEnv* env, jobject thiz, jint width, jint height) {
    LOGD("Initializing renderer with size %dx%d", width, height);
    
    if (g_renderer_initialized) {
        LOGD("Renderer already initialized");
        return JNI_TRUE;
    }
    
    g_screen_width = width;
    g_screen_height = height;

    std::lock_guard<std::mutex> lock(g_render_mutex);
    if (g_native_window != nullptr) {
        ANativeWindow_setBuffersGeometry(g_native_window, g_screen_width, g_screen_height, WINDOW_FORMAT_RGB_565);
    }
    
    g_renderer_initialized = true;
    LOGD("Renderer initialized successfully");
    
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_renderFrame(JNIEnv* env, jobject thiz, jbyteArray pixelData, jint width, jint height) {
    if (!g_renderer_initialized) {
        return;
    }
    
    if (pixelData == nullptr) {
        return;
    }
    
    // Get pixel data from Java array
    jbyte* data = env->GetByteArrayElements(pixelData, nullptr);
    if (data == nullptr) {
        return;
    }
    
    // Release the array
    env->ReleaseByteArrayElements(pixelData, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_cleanupRenderer(JNIEnv* env, jobject thiz) {
    LOGD("Cleaning up renderer");
    std::lock_guard<std::mutex> lock(g_render_mutex);
    if (g_native_window != nullptr) {
        ANativeWindow_release(g_native_window);
        g_native_window = nullptr;
    }
    g_renderer_initialized = false;
    LOGD("Renderer cleanup complete");
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_setSurface(JNIEnv* env, jobject thiz, jobject surface) {
    std::lock_guard<std::mutex> lock(g_render_mutex);

    if (g_native_window != nullptr) {
        ANativeWindow_release(g_native_window);
        g_native_window = nullptr;
    }

    if (surface != nullptr) {
        g_native_window = ANativeWindow_fromSurface(env, surface);
        if (g_native_window != nullptr) {
            ANativeWindow_setBuffersGeometry(g_native_window, g_screen_width, g_screen_height, WINDOW_FORMAT_RGB_565);
            LOGD("Surface set: %dx%d", g_screen_width, g_screen_height);
        }
    } else {
        LOGD("Surface cleared");
    }
}

// Get screen dimensions
extern "C" void get_screen_size(int* width, int* height) {
    if (width) *width = g_screen_width;
    if (height) *height = g_screen_height;
}

// Render a frame - optimized for FMV streaming
extern "C" void render_frame(const void* pixels, int width, int height) {
    if (!g_renderer_initialized || pixels == nullptr) {
        return;
    }

    // Use try_lock so rendering never blocks the emulator thread.
    // If the surface is busy (previous frame still displaying), skip this frame
    // rather than stalling the emulator and causing audio desync.
    std::unique_lock<std::mutex> lock(g_render_mutex, std::try_to_lock);
    if (!lock.owns_lock()) {
        return; // Surface busy, skip frame (audio keeps flowing)
    }

    if (g_native_window == nullptr) {
        return;
    }

    if (width != g_screen_width || height != g_screen_height) {
        g_screen_width = width;
        g_screen_height = height;
        ANativeWindow_setBuffersGeometry(g_native_window, g_screen_width, g_screen_height, WINDOW_FORMAT_RGB_565);
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(g_native_window, &buffer, nullptr) != 0) {
        return;
    }

    const int copy_width = std::min(width, buffer.width);
    const int copy_height = std::min(height, buffer.height);
    const uint8_t* src = static_cast<const uint8_t*>(pixels);
    uint8_t* dst = static_cast<uint8_t*>(buffer.bits);

    const size_t src_stride_bytes = static_cast<size_t>(width) * 2;
    const size_t dst_stride_bytes = static_cast<size_t>(buffer.stride) * 2;
    const size_t row_copy_bytes = static_cast<size_t>(copy_width) * 2;

    // Fast path: single memcpy when strides match (common case)
    if (src_stride_bytes == dst_stride_bytes && copy_width == width) {
        std::memcpy(dst, src, row_copy_bytes * copy_height);
    } else {
        for (int y = 0; y < copy_height; ++y) {
            std::memcpy(dst + (static_cast<size_t>(y) * dst_stride_bytes),
                        src + (static_cast<size_t>(y) * src_stride_bytes),
                        row_copy_bytes);
        }
    }

    ANativeWindow_unlockAndPost(g_native_window);
}
