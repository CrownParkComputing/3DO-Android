#pragma once

#include "renderer_interface.h"
#include <memory>
#include <string>

class GLRenderer;

/**
 * Vulkan renderer implementation.
 * Uses Vulkan API for high-performance GPU rendering.
 * Requires API level 24+ and Vulkan-capable device.
 */
class VulkanRenderer : public IRenderer {
public:
    VulkanRenderer();
    ~VulkanRenderer() override;
    
    // IRenderer interface implementation
    bool initialize(ANativeWindow* window, int width, int height) override;
    void cleanup() override;
    void renderFrame(const void* pixels, int width, int height) override;
    void setFiltering(bool nearest) override;
    void setAspectRatio(float ratio) override;
    void setCrtShaderEnabled(bool enabled) override;
    void setResolutionScale(int scale) override;
    void setAntiAliasingMode(int mode) override;
    void setOutputResolutionPreset(int targetHeight) override;
    void setFlipVertical(bool enabled) override;
    void setFlip(bool flipX, bool flipY);
    const char* getName() const override { return m_rendererName.c_str(); }
    bool isInitialized() const override { return m_initialized; }
    int getWindowWidth() const override { return m_windowWidth; }
    int getWindowHeight() const override { return m_windowHeight; }

private:
    std::unique_ptr<GLRenderer> m_glBackend;
    
    // State
    int m_windowWidth = 0;
    int m_windowHeight = 0;
    int m_frameWidth = 320;
    int m_frameHeight = 240;
    bool m_nearestFiltering = true;
    float m_aspectRatio = 4.0f / 3.0f;
    bool m_crtShaderEnabled = false;
    int m_resolutionScale = 4;
    int m_antiAliasingMode = 0;
    int m_outputTargetHeight = 0;
    bool m_initialized = false;
    std::string m_rendererName = "Vulkan";
};