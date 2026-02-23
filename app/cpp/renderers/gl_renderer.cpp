#include "gl_renderer.h"
#include <android/log.h>
#include <cstring>
#include <cmath>

#define LOG_TAG "GLRenderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Vertex shader - simple pass-through with texture coordinates
static const char* VERTEX_SHADER = R"(
    #version 300 es
    in vec4 aPosition;
    in vec2 aTexCoord;
    out vec2 vTexCoord;
    void main() {
        gl_Position = aPosition;
        vTexCoord = aTexCoord;
    }
)";

// Fragment shader - texture sampling with RGB565 support
static const char* FRAGMENT_SHADER = R"(
    #version 300 es
    precision mediump float;
    in vec2 vTexCoord;
    uniform sampler2D uTexture;
    out vec4 fragColor;
    void main() {
        fragColor = texture(uTexture, vTexCoord);
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
    
    // Clear the framebuffer
    glClear(GL_COLOR_BUFFER_BIT);
    
    // Draw the quad
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, nullptr);
    checkGLError("glDrawElements");
    
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
    
    // Create EGL surface
    m_surface = eglCreateWindowSurface(m_display, config, window, nullptr);
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

void GLRenderer::updateViewport() {
    if (m_windowWidth <= 0 || m_windowHeight <= 0) {
        return;
    }
    
    // Calculate viewport to maintain aspect ratio with letterboxing
    float windowAspect = static_cast<float>(m_windowWidth) / m_windowHeight;
    float frameAspect = static_cast<float>(m_frameWidth) / m_frameHeight;
    
    // Use the user-specified aspect ratio if different from frame
    if (std::abs(m_aspectRatio - frameAspect) > 0.01f) {
        frameAspect = m_aspectRatio;
    }
    
    int viewportX = 0;
    int viewportY = 0;
    int viewportWidth = m_windowWidth;
    int viewportHeight = m_windowHeight;
    
    if (windowAspect > frameAspect) {
        // Window is wider than frame - add side bars
        viewportWidth = static_cast<int>(m_windowHeight * frameAspect);
        viewportX = (m_windowWidth - viewportWidth) / 2;
    } else if (windowAspect < frameAspect) {
        // Window is taller than frame - add letterbox
        viewportHeight = static_cast<int>(m_windowWidth / frameAspect);
        viewportY = (m_windowHeight - viewportHeight) / 2;
    }
    
    glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
    
    LOGD("Viewport set: %d,%d %dx%d (window: %dx%d, frame: %dx%d, aspect: %.2f)",
         viewportX, viewportY, viewportWidth, viewportHeight,
         m_windowWidth, m_windowHeight, m_frameWidth, m_frameHeight, frameAspect);
}