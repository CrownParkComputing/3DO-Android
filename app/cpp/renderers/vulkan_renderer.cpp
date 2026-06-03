#include "vulkan_renderer.h"

#include <android/log.h>
#include <algorithm>
#include <array>
#include <cstring>
#include <limits>
#include <set>

#define LOG_TAG "VulkanRenderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// SPIR-V for the Vulkan renderer. Sourced from app/cpp/renderers/shaders/*.vert
// and *.frag, compiled by glslangValidator, and embedded as a uint32_t array
// by spv_to_cpp.py (committed as vulkan_shaders.gen.cpp so CI builds that lack
// glslangValidator still work).
extern "C" const uint32_t vert_spv[];
extern "C" const size_t   vert_spv_size;
extern "C" const uint32_t frag_spv[];
extern "C" const size_t   frag_spv_size;

// Per-draw push constant layout. Must match the GLSL declaration in
// vulkan.vert (`layout(push_constant) uniform PushConstants { uint flags; uint rotation; }`).
struct VulkanPushConstants {
    uint32_t flags;     // bit 0 = flipX, bit 1 = flipY (applied in texture-local UV space)
    uint32_t rotation;  // 0=0deg, 1=90deg, 2=180deg, 3=270deg
};
static_assert(sizeof(VulkanPushConstants) == 8, "VulkanPushConstants must be 8 bytes");

}


VulkanRenderer::VulkanRenderer() = default;
VulkanRenderer::~VulkanRenderer() { cleanup(); }

bool VulkanRenderer::initialize(ANativeWindow* window, int width, int height) {
    cleanup();
    if (!window) return false;
    m_windowWidth = width;
    m_windowHeight = height;
    m_frameWidth = 320;
    m_frameHeight = 240;

    if (!createInstance() || !createSurface(window) || !pickPhysicalDevice() || !createDevice()
            || !createSwapchain() || !createImageViews() || !createRenderPass()
            || !createDescriptorSetLayout() || !createPipeline() || !createFramebuffers()
            || !createCommandPool() || !createTextureResources() || !createDescriptorPoolAndSet()
            || !createSyncObjects()) {
        cleanup();
        return false;
    }

    m_initialized = true;
    m_rendererName = "Vulkan";
    LOGI("Vulkan renderer initialized: swapchain=%ux%u texture=%s",
         m_swapchainExtent.width, m_swapchainExtent.height, m_textureRgb565 ? "RGB565" : "RGBA8888");
    return true;
}

void VulkanRenderer::cleanup() {
    if (m_device != VK_NULL_HANDLE) vkDeviceWaitIdle(m_device);
    cleanupSwapchain();
    if (m_stagingMapped) {
        vkUnmapMemory(m_device, m_stagingMemory);
        m_stagingMapped = nullptr;
    }
    if (m_stagingBuffer) vkDestroyBuffer(m_device, m_stagingBuffer, nullptr);
    if (m_stagingMemory) vkFreeMemory(m_device, m_stagingMemory, nullptr);
    if (m_textureSampler) vkDestroySampler(m_device, m_textureSampler, nullptr);
    if (m_textureView) vkDestroyImageView(m_device, m_textureView, nullptr);
    if (m_textureImage) vkDestroyImage(m_device, m_textureImage, nullptr);
    if (m_textureMemory) vkFreeMemory(m_device, m_textureMemory, nullptr);
    if (m_descriptorPool) vkDestroyDescriptorPool(m_device, m_descriptorPool, nullptr);
    if (m_descriptorSetLayout) vkDestroyDescriptorSetLayout(m_device, m_descriptorSetLayout, nullptr);
    if (m_imageAvailable) vkDestroySemaphore(m_device, m_imageAvailable, nullptr);
    if (m_renderFinished) vkDestroySemaphore(m_device, m_renderFinished, nullptr);
    if (m_inFlightFence) vkDestroyFence(m_device, m_inFlightFence, nullptr);
    if (m_commandPool) vkDestroyCommandPool(m_device, m_commandPool, nullptr);
    if (m_device) vkDestroyDevice(m_device, nullptr);
    if (m_surface) vkDestroySurfaceKHR(m_instance, m_surface, nullptr);
    if (m_instance) vkDestroyInstance(m_instance, nullptr);
    m_instance = VK_NULL_HANDLE;
    m_surface = VK_NULL_HANDLE;
    m_physicalDevice = VK_NULL_HANDLE;
    m_device = VK_NULL_HANDLE;
    m_graphicsQueue = VK_NULL_HANDLE;
    m_presentQueue = VK_NULL_HANDLE;
    m_stagingBuffer = VK_NULL_HANDLE;
    m_stagingMemory = VK_NULL_HANDLE;
    m_textureSampler = VK_NULL_HANDLE;
    m_textureView = VK_NULL_HANDLE;
    m_textureImage = VK_NULL_HANDLE;
    m_textureMemory = VK_NULL_HANDLE;
    m_descriptorPool = VK_NULL_HANDLE;
    m_descriptorSetLayout = VK_NULL_HANDLE;
    m_imageAvailable = VK_NULL_HANDLE;
    m_renderFinished = VK_NULL_HANDLE;
    m_inFlightFence = VK_NULL_HANDLE;
    m_commandPool = VK_NULL_HANDLE;
    m_stagingSize = 0;
    m_initialized = false;
}

void VulkanRenderer::cleanupSwapchain() {
    if (!m_device) return;
    for (VkFramebuffer framebuffer : m_framebuffers) vkDestroyFramebuffer(m_device, framebuffer, nullptr);
    m_framebuffers.clear();
    if (!m_commandBuffers.empty() && m_commandPool) {
        vkFreeCommandBuffers(m_device, m_commandPool, static_cast<uint32_t>(m_commandBuffers.size()), m_commandBuffers.data());
        m_commandBuffers.clear();
    }
    if (m_pipeline) vkDestroyPipeline(m_device, m_pipeline, nullptr);
    if (m_pipelineLayout) vkDestroyPipelineLayout(m_device, m_pipelineLayout, nullptr);
    if (m_renderPass) vkDestroyRenderPass(m_device, m_renderPass, nullptr);
    for (VkImageView view : m_swapchainImageViews) vkDestroyImageView(m_device, view, nullptr);
    m_swapchainImageViews.clear();
    if (m_swapchain) vkDestroySwapchainKHR(m_device, m_swapchain, nullptr);
    m_swapchain = VK_NULL_HANDLE;
    m_pipeline = VK_NULL_HANDLE;
    m_pipelineLayout = VK_NULL_HANDLE;
    m_renderPass = VK_NULL_HANDLE;
}

bool VulkanRenderer::createInstance() {
    VkApplicationInfo appInfo{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    appInfo.pApplicationName = "4DO";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "4DO";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;
    const char* extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo createInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = 2;
    createInfo.ppEnabledExtensionNames = extensions;
    return vkCreateInstance(&createInfo, nullptr, &m_instance) == VK_SUCCESS;
}

bool VulkanRenderer::createSurface(ANativeWindow* window) {
    VkAndroidSurfaceCreateInfoKHR createInfo{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    createInfo.window = window;
    return vkCreateAndroidSurfaceKHR(m_instance, &createInfo, nullptr, &m_surface) == VK_SUCCESS;
}

bool VulkanRenderer::pickPhysicalDevice() {
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(m_instance, &deviceCount, nullptr);
    if (deviceCount == 0) return false;
    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(m_instance, &deviceCount, devices.data());
    for (VkPhysicalDevice device : devices) {
        uint32_t queueCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, nullptr);
        std::vector<VkQueueFamilyProperties> queues(queueCount);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, queues.data());
        for (uint32_t i = 0; i < queueCount; ++i) {
            VkBool32 present = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(device, i, m_surface, &present);
            if ((queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                m_physicalDevice = device;
                m_graphicsQueueFamily = i;
                m_presentQueueFamily = i;
                return true;
            }
        }
    }
    return false;
}

bool VulkanRenderer::createDevice() {
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueInfo.queueFamilyIndex = m_graphicsQueueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    const char* extensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo createInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    createInfo.queueCreateInfoCount = 1;
    createInfo.pQueueCreateInfos = &queueInfo;
    createInfo.enabledExtensionCount = 1;
    createInfo.ppEnabledExtensionNames = extensions;
    if (vkCreateDevice(m_physicalDevice, &createInfo, nullptr, &m_device) != VK_SUCCESS) return false;
    vkGetDeviceQueue(m_device, m_graphicsQueueFamily, 0, &m_graphicsQueue);
    m_presentQueue = m_graphicsQueue;
    return true;
}

VkSurfaceFormatKHR VulkanRenderer::chooseSwapSurfaceFormat(const std::vector<VkSurfaceFormatKHR>& formats) const {
    for (const auto& format : formats) {
        if (format.format == VK_FORMAT_R8G8B8A8_UNORM || format.format == VK_FORMAT_B8G8R8A8_UNORM) return format;
    }
    return formats[0];
}

VkPresentModeKHR VulkanRenderer::choosePresentMode(const std::vector<VkPresentModeKHR>& modes) const {
    // Prefer MAILBOX: present does NOT block the caller until vblank (it just
    // replaces the queued image), and it is tear-free. The emulator calls
    // renderFrame() on its emulation thread, so a blocking FIFO present would
    // stall emulation for most of every frame (~10 ms) waiting on vblank — that
    // starves heavy games and makes the frameskip heuristic misread CPU cost.
    // MAILBOX decouples the emulation pace (audio-timed) from the display
    // refresh. Fall back to FIFO (always supported) when MAILBOX is unavailable.
    for (VkPresentModeKHR mode : modes) {
        if (mode == VK_PRESENT_MODE_MAILBOX_KHR) return mode;
    }
    return VK_PRESENT_MODE_FIFO_KHR;
}

VkExtent2D VulkanRenderer::chooseSwapExtent(const VkSurfaceCapabilitiesKHR& caps) const {
    if (caps.currentExtent.width != std::numeric_limits<uint32_t>::max()) return caps.currentExtent;
    VkExtent2D extent{static_cast<uint32_t>(std::max(1, m_windowWidth)), static_cast<uint32_t>(std::max(1, m_windowHeight))};
    extent.width = std::max(caps.minImageExtent.width, std::min(caps.maxImageExtent.width, extent.width));
    extent.height = std::max(caps.minImageExtent.height, std::min(caps.maxImageExtent.height, extent.height));
    return extent;
}

bool VulkanRenderer::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps{};
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(m_physicalDevice, m_surface, &caps);
    uint32_t formatCount = 0, presentCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(m_physicalDevice, m_surface, &formatCount, nullptr);
    vkGetPhysicalDeviceSurfacePresentModesKHR(m_physicalDevice, m_surface, &presentCount, nullptr);
    if (formatCount == 0 || presentCount == 0) return false;
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    std::vector<VkPresentModeKHR> presentModes(presentCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(m_physicalDevice, m_surface, &formatCount, formats.data());
    vkGetPhysicalDeviceSurfacePresentModesKHR(m_physicalDevice, m_surface, &presentCount, presentModes.data());
    VkSurfaceFormatKHR surfaceFormat = chooseSwapSurfaceFormat(formats);
    m_swapchainFormat = surfaceFormat.format;
    m_swapchainExtent = chooseSwapExtent(caps);
    VkPresentModeKHR presentMode = choosePresentMode(presentModes);
    // MAILBOX needs at least 3 images to actually run non-blocking (one on
    // screen, one queued, one being rendered). FIFO is fine with 2+1.
    uint32_t desiredImages = (presentMode == VK_PRESENT_MODE_MAILBOX_KHR) ? 3u : 2u;
    uint32_t imageCount = std::max(caps.minImageCount + 1, desiredImages);
    if (caps.maxImageCount > 0) imageCount = std::min(imageCount, caps.maxImageCount);
    VkSwapchainCreateInfoKHR info{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    info.surface = m_surface;
    info.minImageCount = imageCount;
    info.imageFormat = m_swapchainFormat;
    info.imageColorSpace = surfaceFormat.colorSpace;
    info.imageExtent = m_swapchainExtent;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    // On Android, prefer an IDENTITY pre-transform and let the compositor apply
    // the display rotation (exactly the way EGL/GLES does). Using
    // caps.currentTransform here would make the app responsible for pre-rotating
    // its own content; since we draw an unrotated quad, that makes the image
    // appear sideways on portrait-native devices locked to landscape.
    info.preTransform =
        (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
            ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
            : caps.currentTransform;
    info.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    info.presentMode = presentMode;
    info.clipped = VK_TRUE;
    LOGI("SWAPCHAIN: %ux%u images=%u presentMode=%s preTransform=0x%x",
         m_swapchainExtent.width, m_swapchainExtent.height, imageCount,
         presentMode == VK_PRESENT_MODE_MAILBOX_KHR ? "MAILBOX" :
         presentMode == VK_PRESENT_MODE_FIFO_KHR ? "FIFO" : "OTHER",
         info.preTransform);
    if (vkCreateSwapchainKHR(m_device, &info, nullptr, &m_swapchain) != VK_SUCCESS) return false;
    vkGetSwapchainImagesKHR(m_device, m_swapchain, &imageCount, nullptr);
    m_swapchainImages.resize(imageCount);
    vkGetSwapchainImagesKHR(m_device, m_swapchain, &imageCount, m_swapchainImages.data());
    return true;
}

bool VulkanRenderer::createImageViews() {
    m_swapchainImageViews.resize(m_swapchainImages.size());
    for (size_t i = 0; i < m_swapchainImages.size(); ++i) {
        VkImageViewCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        info.image = m_swapchainImages[i];
        info.viewType = VK_IMAGE_VIEW_TYPE_2D;
        info.format = m_swapchainFormat;
        info.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        info.subresourceRange.levelCount = 1;
        info.subresourceRange.layerCount = 1;
        if (vkCreateImageView(m_device, &info, nullptr, &m_swapchainImageViews[i]) != VK_SUCCESS) return false;
    }
    return true;
}

bool VulkanRenderer::createRenderPass() {
    VkAttachmentDescription color{};
    color.format = m_swapchainFormat;
    color.samples = VK_SAMPLE_COUNT_1_BIT;
    color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    color.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &ref;
    VkSubpassDependency dep{};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo info{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    info.attachmentCount = 1;
    info.pAttachments = &color;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    info.dependencyCount = 1;
    info.pDependencies = &dep;
    return vkCreateRenderPass(m_device, &info, nullptr, &m_renderPass) == VK_SUCCESS;
}

bool VulkanRenderer::createDescriptorSetLayout() {
    VkDescriptorSetLayoutBinding binding{};
    binding.binding = 0;
    binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    binding.descriptorCount = 1;
    binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    info.bindingCount = 1;
    info.pBindings = &binding;
    return vkCreateDescriptorSetLayout(m_device, &info, nullptr, &m_descriptorSetLayout) == VK_SUCCESS;
}

bool VulkanRenderer::createPipeline() {
    VkShaderModule vert = createShaderModule(vert_spv, vert_spv_size / sizeof(uint32_t));
    VkShaderModule frag = createShaderModule(frag_spv, frag_spv_size / sizeof(uint32_t));
    if (!vert || !frag) return false;
    VkPipelineShaderStageCreateInfo stages[2] = {{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}, {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}};
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT; stages[0].module = vert; stages[0].pName = "main";
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module = frag; stages[1].pName = "main";
    VkPipelineVertexInputStateCreateInfo vertex{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo assembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    VkViewport viewport{0, 0, static_cast<float>(m_swapchainExtent.width), static_cast<float>(m_swapchainExtent.height), 0, 1};
    VkRect2D scissor{{0, 0}, m_swapchainExtent};
    VkPipelineViewportStateCreateInfo viewportState{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    viewportState.viewportCount = 1; viewportState.pViewports = &viewport; viewportState.scissorCount = 1; viewportState.pScissors = &scissor;
    VkDynamicState dynamics[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dynamic.dynamicStateCount = 2; dynamic.pDynamicStates = dynamics;
    VkPipelineRasterizationStateCreateInfo raster{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    raster.polygonMode = VK_POLYGON_MODE_FILL; raster.lineWidth = 1.0f; raster.cullMode = VK_CULL_MODE_NONE; raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    VkPipelineMultisampleStateCreateInfo msaa{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    msaa.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState blend{};
    blend.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo blendState{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    blendState.attachmentCount = 1; blendState.pAttachments = &blend;
    VkPipelineLayoutCreateInfo layout{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layout.setLayoutCount = 1; layout.pSetLayouts = &m_descriptorSetLayout;
    VkPushConstantRange pushRange{ VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(VulkanPushConstants) };
    layout.pushConstantRangeCount = 1;
    layout.pPushConstantRanges    = &pushRange;
    if (vkCreatePipelineLayout(m_device, &layout, nullptr, &m_pipelineLayout) != VK_SUCCESS) return false;
    VkGraphicsPipelineCreateInfo info{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    info.stageCount = 2; info.pStages = stages; info.pVertexInputState = &vertex; info.pInputAssemblyState = &assembly;
    info.pViewportState = &viewportState; info.pRasterizationState = &raster; info.pMultisampleState = &msaa;
    info.pColorBlendState = &blendState; info.pDynamicState = &dynamic; info.layout = m_pipelineLayout; info.renderPass = m_renderPass;
    bool ok = vkCreateGraphicsPipelines(m_device, VK_NULL_HANDLE, 1, &info, nullptr, &m_pipeline) == VK_SUCCESS;
    vkDestroyShaderModule(m_device, vert, nullptr);
    vkDestroyShaderModule(m_device, frag, nullptr);
    return ok;
}

bool VulkanRenderer::createFramebuffers() {
    m_framebuffers.resize(m_swapchainImageViews.size());
    for (size_t i = 0; i < m_swapchainImageViews.size(); ++i) {
        VkImageView attachments[] = {m_swapchainImageViews[i]};
        VkFramebufferCreateInfo info{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        info.renderPass = m_renderPass;
        info.attachmentCount = 1;
        info.pAttachments = attachments;
        info.width = m_swapchainExtent.width;
        info.height = m_swapchainExtent.height;
        info.layers = 1;
        if (vkCreateFramebuffer(m_device, &info, nullptr, &m_framebuffers[i]) != VK_SUCCESS) return false;
    }
    return true;
}

bool VulkanRenderer::createCommandPool() {
    VkCommandPoolCreateInfo pool{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool.queueFamilyIndex = m_graphicsQueueFamily;
    if (vkCreateCommandPool(m_device, &pool, nullptr, &m_commandPool) != VK_SUCCESS) return false;
    m_commandBuffers.resize(m_swapchainImages.size());
    VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    alloc.commandPool = m_commandPool;
    alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    alloc.commandBufferCount = static_cast<uint32_t>(m_commandBuffers.size());
    return vkAllocateCommandBuffers(m_device, &alloc, m_commandBuffers.data()) == VK_SUCCESS;
}

bool VulkanRenderer::createTextureResources() {
    VkFormatProperties props{};
    vkGetPhysicalDeviceFormatProperties(m_physicalDevice, VK_FORMAT_R5G6B5_UNORM_PACK16, &props);
    m_textureRgb565 = (props.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0;
    m_textureFormat = m_textureRgb565 ? VK_FORMAT_R5G6B5_UNORM_PACK16 : VK_FORMAT_R8G8B8A8_UNORM;
    const VkDeviceSize bytes = static_cast<VkDeviceSize>(m_frameWidth) * m_frameHeight * (m_textureRgb565 ? 2 : 4);
    m_stagingSize = bytes;
    if (!createBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                      m_stagingBuffer, m_stagingMemory)) return false;
    if (vkMapMemory(m_device, m_stagingMemory, 0, bytes, 0, &m_stagingMapped) != VK_SUCCESS) return false;
    if (!createImage(m_frameWidth, m_frameHeight, m_textureFormat, VK_IMAGE_TILING_OPTIMAL,
                     VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                     VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, m_textureImage, m_textureMemory)) return false;
    transitionImageLayout(m_textureImage, m_textureFormat, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    view.image = m_textureImage; view.viewType = VK_IMAGE_VIEW_TYPE_2D; view.format = m_textureFormat;
    view.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; view.subresourceRange.levelCount = 1; view.subresourceRange.layerCount = 1;
    if (vkCreateImageView(m_device, &view, nullptr, &m_textureView) != VK_SUCCESS) return false;
    VkSamplerCreateInfo sampler{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sampler.magFilter = m_nearestFiltering ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
    sampler.minFilter = sampler.magFilter;
    sampler.addressModeU = sampler.addressModeV = sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler.maxLod = 1.0f;
    return vkCreateSampler(m_device, &sampler, nullptr, &m_textureSampler) == VK_SUCCESS;
}

bool VulkanRenderer::createDescriptorPoolAndSet() {
    VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1};
    VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pool.maxSets = 1; pool.poolSizeCount = 1; pool.pPoolSizes = &size;
    if (vkCreateDescriptorPool(m_device, &pool, nullptr, &m_descriptorPool) != VK_SUCCESS) return false;
    VkDescriptorSetAllocateInfo alloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    alloc.descriptorPool = m_descriptorPool; alloc.descriptorSetCount = 1; alloc.pSetLayouts = &m_descriptorSetLayout;
    if (vkAllocateDescriptorSets(m_device, &alloc, &m_descriptorSet) != VK_SUCCESS) return false;
    VkDescriptorImageInfo imageInfo{m_textureSampler, m_textureView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = m_descriptorSet; write.dstBinding = 0; write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; write.pImageInfo = &imageInfo;
    vkUpdateDescriptorSets(m_device, 1, &write, 0, nullptr);
    return true;
}

bool VulkanRenderer::createSyncObjects() {
    VkSemaphoreCreateInfo sem{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkFenceCreateInfo fence{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fence.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    return vkCreateSemaphore(m_device, &sem, nullptr, &m_imageAvailable) == VK_SUCCESS
        && vkCreateSemaphore(m_device, &sem, nullptr, &m_renderFinished) == VK_SUCCESS
        && vkCreateFence(m_device, &fence, nullptr, &m_inFlightFence) == VK_SUCCESS;
}

void VulkanRenderer::renderFrame(const void* pixels, int width, int height) {
    if (!m_initialized || !pixels || width != m_frameWidth || height != m_frameHeight) return;
    vkWaitForFences(m_device, 1, &m_inFlightFence, VK_TRUE, UINT64_MAX);
    vkResetFences(m_device, 1, &m_inFlightFence);
    uploadFrame(pixels, width, height);
    uint32_t imageIndex = 0;
    VkResult acquire = vkAcquireNextImageKHR(m_device, m_swapchain, UINT64_MAX, m_imageAvailable, VK_NULL_HANDLE, &imageIndex);
    if (acquire == VK_ERROR_OUT_OF_DATE_KHR) { recreateSwapchain(); return; }
    if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) return;
    vkResetCommandBuffer(m_commandBuffers[imageIndex], 0);
    recordCommandBuffer(m_commandBuffers[imageIndex], imageIndex);
    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = 1; submit.pWaitSemaphores = &m_imageAvailable; submit.pWaitDstStageMask = &waitStage;
    submit.commandBufferCount = 1; submit.pCommandBuffers = &m_commandBuffers[imageIndex];
    submit.signalSemaphoreCount = 1; submit.pSignalSemaphores = &m_renderFinished;
    if (vkQueueSubmit(m_graphicsQueue, 1, &submit, m_inFlightFence) != VK_SUCCESS) return;
    VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    present.waitSemaphoreCount = 1; present.pWaitSemaphores = &m_renderFinished;
    present.swapchainCount = 1; present.pSwapchains = &m_swapchain; present.pImageIndices = &imageIndex;
    VkResult result = vkQueuePresentKHR(m_presentQueue, &present);
    // Do NOT recreate on SUBOPTIMAL: with an IDENTITY pre-transform the
    // compositor legitimately reports SUBOPTIMAL every frame on a rotated
    // display, which would otherwise trigger an endless recreate loop. Only a
    // genuine OUT_OF_DATE (real resize / surface change) needs a rebuild.
    if (result == VK_ERROR_OUT_OF_DATE_KHR) recreateSwapchain();
}

void VulkanRenderer::recordCommandBuffer(VkCommandBuffer cmd, uint32_t imageIndex) {
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    vkBeginCommandBuffer(cmd, &begin);

    VkImageMemoryBarrier toTransfer{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    toTransfer.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.image = m_textureImage;
    toTransfer.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    toTransfer.subresourceRange.levelCount = 1;
    toTransfer.subresourceRange.layerCount = 1;
    toTransfer.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &toTransfer);

    VkBufferImageCopy copy{};
    copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copy.imageSubresource.layerCount = 1;
    copy.imageExtent = {static_cast<uint32_t>(m_frameWidth), static_cast<uint32_t>(m_frameHeight), 1};
    vkCmdCopyBufferToImage(cmd, m_stagingBuffer, m_textureImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);

    VkImageMemoryBarrier toShader = toTransfer;
    toShader.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toShader.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    toShader.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toShader.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd,
                         VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &toShader);

    VkClearValue clear{{{0.0f, 0.0f, 0.0f, 1.0f}}};
    VkRenderPassBeginInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    rp.renderPass = m_renderPass; rp.framebuffer = m_framebuffers[imageIndex];
    rp.renderArea.offset = {0, 0}; rp.renderArea.extent = m_swapchainExtent;
    rp.clearValueCount = 1; rp.pClearValues = &clear;
    vkCmdBeginRenderPass(cmd, &rp, VK_SUBPASS_CONTENTS_INLINE);
    updateViewportForAspect(cmd);
    VkRect2D scissor{{0, 0}, m_swapchainExtent};
    vkCmdSetScissor(cmd, 0, 1, &scissor);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_pipelineLayout, 0, 1, &m_descriptorSet, 0, nullptr);
    VulkanPushConstants push{};
    push.flags    = (m_flipX ? 1u : 0u) | (m_flipY ? 2u : 0u);
    push.rotation = static_cast<uint32_t>(m_rotation & 3);
    vkCmdPushConstants(cmd, m_pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT,
                       0, sizeof(push), &push);
    vkCmdDraw(cmd, 4, 1, 0, 0);
    vkCmdEndRenderPass(cmd);
    vkEndCommandBuffer(cmd);
}

void VulkanRenderer::updateViewportForAspect(VkCommandBuffer cmd) const {
    float targetAspect = m_aspectRatio;
    float windowAspect = static_cast<float>(m_swapchainExtent.width) / std::max(1u, m_swapchainExtent.height);
    float w = static_cast<float>(m_swapchainExtent.width);
    float h = static_cast<float>(m_swapchainExtent.height);
    float x = 0.0f, y = 0.0f;
    if (windowAspect > targetAspect) {
        w = h * targetAspect; x = (static_cast<float>(m_swapchainExtent.width) - w) * 0.5f;
    } else if (windowAspect < targetAspect) {
        h = w / targetAspect; y = (static_cast<float>(m_swapchainExtent.height) - h) * 0.5f;
    }
    VkViewport viewport{x, y, w, h, 0.0f, 1.0f};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
}

void VulkanRenderer::uploadFrame(const void* pixels, int width, int height) {
    if (m_textureRgb565) {
        std::memcpy(m_stagingMapped, pixels, static_cast<size_t>(width) * height * 2);
    } else {
        const uint16_t* src = static_cast<const uint16_t*>(pixels);
        uint8_t* dst = static_cast<uint8_t*>(m_stagingMapped);
        for (int i = 0; i < width * height; ++i) {
            uint16_t p = src[i];
            dst[i * 4 + 0] = static_cast<uint8_t>(((p >> 11) & 0x1f) * 255 / 31);
            dst[i * 4 + 1] = static_cast<uint8_t>(((p >> 5) & 0x3f) * 255 / 63);
            dst[i * 4 + 2] = static_cast<uint8_t>((p & 0x1f) * 255 / 31);
            dst[i * 4 + 3] = 255;
        }
    }
}

void VulkanRenderer::transitionImageLayout(VkImage image, VkFormat, VkImageLayout oldLayout, VkImageLayout newLayout) {
    VkCommandBuffer cmd = beginSingleTimeCommands();
    VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    barrier.oldLayout = oldLayout; barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image; barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1; barrier.subresourceRange.layerCount = 1;
    VkPipelineStageFlags srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    VkPipelineStageFlags dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    } else if (oldLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
        barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    }
    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    endSingleTimeCommands(cmd);
}

void VulkanRenderer::copyBufferToImage(VkBuffer buffer, VkImage image, uint32_t width, uint32_t height) {
    VkCommandBuffer cmd = beginSingleTimeCommands();
    VkBufferImageCopy copy{};
    copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copy.imageSubresource.layerCount = 1;
    copy.imageExtent = {width, height, 1};
    vkCmdCopyBufferToImage(cmd, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    endSingleTimeCommands(cmd);
}

bool VulkanRenderer::createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags props,
                                  VkBuffer& buffer, VkDeviceMemory& memory) {
    VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    info.size = size; info.usage = usage; info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(m_device, &info, nullptr, &buffer) != VK_SUCCESS) return false;
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(m_device, buffer, &req);
    VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    alloc.allocationSize = req.size; alloc.memoryTypeIndex = findMemoryType(req.memoryTypeBits, props);
    if (vkAllocateMemory(m_device, &alloc, nullptr, &memory) != VK_SUCCESS) return false;
    return vkBindBufferMemory(m_device, buffer, memory, 0) == VK_SUCCESS;
}

bool VulkanRenderer::createImage(uint32_t width, uint32_t height, VkFormat format, VkImageTiling tiling,
                                 VkImageUsageFlags usage, VkMemoryPropertyFlags props,
                                 VkImage& image, VkDeviceMemory& memory) {
    VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    info.imageType = VK_IMAGE_TYPE_2D; info.extent = {width, height, 1}; info.mipLevels = 1; info.arrayLayers = 1;
    info.format = format; info.tiling = tiling; info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    info.usage = usage; info.samples = VK_SAMPLE_COUNT_1_BIT; info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateImage(m_device, &info, nullptr, &image) != VK_SUCCESS) return false;
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(m_device, image, &req);
    VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    alloc.allocationSize = req.size; alloc.memoryTypeIndex = findMemoryType(req.memoryTypeBits, props);
    if (vkAllocateMemory(m_device, &alloc, nullptr, &memory) != VK_SUCCESS) return false;
    return vkBindImageMemory(m_device, image, memory, 0) == VK_SUCCESS;
}

uint32_t VulkanRenderer::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags props) const {
    VkPhysicalDeviceMemoryProperties mem{};
    vkGetPhysicalDeviceMemoryProperties(m_physicalDevice, &mem);
    for (uint32_t i = 0; i < mem.memoryTypeCount; ++i) {
        if ((typeFilter & (1u << i)) && (mem.memoryTypes[i].propertyFlags & props) == props) return i;
    }
    return 0;
}

VkCommandBuffer VulkanRenderer::beginSingleTimeCommands() {
    VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; alloc.commandPool = m_commandPool; alloc.commandBufferCount = 1;
    VkCommandBuffer cmd;
    vkAllocateCommandBuffers(m_device, &alloc, &cmd);
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &begin);
    return cmd;
}

void VulkanRenderer::endSingleTimeCommands(VkCommandBuffer cmd) {
    vkEndCommandBuffer(cmd);
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1; submit.pCommandBuffers = &cmd;
    vkQueueSubmit(m_graphicsQueue, 1, &submit, VK_NULL_HANDLE);
    vkQueueWaitIdle(m_graphicsQueue);
    vkFreeCommandBuffers(m_device, m_commandPool, 1, &cmd);
}

VkShaderModule VulkanRenderer::createShaderModule(const uint32_t* code, size_t wordCount) {
    VkShaderModuleCreateInfo info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    info.codeSize = wordCount * sizeof(uint32_t);
    info.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    return vkCreateShaderModule(m_device, &info, nullptr, &module) == VK_SUCCESS ? module : VK_NULL_HANDLE;
}

bool VulkanRenderer::recreateSwapchain() {
    vkDeviceWaitIdle(m_device);
    cleanupSwapchain();
    return createSwapchain() && createImageViews() && createRenderPass() && createPipeline() && createFramebuffers();
}

void VulkanRenderer::setFiltering(bool nearest) {
    m_nearestFiltering = nearest;
}

void VulkanRenderer::setAspectRatio(float ratio) {
    m_aspectRatio = ratio;
}

void VulkanRenderer::setCrtShaderEnabled(bool enabled) { m_crtShaderEnabled = enabled; }
void VulkanRenderer::setResolutionScale(int scale) { m_resolutionScale = scale; }
void VulkanRenderer::setAntiAliasingMode(int mode) { m_antiAliasingMode = mode; }
void VulkanRenderer::setOutputResolutionPreset(int targetHeight) { m_outputTargetHeight = targetHeight; }
void VulkanRenderer::setFlipVertical(bool enabled) { setFlip(false, enabled); }
void VulkanRenderer::setFlip(bool flipX, bool flipY) { m_flipX = flipX; m_flipY = flipY; }

void VulkanRenderer::setRotation(int degrees) {
    int r = 0;
    switch (degrees) {
        case 0:   r = 0; break;
        case 90:  r = 1; break;
        case 180: r = 2; break;
        case 270: r = 3; break;
        default:  r = 0; break;
    }
    if (r == m_rotation) return;
    m_rotation = r;
    LOGI("Vulkan rotation set to %d deg (slot %d)", degrees, r);
    // No recreateSwapchain() — the rotation is applied entirely in the vertex
    // shader via the push constant in recordCommandBuffer().
}
