#pragma once

#include "renderer_interface.h"
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <string>

/**
 * OpenGL ES 3.0 renderer implementation.
 * Uses EGL for context management and GLES3 for rendering.
 */
class GLRenderer : public IRenderer {
public:
    GLRenderer();
    ~GLRenderer() override;
    
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
    const char* getName() const override { return m_rendererName.c_str(); }
    bool isInitialized() const override { return m_initialized; }
    int getWindowWidth() const override { return m_windowWidth; }
    int getWindowHeight() const override { return m_windowHeight; }
    void setEnhancementProfile(bool enabled);

private:
    bool initEGL(ANativeWindow* window);
    bool initShaders();
    bool initBuffers();
    bool initTexture();
    void computeOutputRenderSize(int& outWidth, int& outHeight) const;
    void computeInternalRenderSize(int& outWidth, int& outHeight) const;
    bool ensureUpscaleTarget(int width, int height);
    void updateViewport();
    void checkGLError(const char* operation);
    
    // EGL resources
    EGLDisplay m_display = EGL_NO_DISPLAY;
    EGLSurface m_surface = EGL_NO_SURFACE;
    EGLContext m_context = EGL_NO_CONTEXT;
    
    // GL resources
    GLuint m_program = 0;
    GLuint m_texture = 0;
    GLuint m_upscaleFbo = 0;
    GLuint m_upscaleTexture = 0;
    GLuint m_vbo = 0;
    GLuint m_ibo = 0;
    GLuint m_vao = 0;
    GLint m_uTexelSize = -1;
    GLint m_uSharpenStrength = -1;
    GLint m_uCrtEnabled = -1;
    GLint m_uCrtStrength = -1;
    GLint m_uAaMode = -1;
    GLint m_uFlipY = -1;
    GLint m_uFlipX = -1;
    
    // State
    int m_windowWidth = 0;
    int m_windowHeight = 0;
    int m_surfaceWidth = 0;
    int m_surfaceHeight = 0;
    int m_upscaleWidth = 0;
    int m_upscaleHeight = 0;
    int m_frameWidth = 320;
    int m_frameHeight = 240;
    bool m_nearestFiltering = true;
    float m_aspectRatio = 4.0f / 3.0f;
    bool m_initialized = false;
    bool m_enhancementProfile = false;
    float m_sharpenStrength = 0.0f;
    bool m_crtShaderEnabled = false;
    bool m_flipY = false;
    bool m_flipX = false;
    int m_resolutionScale = 4;
    int m_antiAliasingMode = 0;
    int m_outputTargetHeight = 0;
    std::string m_rendererName = "OpenGL ES";
    
    // Dirty flags for deferred updates
    bool m_filteringDirty = false;
    bool m_viewportDirty = false;
    
public:
    void setFlipVertical(bool enabled) override;
    void setFlip(bool flipX, bool flipY);
};
