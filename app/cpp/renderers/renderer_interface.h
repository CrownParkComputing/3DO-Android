#pragma once

#include <android/native_window.h>

/**
 * Abstract renderer interface for GPU-accelerated rendering.
 * Implementations: OpenGL ES, Vulkan
 */
class IRenderer {
public:
    virtual ~IRenderer() = default;
    
    /**
     * Initialize the renderer with a native window.
     * @param window The Android native window to render to
     * @param width Initial window width in pixels
     * @param height Initial window height in pixels
     * @return true if initialization succeeded
     */
    virtual bool initialize(ANativeWindow* window, int width, int height) = 0;
    
    /**
     * Clean up all renderer resources.
     * Called when the surface is destroyed or renderer is switched.
     */
    virtual void cleanup() = 0;
    
    /**
     * Render a single frame from pixel data.
     * @param pixels Raw pixel data in RGB565 format
     * @param width Frame width in pixels (typically 320)
     * @param height Frame height in pixels (typically 240)
     */
    virtual void renderFrame(const void* pixels, int width, int height) = 0;
    
    /**
     * Set texture filtering mode.
     * @param nearest true for nearest-neighbor (sharp pixels), false for bilinear (smooth)
     */
    virtual void setFiltering(bool nearest) = 0;
    
    /**
     * Set the aspect ratio for rendering.
     * @param ratio Width/height ratio (e.g., 4.0f/3.0f for 4:3)
     */
    virtual void setAspectRatio(float ratio) = 0;

    /**
     * Enable/disable CRT post-process shader effect.
     */
    virtual void setCrtShaderEnabled(bool enabled) = 0;

    /**
     * Set integer rendering scale target (e.g. 2x/4x/8x).
     */
    virtual void setResolutionScale(int scale) = 0;

    /**
     * Set anti-aliasing mode (0=off, 1=low, 2=high).
     */
    virtual void setAntiAliasingMode(int mode) = 0;

    /**
     * Set output resolution preset by target height (0=native, 720/1080/1440/2160).
     */
    virtual void setOutputResolutionPreset(int targetHeight) = 0;
    /**
     * Set flip state for rendering. Allows independent horizontal/vertical flips.
     */
    virtual void setFlip(bool flipX, bool flipY) = 0;

    /**
     * Set vertical flip for rendering output (useful for device-specific surface orientation fixes).
     */
    virtual void setFlipVertical(bool enabled) = 0;
    
    /**
     * Get the renderer name for display/debugging.
     * @return Human-readable renderer name
     */
    virtual const char* getName() const = 0;
    
    /**
     * Check if the renderer is currently initialized.
     * @return true if the renderer is ready for use
     */
    virtual bool isInitialized() const = 0;
    
    /**
     * Get the current window width.
     * @return Window width in pixels
     */
    virtual int getWindowWidth() const = 0;
    
    /**
     * Get the current window height.
     * @return Window height in pixels
     */
    virtual int getWindowHeight() const = 0;
};

/**
 * Renderer type enumeration for factory creation.
 */
enum class RendererType {
    AUTO,       // Auto-detect best available
    OPENGL_ES,  // OpenGL ES 3.0
    VULKAN,     // Vulkan (if available)
    SOFTWARE    // CPU-based fallback (original ANativeWindow)
};

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Create a renderer instance.
 * @param type The renderer type to create
 * @return Pointer to new renderer instance, or nullptr on failure
 */
IRenderer* createRenderer(RendererType type);

/**
 * Check if Vulkan is available on this device.
 * @return true if Vulkan is supported
 */
bool isVulkanAvailable();

/**
 * Check if OpenGL ES 3.0 is available on this device.
 * @return true if OpenGL ES 3.0+ is supported
 */
bool isOpenGLES30Available();

#ifdef __cplusplus
}
#endif