/**
 * 4DO Unified Renderer
 *
 * OpenGL ES 3.0 rendering bridge for the 3DO emulator.
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
#include <mutex>

#include "renderers/renderer_interface.h"
#include "renderers/gl_renderer.h"
#include "renderers/software_renderer.h"

#define LOG_TAG "4DO-Renderer"
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
static RendererType    g_renderer_type   = RendererType::OPENGL_ES;
static bool            g_nearest         = false;
static bool            g_aspect_wide     = false; // false = 4:3, true = 16:9

// -----------------------------------------------------------------------
// Internal helpers
// -----------------------------------------------------------------------

static IRenderer* create_renderer(RendererType type) {
    switch (type) {
        case RendererType::OPENGL_ES:
        case RendererType::VULKAN:   // Vulkan falls back to OpenGL ES for now
        case RendererType::AUTO:
        default:
            return new GLRenderer();
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

    g_renderer = create_renderer(g_renderer_type);
    if (!g_renderer) {
        LOGE("Failed to allocate renderer");
        return;
    }

    if (!g_renderer->initialize(g_native_window, g_window_width, g_window_height)) {
        LOGE("Renderer initialisation failed, falling back to software");
        delete g_renderer;
        g_renderer = new SoftwareRenderer();
        if (!g_renderer->initialize(g_native_window, g_window_width, g_window_height)) {
            LOGE("Software fallback also failed");
            delete g_renderer;
            g_renderer = nullptr;
            return;
        }
    }

    g_renderer->setFiltering(g_nearest);
    g_renderer->setAspectRatio(g_aspect_wide ? 16.0f / 9.0f : 4.0f / 3.0f);
    LOGD("Renderer ready: %s (%dx%d)", g_renderer->getName(), g_window_width, g_window_height);
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
Java_com_fourdo_android_EmulatorActivity_initRenderer(JNIEnv* /*env*/, jobject /*thiz*/,
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
Java_com_fourdo_android_EmulatorActivity_setSurface(JNIEnv* env, jobject /*thiz*/,
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
Java_com_fourdo_android_EmulatorActivity_cleanupRenderer(JNIEnv* /*env*/, jobject /*thiz*/) {
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
Java_com_fourdo_android_EmulatorActivity_setRendererType(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jint type) {
    LOGD("setRendererType: %d", type);
    std::lock_guard<std::mutex> lock(g_render_mutex);

    g_renderer_type = static_cast<RendererType>(type);
    if (g_native_window) {
        reinit_renderer(); // switch live
    }
}

// -----------------------------------------------------------------------
// JNI: EmulatorActivity.setFiltering(boolean nearest)
// -----------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_setFiltering(JNIEnv* /*env*/, jobject /*thiz*/,
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
Java_com_fourdo_android_EmulatorActivity_getRendererName(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_render_mutex);

    const char* name = g_renderer ? g_renderer->getName() : "None";
    return env->NewStringUTF(name);
}

// -----------------------------------------------------------------------
// JNI: EmulatorActivity.setAspectRatio(boolean wide)
// -----------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_setAspectRatio(JNIEnv* /*env*/, jobject /*thiz*/,
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
Java_com_fourdo_android_EmulatorActivity_getAspectRatio(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_aspect_wide ? JNI_TRUE : JNI_FALSE;
}
