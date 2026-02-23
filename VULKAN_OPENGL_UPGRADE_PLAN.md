# Vulkan/OpenGL ES Rendering Upgrade Plan

## Executive Summary

This document outlines the plan to upgrade the 4DO-Android emulator from the current CPU-based ANativeWindow rendering to modern GPU-accelerated rendering using Vulkan (primary) and OpenGL ES (fallback).

## Current Architecture Analysis

### What We Have Now

**Renderer (`sdl_renderer.cpp`):**
- **NOT actually SDL** - despite the filename, it uses pure ANativeWindow
- CPU-based pixel copying via `ANativeWindow_lock/unlockAndPost`
- RGB565 format (16-bit color)
- Simple memcpy blit - no GPU acceleration
- Non-blocking frame rendering to avoid emulator stalling

**Emulator Core (`emulator_core.cpp`):**
- libopera 3DO emulation core (keep as-is - working well)
- 320x240 native resolution output
- Video buffer filled by `opera_vdlp_set_video_buffer()`
- Audio: lock-free SPSC ring buffer
- Adaptive frame skipping under load

**JNI Bridge (`jni_bridge.cpp`):**
- Java ↔ Native communication
- Surface lifecycle management
- NVRAM persistence

### Key Insight
The libopera emulator core is solid. We only need to upgrade the **rendering layer** to use GPU acceleration.

---

## Proposed Architecture

### New Rendering Pipeline

```
┌─────────────────────────────────────────────────────────────────────┐
│                         libopera Core                               │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  3DO Emulation → Video Buffer (320x240 RGB565)              │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      GPU Renderer (NEW)                             │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  1. Upload texture to GPU (RGB565 → RGBA8888 or native)     │   │
│  │  2. Apply scaling/filtering (nearest or bilinear)           │   │
│  │  3. Optional: CRT shaders, scanlines, etc.                  │   │
│  │  4. Present to Surface                                       │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                          ┌─────────┴─────────┐
                          ▼                   ▼
                   ┌──────────────┐    ┌──────────────┐
                   │    Vulkan    │    │  OpenGL ES   │
                   │  (Primary)   │    │  (Fallback)  │
                   └──────────────┘    └──────────────┘
```

---

## Implementation Phases

### Phase 1: Foundation & OpenGL ES Renderer ✅ COMPLETED

#### 1.1 Create OpenGL ES 3.0 Renderer
- [x] Create `cpp/gl_renderer.cpp` and `cpp/gl_renderer.h`
- [x] Implement shader-based texture rendering
- [x] Support RGB565 internal format for efficiency
- [x] Add configurable filtering (nearest/bilinear)

#### 1.2 Shader Implementation
```glsl
// Vertex Shader
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = aTexCoord;
}

// Fragment Shader
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTexture;
void main() {
    gl_FragColor = texture2D(uTexture, vTexCoord);
}
```

#### 1.3 OpenGL ES Components
- [ ] EGL context creation and management
- [ ] Texture upload pipeline
- [ ] Vertex/index buffers for quad rendering
- [ ] Viewport management for aspect ratio

### Phase 2: Vulkan Renderer
**Estimated Time: 4-5 days**

#### 2.1 Vulkan Infrastructure
- [ ] Create `cpp/vulkan_renderer.cpp` and `cpp/vulkan_renderer.h`
- [ ] Instance creation with validation layers (debug only)
- [ ] Physical device selection
- [ ] Logical device and queue creation
- [ ] Swapchain creation and management

#### 2.2 Vulkan Rendering Pipeline
- [ ] Render pass configuration
- [ ] Pipeline layout and descriptors
- [ ] Graphics pipeline (shaders compiled to SPIR-V)
- [ ] Command buffer management
- [ ] Double/triple buffering

#### 2.3 Texture Management
- [ ] Staging buffer for texture uploads
- [ ] Image creation and layout transitions
- [ ] Sampler configuration (nearest/bilinear)

#### 2.4 Synchronization
- [ ] Semaphores for swapchain synchronization
- [ ] Fences for frame completion
- [ ] Proper cleanup on surface destruction

### Phase 3: Unified Renderer Interface
**Estimated Time: 1-2 days**

#### 3.1 Abstract Renderer Interface
```cpp
// cpp/renderer_interface.h
class IRenderer {
public:
    virtual ~IRenderer() = default;
    
    virtual bool initialize(ANativeWindow* window, int width, int height) = 0;
    virtual void cleanup() = 0;
    virtual void renderFrame(const void* pixels, int width, int height) = 0;
    virtual void setFiltering(bool nearest) = 0;
    virtual void setAspectRatio(float ratio) = 0;
    virtual const char* getName() const = 0;
};
```

#### 3.2 Renderer Factory
- [ ] Auto-detect Vulkan support
- [ ] Fallback to OpenGL ES if Vulkan unavailable
- [ ] User preference setting for renderer selection

### Phase 4: Advanced Features
**Estimated Time: 2-3 days**

#### 4.1 Post-Processing Shaders
- [ ] CRT curve effect
- [ ] Scanline simulation
- [ ] Phosphor glow
- [ ] Color correction

#### 4.2 Performance Optimizations
- [ ] Asynchronous texture upload
- [ ] Triple buffering option
- [ ] Frame pacing improvements

### Phase 5: Integration & Testing
**Estimated Time: 2 days**

- [ ] Integrate with EmulatorActivity
- [ ] Settings UI for renderer selection
- [ ] Performance testing on various devices
- [ ] Memory leak checking

---

## File Structure Changes

```
app/cpp/
├── android_main.cpp          # (unchanged)
├── emulator_core.cpp         # (minor changes - renderer interface)
├── input_handler.cpp         # (unchanged)
├── jni_bridge.cpp            # (minor changes - new JNI methods)
├── sdl_renderer.cpp          # DELETE (replaced)
│
├── renderers/                # NEW DIRECTORY
│   ├── renderer_interface.h  # Abstract interface
│   ├── renderer_factory.cpp  # Factory for creating renderers
│   ├── renderer_factory.h
│   ├── gl_renderer.cpp       # OpenGL ES implementation
│   ├── gl_renderer.h
│   ├── vulkan_renderer.cpp   # Vulkan implementation
│   ├── vulkan_renderer.h
│   └── shaders/              # Shader source files
│       ├── gl_basic.vert
│       ├── gl_basic.frag
│       ├── gl_crt.frag
│       ├── vulkan_basic.vert.spv
│       └── vulkan_basic.frag.spv
```

---

## CMakeLists.txt Changes

```cmake
# Add at the top
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Vulkan package
find_package(Vulkan REQUIRED)

# OpenGL ES package
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)

# Add new source files
add_library(
    native-lib
    SHARED
    cpp/android_main.cpp
    cpp/jni_bridge.cpp
    cpp/input_handler.cpp
    cpp/emulator_core.cpp
    
    # New renderer files
    cpp/renderers/renderer_factory.cpp
    cpp/renderers/gl_renderer.cpp
    cpp/renderers/vulkan_renderer.cpp
    
    # libopera sources (unchanged)
    cpp/libopera/...
)

target_link_libraries(
    native-lib
    ${log-lib}
    ${android-lib}
    Vulkan::Vulkan
    ${egl-lib}
    ${gles-lib}
)
```

---

## Detailed Implementation: Phase 1

### OpenGL ES Renderer Header

```cpp
// cpp/renderers/gl_renderer.h
#pragma once

#include "renderer_interface.h"
#include <EGL/egl.h>
#include <GLES3/gl3.h>

class GLRenderer : public IRenderer {
public:
    GLRenderer();
    ~GLRenderer() override;
    
    bool initialize(ANativeWindow* window, int width, int height) override;
    void cleanup() override;
    void renderFrame(const void* pixels, int width, int height) override;
    void setFiltering(bool nearest) override;
    void setAspectRatio(float ratio) override;
    const char* getName() const override { return "OpenGL ES 3.0"; }
    
private:
    bool initEGL(ANativeWindow* window);
    bool initShaders();
    bool initBuffers();
    bool initTexture();
    void updateViewport();
    
    EGLDisplay m_display = EGL_NO_DISPLAY;
    EGLSurface m_surface = EGL_NO_SURFACE;
    EGLContext m_context = EGL_NO_CONTEXT;
    
    GLuint m_program = 0;
    GLuint m_texture = 0;
    GLuint m_vbo = 0;
    GLuint m_ibo = 0;
    
    int m_windowWidth = 0;
    int m_windowHeight = 0;
    int m_frameWidth = 320;
    int m_frameHeight = 240;
    bool m_nearestFiltering = true;
    float m_aspectRatio = 4.0f / 3.0f;
};
```

### OpenGL ES Renderer Implementation Skeleton

```cpp
// cpp/renderers/gl_renderer.cpp
#include "gl_renderer.h"
#include <android/log.h>

#define LOG_TAG "GLRenderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char* VERTEX_SHADER = R"(
    attribute vec4 aPosition;
    attribute vec2 aTexCoord;
    varying vec2 vTexCoord;
    void main() {
        gl_Position = aPosition;
        vTexCoord = aTexCoord;
    }
)";

static const char* FRAGMENT_SHADER = R"(
    precision mediump float;
    varying vec2 vTexCoord;
    uniform sampler2D uTexture;
    void main() {
        gl_FragColor = texture2D(uTexture, vTexCoord);
    }
)";

GLRenderer::GLRenderer() = default;

GLRenderer::~GLRenderer() {
    cleanup();
}

bool GLRenderer::initialize(ANativeWindow* window, int width, int height) {
    m_windowWidth = width;
    m_windowHeight = height;
    
    if (!initEGL(window)) {
        LOGE("Failed to initialize EGL");
        return false;
    }
    
    if (!initShaders()) {
        LOGE("Failed to initialize shaders");
        return false;
    }
    
    if (!initBuffers()) {
        LOGE("Failed to initialize buffers");
        return false;
    }
    
    if (!initTexture()) {
        LOGE("Failed to initialize texture");
        return false;
    }
    
    updateViewport();
    LOGD("OpenGL ES renderer initialized: %dx%d", width, height);
    return true;
}

void GLRenderer::cleanup() {
    if (m_texture) {
        glDeleteTextures(1, &m_texture);
        m_texture = 0;
    }
    if (m_vbo) {
        glDeleteBuffers(1, &m_vbo);
        m_vbo = 0;
    }
    if (m_ibo) {
        glDeleteBuffers(1, &m_ibo);
        m_ibo = 0;
    }
    if (m_program) {
        glDeleteProgram(m_program);
        m_program = 0;
    }
    
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
}

void GLRenderer::renderFrame(const void* pixels, int width, int height) {
    if (pixels == nullptr || m_display == EGL_NO_DISPLAY) {
        return;
    }
    
    m_frameWidth = width;
    m_frameHeight = height;
    
    // Update texture
    glBindTexture(GL_TEXTURE_2D, m_texture);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, 
                    GL_RGB, GL_UNSIGNED_SHORT_5_6_5, pixels);
    
    // Clear and draw
    glClear(GL_COLOR_BUFFER_BIT);
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, nullptr);
    
    // Swap buffers
    eglSwapBuffers(m_display, m_surface);
}

void GLRenderer::setFiltering(bool nearest) {
    m_nearestFiltering = nearest;
    glBindTexture(GL_TEXTURE_2D, m_texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, 
                    nearest ? GL_NEAREST : GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, 
                    nearest ? GL_NEAREST : GL_LINEAR);
}

void GLRenderer::setAspectRatio(float ratio) {
    m_aspectRatio = ratio;
    updateViewport();
}

bool GLRenderer::initEGL(ANativeWindow* window) {
    m_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (m_display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }
    
    if (!eglInitialize(m_display, nullptr, nullptr)) {
        LOGE("eglInitialize failed");
        return false;
    }
    
    // Configure EGL
    EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 0,
        EGL_DEPTH_SIZE, 0,
        EGL_STENCIL_SIZE, 0,
        EGL_NONE
    };
    
    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(m_display, attribs, &config, 1, &numConfigs)) {
        LOGE("eglChooseConfig failed");
        return false;
    }
    
    m_surface = eglCreateWindowSurface(m_display, config, window, nullptr);
    if (m_surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed");
        return false;
    }
    
    EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    
    m_context = eglCreateContext(m_display, config, EGL_NO_CONTEXT, contextAttribs);
    if (m_context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed");
        return false;
    }
    
    if (!eglMakeCurrent(m_display, m_surface, m_surface, m_context)) {
        LOGE("eglMakeCurrent failed");
        return false;
    }
    
    return true;
}

// ... additional implementation for initShaders, initBuffers, initTexture, updateViewport
```

---

## Benefits of This Upgrade

### Performance
- **GPU-accelerated scaling**: Offloads upscaling from CPU
- **Better frame pacing**: VSync support through swapchain
- **Reduced CPU usage**: Emulator core has more CPU time

### Visual Quality
- **Configurable filtering**: Nearest-neighbor for sharp pixels, bilinear for smooth
- **Shader effects**: CRT simulation, scanlines, color correction
- **Proper aspect ratio handling**: GPU-corrected 4:3 display

### Future-Proofing
- **Modern API**: Vulkan support for newer devices
- **Extensible**: Easy to add new shader effects
- **Maintainable**: Clean separation of concerns

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Vulkan complexity | OpenGL ES fallback always available |
| Device compatibility | Runtime capability detection |
| Performance regression | Benchmark suite, option to use old renderer |
| Shader compilation errors | Extensive device testing, SPIR-V precompilation |

---

## Timeline Summary

| Phase | Duration | Description |
|-------|----------|-------------|
| Phase 1 | 2-3 days | OpenGL ES 3.0 Renderer |
| Phase 2 | 4-5 days | Vulkan Renderer |
| Phase 3 | 1-2 days | Unified Interface & Factory |
| Phase 4 | 2-3 days | Advanced Features & Shaders |
| Phase 5 | 2 days | Integration & Testing |

**Total Estimated Time: 11-15 days**

---

## Next Steps

1. **Approve this plan** - Confirm the approach and priorities
2. **Begin Phase 1** - Implement OpenGL ES renderer first (quick win)
3. **Iterative testing** - Test on multiple devices throughout development
4. **Progress to Vulkan** - Add Vulkan support once GL is stable

---

## Questions to Consider

1. **Minimum API level?** - Vulkan requires API 24+, OpenGL ES 3.0 requires API 18+
2. **Shader preferences?** - Should we include CRT effects in Phase 1?
3. **Performance targets?** - What devices should we optimize for?
4. **Old renderer removal?** - Keep ANativeWindow renderer as fallback, or remove entirely?