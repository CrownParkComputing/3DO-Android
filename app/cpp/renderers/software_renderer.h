#pragma once

#include "renderer_interface.h"
#include <mutex>

/**
 * Software renderer - CPU-based rendering using ANativeWindow.
 * This is the fallback renderer when GPU acceleration is not available.
 */
class SoftwareRenderer : public IRenderer {
public:
    SoftwareRenderer();
    ~SoftwareRenderer() override;
    
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
    void setFlip(bool flipX, bool flipY) override;
    // Software path is already correctly oriented; setRotation is a no-op.
    void setRotation(int degrees) override;
    const char* getName() const override { return "Software (CPU)"; }
    bool isInitialized() const override { return m_initialized; }
    int getWindowWidth() const override { return m_windowWidth; }
    int getWindowHeight() const override { return m_windowHeight; }

private:
    ANativeWindow* m_window = nullptr;
    int m_windowWidth = 0;
    int m_windowHeight = 0;
    int m_frameWidth = 320;
    int m_frameHeight = 240;
    bool m_initialized = false;
    bool m_nearestFiltering = true;
    float m_aspectRatio = 4.0f / 3.0f;
    bool m_flipX = false;
    bool m_flipY = false;
    std::mutex m_mutex;
};
