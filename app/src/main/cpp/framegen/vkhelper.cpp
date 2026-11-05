/* SPDX-License-Identifier: CC-BY-NC-ND-4.0 */

#include "vkhelper.hpp"
#include "perf_metrics.h"

#include <algorithm>
#include <array>
#include <bitset>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <ios>
#include <iostream>
#include <optional>
#include <span>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>
#include <vk>

#if defined(__ANDROID__)
#include <android/hardware_buffer.h>
#endif

/* Device initialization */

vk::UniqueInstance vkhelper::createInstance(
    vk::detail::DispatchLoaderDynamic& dld,
    const std::string& name,
    uint32_t version
) {
    dld.init();

    const vk::ApplicationInfo appInfo{
        .pApplicationName = name.c_str(),
        .applicationVersion = vk::makeVersion(2, 0, 0),
        .pEngineName = name.c_str(),
        .engineVersion = vk::makeVersion(2, 0, 0),
        .apiVersion = version
    };
    const vk::InstanceCreateInfo instanceInfo{
        .pApplicationInfo = &appInfo
    };
    auto instance{vk::createInstanceUnique(instanceInfo, nullptr, dld)};
    dld.init(*instance);

    return instance;
}

vk::PhysicalDevice vkhelper::findPhysicalDevice(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Instance& instance,
    const std::string& id
) {
    for (const auto& physdev : instance.enumeratePhysicalDevices(dld)) {
        // Check for VK_EXT_pci_bus_info
        bool supportsPCIEXT{false};
        for (const auto& ext : physdev.enumerateDeviceExtensionProperties(nullptr, dld)) {
            if (std::string(ext.extensionName) != vk::EXTPciBusInfoExtensionName)
                continue;

            supportsPCIEXT = true;
            break;
        }

        // Fetch properties
        vk::PhysicalDevicePCIBusInfoPropertiesEXT busInfo{};
        vk::PhysicalDeviceProperties2 info{
            .pNext = supportsPCIEXT ? &busInfo : nullptr
        };
        physdev.getProperties2(&info, dld);

        auto& props{info.properties};

        // Check first if id is not given
        if (id.empty())
            return physdev;

        // Compare device name
        props.deviceName.back() = '\0'; // Ensure null-termination
        if (id == std::string(props.deviceName))
            return physdev;

        // Compare Vendor ID + Device ID
        std::ostringstream gpuss;
        gpuss << std::hex << std::setfill('0')
            << std::setw(4) << props.vendorID << ":"
            << std::setw(4) << props.deviceID;
        if (id == gpuss.str())
            return physdev;

        // Compare PCI bus ID
        if (!supportsPCIEXT)
            continue;

        std::ostringstream pciss;
        pciss << std::hex << std::setfill('0')
            << std::setw(4) << busInfo.pciDomain << ":"
            << std::setw(2) << busInfo.pciBus << ":"
            << std::setw(2) << busInfo.pciDevice << "."
            << std::setw(1) << busInfo.pciFunction;
        if (id == pciss.str())
            return physdev;
    }

    throw std::runtime_error("No physical device matching '" + id + "' found");
}

uint32_t vkhelper::findQueueFamilyIndex(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::PhysicalDevice& physdev,
    vk::QueueFlags flags,
    bool requireDedicated
) {
    uint32_t idx{0};
    for (const auto& family : physdev.getQueueFamilyProperties(dld)) {
        if (requireDedicated && (family.queueFlags & vk::QueueFlagBits::eGraphics))
            continue;
        if (family.queueFlags & flags)
            return idx;
        idx++;
    }

    throw std::runtime_error("No compute-capable queue family found");
}

bool vkhelper::checkHalfPrecisionSupport(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::PhysicalDevice& physdev
) {
    vk::PhysicalDeviceVulkan12Features featuresVulkan12{};
    vk::PhysicalDeviceFeatures2 features{
        .pNext = &featuresVulkan12
    };
    physdev.getFeatures2(&features, dld);
    return featuresVulkan12.shaderFloat16;
}

/* Shader modules & pipelines */

vk::UniqueShaderModule vkhelper::createShaderModule(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    const std::span<const uint32_t>& code
) {
    const vk::ShaderModuleCreateInfo shaderInfo{
        .codeSize = code.size() * sizeof(uint32_t),
        .pCode = code.data()
    };
    return device.createShaderModuleUnique(shaderInfo, nullptr, dld);
}

namespace {
    // Base directory for pipeline cache files.
    //
    // Path policy (Android-first, per project rule "never use /tmp/"):
    //   1) explicit dir set by the host app via setPipelineCacheDir()
    //      (app internal files/lsfg-cache, always writable, no permissions)
    //   2) XDG_CACHE_HOME (desktop)
    //   3) $HOME/.cache (desktop)
    //   4) /tmp/lsfg-vk last resort (Linux desktop only; on HyperOS /tmp is a
    //      read-only tmpfs, so creating dirs there throws)
    // Any failure to create the directory degrades to in-memory-only cache
    // (returns an empty path) instead of aborting session creation.
    std::string g_pipelineCacheBase{};

    /// Find the cache file path. Returns an empty path when no usable cache
    /// directory could be established (caller must then skip file I/O).
    std::filesystem::path findPipelineCache(
        const vk::detail::DispatchLoaderDynamic& dld,
        const vk::PhysicalDevice& physdev,
        std::string_view tag
    ) {
        std::filesystem::path base;
        if (!g_pipelineCacheBase.empty())
            base = std::filesystem::path{g_pipelineCacheBase};
        else {
            const char* xdgCacheHome{std::getenv("XDG_CACHE_HOME")};
            if (xdgCacheHome && *xdgCacheHome != '\0')
                base = std::filesystem::path(xdgCacheHome) / "lsfg-vk";
            else {
                const char* home{std::getenv("HOME")};
                if (home && *home != '\0')
                    base = std::filesystem::path(home) / ".cache" / "lsfg-vk";
                else
                    base = std::filesystem::path{"/tmp/lsfg-vk"};
            }
        }

        // Ensure the directory exists (best-effort: a read-only /tmp on some
        // Android builds must not kill session creation).
        std::error_code ec{};
        if (!std::filesystem::exists(base, ec) && ec) {
            if (!g_pipelineCacheBase.empty())
                base = std::filesystem::path{""}; // explicit dir but unwritable: go in-memory
        }
        if (!base.empty() && !std::filesystem::exists(base)) {
            try {
                std::filesystem::create_directories(base);
            } catch (const std::exception&) {
                base = std::filesystem::path{""};
            }
        }

        // Calculate the physical device UUID
        const auto info{physdev.getProperties(dld)};

        std::ostringstream ss;
        ss << std::hex << std::setfill('0');
        for (size_t i = 0; i < 16; i++) {
            ss << std::setw(2) << static_cast<uint32_t>(info.pipelineCacheUUID.at(i));
            if (i == 3 || i == 5 || i == 7 || i == 9) {
                ss << "-";
            }
        }

        // No usable base dir: signal "in-memory only" to the callers.
        if (base.empty())
            return std::filesystem::path{};

        // Return the full path
        return base / ("cache_" + std::string(tag) + "_" + ss.str() + ".bin");
    }
}

void vkhelper::setPipelineCacheDir(const std::string& dir) { g_pipelineCacheBase = dir; }

std::pair<vk::UniquePipelineCache, bool> vkhelper::createPipelineCache(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    const vk::PhysicalDevice& physdev,
    std::string_view tag
) {
    const std::filesystem::path path{findPipelineCache(dld, physdev, tag)};
    const bool valid{!path.empty() && std::filesystem::exists(path) && std::filesystem::file_size(path) > 32};

    // Read cache data (if any). On a fresh install the cache file does not
    // exist yet, so gate on exists() (the directory is created by
    // findPipelineCache, but the file itself is written only after the
    // first pipeline build).
    std::vector<uint8_t> cacheData{};
    if (!path.empty() && std::filesystem::exists(path)) {
        std::ifstream file(path, std::ios::binary | std::ios::ate);
        if (!file.is_open())
            throw std::runtime_error("Unable to open pipeline cache file for reading");

        const auto size{static_cast<std::streamsize>(file.tellg())};
        cacheData = std::vector<uint8_t>(static_cast<size_t>(size));

        file.seekg(0, std::ios::beg);
        if (!file.read(reinterpret_cast<char*>(cacheData.data()), size)) // NOLINT (unsafe cast)
            throw std::runtime_error("Unable to read pipeline cache file");
    }

    // Build pipeline cache
    const vk::PipelineCacheCreateInfo pipelineCacheInfo{
        .initialDataSize = cacheData.size(),
        .pInitialData = cacheData.data()
    };
    return { device.createPipelineCacheUnique(pipelineCacheInfo, nullptr, dld), valid };
}

void vkhelper::persistPipelineCache(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    const vk::PhysicalDevice& physdev,
    const vk::PipelineCache& cache,
    std::string_view tag
) {
    const std::filesystem::path path{findPipelineCache(dld, physdev, tag)};
    if (path.empty())
        return; // no usable cache dir: keep cache in memory only

    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    if (!file.is_open())
        throw std::runtime_error("Unable to open pipeline cache file for writing");

    const std::vector<uint8_t> cacheData{
        device.getPipelineCacheData(cache, dld)
    };
    file.write(
        reinterpret_cast<const char*>(cacheData.data()), // NOLINT (unsafe cast)
        static_cast<std::streamsize>(cacheData.size())
    );

    file.flush();
    file.close();
}

std::pair<vk::UniqueDescriptorSetLayout, vk::UniquePipelineLayout> vkhelper::createLayout(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    const std::vector<vk::DescriptorSetLayoutBinding>& bindings,
    size_t pushConstantSize
) {
    const vk::DescriptorSetLayoutCreateInfo layoutInfo{
        .flags = vk::DescriptorSetLayoutCreateFlagBits::eUpdateAfterBindPool,
        .bindingCount = static_cast<uint32_t>(bindings.size()),
        .pBindings = bindings.data()
    };
    auto descriptorSetLayout{device.createDescriptorSetLayoutUnique(layoutInfo, nullptr, dld)};

    const vk::PushConstantRange pushConstantRange{
        .stageFlags = vk::ShaderStageFlagBits::eCompute,
        .size = static_cast<uint32_t>(pushConstantSize)
    };
    const vk::PipelineLayoutCreateInfo pipelineLayoutInfo{
        .setLayoutCount = 1,
        .pSetLayouts = &*descriptorSetLayout,
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &pushConstantRange
    };
    auto pipelineLayout{device.createPipelineLayoutUnique(pipelineLayoutInfo, nullptr, dld)};

    return { std::move(descriptorSetLayout), std::move(pipelineLayout) };
}

/* Resources */

vk::UniqueImage vkhelper::createImage(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    vk::Extent2D extent,
    vk::Format format,
    uint32_t layers,
    vk::ImageUsageFlags usage,
    bool external
) {
    const vk::ExternalMemoryImageCreateInfo externalInfo{
        .handleTypes = vk::ExternalMemoryHandleTypeFlagBits::eOpaqueFd
    };
    const vk::ImageCreateInfo imageInfo{
        .pNext = external ? &externalInfo : nullptr,
        .imageType = vk::ImageType::e2D,
        .format = format,
        .extent = {
            .width = extent.width,
            .height = extent.height,
            .depth = 1
        },
        .mipLevels = 1,
        .arrayLayers = layers,
        .samples = vk::SampleCountFlagBits::e1,
        .usage = usage
    };
    return device.createImageUnique(imageInfo, nullptr, dld);
}

#if defined(__ANDROID__)
vkhelper::AhbImage vkhelper::createImageFromAhb(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    const void* buffer,
    vk::ImageUsageFlags usage
) {
    lsfgperf::Scope timing(lsfgperf::Import);
    const auto* ahb{static_cast<const AHardwareBuffer*>(buffer)};
    if (!ahb)
        throw std::runtime_error("cannot import a null AHardwareBuffer");
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    if (desc.width == 0 || desc.height == 0)
        throw std::runtime_error("AHardwareBuffer_describe returned an empty buffer");

    auto getAhbProps{dld.vkGetAndroidHardwareBufferPropertiesANDROID};
    if (!getAhbProps)
        throw std::runtime_error("device lacks vkGetAndroidHardwareBufferPropertiesANDROID");

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProps{};
    formatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID props{};
    props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    props.pNext = &formatProps;
    if (getAhbProps(static_cast<VkDevice>(device), ahb, &props) != VK_SUCCESS)
        throw std::runtime_error("vkGetAndroidHardwareBufferPropertiesANDROID failed");
    if (formatProps.format == VK_FORMAT_UNDEFINED)
        throw std::runtime_error("AHardwareBuffer format cannot be blitted as a Vulkan image");
    if (!props.memoryTypeBits)
        throw std::runtime_error("AHardwareBuffer has no importable memory type");

    const vk::ExternalMemoryImageCreateInfo externalInfo{
        .handleTypes = vk::ExternalMemoryHandleTypeFlagBits::eAndroidHardwareBufferANDROID
    };
    const vk::ImageCreateInfo imageInfo{
        .pNext = &externalInfo,
        .imageType = vk::ImageType::e2D,
        .format = static_cast<vk::Format>(formatProps.format),
        .extent = {desc.width, desc.height, 1},
        .mipLevels = 1,
        .arrayLayers = desc.layers,
        .samples = vk::SampleCountFlagBits::e1,
        .usage = usage
    };
    AhbImage imported;
    imported.image = device.createImageUnique(imageInfo, nullptr, dld);

    uint32_t memoryTypeIndex = 0;
    while (!(props.memoryTypeBits & (1u << memoryTypeIndex)))
        ++memoryTypeIndex;

    // AHB imports require a dedicated image allocation using the queried AHB size.
    // Image memory requirements cannot be queried before binding AHB memory.
    const vk::MemoryDedicatedAllocateInfo dedicatedInfo{
        .image = *imported.image
    };
    const vk::ImportAndroidHardwareBufferInfoANDROID importInfo{
        .pNext = &dedicatedInfo,
        .buffer = const_cast<AHardwareBuffer*>(ahb)
    };
    const vk::MemoryAllocateInfo allocInfo{
        .pNext = &importInfo,
        .allocationSize = props.allocationSize,
        .memoryTypeIndex = memoryTypeIndex
    };
    {
        lsfgperf::Scope allocationTiming(lsfgperf::Allocate);
        imported.memory = device.allocateMemoryUnique(allocInfo, nullptr, dld);
    }
    device.bindImageMemory(*imported.image, *imported.memory, 0, dld);
    return imported;
}
#endif

vk::UniqueSampler vkhelper::createSampler(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    vk::SamplerAddressMode mode,
    vk::CompareOp compare,
    bool white
) {
    const vk::SamplerCreateInfo samplerInfo{
        .magFilter = vk::Filter::eLinear,
        .minFilter = vk::Filter::eLinear,
        .mipmapMode = vk::SamplerMipmapMode::eLinear,
        .addressModeU = mode,
        .addressModeV = mode,
        .addressModeW = mode,
        .compareOp = compare,
        .maxLod = vk::LodClampNone,
        .borderColor = white ?
            vk::BorderColor::eFloatOpaqueWhite : vk::BorderColor::eFloatTransparentBlack
    };
    return device.createSamplerUnique(samplerInfo, nullptr, dld);
}

std::pair<vk::UniqueBuffer, vk::UniqueDeviceMemory> vkhelper::createBuffer(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    const vk::PhysicalDevice& physdev,
    vk::BufferUsageFlags usage,
    const void* data,
    size_t size
) {
    // Create buffer
    const vk::BufferCreateInfo bufferInfo{
        .size = size,
        .usage = usage,
        .sharingMode = vk::SharingMode::eExclusive
    };
    auto buffer{device.createBufferUnique(bufferInfo, nullptr, dld)};

    // Allocate memory
    const auto requirements{device.getBufferMemoryRequirements(*buffer, dld)};

    auto memory{vkhelper::allocateMemory(
        dld,
        device,
        physdev,
        requirements.size,
        requirements.memoryTypeBits,
        true
    )};

    // Bind memory
    device.bindBufferMemory(*buffer, *memory, 0, dld);

    // Copy data
    if (data) {
        void* mapped{device.mapMemory(*memory, 0, size, {}, dld)};
        std::copy_n(
            static_cast<const uint8_t*>(data),
            size,
            static_cast<uint8_t*>(mapped)
        );
        device.unmapMemory(*memory, dld);
    }

    return {
        std::move(buffer),
        std::move(memory)
    };
}

/* Memory allocations */

vk::UniqueDeviceMemory vkhelper::allocateMemory(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    const vk::PhysicalDevice& physdev,
    vk::DeviceSize size,
    std::bitset<32> types,
    bool hostVisible,
    const void* next
) {
    // Find a suitable memory type index
    const auto memProps{physdev.getMemoryProperties(dld)};

    std::optional<uint32_t> idealTypeIdx{};
    std::optional<uint32_t> fallbackTypeIdx{};
    for (size_t i = 0; i < memProps.memoryTypeCount; i++) {
        if (!types.test(i))
            continue;
        const auto& memType{memProps.memoryTypes.at(i)};

        // Require host visibility when requested
        const bool isHostVisible{
            memType.propertyFlags & vk::MemoryPropertyFlagBits::eHostVisible &&
            memType.propertyFlags & vk::MemoryPropertyFlagBits::eHostCoherent
        };
        if (hostVisible && !isHostVisible)
            continue;

        // Always prefer device local memory
        const bool isDeviceLocal{
            memType.propertyFlags & vk::MemoryPropertyFlagBits::eDeviceLocal
        };
        if (isDeviceLocal && !idealTypeIdx.has_value()) {
            idealTypeIdx = i;
        }

        // Fallback to host-visible memory if no device-local memory is available
        if (!isDeviceLocal && !fallbackTypeIdx.has_value()) {
            fallbackTypeIdx = i;
        }
    }

    // Allocate memory
    try {
        const vk::MemoryAllocateInfo allocInfo{
            .pNext = next,
            .allocationSize = size,
            .memoryTypeIndex = *idealTypeIdx // Throws when not present
        };
        return device.allocateMemoryUnique(allocInfo, nullptr, dld);
    } catch (...) {
        if (!fallbackTypeIdx)
            throw "No suitable memory type found for allocation";
        const vk::MemoryAllocateInfo allocInfo{
            .pNext = next,
            .allocationSize = size,
            .memoryTypeIndex = *fallbackTypeIdx
        };
        return device.allocateMemoryUnique(allocInfo, nullptr, dld);
    }
}

/* Descriptors */

std::pair<vk::UniqueDescriptorPool, vk::DescriptorSet> vkhelper::createDescriptorSet(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    const vk::DescriptorSetLayout& layout,
    uint32_t samplers, uint32_t buffers,
    uint32_t sampledImages, uint32_t storageImages
) {
    const std::array<vk::DescriptorPoolSize, 4> poolSizes{{
        { .type = vk::DescriptorType::eSampler,
          .descriptorCount = samplers },
        { .type = vk::DescriptorType::eSampledImage,
          .descriptorCount = sampledImages },
        { .type = vk::DescriptorType::eStorageImage,
          .descriptorCount = storageImages },
        { .type = vk::DescriptorType::eUniformBuffer,
          .descriptorCount = buffers }
    }};
    auto pool{device.createDescriptorPoolUnique({
        .flags = vk::DescriptorPoolCreateFlagBits::eUpdateAfterBind,
        .maxSets = 1,
        .poolSizeCount = static_cast<uint32_t>(poolSizes.size()),
        .pPoolSizes = poolSizes.data()
    }, nullptr, dld)};

    const auto set{device.allocateDescriptorSets({
        .descriptorPool = *pool,
        .descriptorSetCount = 1,
        .pSetLayouts = &layout
    }, dld).at(0)};

    return{
        std::move(pool),
        set
    };
}

vk::UniqueImageView vkhelper::createImageView(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    const vk::Image& image,
    vk::Format format,
    uint32_t layers
) {
    const vk::ImageViewCreateInfo viewInfo{
        .image = image,
        .viewType = layers == 1 ? vk::ImageViewType::e2D : vk::ImageViewType::e2DArray,
        .format = format,
        .subresourceRange = {
            .aspectMask = vk::ImageAspectFlagBits::eColor,
            .levelCount = 1,
            .layerCount = layers
        }
    };
    return device.createImageViewUnique(viewInfo, nullptr, dld);
}

/* Command buffers */

vk::UniqueCommandPool vkhelper::createCommandPool(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    uint32_t qfi,
    bool resettable
) {
    const vk::CommandPoolCreateInfo cmdpoolInfo{
        .flags = resettable ? vk::CommandPoolCreateFlagBits::eResetCommandBuffer
            : vk::CommandPoolCreateFlagBits{0},
        .queueFamilyIndex = qfi,
    };
    return device.createCommandPoolUnique(cmdpoolInfo, nullptr, dld);
}

vk::UniqueCommandBuffer vkhelper::createCommandBuffer(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    const vk::CommandPool& cmdpool
) {
    const vk::CommandBufferAllocateInfo cmdbufInfo{
        .commandPool = cmdpool,
        .commandBufferCount = 1
    };
    return { std::move(device.allocateCommandBuffersUnique(cmdbufInfo, dld).front()) };
}

void vkhelper::blitImage(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::CommandBuffer& cmdbuf,
    const vk::Image& srcImage,
    uint32_t srcLayer,
    const vk::Image& dstImage,
    uint32_t dstLayer,
    vk::Extent2D extent, vk::Extent2D dstExtent
) {
    if (!dstExtent.width) dstExtent = extent;
    const vk::ImageBlit blitInfo{
        .srcSubresource = {
            .aspectMask = vk::ImageAspectFlagBits::eColor,
            .baseArrayLayer = srcLayer,
            .layerCount = 1,
        },
        .srcOffsets = {{
            vk::Offset3D { 0, 0, 0 },
            { static_cast<int32_t>(extent.width), static_cast<int32_t>(extent.height), 1 }
        }},
        .dstSubresource = {
            .aspectMask = vk::ImageAspectFlagBits::eColor,
            .baseArrayLayer = dstLayer,
            .layerCount = 1,
        },
        .dstOffsets = {{
            vk::Offset3D { 0, 0, 0 },
            { static_cast<int32_t>(dstExtent.width), static_cast<int32_t>(dstExtent.height), 1 }
        }}
    };
    cmdbuf.blitImage(
        srcImage, vk::ImageLayout::eGeneral,
        dstImage, vk::ImageLayout::eGeneral,
        blitInfo,
        vk::Filter::eNearest,
        dld
    );
}

/* Synchronization */

vk::UniqueSemaphore vkhelper::createSemaphore(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    bool timeline,
    bool external
) {
    const vk::ExportSemaphoreCreateInfo exportInfo{
        .handleTypes = vk::ExternalSemaphoreHandleTypeFlagBits::eOpaqueFd
    };
    const vk::SemaphoreTypeCreateInfo typeInfo{
        .pNext = external ? &exportInfo : nullptr,
        .semaphoreType = vk::SemaphoreType::eTimeline,
    };
    const vk::SemaphoreCreateInfo createInfo{
        .pNext = timeline ? static_cast<const void*>(&typeInfo)
            : (external ? &exportInfo : nullptr) // NOLINT (ternary)
    };
    return device.createSemaphoreUnique(createInfo, nullptr, dld);
}

vk::UniqueFence vkhelper::createFence(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device
) {
    return device.createFenceUnique({}, nullptr, dld);
}

/* External memory */

int vkhelper::exportMemoryFd(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    const vk::DeviceMemory& memory
) {
    const vk::MemoryGetFdInfoKHR fdInfo{
        .memory = memory,
        .handleType = vk::ExternalMemoryHandleTypeFlagBits::eOpaqueFd
    };
    return device.getMemoryFdKHR(fdInfo, dld);
}

int vkhelper::exportSemaphoreFd(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    const vk::Semaphore& semaphore
) {
    const vk::SemaphoreGetFdInfoKHR fdInfo{
        .semaphore = semaphore,
        .handleType = vk::ExternalSemaphoreHandleTypeFlagBits::eOpaqueFd
    };
    return device.getSemaphoreFdKHR(fdInfo, dld);
}

void vkhelper::importSemaphoreFd(
    const vk::detail::DispatchLoaderDynamic& dld,
    const vk::Device& device,
    const vk::Semaphore& semaphore,
    int fd
) {
    const vk::ImportSemaphoreFdInfoKHR importInfo{
        .semaphore = semaphore,
        .handleType = vk::ExternalSemaphoreHandleTypeFlagBits::eOpaqueFd,
        .fd = fd
    };
    device.importSemaphoreFdKHR(importInfo, dld);
}
