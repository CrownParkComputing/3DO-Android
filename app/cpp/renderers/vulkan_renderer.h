#pragma once

#include "renderer_interface.h"
#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR 1
#endif
#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <vector>
#include <string>

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
    void setFlip(bool flipX, bool flipY) override;
    void setRotation(int degrees) override;
    void setVulkanDriverPath(const char* path) override;
    const char* getName() const override { return m_rendererName.c_str(); }
    bool isInitialized() const override { return m_initialized; }
    int getWindowWidth() const override { return m_windowWidth; }
    int getWindowHeight() const override { return m_windowHeight; }

private:
    bool createInstance();
    bool createSurface(ANativeWindow* window);
    bool pickPhysicalDevice();
    bool createDevice();
    bool createSwapchain();
    bool createImageViews();
    bool createRenderPass();
    bool createDescriptorSetLayout();
    bool createPipeline();
    bool createFramebuffers();
    bool createCommandPool();
    bool createTextureResources();
    bool createDescriptorPoolAndSet();
    bool createSyncObjects();
    bool recreateSwapchain();
    void cleanupSwapchain();
    void recordCommandBuffer(VkCommandBuffer commandBuffer, uint32_t imageIndex);
    void uploadFrame(const void* pixels, int width, int height);
    void transitionImageLayout(VkImage image, VkFormat format, VkImageLayout oldLayout, VkImageLayout newLayout);
    void copyBufferToImage(VkBuffer buffer, VkImage image, uint32_t width, uint32_t height);
    bool createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags properties,
                      VkBuffer& buffer, VkDeviceMemory& memory);
    bool createImage(uint32_t width, uint32_t height, VkFormat format, VkImageTiling tiling,
                     VkImageUsageFlags usage, VkMemoryPropertyFlags properties,
                     VkImage& image, VkDeviceMemory& memory);
    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) const;
    VkCommandBuffer beginSingleTimeCommands();
    void endSingleTimeCommands(VkCommandBuffer commandBuffer);
    VkShaderModule createShaderModule(const uint32_t* code, size_t wordCount);
    VkExtent2D chooseSwapExtent(const VkSurfaceCapabilitiesKHR& capabilities) const;
    VkSurfaceFormatKHR chooseSwapSurfaceFormat(const std::vector<VkSurfaceFormatKHR>& formats) const;
    VkPresentModeKHR choosePresentMode(const std::vector<VkPresentModeKHR>& modes) const;
    void updateViewportForAspect(VkCommandBuffer commandBuffer) const;
    
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
    bool m_flipX = false;
    bool m_flipY = false;
    int  m_rotation = 0; // 0=0deg, 1=90deg, 2=180deg, 3=270deg
    std::string m_driverPath;
    bool m_initialized = false;
    std::string m_rendererName = "Vulkan";

    VkInstance m_instance = VK_NULL_HANDLE;
    VkSurfaceKHR m_surface = VK_NULL_HANDLE;
    VkPhysicalDevice m_physicalDevice = VK_NULL_HANDLE;
    VkDevice m_device = VK_NULL_HANDLE;
    VkQueue m_graphicsQueue = VK_NULL_HANDLE;
    VkQueue m_presentQueue = VK_NULL_HANDLE;
    uint32_t m_graphicsQueueFamily = UINT32_MAX;
    uint32_t m_presentQueueFamily = UINT32_MAX;

    VkSwapchainKHR m_swapchain = VK_NULL_HANDLE;
    VkFormat m_swapchainFormat = VK_FORMAT_UNDEFINED;
    VkExtent2D m_swapchainExtent{};
    std::vector<VkImage> m_swapchainImages;
    std::vector<VkImageView> m_swapchainImageViews;
    std::vector<VkFramebuffer> m_framebuffers;

    VkRenderPass m_renderPass = VK_NULL_HANDLE;
    VkDescriptorSetLayout m_descriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout m_pipelineLayout = VK_NULL_HANDLE;
    VkPipeline m_pipeline = VK_NULL_HANDLE;
    VkCommandPool m_commandPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> m_commandBuffers;

    VkImage m_textureImage = VK_NULL_HANDLE;
    VkDeviceMemory m_textureMemory = VK_NULL_HANDLE;
    VkImageView m_textureView = VK_NULL_HANDLE;
    VkSampler m_textureSampler = VK_NULL_HANDLE;
    VkFormat m_textureFormat = VK_FORMAT_R5G6B5_UNORM_PACK16;
    bool m_textureRgb565 = true;
    VkBuffer m_stagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory m_stagingMemory = VK_NULL_HANDLE;
    void* m_stagingMapped = nullptr;
    VkDeviceSize m_stagingSize = 0;

    VkDescriptorPool m_descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet m_descriptorSet = VK_NULL_HANDLE;

    VkSemaphore m_imageAvailable = VK_NULL_HANDLE;
    VkSemaphore m_renderFinished = VK_NULL_HANDLE;
    VkFence m_inFlightFence = VK_NULL_HANDLE;
};
