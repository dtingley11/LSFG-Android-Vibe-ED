/* SPDX-License-Identifier: CC-BY-NC-ND-4.0 */

#pragma once

#include <bitset>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>
#include <vk>

#if defined(__ANDROID__)
struct AHardwareBuffer;
#endif

///
/// Various helper functions to deduplicate Vulkan boilerplate code tailored to lsfg-vk.
///
/// @throws std::runtime_error on failure
///
namespace vkhelper {

    /* Device initialization */

    ///
    /// Create a Vulkan instance
    ///
    /// @param dld Dynamic dispatch loader
    /// @param name Application and engine name
    /// @param version Vulkan API version
    ///
    vk::UniqueInstance createInstance(
        vk::detail::DispatchLoaderDynamic& dld,
        const std::string& name,
        uint32_t version
    );

    ///
    /// Find a physical device through a custom identifier
    ///
    /// The custom identifier may be one of:
    /// - Device name (e.g. "NVIDIA GeForce RTX 5080")
    /// - Vendor ID + Device ID in lowercase hexadecimal (e.g. "10de:2c02")
    /// - PCI bus ID with padded zeroes (e.g. "0000:01:00.0")
    ///
    /// Throws if no device matching the identifier is found.
    ///
    /// @param id Custom identifier
    /// @return Selected physical device
    ///
    vk::PhysicalDevice findPhysicalDevice(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Instance& instance,
        const std::string& id
    );

    ///
    /// Find the a queue family index
    ///
    /// @param requireDedicated Whether to skip any graphics-capable queues
    /// @return Queue family index
    ///
    uint32_t findQueueFamilyIndex(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::PhysicalDevice& physdev,
        vk::QueueFlags flags,
        bool requireDedicated = false
    );

    ///
    /// Check a physical device for half-precision float support
    ///
    /// @return Whether half-precision float is supported
    ///
    bool checkHalfPrecisionSupport(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::PhysicalDevice& physdev
    );

    /* Shader modules & pipelines */

    ///
    /// Create a Vulkan shader module from SPIR-V bytecode
    ///
    /// @param code SPIR-V bytecode
    ///
    vk::UniqueShaderModule createShaderModule(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const std::span<const uint32_t>& code
    );

    ///
    /// Set the base directory for pipeline cache files (call once at init).
    /// On Android this must be the app's internal files dir (/data/data/<pkg>/files/lsfg-cache).
    /// Empty string: fall back to XDG/HOME env dirs, then /tmp/lsfg-vk.
    ///
    void setPipelineCacheDir(const std::string& dir);

    ///
    /// Create and maintain the Vulkan pipeline cache for lsfg-vk
    ///
    /// @param tag Cache tag for different pipelines
    ///
    std::pair<vk::UniquePipelineCache, bool> createPipelineCache(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const vk::PhysicalDevice& physdev,
        std::string_view tag
    );

    ///
    /// Persist the Vulkan pipeline cache to disk
    ///
    /// @param tag Cache tag for different pipelines
    ///
    void persistPipelineCache(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const vk::PhysicalDevice& physdev,
        const vk::PipelineCache& cache,
        std::string_view tag
    );

    ///
    /// Create a Vulkan descriptor set layout
    ///
    std::pair<vk::UniqueDescriptorSetLayout, vk::UniquePipelineLayout> createLayout(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const std::vector<vk::DescriptorSetLayoutBinding>& bindings,
        size_t pushConstantSize
    );

    /* Resources */

    ///
    /// Create a (unallocated) Vulkan image
    ///
    /// @param external Whether the image should be exportable/importable
    ///
    vk::UniqueImage createImage(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        vk::Extent2D extent,
        vk::Format format,
        uint32_t layers,
        vk::ImageUsageFlags usage,
        bool external = false
    );

#if defined(__ANDROID__)
    struct AhbImage {
        // Members are destroyed in reverse order: image before its backing memory.
        vk::UniqueDeviceMemory memory;
        vk::UniqueImage image;
    };

    /// Import an AHardwareBuffer, retaining both its image and dedicated allocation.
    AhbImage createImageFromAhb(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const void* buffer,
        vk::ImageUsageFlags usage = vk::ImageUsageFlagBits::eTransferSrc | vk::ImageUsageFlagBits::eTransferDst
    );
#endif

    ///
    /// Create a Vulkan sampler
    ///
    /// @param mode Address mode
    /// @param compare Comparison mode
    /// @param white Black/White border color
    ///
    vk::UniqueSampler createSampler(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        vk::SamplerAddressMode mode,
        vk::CompareOp compare,
        bool white
    );

    // (forward decl)
    std::pair<vk::UniqueBuffer, vk::UniqueDeviceMemory> createBuffer(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const vk::PhysicalDevice& physdev,
        vk::BufferUsageFlags usage,
        const void* data,
        size_t size
    );

    ///
    /// Create a Vulkan buffer
    ///
    template<typename T>
    std::pair<vk::UniqueBuffer, vk::UniqueDeviceMemory> createBuffer(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const vk::PhysicalDevice& physdev,
        const T& data
    ) {
        return createBuffer(
            dld,
            device,
            physdev,
            vk::BufferUsageFlagBits::eUniformBuffer | vk::BufferUsageFlagBits::eTransferDst,
            static_cast<const void*>(&data),
            sizeof(T)
        );
    }

    /* Memory allocations */

    ///
    /// Create a Vulkan memory allocation
    ///
    /// @param hostVisible Require host visible memory
    /// @param next Optional pNext chain for memory allocation info
    ///
    vk::UniqueDeviceMemory allocateMemory(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const vk::PhysicalDevice& physdev,
        vk::DeviceSize size,
        std::bitset<32> types,
        bool hostVisible = false,
        const void* next = nullptr
    );

    ///
    /// Align a memory allocation
    ///
    inline vk::DeviceSize align(vk::DeviceSize size, vk::DeviceSize align) noexcept {
        return (size + align - 1) & ~(align - 1);
    }

    /* Descriptors */

    ///
    /// Create a Vulkan descriptor set
    ///
    std::pair<vk::UniqueDescriptorPool, vk::DescriptorSet> createDescriptorSet(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const vk::DescriptorSetLayout& layout,
        uint32_t samplers, uint32_t buffers,
        uint32_t sampledImages, uint32_t storageImages
    );

    ///
    /// Create an image view
    ///
    vk::UniqueImageView createImageView(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const vk::Image& image,
        vk::Format format,
        uint32_t layers
    );

    /* Command buffers */

    ///
    /// Create a Vulkan command pool
    ///
    /// @param qfi Queue family index
    /// @param resettable Whether command buffers allocated from this pool should be resettable
    ///
    vk::UniqueCommandPool createCommandPool(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        uint32_t qfi,
        bool resettable = false
    );

    ///
    /// Create a Vulkan command buffer
    ///
    vk::UniqueCommandBuffer createCommandBuffer(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const vk::CommandPool& cmdpool
    );

    ///
    /// Create a image memory barrier
    ///
    inline vk::ImageMemoryBarrier barrier(
        const vk::Image& image,
        vk::AccessFlags srcAccessMask,
        vk::AccessFlags dstAccessMask,
        vk::ImageLayout oldLayout,
        vk::ImageLayout newLayout,
        uint32_t baseLayer = 0,
        uint32_t layerCount = 1
    ) {
        return {
            .srcAccessMask = srcAccessMask,
            .dstAccessMask = dstAccessMask,
            .oldLayout = oldLayout,
            .newLayout = newLayout,
            .srcQueueFamilyIndex = vk::QueueFamilyIgnored,
            .dstQueueFamilyIndex = vk::QueueFamilyIgnored,
            .image = image,
            .subresourceRange = {
                .aspectMask = vk::ImageAspectFlagBits::eColor,
                .levelCount = 1,
                .baseArrayLayer = baseLayer,
                .layerCount = layerCount
            }
        };
    }

    ///
    /// Perform a blit operation
    ///
    void blitImage(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::CommandBuffer& cmdbuf,
        const vk::Image& srcImage,
        uint32_t srcLayer,
        const vk::Image& dstImage,
        uint32_t dstLayer,
        vk::Extent2D extent, vk::Extent2D dstExtent = {}
    );

    /* Synchronization */

    ///
    /// Create a semaphore
    ///
    /// @param timeline Whether the semaphore should be a timeline semaphore
    /// @param external Whether the semaphore should be exportable/importable
    ///
    vk::UniqueSemaphore createSemaphore(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        bool timeline = false,
        bool external = false
    );

    ///
    /// Create a fence
    ///
    vk::UniqueFence createFence(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device
    );

    /* External memory */

    ///
    /// Export a Vulkan memory allocation as a fd
    ///
    /// @return File descriptor to the allocation
    ///
    int exportMemoryFd(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const vk::DeviceMemory& memory
    );

    ///
    /// Export a Vulkan semaphore as a fd
    ///
    /// @return File descriptor to the semaphore
    ///
    int exportSemaphoreFd(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const vk::Semaphore& semaphore
    );

    ///
    /// Import a Vulkan semaphore from a fd
    ///
    void importSemaphoreFd(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::Device& device,
        const vk::Semaphore& semaphore,
        int fd
    );

}
