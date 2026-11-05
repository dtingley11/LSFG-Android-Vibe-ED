#pragma once

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.hpp>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace lsfgvk {

struct OutputRingError : std::runtime_error {
    VkResult result;
    OutputRingError(const char* operation, VkResult code)
        : std::runtime_error(std::string(operation) + ": " + std::to_string(code)), result(code) {}
};

// Construction submits no GPU work. A partial ring can therefore unwind safely.
// The session must stop the presenter and confirm GPU completion before destruction.
class AhbOutputRing {
public:
    struct Slot {
        const vk::detail::DispatchLoaderDynamic& api;
        VkDevice device;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImage image = VK_NULL_HANDLE;
        AHardwareBuffer* buffer = nullptr;
        bool initialized = false;
        Slot(const vk::detail::DispatchLoaderDynamic& dispatch, VkDevice dev) : api(dispatch), device(dev) {}
        Slot(const Slot&) = delete;
        Slot& operator=(const Slot&) = delete;
        ~Slot() {
            if (image) api.vkDestroyImage(device, image, nullptr);
            if (memory) api.vkFreeMemory(device, memory, nullptr);
            if (buffer) AHardwareBuffer_release(buffer);
        }
    };

    static std::unique_ptr<AhbOutputRing> create(
        const vk::detail::DispatchLoaderDynamic& api, VkPhysicalDevice physical, VkDevice device,
        uint32_t width, uint32_t height, uint32_t count, std::string& reason);

    Slot* find(const void* buffer) const {
        for (const auto& slot : slots) if (slot->buffer == buffer) return slot.get();
        return nullptr;
    }

    std::vector<std::unique_ptr<Slot>> slots;
};

} // namespace lsfgvk
