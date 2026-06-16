/**
 * 3DO Opera Unified Renderer
 *
 * Vulkan rendering bridge for the 3DO emulator.
 *
 * Provides:
 *   - render_frame()  – called from the emulator thread each frame
 *   - JNI bindings for EmulatorActivity (initRenderer, cleanupRenderer,
 *     setSurface, setRendererType, setFiltering, getRendererName,
 *     setAspectRatio, getAspectRatio)
 */

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <atomic>
#include <array>
#include <cmath>
#include <mutex>
#include <string>

#include "renderers/renderer_interface.h"
#include "renderers/software_renderer.h"
#include "renderers/vulkan_renderer.h"
#include "renderers/gl_renderer.h"

#define LOG_TAG "3DOOpera-Renderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// -----------------------------------------------------------------------
// Global renderer state
// -----------------------------------------------------------------------

static std::mutex      g_render_mutex;
static IRenderer*      g_renderer        = nullptr;
static ANativeWindow*  g_native_window   = nullptr;
static int             g_window_width    = 0;
static int             g_window_height   = 0;
static RendererType    g_renderer_type   = RendererType::VULKAN;
static bool            g_nearest         = false;
static bool            g_aspect_wide     = false; // false = 4:3, true = 16:9
static bool            g_crt_enabled     = false;
static int             g_resolution_scale = 0;
static int             g_aa_mode         = 0;
static int             g_output_preset_height = 0;
static bool            g_flip_vertical   = false;
static bool            g_flip_x = false;
static std::string     g_vulkan_driver_path;
// Historically the renderer expected the texture origin to be top-left;
// keep vertical flip enabled by default to preserve existing output orientation.
static bool            g_flip_y = true;
static int             g_rotation       = 0; // 0, 90, 180, or 270
static std::atomic<uint32_t> g_rendered_frames{0};

static std::string format_render_target_info() {
    const int safeWindowWidth = g_window_width > 0 ? g_window_width : 320;
    const int safeWindowHeight = g_window_height > 0 ? g_window_height : 240;
    const int effectiveScale = g_resolution_scale > 1 ? g_resolution_scale : 1;

    const float targetAspect = g_aspect_wide ? (16.0f / 9.0f) : (4.0f / 3.0f);
    const float windowAspect = safeWindowHeight > 0
            ? static_cast<float>(safeWindowWidth) / static_cast<float>(safeWindowHeight)
            : targetAspect;

    int viewportWidth = safeWindowWidth;
    int viewportHeight = safeWindowHeight;
    if (windowAspect > targetAspect) {
        viewportWidth = std::max(1, static_cast<int>(std::lround(static_cast<float>(safeWindowHeight) * targetAspect)));
    } else if (windowAspect < targetAspect) {
        viewportHeight = std::max(1, static_cast<int>(std::lround(static_cast<float>(safeWindowWidth) / targetAspect)));
    }

    int internalWidth = safeWindowWidth;
    int internalHeight = safeWindowHeight;

    if (g_output_preset_height > 0) {
        float aspect = 4.0f / 3.0f;
        if (safeWindowWidth > 0 && safeWindowHeight > 0) {
            aspect = static_cast<float>(safeWindowWidth) / static_cast<float>(safeWindowHeight);
        }
        internalHeight = g_output_preset_height;
        internalWidth = std::max(1, static_cast<int>(std::lround(static_cast<float>(internalHeight) * aspect)));
    } else if (effectiveScale > 1) {
        internalWidth = 320 * effectiveScale;
        internalHeight = 240 * effectiveScale;
    }

    char buffer[128];
    if (g_output_preset_height > 0) {
        std::snprintf(buffer, sizeof(buffer), "Surface:%dx%d Viewport:%dx%d InternalRT:%dx%d Preset:%dp Scale:%dx",
                      safeWindowWidth, safeWindowHeight,
                      viewportWidth, viewportHeight,
                      internalWidth, internalHeight,
                      g_output_preset_height, effectiveScale);
    } else {
        std::snprintf(buffer, sizeof(buffer), "Surface:%dx%d Viewport:%dx%d InternalRT:%dx%d Preset:Native Scale:%dx",
                      safeWindowWidth, safeWindowHeight,
                      viewportWidth, viewportHeight,
                      internalWidth, internalHeight,
                      effectiveScale);
    }
    return std::string(buffer);
}

// -----------------------------------------------------------------------
// Internal helpers
// -----------------------------------------------------------------------

static IRenderer* create_renderer(RendererType type) {
    switch (type) {
        case RendererType::VULKAN:
            return new VulkanRenderer();
        case RendererType::OPENGL_ES:
            return new GLRenderer();
        case RendererType::AUTO:
        default:
            return new VulkanRenderer();
        case RendererType::SOFTWARE:
            return new SoftwareRenderer();
    }
}

/** (Re-)initialise the renderer against the current native window. */
static void reinit_renderer() {
    if (g_native_window == nullptr || g_window_width == 0 || g_window_height == 0) {
        return;
    }

    if (g_renderer) {
        g_renderer->cleanup();
        delete g_renderer;
        g_renderer = nullptr;
    }

    std::array<RendererType, 3> candidates = {RendererType::VULKAN, RendererType::OPENGL_ES, RendererType::SOFTWARE};
    size_t candidateCount = 0;
    switch (g_renderer_type) {
        case RendererType::VULKAN:
            candidates = {RendererType::VULKAN, RendererType::OPENGL_ES, RendererType::SOFTWARE};
            candidateCount = 3;
            break;
        case RendererType::OPENGL_ES:
            candidates = {RendererType::OPENGL_ES, RendererType::VULKAN, RendererType::SOFTWARE};
            candidateCount = 3;
            break;
        case RendererType::SOFTWARE:
            candidates = {RendererType::SOFTWARE, RendererType::SOFTWARE, RendererType::SOFTWARE};
            candidateCount = 1;
            break;
        case RendererType::AUTO:
        default:
            candidates = {RendererType::VULKAN, RendererType::OPENGL_ES, RendererType::SOFTWARE};
            candidateCount = 3;
            break;
    }

    for (size_t i = 0; i < candidateCount; i++) {
        IRenderer* candidate = create_renderer(candidates[i]);
        if (!candidate) {
            continue;
        }

        candidate->setFiltering(g_nearest);
        candidate->setAspectRatio(g_aspect_wide ? 16.0f / 9.0f : 4.0f / 3.0f);
        candidate->setCrtShaderEnabled(g_crt_enabled);
        candidate->setResolutionScale(g_resolution_scale);
        candidate->setAntiAliasingMode(g_aa_mode);
        candidate->setOutputResolutionPreset(g_output_preset_height);
        candidate->setFlip(g_flip_x, g_flip_y);
        candidate->setRotation(g_rotation);
        candidate->setVulkanDriverPath(g_vulkan_driver_path.c_str());

        if (candidate->initialize(g_native_window, g_window_width, g_window_height)) {
            g_renderer = candidate;
            LOGD("Renderer ready: %s (%dx%d)", g_renderer->getName(), g_window_width, g_window_height);
            return;
        }

        delete candidate;
    }

    LOGE("All renderer initialization attempts failed");
    g_renderer = nullptr;
}

// -----------------------------------------------------------------------
// C linkage: called from the emulator thread each frame
// -----------------------------------------------------------------------

extern "C" void render_frame(const void* pixels, int width, int height) {
    if (pixels == nullptr) return;

    std::unique_lock<std::mutex> lock(g_render_mutex, std::try_to_lock);
    if (!lock.owns_lock()) return; // surface busy -- skip frame, audio keeps flowing

    if (g_renderer && g_renderer->isInitialized()) {
        g_renderer->renderFrame(pixels, width, height);
        uint32_t count = g_rendered_frames.fetch_add(1, std::memory_order_relaxed) + 1;
        // Log every 300 frames (~5 s at 60 fps) so we know rendering is alive.
        // Also log the first frame and sample the first pixel to catch black frames.
        if (count == 1 || (count % 300) == 0) {
            uint32_t first_pixel = *static_cast<const uint32_t*>(pixels);
            LOGD("render_frame #%u %dx%d first_pixel=0x%08X renderer=%s",
                 count, width, height, first_pixel,
                 g_renderer ? g_renderer->getName() : "null");
        }
    } else {
        static uint32_t s_no_renderer_warned = 0;
        if (s_no_renderer_warned++ == 0) {
            LOGE("render_frame called but renderer not ready (renderer=%p init=%d)",
                 (void*)g_renderer,
                 g_renderer ? (int)g_renderer->isInitialized() : -1);
        }
    }
}

extern "C" void get_screen_size(int* width, int* height) {
    if (width)  *width  = g_window_width  > 0 ? g_window_width  : 320;
    if (height) *height = g_window_height > 0 ? g_window_height : 240;
}

// -----------------------------------------------------------------------
// JNI: EmulatorActivity.initRenderer(width, height)
// -----------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdo_android_MainActivity_initRenderer(JNIEnv* /*env*/, jobject /*thiz*/,
                                                       jint width, jint height) {
    LOGD("initRenderer %dx%d", width, height);
    std::lock_guard<std::mutex> lock(g_render_mutex);

    g_window_width  = width;
    g_window_height = height;

    // Actual renderer creation is deferred until setSurface() provides the window.
    return JNI_TRUE;
}

// -----------------------------------------------------------------------
// JNI: EmulatorActivity.setSurface(surface)
// -----------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_setSurface(JNIEnv* env, jobject /*thiz*/,
                                                     jobject surface) {
    std::lock_guard<std::mutex> lock(g_render_mutex);

    // Cleanup existing renderer & window.
    if (g_renderer) {
        g_renderer->cleanup();
        delete g_renderer;
        g_renderer = nullptr;
    }
    if (g_native_window) {
        ANativeWindow_release(g_native_window);
        g_native_window = nullptr;
    }

    if (surface == nullptr) {
        LOGD("Surface cleared");
        return;
    }

    g_native_window = ANativeWindow_fromSurface(env, surface);
    if (!g_native_window) {
        LOGE("ANativeWindow_fromSurface failed");
        return;
    }

    LOGD("Surface set: %dx%d", g_window_width, g_window_height);
    reinit_renderer();
}

// -----------------------------------------------------------------------
// JNI: EmulatorActivity.cleanupRenderer()
// -----------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_cleanupRenderer(JNIEnv* /*env*/, jobject /*thiz*/) {
    LOGD("cleanupRenderer");
    std::lock_guard<std::mutex> lock(g_render_mutex);

    if (g_renderer) {
        g_renderer->cleanup();
        delete g_renderer;
        g_renderer = nullptr;
    }
    if (g_native_window) {
        ANativeWindow_release(g_native_window);
        g_native_window = nullptr;
    }
}

// -----------------------------------------------------------------------
// JNI: EmulatorActivity.setRendererType(int type)
//   0 = AUTO, 1 = OPENGL_ES, 2 = VULKAN, 3 = SOFTWARE
// -----------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_setRendererType(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jint type) {
    LOGD("setRendererType: %d", type);
    std::lock_guard<std::mutex> lock(g_render_mutex);

    g_renderer_type = static_cast<RendererType>(type);
    if (g_native_window) {
        reinit_renderer(); // switch live
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_setVulkanDriverPath(JNIEnv* env, jobject /*thiz*/,
                                                              jstring path) {
    std::lock_guard<std::mutex> lock(g_render_mutex);
    if (path == nullptr) {
        g_vulkan_driver_path.clear();
    } else {
        const char* c_path = env->GetStringUTFChars(path, nullptr);
        g_vulkan_driver_path = c_path;
        env->ReleaseStringUTFChars(path, c_path);
    }
    LOGD("setVulkanDriverPath: %s", g_vulkan_driver_path.c_str());
}

// -----------------------------------------------------------------------
// JNI: EmulatorActivity.setFiltering(boolean nearest)
// -----------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_setFiltering(JNIEnv* /*env*/, jobject /*thiz*/,
                                                       jboolean nearest) {
    LOGD("setFiltering: %d", (int)nearest);
    std::lock_guard<std::mutex> lock(g_render_mutex);

    g_nearest = (nearest == JNI_TRUE);
    if (g_renderer) {
        g_renderer->setFiltering(g_nearest);
    }
}

// -----------------------------------------------------------------------
// JNI: EmulatorActivity.getRendererName()
// -----------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_fourdo_android_MainActivity_getRendererName(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_render_mutex);

    const char* name = g_renderer ? g_renderer->getName() : "None";
    return env->NewStringUTF(name);
}

// -----------------------------------------------------------------------
// JNI: EmulatorActivity.setAspectRatio(boolean wide)
// -----------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_setAspectRatio(JNIEnv* /*env*/, jobject /*thiz*/,
                                                         jboolean wide) {
    LOGD("setAspectRatio: wide=%d", (int)wide);
    std::lock_guard<std::mutex> lock(g_render_mutex);

    g_aspect_wide = (wide == JNI_TRUE);
    if (g_renderer) {
        g_renderer->setAspectRatio(g_aspect_wide ? 16.0f / 9.0f : 4.0f / 3.0f);
    }
}

// -----------------------------------------------------------------------
// JNI: EmulatorActivity.getAspectRatio()
// -----------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdo_android_MainActivity_getAspectRatio(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_aspect_wide ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_setCrtShaderEnabled(JNIEnv* /*env*/, jobject /*thiz*/,
                                                              jboolean enabled) {
    LOGD("setCrtShaderEnabled: %d", (int)enabled);
    std::lock_guard<std::mutex> lock(g_render_mutex);

    g_crt_enabled = (enabled == JNI_TRUE);
    if (g_renderer) {
        g_renderer->setCrtShaderEnabled(g_crt_enabled);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdo_android_MainActivity_getCrtShaderEnabled(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_crt_enabled ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_setResolutionScale(JNIEnv* /*env*/, jobject /*thiz*/,
                                                             jint scale) {
    std::lock_guard<std::mutex> lock(g_render_mutex);

    int clampedScale = scale;
    if (clampedScale < 0) clampedScale = 0;
    if (clampedScale > 9) clampedScale = 9;

    LOGD("setResolutionScale: %d", clampedScale);
    g_resolution_scale = clampedScale;
    if (g_renderer) {
        g_renderer->setResolutionScale(g_resolution_scale);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fourdo_android_MainActivity_getResolutionScale(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(g_resolution_scale);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_setAntiAliasingMode(JNIEnv* /*env*/, jobject /*thiz*/,
                                                              jint mode) {
    std::lock_guard<std::mutex> lock(g_render_mutex);
    int clampedMode = mode;
    if (clampedMode < 0) clampedMode = 0;
    if (clampedMode > 2) clampedMode = 2;

    LOGD("setAntiAliasingMode: %d", clampedMode);
    g_aa_mode = clampedMode;
    if (g_renderer) {
        g_renderer->setAntiAliasingMode(g_aa_mode);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fourdo_android_MainActivity_getAntiAliasingMode(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(g_aa_mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_setOutputResolutionPreset(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                    jint targetHeight) {
    std::lock_guard<std::mutex> lock(g_render_mutex);

    int clampedHeight = targetHeight;
    if (clampedHeight < 0) clampedHeight = 0;
    if (clampedHeight > 0 && clampedHeight < 720) clampedHeight = 720;
    if (clampedHeight > 720 && clampedHeight < 1080) clampedHeight = 1080;
    if (clampedHeight > 1080 && clampedHeight < 1440) clampedHeight = 1440;
    if (clampedHeight > 1440 && clampedHeight < 2160) clampedHeight = 2160;
    if (clampedHeight > 2160) clampedHeight = 2160;

    LOGD("setOutputResolutionPreset: %d", clampedHeight);
    g_output_preset_height = clampedHeight;
    if (g_renderer) {
        g_renderer->setOutputResolutionPreset(g_output_preset_height);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_setFlipVertical(JNIEnv* /*env*/, jobject /*thiz*/, jboolean flip) {
    std::lock_guard<std::mutex> lock(g_render_mutex);
    g_flip_vertical = (flip == JNI_TRUE);
    // For backwards compatibility, set both axes when setFlipVertical is called
    g_flip_x = g_flip_vertical;
    g_flip_y = g_flip_vertical;
    if (g_renderer) {
        g_renderer->setFlip(g_flip_x, g_flip_y);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_setFlipX(JNIEnv* /*env*/, jobject /*thiz*/, jboolean flip) {
    std::lock_guard<std::mutex> lock(g_render_mutex);
    g_flip_x = (flip == JNI_TRUE);
    if (g_renderer) {
        g_renderer->setFlip(g_flip_x, g_flip_y);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_setFlipY(JNIEnv* /*env*/, jobject /*thiz*/, jboolean flip) {
    std::lock_guard<std::mutex> lock(g_render_mutex);
    g_flip_y = (flip == JNI_TRUE);
    if (g_renderer) {
        g_renderer->setFlip(g_flip_x, g_flip_y);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_setRotation(JNIEnv* /*env*/, jobject /*thiz*/, jint degrees) {
    int clamped = 0;
    switch (degrees) {
        case 0:   clamped = 0;   break;
        case 90:  clamped = 90;  break;
        case 180: clamped = 180; break;
        case 270: clamped = 270; break;
        default:  clamped = 0;   break;
    }
    LOGD("setRotation: %d", clamped);
    std::lock_guard<std::mutex> lock(g_render_mutex);
    g_rotation = clamped;
    if (g_renderer) {
        g_renderer->setRotation(clamped);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fourdo_android_MainActivity_getOutputResolutionPreset(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(g_output_preset_height);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fourdo_android_MainActivity_consumeRenderedFrames(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(g_rendered_frames.exchange(0, std::memory_order_relaxed));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fourdo_android_MainActivity_getRenderTargetInfo(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_render_mutex);
    std::string info = format_render_target_info();
    return env->NewStringUTF(info.c_str());
}
