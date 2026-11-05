/* SPDX-License-Identifier: CC-BY-NC-ND-4.0 */

#pragma once

#include "library.hpp"
#include "signature.hpp"
#include "signature/image.hpp"

#include "lsfgvk_public.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>
#include <vk>

namespace lsfgvk {

    /// Handle to an external image
    struct ExternalImage {
        /// Image Extent
        vk::Extent2D extent;
        /// Image Format
        vk::Format format;
        /// Amount of layers in image
        uint32_t layers;

        /// Handle to the Vulkan image (not owned)
        vk::Image image;
        /// Handle to the Vulkan memory (not owned)
        vk::DeviceMemory memory;

        /// True when this image's memory can be exported as an AHardwareBuffer,
        /// which is the only cross-VkDevice share that works on Adreno.
        bool ahbExportable{false};
    };

    /// Struct for the uniform buffer
    struct UniformBuffer {
        float timestamp;
        uint32_t iteration;
        uint32_t transferFunction;
        uint32_t colorSpace;
        float resolutionInvScale;
        float uiThreshold;
    };

    /// Struct for push constants
    struct PushConstants {
        uint32_t specialFlag;
        uint32_t subiteration;
    };

    ///
    /// Vulkan pipeline created from a signature
    ///
    class Pipeline {
    public:
        ///
        /// Create a new pipeline
        ///
        /// @param queue Compute queue
        /// @param queueFamilyIndex Compute queue family index
        /// @param extent Base extent
        /// @param flow Flow scale
        /// @param perf Performance mode
        /// @param hbd High bit-depth variant
        /// @throws std::runtime_error on failure
        ///
        explicit Pipeline(
            const vk::detail::DispatchLoaderDynamic& dld,
            const vk::Device& device,
            const vk::PhysicalDevice& physdev,
            const vk::Queue& queue,
            uint32_t queueFamilyIndex,
            const ShaderLibrary& library,
            const PipelineSignature& signature,
            vk::Extent2D extent,
            float flow,
            bool perf,
            uint8_t hbd,
            ColorSpace colorSpace,
            TransferFunction transferFunction,
            std::function<void(const std::string&)>& logCallback
        );

        /// Get all external input images
        [[nodiscard]] auto& getExternalInputs() const {
            return this->m_externalInputs;
        }

        /// Get all external output images
        [[nodiscard]] auto& getExternalOutputs() const {
            return this->m_externalOutputs;
        }

        /// Get the mapped uniform buffer
        [[nodiscard]] auto* getMappedBuffer() const {
            return *this->m_descriptorSet.mappedBuffer.get();
        }

        /// Get all command buffers
        [[nodiscard]] auto& getCmdbufs() const {
            return this->m_cmdbufs;
        }

        ///
        /// Build a transition command buffer
        ///
        /// @param iteration Current iteration
        /// @param index Index of the iteration
        /// @param total Total iterations
        ///
        vk::CommandBuffer buildTransCmdbuf(
            const vk::detail::DispatchLoaderDynamic& dld,
            const vk::Device& device,
            uint32_t iteration,
            uint32_t index,
            uint32_t total
        );

        /// Completed GPU timestamp spans in nanoseconds, or -1 when unavailable.
        std::array<double, 2> gpuDurations(
            const vk::detail::DispatchLoaderDynamic& dld, const vk::Device& device) const;

    private:
        /// Vulkan descriptor set & pipeline layout
        struct Layout {
            vk::UniqueDescriptorSetLayout layout;
            vk::UniquePipelineLayout pipelineLayout;
        };
        Layout m_layout;

        /// Sub-image of a Vulkan image
        struct SubImage {
            vk::UniqueImage image;
            vk::MemoryRequirements memory;
            vk::UniqueImageView view;
        };

        /// Vulkan image created from an ImageSignature
        struct Image {
            ImageSignature signature;
            std::vector<SubImage> subimages;
            vk::DeviceSize size{};
        };
        std::vector<Image> m_images;

        std::vector<ExternalImage> m_externalInputs;
        std::vector<ExternalImage> m_externalOutputs;

        /// Memory allocation sub-segment
        struct MemorySubSegment {
            vk::DeviceSize size{};
            vk::DeviceSize offset{}; // Offset in memory segment
        };

        /// Memory allocation segment
        struct MemorySegment {
            size_t imageIdx{};
            std::vector<MemorySubSegment> subsegments;
            vk::DeviceSize size{};
            vk::DeviceSize offset{}; // Offset in allocation
        };

        /// Memory allocation info
        struct AllocationInfo {
            vk::UniqueDeviceMemory memory;
            std::vector<MemorySegment> segments;
            vk::DeviceSize size{};
        };
        std::array<AllocationInfo, 2> m_allocations;
        std::unordered_map<size_t, vk::UniqueDeviceMemory> m_externalAllocations;

        /// Vulkan descriptor set
        struct DescriptorSet {
            vk::UniqueDescriptorPool pool;
            vk::DescriptorSet set; // Can not be freed
            std::pair<vk::UniqueBuffer, vk::UniqueDeviceMemory> buffer;
            std::shared_ptr<UniformBuffer*> mappedBuffer;
            std::array<vk::UniqueSampler, 3> samplers;
        };
        DescriptorSet m_descriptorSet;

        vk::UniquePipelineCache m_cache;
        std::unordered_map<std::string_view, vk::UniquePipeline> m_pipelines;

        /// Single iteration of a sub-stage
        struct SubIteration {
            uint32_t iterationIndex{};
            vk::Extent2D dispatch;
            bool isSpecial{};
        };

        /// Sub-stage of an execution stage
        struct SubStage {
            std::string_view pipeline;
            std::vector<SubIteration> subiterations;
        };

        /// Execution stage
        struct Stage {
            std::vector<SubStage> substages;
            std::vector<size_t> sampledImages;
            std::vector<size_t> storageImages;
        };
        std::vector<Stage> m_stages;

        vk::UniqueQueryPool m_timestamps;
        double m_timestampPeriod = 0;
        uint32_t m_timestampBits = 0;
        vk::UniqueCommandPool m_pool;
        std::vector<vk::UniqueCommandBuffer> m_cmdbufs;
        std::unordered_map<uint64_t, vk::UniqueCommandBuffer> m_transCmdbufs;
    };

}
