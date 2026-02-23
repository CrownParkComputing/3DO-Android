#pragma once

#include "renderer_interface.h"
#include <EGL/egl.h>
#include <GLES3/gl3.h>

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
    const char* getName() const override { return "OpenGL ES 3.0"; }
    bool isInitialized() const override { return m_initialized; }
    int getWindowWidth() const override { return m_windowWidth; }
    int getWindowHeight() const override { return m_windowHeight; }

private:
    bool initEGL(ANativeWindow* window);
    bool initShaders();
    bool initBuffers();
    bool initTexture();
    void updateViewport();
    void checkGLError(const char* operation);
    
    // EGL resources
    EGLDisplay m_display = EGL_NO_DISPLAY;
    EGLSurface m_surface = EGL_NO_SURFACE;
    EGLContext m_context = EGL_NO_CONTEXT;
    
    // GL resources
    GLuint m_program = 0;
    GLuint m_texture = 0;
    GLuint m_vbo = 0;
    GLuint m_ibo = 0;
    GLuint m_vao = 0;
    
    // State
    int m_windowWidth = 0;
    int m_windowHeight = 0;
    int m_frameWidth = 320;
    int m_frameHeight = 240;
    bool m_nearestFiltering = true;
    float m_aspectRatio = 4.0f / 3.0f;
    bool m_initialized = false;
    
    // Dirty flags for deferred updates
    bool m_filteringDirty = false;
    bool m_viewportDirty = false;
};
