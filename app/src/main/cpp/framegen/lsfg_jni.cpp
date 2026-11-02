/* LSFG-Fork framegen JNI bridge */

#include "lsfgvk.hpp"
#include "lsfg_display.h"
#include "vkhelper.hpp"
#include "jnifile_log.h"
#include "perf_metrics.h"

#include <android/log.h>
#include <android/hardware_buffer.h>
#include <jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan.hpp>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vk>

// Pipeline telemetry (framegen side). These count actual GPU operations, not
// configured targets: srcWritten = source AHBs blitted into the pipeline input;
// dispatches = pipeline dispatches submitted; framesAcquired = generated frames
// blitted into destination AHBs. Logged on destroy and exposed to Kotlin via
// nativeGetStats so the per-second pipeline report shows real generated rates.
std::atomic<uint64_t> g_fgSrcWritten{0};
std::atomic<uint64_t> g_fgDispatches{0};
std::atomic<uint64_t> g_fgFramesAcquired{0};

#define LOG_TAG "lsfg-framegen"
#define FLOG(...) lsfgjlog::logprintf(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define FLOGE(...) lsfgjlog::logprintf(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

    struct JniState {
        std::unique_ptr<lsfgvk::Instance> instance;
        std::unique_ptr<lsfgvk::Context> context;

        vk::detail::DispatchLoaderDynamic* dld{nullptr};
        vk::Device device{};
        vk::Queue queue{};
        uint32_t qfi{0};

        // Keep the pool alive until its command buffers have been freed.
        vk::UniqueCommandPool blitPool;
        vk::UniqueFence blitFence;

        // Cached imports own both the image and its backing allocation.
        std::unordered_map<uintptr_t, vkhelper::AhbImage> dstImages;
        // Pre-recorded blits: dst AHB ptr -> command buffer that copies the pipeline
        // output image into that imported destination image.
        std::unordered_map<uintptr_t, vk::UniqueCommandBuffer> dstBlits;

        bool firstAcquire = true;
        std::mutex lock;
    };

    JniState* fromJni(jlong h) { return reinterpret_cast<JniState*>(h); }

    // Forward declarations for the AHB import / blit helpers (defined below).
    vk::Image ensureDstImage(JniState& st, const void* ahb);
    vk::CommandBuffer ensureDstBlit(JniState& st, const void* dstAhb);

    void submitAndWait(JniState& st, vk::CommandBuffer cmdbuf) {
        auto& dld{*st.dld};
        const auto submitStart = lsfgperf::nowNs();
        st.queue.submit({{
            .commandBufferCount = 1,
            .pCommandBuffers = &cmdbuf
        }}, *st.blitFence, dld);
        lsfgperf::metrics.record(lsfgperf::Submit, lsfgperf::nowNs() - submitStart);
        lsfgperf::Scope wait(lsfgperf::Wait);
        if (st.device.waitForFences(*st.blitFence, VK_TRUE, UINT64_MAX, dld) != vk::Result::eSuccess) {
            throw std::runtime_error("framegen blit wait failed");
        }
        st.device.resetFences(*st.blitFence, dld);
    }

    // Import a source AHB, blit it into the requested pipeline input layer, wait for
    // completion, then release the one-shot import. Source AHBs are one-shot (captured,
    // blitted, then closed by the producer), so no import cache is kept (avoids leaks).
    void writeSourceLayer(JniState& st, const void* srcAhb, uint32_t layer) {
        lsfgperf::Scope timing(lsfgperf::SourceUpload);
        auto& dld{*st.dld};

        auto srcImage = vkhelper::createImageFromAhb(dld, st.device, srcAhb);

        // Transition the imported source to a general layout (ready to blit from).
        {
            auto cmdbuf = vkhelper::createCommandBuffer(dld, st.device, *st.blitPool);
            auto fence = vkhelper::createFence(dld, st.device);
            vk::ImageMemoryBarrier ib{
                .sType = vk::StructureType::eImageMemoryBarrier,
                .pNext = nullptr,
                .srcAccessMask = vk::AccessFlags(),
                .dstAccessMask = vk::AccessFlagBits::eTransferRead,
                .oldLayout = vk::ImageLayout::eUndefined,
                .newLayout = vk::ImageLayout::eGeneral,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = *srcImage.image,
                .subresourceRange = vk::ImageSubresourceRange{ vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1 }
            };
            cmdbuf->begin({ .flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit }, dld);
            cmdbuf->pipelineBarrier(
                vk::PipelineStageFlagBits::eTopOfPipe,
                vk::PipelineStageFlagBits::eTransfer,
                vk::DependencyFlags(),
                0, nullptr,
                0, nullptr,
                1, &ib,
                dld
            );
            cmdbuf->end(dld);
            st.queue.submit({{
                .commandBufferCount = 1,
                .pCommandBuffers = &*cmdbuf
            }}, *fence, dld);
            if (st.device.waitForFences(*fence, VK_TRUE, UINT64_MAX, dld) != vk::Result::eSuccess)
                throw std::runtime_error("src AHB transition failed");
        }

        const auto inImage = st.context->pipeline().getExternalInputs().front().image;
        const auto inExtent = st.context->pipeline().getExternalInputs().front().extent;

        // Blit the imported source into the pipeline input layer, synchronously.
        {
            auto cmdbuf = vkhelper::createCommandBuffer(dld, st.device, *st.blitPool);
            cmdbuf->begin({ .flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit }, dld);

            vk::ImageMemoryBarrier inBarrier{
                .sType = vk::StructureType::eImageMemoryBarrier,
                .pNext = nullptr,
                .srcAccessMask = vk::AccessFlags(),
                .dstAccessMask = vk::AccessFlagBits::eTransferWrite,
                .oldLayout = vk::ImageLayout::eGeneral,
                .newLayout = vk::ImageLayout::eGeneral,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = inImage,
                .subresourceRange = vk::ImageSubresourceRange{ vk::ImageAspectFlagBits::eColor, 0, 1, 0, 2 }
            };
            cmdbuf->pipelineBarrier(
                vk::PipelineStageFlagBits::eTopOfPipe,
                vk::PipelineStageFlagBits::eTransfer,
                vk::DependencyFlags(),
                0, nullptr,
                0, nullptr,
                1, &inBarrier,
                dld
            );

            vkhelper::blitImage(
                dld, *cmdbuf,
                *srcImage.image, 0,
                inImage, layer,
                inExtent
            );
            cmdbuf->end(dld);

            st.queue.submit({{
                .commandBufferCount = 1,
                .pCommandBuffers = &*cmdbuf
            }}, *st.blitFence, dld);
            if (st.device.waitForFences(*st.blitFence, VK_TRUE, UINT64_MAX, dld) != vk::Result::eSuccess)
                throw std::runtime_error("src blit wait failed");
            st.device.resetFences(*st.blitFence, dld);
        }

        // The one-shot import and its memory are released together after completion.
    }

    // Ensure a destination AHardwareBuffer is imported (cached by pointer) and
    // transitioned to a general layout ready to be blitted into.
    vk::Image ensureDstImage(JniState& st, const void* ahb) {
        auto& dld{*st.dld};
        const uintptr_t key{reinterpret_cast<uintptr_t>(ahb)};
        const auto it{st.dstImages.find(key)};
        if (it != st.dstImages.end())
            return *it->second.image;

        auto image = vkhelper::createImageFromAhb(dld, st.device, ahb);

        // Transition to general (ready to be the blit destination).
        {
            auto cmdbuf = vkhelper::createCommandBuffer(dld, st.device, *st.blitPool);
            auto fence = vkhelper::createFence(dld, st.device);
            vk::ImageMemoryBarrier ib{
                .sType = vk::StructureType::eImageMemoryBarrier,
                .pNext = nullptr,
                .srcAccessMask = vk::AccessFlags(),
                .dstAccessMask = vk::AccessFlagBits::eTransferWrite,
                .oldLayout = vk::ImageLayout::eUndefined,
                .newLayout = vk::ImageLayout::eGeneral,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = *image.image,
                .subresourceRange = vk::ImageSubresourceRange{ vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1 }
            };
            cmdbuf->begin({ .flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit }, dld);
            cmdbuf->pipelineBarrier(
                vk::PipelineStageFlagBits::eTopOfPipe,
                vk::PipelineStageFlagBits::eTransfer,
                vk::DependencyFlags(),
                0, nullptr,
                0, nullptr,
                1, &ib,
                dld
            );
            cmdbuf->end(dld);
            st.queue.submit({{
                .commandBufferCount = 1,
                .pCommandBuffers = &*cmdbuf
            }}, *fence, dld);
            if (st.device.waitForFences(*fence, VK_TRUE, UINT64_MAX, dld) != vk::Result::eSuccess)
                throw std::runtime_error("dst AHB transition failed");
        }

        const auto handle = *image.image;
        st.dstImages.emplace(key, std::move(image));
        return handle;
    }

    // Ensure a pre-recorded command buffer exists that blits the pipeline's external
    // output image into the given imported destination AHB (cached by AHB pointer).
    vk::CommandBuffer ensureDstBlit(JniState& st, const void* dstAhb) {
        auto& dld{*st.dld};
        const uintptr_t key{reinterpret_cast<uintptr_t>(dstAhb)};
        const auto it{st.dstBlits.find(key)};
        if (it != st.dstBlits.end())
            return *it->second;

        auto dstImage = ensureDstImage(st, dstAhb);
        auto outImage = st.context->pipeline().getExternalOutputs().front().image;
        auto outExtent = st.context->pipeline().getExternalOutputs().front().extent;

        auto cmdbuf = vkhelper::createCommandBuffer(dld, st.device, *st.blitPool);
        // This cached command buffer is resubmitted after each completed fence wait.
        cmdbuf->begin(vk::CommandBufferBeginInfo{}, dld);

        // The pipeline's output image is written by compute; make it transfer-readable.
        vk::ImageMemoryBarrier outBarrier{
            .sType = vk::StructureType::eImageMemoryBarrier,
            .pNext = nullptr,
            .srcAccessMask = vk::AccessFlags(),
            .dstAccessMask = vk::AccessFlagBits::eTransferRead,
            .oldLayout = vk::ImageLayout::eGeneral,
            .newLayout = vk::ImageLayout::eGeneral,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = outImage,
            .subresourceRange = vk::ImageSubresourceRange{ vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1 }
        };
        cmdbuf->pipelineBarrier(
            vk::PipelineStageFlagBits::eTopOfPipe,
            vk::PipelineStageFlagBits::eTransfer,
            vk::DependencyFlags(),
            0, nullptr,
            0, nullptr,
            1, &outBarrier,
            dld
        );

        vkhelper::blitImage(
            dld, *cmdbuf,
            outImage, 0,
            dstImage, 0,
            outExtent
        );
        cmdbuf->end(dld);

        const auto handle = *cmdbuf;
        st.dstBlits.emplace(key, std::move(cmdbuf));
        return handle;
    }

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_lsfg_fork_framegen_FramegenBridge_nativeInit(JNIEnv* env, jclass, jstring dllPath, jboolean allowFP16) {
    try {
        if (!dllPath) return 0;
        const char* dll = env->GetStringUTFChars(dllPath, nullptr);
        std::string dllStr = dll;
        env->ReleaseStringUTFChars(dllPath, dll);

        auto st = std::make_unique<JniState>();
        g_fgSrcWritten = 0; g_fgDispatches = 0; g_fgFramesAcquired = 0;
        lsfgperf::metrics.reset();
        st->instance = std::make_unique<lsfgvk::Instance>(
            "", std::filesystem::path(dllStr), allowFP16 == JNI_TRUE, false,
            [](const std::string& msg) { FLOG("%s", msg.c_str()); }
        );

        auto& vk{st->instance->_impl().vk};
        st->dld = vk.dld.get();
        st->device = *vk.device;
        st->queue = vk.queue;
        st->qfi = vk.qfi;

        FLOG("framegen instance created, dll=%s", dllStr.c_str());
        return reinterpret_cast<jlong>(st.release());
    } catch (const std::exception& e) {
        FLOGE("nativeInit failed: %s", e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_lsfg_fork_framegen_FramegenBridge_nativeSetCacheDir(JNIEnv* env, jclass, jstring dir) {
    if (!dir) return;
    const char* d = env->GetStringUTFChars(dir, nullptr);
    try { vkhelper::setPipelineCacheDir(d); }
    catch (const std::exception& e) { FLOGE("setCacheDir: %s", e.what()); }
    env->ReleaseStringUTFChars(dir, d);
}

JNIEXPORT jboolean JNICALL
Java_com_lsfg_fork_framegen_FramegenBridge_nativeCreateSession(
    JNIEnv* env, jclass, jlong handle,
    jint width, jint height, jfloat flowScale, jboolean performance) {
    auto* st = fromJni(handle);
    if (!st || !st->instance) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(st->lock);
    try {
        st->context = std::make_unique<lsfgvk::Context>(
            *st->instance, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
            static_cast<float>(flowScale), performance == JNI_TRUE,
            lsfgvk::BitDepth::R8G8B8A8, lsfgvk::ColorSpace::SRGB,
            lsfgvk::TransferFunction::SRGB, nullptr, nullptr
        );

        auto& dld{*st->dld};
        st->blitPool = vkhelper::createCommandPool(dld, st->device, st->qfi);
        st->blitFence = vkhelper::createFence(dld, st->device);
        // Source/destination AHBs (and their blit command buffers) are imported
        // lazily on the first writeSource/acquireToDst for each buffer.

        FLOG("session created %dx%d flow=%.2f perf=%d",
            width, height, static_cast<double>(flowScale), performance ? 1 : 0);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        FLOGE("nativeCreateSession failed: %s", e.what());
        st->context = nullptr;
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_lsfg_fork_framegen_FramegenBridge_nativeWriteSource(
    JNIEnv* env, jclass, jlong handle, jlong srcAhbPtr, jint slot) {
    auto* st = fromJni(handle);
    if (!st || !st->context) return;
    std::lock_guard<std::mutex> lock(st->lock);
    try {
        const void* src = reinterpret_cast<const void*>(srcAhbPtr);
        const uint32_t layer = static_cast<uint32_t>(slot) % 2;
        writeSourceLayer(*st, src, layer);
        g_fgSrcWritten++;
    } catch (const std::exception& e) {
        FLOGE("nativeWriteSource failed: %s", e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_lsfg_fork_framegen_FramegenBridge_nativeDispatch(
    JNIEnv* env, jclass, jlong handle, jint total) {
    auto* st = fromJni(handle);
    if (!st || !st->context) return;
    try {
        // Source uploads complete on this queue before dispatch; there is no
        // external fd-based producer to signal the context's sync semaphore.
        lsfgperf::Scope timing(lsfgperf::Submit);
        st->context->dispatch(static_cast<uint32_t>(total), true);
        st->firstAcquire = true;
        g_fgDispatches++;
    } catch (const std::exception& e) {
        FLOGE("nativeDispatch failed: %s", e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_lsfg_fork_framegen_FramegenBridge_nativeAcquire(
    JNIEnv* env, jclass, jlong handle) {
    auto* st = fromJni(handle);
    if (!st || !st->context) return;
    try {
        st->context->acquire(true);
    } catch (const std::exception& e) {
        FLOGE("nativeAcquire failed: %s", e.what());
    }
}

/// Acquire the next generated frame and blit it into the shared destination
/// AHardwareBuffer that the WSI presenter reads from.
JNIEXPORT void JNICALL
Java_com_lsfg_fork_framegen_FramegenBridge_nativeAcquireToDst(
    JNIEnv* env, jclass, jlong handle, jlong dstAhbPtr) {
    auto* st = fromJni(handle);
    if (!st || !st->context) return;
    std::lock_guard<std::mutex> lock(st->lock);
    try {
        lsfgperf::Scope outputTiming(lsfgperf::Output);
        const void* dst = reinterpret_cast<const void*>(dstAhbPtr);

        // Generate the next frame (into the pipeline's external output image).
        // The destination blit and its fence wait run on the same queue, so no
        // external consumer semaphore is needed between generated frames.
        st->context->acquire(true);

        // Blit the generated frame into the presenter's destination AHB (importing /
        // recording the blit on first use for this buffer), synchronously.
        auto blit = ensureDstBlit(*st, dst);
        submitAndWait(*st, blit);
        const auto timings = st->context->pipeline().gpuDurations(*st->dld, st->device);
        if (st->firstAcquire && timings[0] >= 0)
            lsfgperf::metrics.record(lsfgperf::PrepassGpu, static_cast<int64_t>(timings[0]));
        if (timings[1] >= 0)
            lsfgperf::metrics.record(lsfgperf::MainGpu, static_cast<int64_t>(timings[1]));
        st->firstAcquire = false;
        g_fgFramesAcquired++;
    } catch (const std::exception& e) {
        FLOGE("nativeAcquireToDst failed: %s", e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_lsfg_fork_framegen_FramegenBridge_nativeIdle(
    JNIEnv* env, jclass, jlong handle) {
    auto* st = fromJni(handle);
    if (!st || !st->context) return;
    try {
        st->context->idle();
    } catch (const std::exception& e) {
        FLOGE("nativeIdle failed: %s", e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_lsfg_fork_framegen_FramegenBridge_nativeDestroy(
    JNIEnv* env, jclass, jlong handle) {
    auto* st = fromJni(handle);
    if (!st) return;
    auto& dld{*st->dld};
    try { st->device.waitIdle(dld); }
    catch (const std::exception& e) { FLOGE("framegen shutdown wait failed: %s", e.what()); }
    FLOG("framegen session stats: srcWritten=%llu dispatches=%llu framesAcquired=%llu",
        (unsigned long long)g_fgSrcWritten.load(),
        (unsigned long long)g_fgDispatches.load(),
        (unsigned long long)g_fgFramesAcquired.load());
    // RAII members release command buffers, imports, fence and pool before the
    // context and instance. Each Vulkan handle is destroyed exactly once.
    delete st;
}

JNIEXPORT jstring JNICALL
Java_com_lsfg_fork_framegen_FramegenBridge_nativePerformanceStats(JNIEnv* env, jclass) {
    return env->NewStringUTF(lsfgperf::metrics.summary().c_str());
}

/// Pipeline telemetry snapshot for the per-second Kotlin stats report.
/// Returns "srcWritten,dispatches,framesAcquired" so generated-fps can be
/// computed from real GPU operation counts, not assumed multipliers.
JNIEXPORT jstring JNICALL
Java_com_lsfg_fork_framegen_FramegenBridge_nativeGetStats(JNIEnv* env, jclass) {
    char buf[96];
    snprintf(buf, sizeof(buf), "%llu,%llu,%llu",
        (unsigned long long)g_fgSrcWritten.load(),
        (unsigned long long)g_fgDispatches.load(),
        (unsigned long long)g_fgFramesAcquired.load());
    return env->NewStringUTF(buf);
}

} // extern "C"

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK)
        lsfgjlog::init(env, vm);
    return JNI_VERSION_1_6;
}
