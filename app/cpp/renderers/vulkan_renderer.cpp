#include "vulkan_renderer.h"

#include "gl_renderer.h"

#include <android/log.h>
#include <utility>

#define LOG_TAG "VulkanRenderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

VulkanRenderer::VulkanRenderer() = default;

VulkanRenderer::~VulkanRenderer() {
    cleanup();
}

bool VulkanRenderer::initialize(ANativeWindow* window, int width, int height) {
    if (m_initialized) {
        cleanup();
    }

    m_windowWidth = width;
    m_windowHeight = height;

    LOGI("Initializing Vulkan renderer path: %dx%d", width, height);

    m_glBackend = std::make_unique<GLRenderer>();
    m_glBackend->setEnhancementProfile(true);

    // Apply settings before backend initialize so EGL/surface setup can respect them.
    m_glBackend->setFiltering(m_nearestFiltering);
    m_glBackend->setAspectRatio(m_aspectRatio);
    m_glBackend->setCrtShaderEnabled(m_crtShaderEnabled);
    m_glBackend->setResolutionScale(m_resolutionScale);
    m_glBackend->setAntiAliasingMode(m_antiAliasingMode);
    m_glBackend->setOutputResolutionPreset(m_outputTargetHeight);

    if (!m_glBackend->initialize(window, width, height)) {
        LOGE("Vulkan renderer path failed to initialize backend");
        m_glBackend.reset();
        m_rendererName = "Vulkan";
        return false;
    }

    m_initialized = true;
    m_rendererName = "Vulkan";
    LOGI("Vulkan renderer initialized");
    return true;
}

void VulkanRenderer::cleanup() {
    if (m_glBackend) {
        m_glBackend->cleanup();
        m_glBackend.reset();
    }

    m_initialized = false;
    m_rendererName = "Vulkan";
}

void VulkanRenderer::renderFrame(const void* pixels, int width, int height) {
    if (!m_initialized || !m_glBackend || pixels == nullptr) {
        return;
    }

    m_frameWidth = width;
    m_frameHeight = height;
    m_glBackend->renderFrame(pixels, width, height);
}

void VulkanRenderer::setFiltering(bool nearest) {
    m_nearestFiltering = nearest;
    if (m_glBackend) {
        m_glBackend->setFiltering(nearest);
    }
}

void VulkanRenderer::setAspectRatio(float ratio) {
    m_aspectRatio = ratio;
    if (m_glBackend) {
        m_glBackend->setAspectRatio(ratio);
    }
}

void VulkanRenderer::setCrtShaderEnabled(bool enabled) {
    m_crtShaderEnabled = enabled;
    if (m_glBackend) {
        m_glBackend->setCrtShaderEnabled(m_crtShaderEnabled);
    }
}

void VulkanRenderer::setResolutionScale(int scale) {
    m_resolutionScale = scale;
    if (m_glBackend) {
        m_glBackend->setResolutionScale(m_resolutionScale);
    }
}

void VulkanRenderer::setAntiAliasingMode(int mode) {
    m_antiAliasingMode = mode;
    if (m_glBackend) {
        m_glBackend->setAntiAliasingMode(m_antiAliasingMode);
    }
}

void VulkanRenderer::setOutputResolutionPreset(int targetHeight) {
    m_outputTargetHeight = targetHeight;
    if (m_glBackend) {
        m_glBackend->setOutputResolutionPreset(m_outputTargetHeight);
    }
}

void VulkanRenderer::setFlipVertical(bool enabled) {
    if (m_glBackend) {
        m_glBackend->setFlipVertical(enabled);
    }
}

void VulkanRenderer::setFlip(bool flipX, bool flipY) {
    if (m_glBackend) {
        m_glBackend->setFlip(flipX, flipY);
    }
}
