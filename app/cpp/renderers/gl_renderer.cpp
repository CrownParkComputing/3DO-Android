#include "gl_renderer.h"
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <algorithm>

#define LOG_TAG "GLRenderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Vertex shader - pass-through with optional runtime flip via uniforms
static const char* VERTEX_SHADER = R"(
    #version 300 es
    in vec4 aPosition;
    in vec2 aTexCoord;
    out vec2 vTexCoord;
    uniform int uFlipX;
    uniform int uFlipY;
    void main() {
        gl_Position = aPosition;
        float u = aTexCoord.x;
        float v = aTexCoord.y;
        if (uFlipX != 0) u = 1.0 - u;
        if (uFlipY != 0) v = 1.0 - v;
        vTexCoord = vec2(u, v);
    }
)";

// Fragment shader - texture sampling with RGB565 support
static const char* FRAGMENT_SHADER = R"(
    #version 300 es
    precision mediump float;
    in vec2 vTexCoord;
    uniform sampler2D uTexture;
    uniform vec2 uTexelSize;
    uniform float uSharpenStrength;
    uniform int uCrtEnabled;
    uniform float uCrtStrength;
    uniform int uAaMode;
    out vec4 fragColor;

    vec4 sampleFxaa(vec2 uv, float amount) {
        vec2 offset = uTexelSize * amount;
        vec4 c = texture(uTexture, uv);
        vec4 n = texture(uTexture, uv + vec2(0.0, -offset.y));
        vec4 s = texture(uTexture, uv + vec2(0.0, offset.y));
        vec4 e = texture(uTexture, uv + vec2(offset.x, 0.0));
        vec4 w = texture(uTexture, uv + vec2(-offset.x, 0.0));
        vec4 ne = texture(uTexture, uv + vec2(offset.x, -offset.y));
        vec4 nw = texture(uTexture, uv + vec2(-offset.x, -offset.y));
        vec4 se = texture(uTexture, uv + vec2(offset.x, offset.y));
        vec4 sw = texture(uTexture, uv + vec2(-offset.x, offset.y));
        vec4 avg = (n + s + e + w + ne + nw + se + sw) / 8.0;
        float edge = length((n.rgb + s.rgb + e.rgb + w.rgb) - (4.0 * c.rgb));
        float edgeFactor = clamp(edge * 2.5, 0.0, 1.0);
        return mix(c, avg, edgeFactor * 0.6);
    }

    void main() {
        vec4 center = texture(uTexture, vTexCoord);
        vec4 baseColor = center;

        if (uAaMode > 0) {
            float amount = (uAaMode == 1) ? 0.75 : 1.25;
            baseColor = sampleFxaa(vTexCoord, amount);
            center = baseColor;
        }

        if (uSharpenStrength > 0.0001) {
            vec4 north = texture(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y));
            vec4 south = texture(uTexture, vTexCoord + vec2(0.0,  uTexelSize.y));
            vec4 west  = texture(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0));
            vec4 east  = texture(uTexture, vTexCoord + vec2( uTexelSize.x, 0.0));
            vec4 laplace = (center * 4.0) - (north + south + west + east);
            baseColor = clamp(center + (uSharpenStrength * laplace), 0.0, 1.0);
        }

        if (uCrtEnabled != 0) {
            float scan = 0.88 + 0.12 * sin(vTexCoord.y / max(uTexelSize.y, 0.000001) * 3.14159265);
            float mask = 0.92 + 0.08 * sin(vTexCoord.x / max(uTexelSize.x, 0.000001) * 1.57079632);
            vec2 dist = abs(vTexCoord - vec2(0.5));
            float vignette = clamp(1.0 - dot(dist, dist) * 1.2, 0.0, 1.0);
            float crtFactor = mix(1.0, scan * mask * vignette, clamp(uCrtStrength, 0.0, 1.0));
            fragColor = vec4(baseColor.rgb * crtFactor, baseColor.a);
        } else {
            fragColor = baseColor;
        }
    }
)";

GLRenderer::GLRenderer() = default;

GLRenderer::~GLRenderer() {
    cleanup();
}

void GLRenderer::checkGLError(const char* operation) {
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        const char* errorStr = "Unknown";
        switch (error) {
            case GL_INVALID_ENUM: errorStr = "GL_INVALID_ENUM"; break;
            case GL_INVALID_VALUE: errorStr = "GL_INVALID_VALUE"; break;
            case GL_INVALID_OPERATION: errorStr = "GL_INVALID_OPERATION"; break;
            case GL_INVALID_FRAMEBUFFER_OPERATION: errorStr = "GL_INVALID_FRAMEBUFFER_OPERATION"; break;
            case GL_OUT_OF_MEMORY: errorStr = "GL_OUT_OF_MEMORY"; break;
        }
        LOGE("GL error after %s: %s (0x%x)", operation, errorStr, error);
    }
}

bool GLRenderer::initialize(ANativeWindow* window, int width, int height) {
    if (m_initialized) {
        LOGD("Renderer already initialized, cleaning up first");
        cleanup();
    }
    
    m_windowWidth = width;
    m_windowHeight = height;
    
    LOGD("Initializing OpenGL ES renderer: %dx%d", width, height);
    
    if (!initEGL(window)) {
        LOGE("Failed to initialize EGL");
        cleanup();
        return false;
    }
    
    if (!initShaders()) {
        LOGE("Failed to initialize shaders");
        cleanup();
        return false;
    }
    
    if (!initBuffers()) {
        LOGE("Failed to initialize buffers");
        cleanup();
        return false;
    }
    
    if (!initTexture()) {
        LOGE("Failed to initialize texture");
        cleanup();
        return false;
    }
    
    // Set up initial GL state
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    
    updateViewport();
    
    m_initialized = true;
    const char* glRenderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    if (glRenderer != nullptr) {
        m_rendererName = std::string("OpenGL ES (") + glRenderer + ")";
    } else {
        m_rendererName = "OpenGL ES";
    }
    LOGI("OpenGL ES renderer initialized successfully");
    LOGI("  GL Version: %s", glGetString(GL_VERSION));
    LOGI("  GL Renderer: %s", glGetString(GL_RENDERER));
    LOGI("  GL Vendor: %s", glGetString(GL_VENDOR));
    
    // Release the context so the emulator thread can use it
    eglMakeCurrent(m_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    LOGD("EGL context released from UI thread");
    
    return true;
}

void GLRenderer::cleanup() {
    if (!m_initialized && m_display == EGL_NO_DISPLAY) {
        return;
    }
    
    LOGD("Cleaning up OpenGL ES renderer");
    
    // Make context current before deleting resources
    if (m_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(m_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    
    // Delete GL resources
    if (m_vao) {
        glDeleteVertexArrays(1, &m_vao);
        m_vao = 0;
    }
    if (m_vbo) {
        glDeleteBuffers(1, &m_vbo);
        m_vbo = 0;
    }
    if (m_ibo) {
        glDeleteBuffers(1, &m_ibo);
        m_ibo = 0;
    }
    if (m_texture) {
        glDeleteTextures(1, &m_texture);
        m_texture = 0;
    }
    if (m_upscaleTexture) {
        glDeleteTextures(1, &m_upscaleTexture);
        m_upscaleTexture = 0;
    }
    if (m_upscaleFbo) {
        glDeleteFramebuffers(1, &m_upscaleFbo);
        m_upscaleFbo = 0;
    }
    if (m_program) {
        glDeleteProgram(m_program);
        m_program = 0;
    }
    
    // Destroy EGL resources
    if (m_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(m_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        
        if (m_context != EGL_NO_CONTEXT) {
            eglDestroyContext(m_display, m_context);
            m_context = EGL_NO_CONTEXT;
        }
        
        if (m_surface != EGL_NO_SURFACE) {
            eglDestroySurface(m_display, m_surface);
            m_surface = EGL_NO_SURFACE;
        }
        
        eglTerminate(m_display);
        m_display = EGL_NO_DISPLAY;
    }
    
    m_initialized = false;
    m_upscaleWidth = 0;
    m_upscaleHeight = 0;
    m_rendererName = "OpenGL ES";
    LOGD("OpenGL ES renderer cleanup complete");
}

void GLRenderer::renderFrame(const void* pixels, int width, int height) {
    if (pixels == nullptr) {
        return;
    }
    
    // Make context current on this thread if needed
    if (eglGetCurrentContext() != m_context) {
        if (!eglMakeCurrent(m_display, m_surface, m_surface, m_context)) {
            LOGE("eglMakeCurrent failed in renderFrame: 0x%x", eglGetError());
            return;
        }
    }
    
    // Check if surface is valid
    if (m_surface == EGL_NO_SURFACE) {
        LOGE("Surface is EGL_NO_SURFACE");
        return;
    }
    
    // Update frame dimensions if changed
    if (width != m_frameWidth || height != m_frameHeight) {
        m_frameWidth = width;
        m_frameHeight = height;
        glBindTexture(GL_TEXTURE_2D, m_texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, m_frameWidth, m_frameHeight, 0,
                     GL_RGB, GL_UNSIGNED_SHORT_5_6_5, nullptr);
        m_viewportDirty = true;
    }
    
    // Apply deferred filtering change
    if (m_filteringDirty) {
        glBindTexture(GL_TEXTURE_2D, m_texture);
        GLint filter = m_nearestFiltering ? GL_NEAREST : GL_LINEAR;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        m_filteringDirty = false;
        LOGI("Applied filtering: %s", m_nearestFiltering ? "nearest" : "linear");
    }
    
    // Apply deferred viewport change
    if (m_viewportDirty) {
        updateViewport();
        m_viewportDirty = false;
    }
    
    // Bind texture and upload new pixel data
    glBindTexture(GL_TEXTURE_2D, m_texture);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GL_RGB, GL_UNSIGNED_SHORT_5_6_5, pixels);
    checkGLError("glTexSubImage2D");

    glUseProgram(m_program);
    glBindVertexArray(m_vao);
    glActiveTexture(GL_TEXTURE0);
    if (m_uFlipY >= 0) {
        glUniform1i(m_uFlipY, m_flipY ? 1 : 0);
    }
    if (m_uFlipX >= 0) {
        glUniform1i(m_uFlipX, m_flipX ? 1 : 0);
    }

    int internalWidth = 0;
    int internalHeight = 0;
    computeInternalRenderSize(internalWidth, internalHeight);
    const bool useUpscaleTarget = internalWidth > 0 && internalHeight > 0;

    if (useUpscaleTarget && ensureUpscaleTarget(internalWidth, internalHeight)) {
        glBindFramebuffer(GL_FRAMEBUFFER, m_upscaleFbo);
        glViewport(0, 0, m_upscaleWidth, m_upscaleHeight);
        glClear(GL_COLOR_BUFFER_BIT);

        float targetSharpen = 0.0f;
        if (m_enhancementProfile) {
            targetSharpen = m_nearestFiltering ? 0.22f : 0.35f;
        }
        if (std::abs(targetSharpen - m_sharpenStrength) > 0.0001f) {
            m_sharpenStrength = targetSharpen;
        }

        if (m_uTexelSize >= 0) {
            glUniform2f(m_uTexelSize,
                        1.0f / std::max(1, width),
                        1.0f / std::max(1, height));
        }
        if (m_uSharpenStrength >= 0) {
            glUniform1f(m_uSharpenStrength, m_sharpenStrength);
        }
        if (m_uCrtEnabled >= 0) {
            glUniform1i(m_uCrtEnabled, m_crtShaderEnabled ? 1 : 0);
        }
        if (m_uCrtStrength >= 0) {
            glUniform1f(m_uCrtStrength, m_crtShaderEnabled ? 0.85f : 0.0f);
        }
        if (m_uAaMode >= 0) {
            glUniform1i(m_uAaMode, m_antiAliasingMode);
        }

        glBindTexture(GL_TEXTURE_2D, m_texture);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, nullptr);
        checkGLError("glDrawElements upscale pass");

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        updateViewport();
        m_viewportDirty = false;
        glClear(GL_COLOR_BUFFER_BIT);

        if (m_uTexelSize >= 0) {
            glUniform2f(m_uTexelSize,
                        1.0f / std::max(1, m_upscaleWidth),
                        1.0f / std::max(1, m_upscaleHeight));
        }
        if (m_uSharpenStrength >= 0) {
            glUniform1f(m_uSharpenStrength, 0.0f);
        }
        if (m_uCrtEnabled >= 0) {
            glUniform1i(m_uCrtEnabled, 0);
        }
        if (m_uCrtStrength >= 0) {
            glUniform1f(m_uCrtStrength, 0.0f);
        }
        if (m_uAaMode >= 0) {
            glUniform1i(m_uAaMode, 0);
        }

        // The FBO stores the image with OpenGL's bottom-up convention.
        // When presenting to the screen we must flip Y to compensate,
        // otherwise the image appears upside-down.
        if (m_uFlipY >= 0) {
            glUniform1i(m_uFlipY, 1);
        }
        if (m_uFlipX >= 0) {
            glUniform1i(m_uFlipX, 0);
        }

        glBindTexture(GL_TEXTURE_2D, m_upscaleTexture);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, nullptr);
        checkGLError("glDrawElements present pass");
    } else {
        // Clear the framebuffer
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glClear(GL_COLOR_BUFFER_BIT);

        float targetSharpen = 0.0f;
        if (m_enhancementProfile) {
            targetSharpen = m_nearestFiltering ? 0.22f : 0.35f;
        }
        if (std::abs(targetSharpen - m_sharpenStrength) > 0.0001f) {
            m_sharpenStrength = targetSharpen;
        }

        if (m_uTexelSize >= 0) {
            glUniform2f(m_uTexelSize,
                        1.0f / std::max(1, width),
                        1.0f / std::max(1, height));
        }
        if (m_uSharpenStrength >= 0) {
            glUniform1f(m_uSharpenStrength, m_sharpenStrength);
        }
        if (m_uCrtEnabled >= 0) {
            glUniform1i(m_uCrtEnabled, m_crtShaderEnabled ? 1 : 0);
        }
        if (m_uCrtStrength >= 0) {
            glUniform1f(m_uCrtStrength, m_crtShaderEnabled ? 0.85f : 0.0f);
        }
        if (m_uAaMode >= 0) {
            glUniform1i(m_uAaMode, m_antiAliasingMode);
        }

        glBindTexture(GL_TEXTURE_2D, m_texture);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, nullptr);
        checkGLError("glDrawElements");
    }
    
    // Swap buffers
    if (!eglSwapBuffers(m_display, m_surface)) {
        EGLint error = eglGetError();
        // Don't mark as uninitialized for temporary errors
        // EGL_BAD_SURFACE can happen during surface resize
        if (error == EGL_BAD_SURFACE || error == EGL_BAD_NATIVE_WINDOW) {
            LOGD("eglSwapBuffers warning: 0x%x (surface may be resizing)", error);
        } else if (error != EGL_SUCCESS) {
            LOGE("eglSwapBuffers error: 0x%x", error);
        }
    }
}

void GLRenderer::setFiltering(bool nearest) {
    if (m_nearestFiltering == nearest) {
        return;
    }
    
    m_nearestFiltering = nearest;
    m_filteringDirty = true;  // Apply during next render
    LOGD("Filtering set to: %s (will apply on next frame)", nearest ? "nearest" : "linear");
}

void GLRenderer::setAspectRatio(float ratio) {
    if (std::abs(m_aspectRatio - ratio) < 0.001f) {
        return;
    }
    
    m_aspectRatio = ratio;
    m_viewportDirty = true;  // Apply during next render
    LOGD("Aspect ratio set to: %.3f (will apply on next frame)", ratio);
}

void GLRenderer::setCrtShaderEnabled(bool enabled) {
    if (m_crtShaderEnabled == enabled) {
        return;
    }
    m_crtShaderEnabled = enabled;
    LOGI("CRT shader: %s", enabled ? "enabled" : "disabled");
}

void GLRenderer::setResolutionScale(int scale) {
    int clamped = std::max(0, std::min(scale, 9));
    if (m_resolutionScale == clamped) {
        return;
    }
    m_resolutionScale = clamped;
    m_viewportDirty = true;
    LOGI("Resolution scale set: %dx", m_resolutionScale);
}

void GLRenderer::setAntiAliasingMode(int mode) {
    int clamped = std::max(0, std::min(mode, 2));
    if (m_antiAliasingMode == clamped) {
        return;
    }
    m_antiAliasingMode = clamped;
    LOGI("Anti-aliasing mode set: %d", m_antiAliasingMode);
}

void GLRenderer::setOutputResolutionPreset(int targetHeight) {
    int clamped = targetHeight;
    if (clamped < 0) clamped = 0;
    if (clamped > 0 && clamped < 720) clamped = 720;
    if (clamped > 720 && clamped < 1080) clamped = 1080;
    if (clamped > 1080 && clamped < 1440) clamped = 1440;
    if (clamped > 1440 && clamped < 2160) clamped = 2160;
    if (clamped > 2160) clamped = 2160;

    if (m_outputTargetHeight == clamped) {
        return;
    }

    m_outputTargetHeight = clamped;
    m_viewportDirty = true;
    if (m_outputTargetHeight == 0) {
        LOGI("Output resolution preset: Native");
    } else {
        LOGI("Output resolution preset: %dp", m_outputTargetHeight);
    }
}

void GLRenderer::setEnhancementProfile(bool enabled) {
    if (m_enhancementProfile == enabled) {
        return;
    }
    m_enhancementProfile = enabled;
    m_sharpenStrength = 0.0f;
    LOGI("Enhancement profile: %s", enabled ? "vulkan" : "default");
}

void GLRenderer::setFlipVertical(bool enabled) {
    setFlip(enabled, enabled);
}

void GLRenderer::setFlip(bool flipX, bool flipY) {
    if (m_flipX == flipX && m_flipY == flipY) return;
    m_flipX = flipX;
    m_flipY = flipY;
    m_viewportDirty = true;
    LOGI("Flip set: X=%d Y=%d", m_flipX ? 1 : 0, m_flipY ? 1 : 0);
}

bool GLRenderer::initEGL(ANativeWindow* window) {
    // Get EGL display
    m_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (m_display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }
    
    // Initialize EGL
    EGLint majorVersion, minorVersion;
    if (!eglInitialize(m_display, &majorVersion, &minorVersion)) {
        LOGE("eglInitialize failed");
        return false;
    }
    LOGD("EGL initialized: version %d.%d", majorVersion, minorVersion);
    
    // Choose EGL config
    EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 0,  // No alpha needed for output
        EGL_DEPTH_SIZE, 0,  // No depth buffer needed
        EGL_STENCIL_SIZE, 0,
        EGL_NONE
    };
    
    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(m_display, attribs, &config, 1, &numConfigs) || numConfigs < 1) {
        LOGE("eglChooseConfig failed");
        return false;
    }

    EGLint nativeVisualId = 0;
    if (!eglGetConfigAttrib(m_display, config, EGL_NATIVE_VISUAL_ID, &nativeVisualId)) {
        LOGE("eglGetConfigAttrib(EGL_NATIVE_VISUAL_ID) failed: 0x%x", eglGetError());
        return false;
    }

    const int targetWidth = 0;
    const int targetHeight = 0;
    if (ANativeWindow_setBuffersGeometry(window, targetWidth, targetHeight, nativeVisualId) != 0) {
        LOGE("ANativeWindow_setBuffersGeometry failed for visual id %d (%dx%d)",
             nativeVisualId, targetWidth, targetHeight);
        return false;
    }
    LOGI("Using native output surface resolution (internal upscale active when selected)");
    
    // Create EGL surface
    m_surface = eglCreateWindowSurface(m_display, config, window, nullptr);
        EGLint surfaceWidth = 0;
        EGLint surfaceHeight = 0;
        if (eglQuerySurface(m_display, m_surface, EGL_WIDTH, &surfaceWidth) &&
            eglQuerySurface(m_display, m_surface, EGL_HEIGHT, &surfaceHeight)) {
            m_surfaceWidth = static_cast<int>(surfaceWidth);
            m_surfaceHeight = static_cast<int>(surfaceHeight);
            LOGI("EGL surface size: %dx%d (window: %dx%d)",
                 m_surfaceWidth, m_surfaceHeight, m_windowWidth, m_windowHeight);
        } else {
            m_surfaceWidth = m_windowWidth;
            m_surfaceHeight = m_windowHeight;
        }

    if (m_surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return false;
    }
    
    // Create EGL context (OpenGL ES 3.0)
    EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    
    m_context = eglCreateContext(m_display, config, EGL_NO_CONTEXT, contextAttribs);
    if (m_context == EGL_NO_CONTEXT) {
        // Try OpenGL ES 2.0 as fallback
        LOGD("OpenGL ES 3.0 context creation failed, trying 2.0");
        contextAttribs[1] = 2;
        m_context = eglCreateContext(m_display, config, EGL_NO_CONTEXT, contextAttribs);
        if (m_context == EGL_NO_CONTEXT) {
            LOGE("eglCreateContext failed for both ES 3.0 and 2.0: 0x%x", eglGetError());
            return false;
        }
    }
    
    // Make context current temporarily for GL resource initialization
    if (!eglMakeCurrent(m_display, m_surface, m_surface, m_context)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }
    
    // Note: We'll release the context after initialization so it can be used
    // by the emulator thread. The emulator thread will make it current when needed.
    
    return true;
}

bool GLRenderer::initShaders() {
    // Compile vertex shader
    GLuint vertexShader = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vertexShader, 1, &VERTEX_SHADER, nullptr);
    glCompileShader(vertexShader);
    
    GLint compiled = 0;
    glGetShaderiv(vertexShader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(vertexShader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetShaderInfoLog(vertexShader, infoLen, nullptr, infoLog);
            LOGE("Vertex shader compilation failed: %s", infoLog);
            delete[] infoLog;
        }
        glDeleteShader(vertexShader);
        return false;
    }
    
    // Compile fragment shader
    GLuint fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fragmentShader, 1, &FRAGMENT_SHADER, nullptr);
    glCompileShader(fragmentShader);
    
    glGetShaderiv(fragmentShader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(fragmentShader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetShaderInfoLog(fragmentShader, infoLen, nullptr, infoLog);
            LOGE("Fragment shader compilation failed: %s", infoLog);
            delete[] infoLog;
        }
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        return false;
    }
    
    // Link program
    m_program = glCreateProgram();
    glAttachShader(m_program, vertexShader);
    glAttachShader(m_program, fragmentShader);
    
    // Bind attribute locations before linking
    glBindAttribLocation(m_program, 0, "aPosition");
    glBindAttribLocation(m_program, 1, "aTexCoord");
    
    glLinkProgram(m_program);
    
    GLint linked = 0;
    glGetProgramiv(m_program, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(m_program, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetProgramInfoLog(m_program, infoLen, nullptr, infoLog);
            LOGE("Shader program link failed: %s", infoLog);
            delete[] infoLog;
        }
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        glDeleteProgram(m_program);
        m_program = 0;
        return false;
    }
    
    // Clean up shaders (they're now part of the program)
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);
    
    // Use the program
    glUseProgram(m_program);
    
    // Set texture uniform
    GLint textureLocation = glGetUniformLocation(m_program, "uTexture");
    glUniform1i(textureLocation, 0);
    m_uTexelSize = glGetUniformLocation(m_program, "uTexelSize");
    m_uSharpenStrength = glGetUniformLocation(m_program, "uSharpenStrength");
    m_uCrtEnabled = glGetUniformLocation(m_program, "uCrtEnabled");
    m_uCrtStrength = glGetUniformLocation(m_program, "uCrtStrength");
    m_uAaMode = glGetUniformLocation(m_program, "uAaMode");
    m_uFlipY = glGetUniformLocation(m_program, "uFlipY");
    m_uFlipX = glGetUniformLocation(m_program, "uFlipX");
    if (m_uTexelSize >= 0) {
        glUniform2f(m_uTexelSize,
                    1.0f / static_cast<float>(m_frameWidth),
                    1.0f / static_cast<float>(m_frameHeight));
    }
    if (m_uSharpenStrength >= 0) {
        glUniform1f(m_uSharpenStrength, 0.0f);
    }
    if (m_uCrtEnabled >= 0) {
        glUniform1i(m_uCrtEnabled, 0);
    }
    if (m_uCrtStrength >= 0) {
        glUniform1f(m_uCrtStrength, 0.0f);
    }
    if (m_uAaMode >= 0) {
        glUniform1i(m_uAaMode, 0);
    }
    
    return true;
}

bool GLRenderer::initBuffers() {
    // Vertex data: position (x, y, z, w) + texcoord (u, v)
    // Normalized device coordinates: -1 to 1
    // Texture coordinates: 0 to 1 (origin at top-left for RGB565)
    float vertices[] = {
        // Position (x, y, z, w)    TexCoord (u, v)
        -1.0f,  1.0f, 0.0f, 1.0f,  0.0f, 0.0f,  // Top-left
         1.0f,  1.0f, 0.0f, 1.0f,  1.0f, 0.0f,  // Top-right
         1.0f, -1.0f, 0.0f, 1.0f,  1.0f, 1.0f,  // Bottom-right
        -1.0f, -1.0f, 0.0f, 1.0f,  0.0f, 1.0f,  // Bottom-left
    };
    
    // Index data for two triangles
    uint16_t indices[] = {
        0, 1, 2,  // First triangle
        0, 2, 3,  // Second triangle
    };
    
    // Create and bind VAO
    glGenVertexArrays(1, &m_vao);
    glBindVertexArray(m_vao);
    
    // Create VBO
    glGenBuffers(1, &m_vbo);
    glBindBuffer(GL_ARRAY_BUFFER, m_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    
    // Create IBO
    glGenBuffers(1, &m_ibo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, m_ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof(indices), indices, GL_STATIC_DRAW);
    
    // Set up vertex attributes
    // Position attribute (location 0)
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 6 * sizeof(float), nullptr);
    
    // TexCoord attribute (location 1)
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 6 * sizeof(float), 
                          reinterpret_cast<void*>(4 * sizeof(float)));
    
    checkGLError("initBuffers");
    
    return true;
}

bool GLRenderer::initTexture() {
    glGenTextures(1, &m_texture);
    glBindTexture(GL_TEXTURE_2D, m_texture);
    
    // Set texture parameters
    GLint filter = m_nearestFiltering ? GL_NEAREST : GL_LINEAR;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    
    // Allocate texture storage (RGB565)
    // Initial size 320x240, will be updated with glTexSubImage2D
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, m_frameWidth, m_frameHeight, 0,
                 GL_RGB, GL_UNSIGNED_SHORT_5_6_5, nullptr);
    
    checkGLError("initTexture");
    
    return true;
}

void GLRenderer::computeOutputRenderSize(int& outWidth, int& outHeight) const {
    outWidth = 0;
    outHeight = 0;

    const int safeFrameWidth = std::max(1, m_frameWidth);
    const int safeFrameHeight = std::max(1, m_frameHeight);

    if (m_outputTargetHeight > 0) {
        float aspect = 4.0f / 3.0f;
        if (m_windowWidth > 0 && m_windowHeight > 0) {
            aspect = static_cast<float>(m_windowWidth) / static_cast<float>(m_windowHeight);
        } else if (safeFrameHeight > 0) {
            aspect = static_cast<float>(safeFrameWidth) / static_cast<float>(safeFrameHeight);
        }

        outHeight = m_outputTargetHeight;
        outWidth = std::max(1, static_cast<int>(std::lround(static_cast<float>(outHeight) * aspect)));
        return;
    }

    const int upscale = std::max(0, std::min(m_resolutionScale, 9));
    if (upscale > 1) {
        outWidth = safeFrameWidth * upscale;
        outHeight = safeFrameHeight * upscale;
    }
}

void GLRenderer::computeInternalRenderSize(int& outWidth, int& outHeight) const {
    outWidth = 0;
    outHeight = 0;

    const int safeFrameWidth = std::max(1, m_frameWidth);
    const int safeFrameHeight = std::max(1, m_frameHeight);

    if (m_outputTargetHeight > 0) {
        float aspect = 4.0f / 3.0f;
        if (m_windowWidth > 0 && m_windowHeight > 0) {
            aspect = static_cast<float>(m_windowWidth) / static_cast<float>(m_windowHeight);
        } else if (safeFrameHeight > 0) {
            aspect = static_cast<float>(safeFrameWidth) / static_cast<float>(safeFrameHeight);
        }

        outHeight = m_outputTargetHeight;
        outWidth = std::max(1, static_cast<int>(std::lround(static_cast<float>(outHeight) * aspect)));
        return;
    }

    const int upscale = std::max(0, std::min(m_resolutionScale, 9));
    if (upscale > 1) {
        outWidth = safeFrameWidth * upscale;
        outHeight = safeFrameHeight * upscale;
    }
}

bool GLRenderer::ensureUpscaleTarget(int width, int height) {
    if (width <= 0 || height <= 0) {
        return false;
    }

    GLint maxTextureSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);
    if (maxTextureSize > 0) {
        width = std::min(width, static_cast<int>(maxTextureSize));
        height = std::min(height, static_cast<int>(maxTextureSize));
    }

    if (m_upscaleFbo != 0 && m_upscaleTexture != 0 && m_upscaleWidth == width && m_upscaleHeight == height) {
        return true;
    }

    if (m_upscaleTexture != 0) {
        glDeleteTextures(1, &m_upscaleTexture);
        m_upscaleTexture = 0;
    }
    if (m_upscaleFbo != 0) {
        glDeleteFramebuffers(1, &m_upscaleFbo);
        m_upscaleFbo = 0;
    }

    glGenTextures(1, &m_upscaleTexture);
    glBindTexture(GL_TEXTURE_2D, m_upscaleTexture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenFramebuffers(1, &m_upscaleFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, m_upscaleFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, m_upscaleTexture, 0);

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("Upscale framebuffer incomplete: 0x%x (%dx%d)", status, width, height);
        if (m_upscaleTexture != 0) {
            glDeleteTextures(1, &m_upscaleTexture);
            m_upscaleTexture = 0;
        }
        if (m_upscaleFbo != 0) {
            glDeleteFramebuffers(1, &m_upscaleFbo);
            m_upscaleFbo = 0;
        }
        m_upscaleWidth = 0;
        m_upscaleHeight = 0;
        return false;
    }

    m_upscaleWidth = width;
    m_upscaleHeight = height;
    LOGI("Internal upscale target: %dx%d", m_upscaleWidth, m_upscaleHeight);
    return true;
}

void GLRenderer::updateViewport() {
    const int renderWidth = m_surfaceWidth > 0 ? m_surfaceWidth : m_windowWidth;
    const int renderHeight = m_surfaceHeight > 0 ? m_surfaceHeight : m_windowHeight;

    if (renderWidth <= 0 || renderHeight <= 0) {
        return;
    }

    if (m_outputTargetHeight > 0) {
        glViewport(0, 0, renderWidth, renderHeight);
        LOGD("Viewport set fullscreen for output preset: 0,0 %dx%d (preset: %dp)",
             renderWidth, renderHeight, m_outputTargetHeight);
        return;
    }
    
    // Calculate viewport to maintain aspect ratio with letterboxing
    float windowAspect = static_cast<float>(renderWidth) / renderHeight;
    float frameAspect = static_cast<float>(m_frameWidth) / m_frameHeight;
    
    // Use the user-specified aspect ratio if different from frame
    if (std::abs(m_aspectRatio - frameAspect) > 0.01f) {
        frameAspect = m_aspectRatio;
    }
    
    int viewportX = 0;
    int viewportY = 0;
    int viewportWidth = renderWidth;
    int viewportHeight = renderHeight;
    
    if (windowAspect > frameAspect) {
        // Window is wider than frame - add side bars
        viewportWidth = static_cast<int>(renderHeight * frameAspect);
        viewportX = (renderWidth - viewportWidth) / 2;
    } else if (windowAspect < frameAspect) {
        // Window is taller than frame - add letterbox
        viewportHeight = static_cast<int>(renderWidth / frameAspect);
        viewportY = (renderHeight - viewportHeight) / 2;
    }

    glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
    
    LOGD("Viewport set: %d,%d %dx%d (window: %dx%d, frame: %dx%d, aspect: %.2f)",
         viewportX, viewportY, viewportWidth, viewportHeight,
            renderWidth, renderHeight, m_frameWidth, m_frameHeight, frameAspect);
}

void GLRenderer::setRotation(int /*degrees*/) {
    // The GL renderer is already correctly oriented via Android's EGL
    // surface handling. We deliberately do not regress it. The interface
    // method exists for parity with VulkanRenderer.
}