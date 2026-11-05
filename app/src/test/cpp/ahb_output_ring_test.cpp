#include "framegen/ahb_output_ring.hpp"
#include <cassert>
#include <iostream>
#include <map>
#include <set>
#include <vector>

namespace {
struct MockDriver {
    unsigned next = 0, allocations = 0, failAt = 0;
    bool supported = true, badGeometry = false, lost = false;
    uint32_t maxWidth = 4096, expectedWidth = 1920, expectedHeight = 1080;
    std::set<VkImage> images;
    std::set<VkDeviceMemory> memories;
    std::map<VkDeviceMemory, VkImage> bindings;
    std::map<VkDeviceMemory, AHardwareBuffer*> exports;
    std::vector<std::unique_ptr<AHardwareBuffer>> buffers;
    static MockDriver* current;
    vk::detail::DispatchLoaderDynamic api;
    MockDriver() {
        current = this;
        api.vkGetPhysicalDeviceImageFormatProperties2 = [](VkPhysicalDevice, const VkPhysicalDeviceImageFormatInfo2* f, VkImageFormatProperties2* out) {
            auto& m = *current;
            auto* handle = static_cast<const VkPhysicalDeviceExternalImageFormatInfo*>(f->pNext);
            assert(handle->handleType == VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID);
            assert(f->format == VK_FORMAT_R8G8B8A8_UNORM);
            assert(f->usage & VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            auto* ext = static_cast<VkExternalImageFormatProperties*>(out->pNext);
            ext->externalMemoryProperties.externalMemoryFeatures = VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT |
                (m.supported ? VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT : 0);
            ext->externalMemoryProperties.compatibleHandleTypes = handle->handleType;
            out->imageFormatProperties.maxExtent = {m.maxWidth, 4096, 1};
            out->imageFormatProperties.maxMipLevels = out->imageFormatProperties.maxArrayLayers = 1;
            out->imageFormatProperties.sampleCounts = VK_SAMPLE_COUNT_1_BIT;
            return VK_SUCCESS;
        };
        api.vkGetPhysicalDeviceMemoryProperties = [](VkPhysicalDevice, VkPhysicalDeviceMemoryProperties* p) {
            p->memoryTypeCount = 2;
            p->memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_PROTECTED_BIT | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            p->memoryTypes[1].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
        };
        api.vkCreateImage = [](VkDevice, const VkImageCreateInfo* p, const VkAllocationCallbacks*, VkImage* out) {
            auto& m = *current;
            assert(p->extent.width == m.expectedWidth && p->extent.height == m.expectedHeight);
            assert(p->arrayLayers == 1 && p->initialLayout == VK_IMAGE_LAYOUT_UNDEFINED);
            *out = reinterpret_cast<VkImage>(uintptr_t(++m.next)); m.images.insert(*out); return VK_SUCCESS;
        };
        api.vkAllocateMemory = [](VkDevice, const VkMemoryAllocateInfo* p, const VkAllocationCallbacks*, VkDeviceMemory* out) {
            auto& m = *current;
            // Catch both ZIP regressions: nonzero export size and pre-bind requirements queries.
            assert(p->allocationSize == 0 && p->memoryTypeIndex == 1);
            auto* dedicated = static_cast<const VkMemoryDedicatedAllocateInfo*>(p->pNext);
            assert(dedicated->sType == VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO && m.images.contains(dedicated->image));
            auto* exportInfo = static_cast<const VkExportMemoryAllocateInfo*>(dedicated->pNext);
            assert(exportInfo->handleTypes == VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID);
            if (++m.allocations == m.failAt) return m.lost ? VK_ERROR_DEVICE_LOST : VK_ERROR_OUT_OF_DEVICE_MEMORY;
            *out = reinterpret_cast<VkDeviceMemory>(uintptr_t(++m.next));
            m.memories.insert(*out); m.bindings[*out] = dedicated->image; return VK_SUCCESS;
        };
        api.vkGetImageMemoryRequirements = [](VkDevice, VkImage, VkMemoryRequirements*) { assert(false && "AHB image queried before binding"); };
        api.vkBindImageMemory = [](VkDevice, VkImage image, VkDeviceMemory memory, VkDeviceSize offset) {
            assert(current->bindings.at(memory) == image && offset == 0); return VK_SUCCESS;
        };
        api.vkGetMemoryAndroidHardwareBufferANDROID = [](VkDevice, const VkMemoryGetAndroidHardwareBufferInfoANDROID* p, AHardwareBuffer** out) {
            auto& m = *current;
            auto b = std::make_unique<AHardwareBuffer>(); b->refs = 3; // test owner + driver + exported reference
            b->description = {m.expectedWidth, m.badGeometry ? 1 : m.expectedHeight, 1, 1, 0, m.expectedWidth};
            *out = b.get(); m.exports[p->memory] = *out; m.buffers.push_back(std::move(b)); return VK_SUCCESS;
        };
        api.vkDestroyImage = [](VkDevice, VkImage image, const VkAllocationCallbacks*) { assert(current->images.erase(image) == 1); };
        api.vkFreeMemory = [](VkDevice, VkDeviceMemory memory, const VkAllocationCallbacks*) {
            auto& m = *current;
            assert(!m.images.contains(m.bindings.at(memory))); // image must die before allocation
            assert(m.memories.erase(memory) == 1);
            if (m.exports.contains(memory)) --m.exports.at(memory)->refs;
        };
    }
    void cleaned() {
        assert(images.empty() && memories.empty());
        for (auto& b : buffers) assert(b->refs == 1);
    }
};
MockDriver* MockDriver::current = nullptr;
}

int main() {
    std::string reason;
    {
        MockDriver m;
        auto ring = lsfgvk::AhbOutputRing::create(m.api, {}, {}, 1920, 1080, 8, reason);
        assert(ring && reason.empty() && ring->slots.size() == 8);
        assert(ring->find(ring->slots[3]->buffer) == ring->slots[3].get());
        assert(!ring->find(nullptr));
        ring.reset(); m.cleaned();
    }
    for (unsigned failure = 1; failure <= 8; ++failure) {
        MockDriver m; m.failAt = failure;
        auto ring = lsfgvk::AhbOutputRing::create(m.api, {}, {}, 1920, 1080, 8, reason);
        assert(!ring && !reason.empty()); m.cleaned();
    }
    {
        MockDriver m; m.supported = false;
        assert(!lsfgvk::AhbOutputRing::create(m.api, {}, {}, 1920, 1080, 8, reason));
        assert(m.allocations == 0); m.cleaned();
    }
    {
        MockDriver m; m.maxWidth = 1024;
        assert(!lsfgvk::AhbOutputRing::create(m.api, {}, {}, 1920, 1080, 8, reason)); m.cleaned();
        assert(m.allocations == 0);
    }
    {
        MockDriver m; m.badGeometry = true;
        assert(!lsfgvk::AhbOutputRing::create(m.api, {}, {}, 1920, 1080, 8, reason)); m.cleaned();
    }
    {
        MockDriver m; m.failAt = 2; m.lost = true;
        bool threw = false;
        try { lsfgvk::AhbOutputRing::create(m.api, {}, {}, 1920, 1080, 8, reason); }
        catch (const lsfgvk::OutputRingError& e) { threw = e.result == VK_ERROR_DEVICE_LOST; }
        assert(threw); m.cleaned();
    }
    std::cout << "AHB export ring: capability checks, dedicated allocation, all partial failures and cleanup passed\n";
}
