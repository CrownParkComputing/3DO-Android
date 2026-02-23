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

// -----------------------------------------------------------------------
// Renderer control JNI functions
// (setRendererType / setFiltering / getRendererName / setAspectRatio /
//  getAspectRatio are declared native in EmulatorActivity.java)
// -----------------------------------------------------------------------

#include <atomic>
static std::atomic<int>  g_renderer_type{1};    // 1 = OpenGL ES (default)
static std::atomic<bool> g_nearest_filtering{false};
static std::atomic<bool> g_aspect_wide{false};  // false = 4:3, true = 16:9

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_setRendererType(JNIEnv* /*env*/, jobject /*thiz*/, jint type) {
    g_renderer_type.store(static_cast<int>(type), std::memory_order_relaxed);
    LOGD("setRendererType: %d", static_cast<int>(type));
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_setFiltering(JNIEnv* /*env*/, jobject /*thiz*/, jboolean nearest) {
    g_nearest_filtering.store(nearest == JNI_TRUE, std::memory_order_relaxed);
    LOGD("setFiltering: nearest=%d", static_cast<int>(nearest));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fourdo_android_EmulatorActivity_getRendererName(JNIEnv* env, jobject /*thiz*/) {
    int type = g_renderer_type.load(std::memory_order_relaxed);
    const char* name = "Software";
    if (type == 1) name = "OpenGL ES";
    else if (type == 2) name = "Vulkan";
    return env->NewStringUTF(name);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_setAspectRatio(JNIEnv* /*env*/, jobject /*thiz*/, jboolean wide) {
    g_aspect_wide.store(wide == JNI_TRUE, std::memory_order_relaxed);
    LOGD("setAspectRatio: wide=%d", static_cast<int>(wide));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdo_android_EmulatorActivity_getAspectRatio(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_aspect_wide.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}
