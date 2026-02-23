#include "renderer_interface.h"
#include "gl_renderer.h"
#include "software_renderer.h"
#include "vulkan_renderer.h"
#include <android/log.h>
#include <dlfcn.h>

#define LOG_TAG "RendererFactory"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Vulkan availability check
bool isVulkanAvailable() {
    // Try to load Vulkan library
    void* vulkanLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (vulkanLib != nullptr) {
        dlclose(vulkanLib);
        return true;
    }
    return false;
}

// OpenGL ES 3.0 availability check
bool isOpenGLES30Available() {
    // Most modern Android devices support OpenGL ES 3.0+
    // We'll try to create a GL renderer and fall back if it fails
    return true;  // Optimistic - actual check happens during renderer initialization
}

IRenderer* createRenderer(RendererType type) {
    IRenderer* renderer = nullptr;
    
    switch (type) {
        case RendererType::VULKAN:
            LOGD("Creating Vulkan renderer...");
            if (isVulkanAvailable()) {
                renderer = new VulkanRenderer();
                if (renderer != nullptr) {
                    break;  // Successfully created, don't fall through
                }
            }
            LOGD("Vulkan not available or creation failed, falling back to OpenGL ES");
            // Fall through to OpenGL ES
            type = RendererType::OPENGL_ES;
            // intentional fallthrough
            
        case RendererType::OPENGL_ES:
            LOGD("Creating OpenGL ES renderer...");
            renderer = new GLRenderer();
            break;
            
        case RendererType::SOFTWARE:
            LOGD("Creating Software renderer...");
            renderer = new SoftwareRenderer();
            break;
            
        case RendererType::AUTO:
        default:
            // Auto-detect best available renderer
            LOGD("Auto-detecting best renderer...");
            
            // For now, prefer OpenGL ES as it's more widely compatible
            // Vulkan can be enabled once fully implemented
            renderer = new GLRenderer();
            LOGI("Selected OpenGL ES renderer (auto-detect)");
            break;
    }
    
    return renderer;
}

// Helper function to create and initialize a renderer (C linkage for JNI)
extern "C" IRenderer* createAndInitRenderer(RendererType type, ANativeWindow* window, int width, int height) {
    IRenderer* renderer = createRenderer(type);
    if (renderer == nullptr) {
        LOGE("Failed to create renderer");
        return nullptr;
    }
    
    if (!renderer->initialize(window, width, height)) {
        LOGE("Failed to initialize %s, trying fallback", renderer->getName());
        delete renderer;
        
        // Try software fallback
        if (type != RendererType::SOFTWARE) {
            renderer = new SoftwareRenderer();
            if (!renderer->initialize(window, width, height)) {
                LOGE("Software fallback also failed");
                delete renderer;
                return nullptr;
            }
            LOGI("Using software renderer as fallback");
        } else {
            return nullptr;
        }
    }
    
    LOGI("Renderer initialized: %s", renderer->getName());
    return renderer;
}