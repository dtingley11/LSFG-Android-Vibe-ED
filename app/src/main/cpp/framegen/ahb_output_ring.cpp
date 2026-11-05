#include "ahb_output_ring.hpp"

#include <optional>

namespace {
void check(VkResult result, const char* operation) {
    if (result != VK_SUCCESS) throw lsfgvk::OutputRingError(operation, result);
}
}

std::unique_ptr<lsfgvk::AhbOutputRing> lsfgvk::AhbOutputRing::create(
    const vk::detail::DispatchLoaderDynamic& api, VkPhysicalDevice physical, VkDevice device,
    uint32_t width, uint32_t height, uint32_t count, std::string& reason) {
    reason.clear();
    if (!width || !height || count < 1 || count > 8) {
        reason = "invalid output ring dimensions or count";
        return nullptr;
    }
    if (!api.vkGetPhysicalDeviceImageFormatProperties2 || !api.vkGetPhysicalDeviceMemoryProperties ||
        !api.vkCreateImage || !api.vkDestroyImage || !api.vkAllocateMemory || !api.vkFreeMemory ||
        !api.vkBindImageMemory || !api.vkGetMemoryAndroidHardwareBufferANDROID) {
        reason = "AHB export entry points unavailable";
        return nullptr;
    }

    constexpr auto handle = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    constexpr VkImageUsageFlags usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT |
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    VkPhysicalDeviceExternalImageFormatInfo external{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO};
    external.handleType = handle;
    VkPhysicalDeviceImageFormatInfo2 format{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2};
    format.pNext = &external;
    format.format = VK_FORMAT_R8G8B8A8_UNORM;
    format.type = VK_IMAGE_TYPE_2D;
    format.tiling = VK_IMAGE_TILING_OPTIMAL;
    format.usage = usage;
    VkExternalImageFormatProperties externalProperties{VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};
    VkImageFormatProperties2 properties{VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2};
    properties.pNext = &externalProperties;
    const auto query = api.vkGetPhysicalDeviceImageFormatProperties2(physical, &format, &properties);
    if (query == VK_ERROR_DEVICE_LOST) throw OutputRingError("AHB export format query", query);
    const auto& limits = properties.imageFormatProperties;
    const auto& sharing = externalProperties.externalMemoryProperties;
    constexpr auto required = VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT | VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT;
    if (query != VK_SUCCESS || (sharing.externalMemoryFeatures & required) != required ||
        !(sharing.compatibleHandleTypes & handle) || width > limits.maxExtent.width || height > limits.maxExtent.height ||
        limits.maxArrayLayers < 1 || limits.maxMipLevels < 1 || !(limits.sampleCounts & VK_SAMPLE_COUNT_1_BIT)) {
        reason = "RGBA output dimensions/usage are not AHB exportable and importable";
        return nullptr;
    }

    // AHB export cannot query image requirements before binding (VUID 04004).
    // Choose an ordinary device-local type; for AHB exports the implementation
    // determines the actual allocation from the dedicated image (memory spec).
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    api.vkGetPhysicalDeviceMemoryProperties(physical, &memoryProperties);
    std::optional<uint32_t> memoryType;
    for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; ++i) {
        const auto flags = memoryProperties.memoryTypes[i].propertyFlags;
        if (flags & (VK_MEMORY_PROPERTY_PROTECTED_BIT | VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT)) continue;
        if (!memoryType || (flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) memoryType = i;
        if (flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) break;
    }
    if (!memoryType) { reason = "no unprotected output memory type"; return nullptr; }

    auto ring = std::make_unique<AhbOutputRing>();
    try {
        for (uint32_t i = 0; i < count; ++i) {
            auto slot = std::make_unique<Slot>(api, device);
            VkExternalMemoryImageCreateInfo imageExternal{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
            imageExternal.handleTypes = handle;
            VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
            imageInfo.pNext = &imageExternal;
            imageInfo.imageType = VK_IMAGE_TYPE_2D;
            imageInfo.format = format.format;
            imageInfo.extent = {width, height, 1};
            imageInfo.mipLevels = 1;
            imageInfo.arrayLayers = 1;
            imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
            imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
            imageInfo.usage = usage;
            imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            check(api.vkCreateImage(device, &imageInfo, nullptr, &slot->image), "create output image");

            VkExportMemoryAllocateInfo exportInfo{VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO};
            exportInfo.handleTypes = handle;
            VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
            dedicated.pNext = &exportInfo;
            dedicated.image = slot->image;
            VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            allocation.pNext = &dedicated;
            allocation.allocationSize = 0; // Required for dedicated AHB exports (VUID 01874).
            allocation.memoryTypeIndex = *memoryType;
            check(api.vkAllocateMemory(device, &allocation, nullptr, &slot->memory), "allocate output memory");
            check(api.vkBindImageMemory(device, slot->image, slot->memory, 0), "bind output memory");
            VkMemoryGetAndroidHardwareBufferInfoANDROID getBuffer{VK_STRUCTURE_TYPE_MEMORY_GET_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
            getBuffer.memory = slot->memory;
            check(api.vkGetMemoryAndroidHardwareBufferANDROID(device, &getBuffer, &slot->buffer), "export output AHB");
            if (!slot->buffer) throw std::runtime_error("driver returned a null output AHB");
            AHardwareBuffer_Desc description{};
            AHardwareBuffer_describe(slot->buffer, &description);
            if (description.width != width || description.height != height || description.layers != 1 ||
                description.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM)
                throw std::runtime_error("exported output AHB geometry or format mismatch");
            ring->slots.push_back(std::move(slot));
        }
        return ring;
    } catch (const OutputRingError& e) {
        if (e.result == VK_ERROR_DEVICE_LOST) throw;
        reason = e.what();
    } catch (const std::exception& e) { reason = e.what(); }
    return nullptr; // No partial ring escapes; no submitted work references its resources.
}
