#include "software_renderer.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>

#define LOG_TAG "SoftwareRenderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

SoftwareRenderer::SoftwareRenderer() = default;

SoftwareRenderer::~SoftwareRenderer() {
    cleanup();
}

bool SoftwareRenderer::initialize(ANativeWindow* window, int width, int height) {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    if (m_initialized) {
        LOGD("Renderer already initialized, cleaning up first");
        cleanup();
    }
    
    if (window == nullptr) {
        LOGE("Cannot initialize with null window");
        return false;
    }
    
    m_window = window;
    m_windowWidth = width;
    m_windowHeight = height;
    
    // Set buffer geometry for RGB565
    ANativeWindow_setBuffersGeometry(m_window, width, height, WINDOW_FORMAT_RGB_565);
    
    m_initialized = true;
    LOGD("Software renderer initialized: %dx%d", width, height);
    
    return true;
}

void SoftwareRenderer::cleanup() {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    if (!m_initialized) {
        return;
    }
    
    if (m_window != nullptr) {
        ANativeWindow_setBuffersGeometry(m_window, 0, 0, 0);
    }

    // Note: We don't release the window here as it's managed externally
    // The window is passed to us and we just hold a reference
    m_window = nullptr;
    m_initialized = false;
    
    LOGD("Software renderer cleanup complete");
}

void SoftwareRenderer::renderFrame(const void* pixels, int width, int height) {
    if (!m_initialized || pixels == nullptr) {
        return;
    }
    
    // Use try_lock to avoid blocking the emulator thread
    std::unique_lock<std::mutex> lock(m_mutex, std::try_to_lock);
    if (!lock.owns_lock()) {
        // Surface busy, skip frame (audio keeps flowing)
        return;
    }
    
    if (m_window == nullptr) {
        return;
    }
    
    // Update frame dimensions if changed
    if (width != m_frameWidth || height != m_frameHeight) {
        m_frameWidth = width;
        m_frameHeight = height;
    }
    
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(m_window, &buffer, nullptr) != 0) {
        return;
    }
    
    // Check if we can do a direct memcpy (native resolution, no scaling)
    bool useDirectMemcpy = (width == buffer.width && height == buffer.height);
    
    if (useDirectMemcpy) {
        // Fast path: no aspect ratio correction needed, just copy
        std::memcpy(buffer.bits, pixels, static_cast<size_t>(width) * height * 2);
        ANativeWindow_unlockAndPost(m_window);
        return;
    }
    
    // Clear buffer with black (for letterboxing)
    memset(buffer.bits, 0, static_cast<size_t>(buffer.stride) * buffer.height * 2);
    
    // Calculate destination rect for aspect ratio
    float frameAspect = m_aspectRatio;
    float windowAspect = static_cast<float>(buffer.width) / buffer.height;
    
    int dstX = 0, dstY = 0;
    int dstWidth = buffer.width;
    int dstHeight = buffer.height;
    
    if (windowAspect > frameAspect) {
        // Window is wider - add side bars
        dstWidth = static_cast<int>(buffer.height * frameAspect);
        dstX = (buffer.width - dstWidth) / 2;
    } else {
        // Window is taller - add letterbox
        dstHeight = static_cast<int>(buffer.width / frameAspect);
        dstY = (buffer.height - dstHeight) / 2;
    }
    
    // Scale factors
    float scaleX = static_cast<float>(width) / dstWidth;
    float scaleY = static_cast<float>(height) / dstHeight;
    
    const uint8_t* src = static_cast<const uint8_t*>(pixels);
    uint8_t* dst = static_cast<uint8_t*>(buffer.bits);
    const size_t dstStrideBytes = static_cast<size_t>(buffer.stride) * 2;
    
    // Fast nearest-neighbor scaling with pre-computed source row pointers
    // Only scale when needed, otherwise just copy
    if (dstHeight == height && dstWidth == width) {
        // No scaling needed, just offset
        for (int dy = 0; dy < dstHeight; dy++) {
            uint8_t* dstRow = dst + (static_cast<size_t>(dstY + dy) * dstStrideBytes) + (dstX * 2);
            const uint8_t* srcRow = src + (static_cast<size_t>(dy) * width * 2);
            std::memcpy(dstRow, srcRow, width * 2);
        }
    } else {
        // Has scaling - use fast row-copy approach with memcpy for each row
        // (avoiding nested loop for inner pixels)
        for (int dy = 0; dy < dstHeight; dy++) {
            int sy = m_flipY ? static_cast<int>((dstHeight - 1 - dy) * scaleY)
                             : static_cast<int>(dy * scaleY);
            if (sy >= height) sy = height - 1;
            if (sy < 0) sy = 0;
            
            uint8_t* dstRow = dst + (static_cast<size_t>(dstY + dy) * dstStrideBytes) + (dstX * 2);
            const uint8_t* srcRow = src + (static_cast<size_t>(sy) * width * 2);
            
            // Copy row by row with horizontal scaling
            for (int dx = 0; dx < dstWidth; dx++) {
                int sx = m_flipX ? static_cast<int>((dstWidth - 1 - dx) * scaleX)
                                 : static_cast<int>(dx * scaleX);
                if (sx >= width) sx = width - 1;
                if (sx < 0) sx = 0;
                
                // Copy 2 bytes (RGB565 pixel)
                dstRow[dx * 2] = srcRow[sx * 2];
                dstRow[dx * 2 + 1] = srcRow[sx * 2 + 1];
            }
        }
    }
    
    ANativeWindow_unlockAndPost(m_window);
}

void SoftwareRenderer::setFiltering(bool nearest) {
    // Software renderer doesn't support filtering - ignored
    m_nearestFiltering = nearest;
}

void SoftwareRenderer::setAspectRatio(float ratio) {
    // Software renderer doesn't support aspect ratio adjustment - ignored
    m_aspectRatio = ratio;
}

void SoftwareRenderer::setCrtShaderEnabled(bool enabled) {
    (void)enabled;
}

void SoftwareRenderer::setResolutionScale(int scale) {
    (void)scale;
}

void SoftwareRenderer::setAntiAliasingMode(int mode) {
    (void)mode;
}

void SoftwareRenderer::setOutputResolutionPreset(int targetHeight) {
    (void)targetHeight;
}

void SoftwareRenderer::setFlipVertical(bool enabled) {
    m_flipX = enabled;
    m_flipY = enabled;
}

void SoftwareRenderer::setFlip(bool flipX, bool flipY) {
    m_flipX = flipX;
    m_flipY = flipY;
}

void SoftwareRenderer::setRotation(int /*degrees*/) {
    // Software path is already correctly oriented via the ANativeWindow
    // surface handling. Kept as a no-op override for IRenderer parity.
}