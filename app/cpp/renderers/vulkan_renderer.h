#pragma once

#include "renderer_interface.h"

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
    const char* getName() const override { return "Vulkan"; }
    bool isInitialized() const override { return m_initialized; }
    int getWindowWidth() const override { return m_windowWidth; }
    int getWindowHeight() const override { return m_windowHeight; }

private:
    bool createInstance();
    bool pickPhysicalDevice();
    bool createLogicalDevice();
    bool createSurface(ANativeWindow* window);
    bool createSwapChain(int width, int height);
    bool createImageViews();
    bool createRenderPass();
    bool createDescriptorSetLayout();
    bool createPipeline();
    bool createFramebuffers();
    bool createCommandPool();
    bool createTextureImage();
    bool createTextureImageView();
    bool createTextureSampler();
    bool createDescriptorPool();
    bool createDescriptorSets();
    bool createCommandBuffers();
    bool createSyncObjects();
    void cleanupSwapChain();
    void recreateSwapChain(int width, int height);
    
    // Vulkan handles
    void* m_instance = nullptr;           // VkInstance
    void* m_physicalDevice = nullptr;     // VkPhysicalDevice
    void* m_device = nullptr;             // VkDevice
    void* m_surface = nullptr;            // VkSurfaceKHR
    void* m_swapChain = nullptr;          // VkSwapchainKHR
    void* m_renderPass = nullptr;         // VkRenderPass
    void* m_pipelineLayout = nullptr;     // VkPipelineLayout
    void* m_pipeline = nullptr;           // VkPipeline
    void* m_commandPool = nullptr;        // VkCommandPool
    void* m_textureImage = nullptr;       // VkImage
    void* m_textureImageMemory = nullptr; // VkDeviceMemory
    void* m_textureImageView = nullptr;   // VkImageView
    void* m_textureSampler = nullptr;     // VkSampler
    void* m_descriptorPool = nullptr;     // VkDescriptorPool
    void* m_descriptorSetLayout = nullptr;// VkDescriptorSetLayout
    void* m_descriptorSet = nullptr;      // VkDescriptorSet
    
    void** m_swapChainImages = nullptr;        // VkImage array
    void** m_swapChainImageViews = nullptr;    // VkImageView array
    void** m_swapChainFramebuffers = nullptr;  // VkFramebuffer array
    void** m_commandBuffers = nullptr;         // VkCommandBuffer array
    void** m_imageAvailableSemaphores = nullptr; // VkSemaphore array
    void** m_renderFinishedSemaphores = nullptr; // VkSemaphore array
    void** m_inFlightFences = nullptr;         // VkFence array
    
    uint32_t m_graphicsQueueFamily = 0;
    uint32_t m_presentQueueFamily = 0;
    void* m_graphicsQueue = nullptr;      // VkQueue
    void* m_presentQueue = nullptr;       // VkQueue
    
    uint32_t m_swapChainImageCount = 0;
    uint32_t m_currentFrame = 0;
    uint32_t m_imageIndex = 0;
    
    // State
    int m_windowWidth = 0;
    int m_windowHeight = 0;
    int m_frameWidth = 320;
    int m_frameHeight = 240;
    bool m_nearestFiltering = true;
    float m_aspectRatio = 4.0f / 3.0f;
    bool m_initialized = false;
    
    // Constants
    static const int MAX_FRAMES_IN_FLIGHT = 2;
};