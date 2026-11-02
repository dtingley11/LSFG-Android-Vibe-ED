/* LSFG-Fork WSI presenter */

#define VK_NO_PROTOTYPES
#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <vulkan/vulkan.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include "volk.h"
#include "lsfg_display.h"
#include "jnifile_log.h"
#include "perf_metrics.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "lsfg-wsi"
#define WLOG(...) lsfgjlog::logprintf(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define WLOGE(...) lsfgjlog::logprintf(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// --- Native crash diagnostics -------------------------------------------------
// When the Vulkan/driver path segfaults (which is what kills the app ~1.3s after
// 'beginSession'), the Android tombstone usually only says "Process died" and
// the real call stack is lost.  Install a SIGSEGV/SIGBUS/SIGABRT handler that
// logs a one-line marker to logcat (tag "lsfg-wsi") so the user's next crash
// dump tells us EXACTLY which native step died and the fault address.  The
// handler is minimal (async-signal-safe: only __android_log_print, no malloc /
// std::string / printf) and always re-raises the signal so the normal Android
// crash dump is still produced.
#include <csignal>
#include <string.h>
#include <cstddef>
#include <dlfcn.h>
// Save the pre-existing handler per signal so we can CHAIN to it (the linker
// crash handler) instead of re-raising with SIG_DFL.  Re-raising with SIG_DFL
// replaces the linker's handler with the kernel default, which DROPS the full
// Android backtrace / tombstone.  Chaining preserves it.
static struct sigaction s_prev_action[NSIG];
static void nativeCrashHandler(int sig, siginfo_t* info, void* ctx) {
    // Async-signal-safe: __android_log_print only.  No file I/O (the NDK 'open'
    // and 'write' collide with other headers and fail to compile in this
    // environment).  logcat is the canonical crash sink and is already captured.
    //
    // Format the signal number + fault address as a fixed-width string without
    // malloc / snprintf / std::string (not async-signal-safe).
    char line[96];
    int n = 0;
    const char* pre = "=== LSFG NATIVE CRASH === sig=";
    for (const char* p = pre; *p && n < (int)sizeof(line) - 2; ++p) line[n++] = *p;
    // signal number (decimal)
    int v = sig;
    char num[16]; int m = 0;
    do { num[m++] = (char)('0' + (v % 10)); v /= 10; } while (v && m < 15);
    for (int i = m - 1; i >= 0; --i) line[n++] = num[i];
    const char* mid = " fault_addr=0x";
    for (const char* p = mid; *p && n < (int)sizeof(line) - 2; ++p) line[n++] = *p;
    // fault address as 16 hex digits
    uintptr_t a = (uintptr_t)(info ? info->si_addr : nullptr);
    for (int i = 15; i >= 0 && n < (int)sizeof(line) - 2; --i) {
        int d = (int)((a >> (i * 4)) & 0xF);
        line[n++] = (char)(d < 10 ? ('0' + d) : ('a' + d - 10));
    }
    line[n] = '\0';
    __android_log_print(ANDROID_LOG_ERROR, "lsfg-wsi", "%s", line);
    // Runtime backtrace via dlsym (the linked libc variant does not export
    // backtrace as a strong symbol, so resolve it at runtime). Best-effort,
    // not strictly async-signal-safe, but reliable in practice on bionic.
    do {
        using bt_fn = int (*)(void**, int);
        using btf_fn = void (*)(void* const*, int, int);
        bt_fn bt = (bt_fn)dlsym(nullptr, "backtrace");
        btf_fn btf = (btf_fn)dlsym(nullptr, "backtrace_symbols_fd");
        if (bt && btf) {
            void* frames[48];
            int cnt = bt(frames, 48);
            if (cnt > 0)
                btf(frames, cnt, 2); // stderr -> logcat crash buffer
            char btl[64];
            int bl = 0;
            const char* bpre = "backtrace frames=";
            for (const char* p = bpre; *p && bl < 50; ++p) btl[bl++] = *p;
            int vv = cnt; char nb[16]; int mm = 0;
            do { nb[mm++] = (char)('0' + (vv % 10)); vv /= 10; } while (vv && mm < 15);
            for (int i = mm - 1; i >= 0 && bl < 60; --i) btl[bl++] = nb[i];
            btl[bl] = 0;
            __android_log_print(ANDROID_LOG_ERROR, "lsfg-wsi", "%s", btl);
        }
    } while (0);
    // Chain to the previous handler (the Android/linker crash handler) so the
    // full backtrace + tombstone is still produced.
    if (sig < NSIG && s_prev_action[sig].sa_handler != SIG_DFL) {
        if (s_prev_action[sig].sa_flags & SA_SIGINFO)
            s_prev_action[sig].sa_sigaction(sig, info, ctx);
        else
            s_prev_action[sig].sa_handler(sig);
        return;
    }
    // No previous handler: fall back to re-raising with the default.
    signal(sig, SIG_DFL);
    raise(sig);
}
static void installCrashHandler() {
    struct sigaction sa{};
    sa.sa_sigaction = nativeCrashHandler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    // Save the previous handler for each signal so we can chain to it.
    sigaction(SIGSEGV, &sa, &s_prev_action[SIGSEGV]);
    sigaction(SIGBUS,  &sa, &s_prev_action[SIGBUS]);
    sigaction(SIGABRT, &sa, &s_prev_action[SIGABRT]);
}
// --- end native crash diagnostics -------------------------------------------

#define VK_CHECK(expr) do { \
        VkResult r_ = (expr); \
        if (r_ != VK_SUCCESS) { \
            WLOGE("Vulkan error %d at %s:%s (line %d)", (int)r_, #expr, __FILE__, __LINE__); \
            throw std::runtime_error(std::string("vk error: ") + #expr); \
        } \
    } while (0)

namespace {

    struct Presenter {
        VkInstance instance{};
        VkPhysicalDevice physdev{};
        VkDevice device{};
        VkQueue queue{};
        uint32_t qfi{0};
        uint32_t swapchainCount{0};
        VkFormat swapchainFormat{};
        VkSwapchainKHR swapchain{};
        VkSurfaceKHR surface{};
        VkImage swapchainImages[8]{};
        VkImageView swapchainViews[8]{};
        VkSemaphore imageAcquired{};
        VkSemaphore frameReady{};
        VkFence presentFence{};
        VkCommandPool cmdPool{};

        // Imported AHB frames. Each record owns the full import: the image
        // view, the image, and the device memory bound to it. All three must
        // be released together (view -> image -> memory) on eviction and at
        // shutdown; destroying the image alone leaks the VkDeviceMemory.
        struct AhbImport {
            VkImageView view{};
            VkImage image{};
            VkDeviceMemory memory{};
        };
        std::map<uintptr_t, AhbImport> ahbImports;

        ANativeWindow* window{nullptr};
        uint32_t width{0};
        uint32_t height{0};
        bool surfaceValid{false};

        std::thread thread;
        // running: request to present frames / thread-liveness signal. It is
        // NOT a cleanup state: stopPresenter() must run even after the present
        // thread has cleared it itself (terminal GPU failure).
        std::atomic<bool> running{false};
        std::atomic<bool> paused{false};

        // Set when a submitted command buffer's completion could not be
        // confirmed (fence timeout / device loss). The command buffer is kept
        // in pendingCmdbuf and must not be freed while the GPU may still be
        // executing it; stopPresenter() applies the terminal-failure teardown.
        bool gpuFailure{false};
        VkCommandBuffer pendingCmdbuf{};

        std::atomic<float> targetFps{60.0f};
        std::atomic<uint64_t> framesPresented{0};
        bool constructed{false};
    };

    Presenter g_presenter;
    std::mutex g_initLock;

    // Per-AHardwareBuffer mutexes that serialize the producer's CPU fill
    // (nativeFillAhb -> AHardwareBuffer_lock) against the presenter's GPU
    // import/copy (importAhbLocked / transitionToGeneral / vkCmdCopyImage) of
    // the SAME buffer.  Without this, the producer's AHardwareBuffer_lock races
    // the presenter's Vulkan read of the same gralloc buffer; on the emulator's
    // gralloc that double-closes an fd and fdsan aborts the present thread
    // ("fdsan: attempted to close file descriptor 0", backtrace: nativeFillAhb
    // -> AHardwareBuffer_lock -> Gralloc3Mapper::lock).  The presenter holds a
    // buffer's lock until its present fence signals (GPU done reading it), so
    // the producer cannot CPU-write a buffer the GPU is still reading.
    std::map<uintptr_t, std::shared_ptr<std::mutex>> g_ahb_locks;
    std::mutex g_ahb_locks_guard;
    std::shared_ptr<std::mutex> getAhbLock(uintptr_t ahbKey) {
        std::lock_guard<std::mutex> g(g_ahb_locks_guard);
        auto it = g_ahb_locks.find(ahbKey);
        if (it != g_ahb_locks.end()) return it->second;
        auto m = std::make_shared<std::mutex>();
        g_ahb_locks[ahbKey] = m;
        return m;
    }

    // Tear down one imported AHB in the order Vulkan requires: image view
    // first, then the image, then the device memory bound to it. Every
    // successful import is released exactly once, through this function.
    void destroyAhbImport(Presenter::AhbImport& imp) {
        if (imp.view) {
            vkDestroyImageView(g_presenter.device, imp.view, nullptr);
            imp.view = VK_NULL_HANDLE;
        }
        if (imp.image) {
            vkDestroyImage(g_presenter.device, imp.image, nullptr);
            imp.image = VK_NULL_HANDLE;
        }
        if (imp.memory) {
            vkFreeMemory(g_presenter.device, imp.memory, nullptr);
            imp.memory = VK_NULL_HANDLE;
        }
    }

    // Tear down whatever Vulkan resources exist, in dependency order, and clear
    // the trackers so a second call is a cheap no-op. Handles partially
    // initialized state from a failed startup as well as a full session.
    //
    // gpuFailure means a submitted command buffer's completion was never
    // confirmed (fence timeout / device loss) or the device could not be
    // brought to idle. In that state destroying ANY part of the Vulkan
    // ownership hierarchy is invalid: the pending command buffer may still be
    // executing, so its command pool, fence, swapchain, imported image/memory
    // and the device/instance itself must not be destroyed (and destroying the
    // device would dangle every retained child handle). The entire hierarchy is
    // therefore retained and leaked until process exit, where the OS reclaims
    // it. This is a bounded, documented terminal-failure leak, preferred over
    // use-after-free or undefined behavior. Normal shutdown (gpuFailure false)
    // destroys everything.
    static void teardownVulkanResources() {
        if (g_presenter.gpuFailure) {
            WLOGE("GPU failure: retaining Vulkan device/instance, pool, fence, swapchain, "
                   "%zu import(s) and pending command buffer until process exit",
                   g_presenter.ahbImports.size());
            g_presenter.ahbImports.clear();
            g_presenter.pendingCmdbuf = VK_NULL_HANDLE;
            g_presenter.swapchainCount = 0;
            g_presenter.swapchain = VK_NULL_HANDLE;
            g_presenter.cmdPool = VK_NULL_HANDLE;
            g_presenter.presentFence = VK_NULL_HANDLE;
            g_presenter.imageAcquired = VK_NULL_HANDLE;
            g_presenter.frameReady = VK_NULL_HANDLE;
            g_presenter.device = VK_NULL_HANDLE;
            g_presenter.surface = VK_NULL_HANDLE;
            g_presenter.instance = VK_NULL_HANDLE;
            g_presenter.physdev = VK_NULL_HANDLE;
            g_presenter.queue = VK_NULL_HANDLE;
            return;
        }
        // Normal shutdown: the device is idle, so no GPU operation is still
        // using the cached imports or any command buffer.
        for (auto& [key, imp] : g_presenter.ahbImports)
            destroyAhbImport(imp);
        g_presenter.ahbImports.clear();
        if (g_presenter.pendingCmdbuf) {
            vkFreeCommandBuffers(g_presenter.device, g_presenter.cmdPool, 1, &g_presenter.pendingCmdbuf);
            g_presenter.pendingCmdbuf = VK_NULL_HANDLE;
        }
        for (uint32_t i = 0; i < g_presenter.swapchainCount; i++) {
            if (g_presenter.swapchainViews[i])
                vkDestroyImageView(g_presenter.device, g_presenter.swapchainViews[i], nullptr);
            g_presenter.swapchainViews[i] = VK_NULL_HANDLE;
        }
        if (g_presenter.swapchain)
            vkDestroySwapchainKHR(g_presenter.device, g_presenter.swapchain, nullptr);
        g_presenter.swapchain = VK_NULL_HANDLE;
        g_presenter.swapchainCount = 0;
        if (g_presenter.cmdPool)
            vkDestroyCommandPool(g_presenter.device, g_presenter.cmdPool, nullptr);
        g_presenter.cmdPool = VK_NULL_HANDLE;
        if (g_presenter.presentFence)
            vkDestroyFence(g_presenter.device, g_presenter.presentFence, nullptr);
        g_presenter.presentFence = VK_NULL_HANDLE;
        if (g_presenter.imageAcquired)
            vkDestroySemaphore(g_presenter.device, g_presenter.imageAcquired, nullptr);
        g_presenter.imageAcquired = VK_NULL_HANDLE;
        if (g_presenter.frameReady)
            vkDestroySemaphore(g_presenter.device, g_presenter.frameReady, nullptr);
        g_presenter.frameReady = VK_NULL_HANDLE;
        if (g_presenter.device)
            vkDestroyDevice(g_presenter.device, nullptr);
        g_presenter.device = VK_NULL_HANDLE;
        if (g_presenter.surface)
            vkDestroySurfaceKHR(g_presenter.instance, g_presenter.surface, nullptr);
        g_presenter.surface = VK_NULL_HANDLE;
        if (g_presenter.instance)
            vkDestroyInstance(g_presenter.instance, nullptr);
        g_presenter.instance = VK_NULL_HANDLE;
        g_presenter.physdev = VK_NULL_HANDLE;
        g_presenter.queue = VK_NULL_HANDLE;
    }

    void stopPresenter() {
        // g_initLock serializes start/stop; the present thread never calls
        // stopPresenter, so there is no self-join and no double cleanup.
        std::lock_guard<std::mutex> lock(g_initLock);
        // Request stop. 'running' may already be false (the present thread
        // clears it on a terminal GPU failure) - cleanup must still run.
        g_presenter.running = false;
        if (g_presenter.thread.joinable())
            g_presenter.thread.join();
        g_presenter.paused = false;
        lsfgdisplay::clear();
        if (g_presenter.instance || g_presenter.device) {
            if (!g_presenter.gpuFailure) {
                // vkDeviceWaitIdle establishes that no GPU work is outstanding,
                // which is what makes the destruction below legal. A failure
                // here means completion could not be confirmed, so switch to
                // the terminal-failure retention path instead of destroying
                // resources the GPU may still be using.
                if (vkDeviceWaitIdle(g_presenter.device) != VK_SUCCESS) {
                    WLOGE("vkDeviceWaitIdle failed; retaining Vulkan resources (terminal failure)");
                    g_presenter.gpuFailure = true;
                }
            }
            teardownVulkanResources();
        }
        g_presenter.framesPresented = 0;
        g_presenter.surfaceValid = false;
        g_presenter.window = nullptr;
        g_presenter.gpuFailure = false;
        g_presenter.pendingCmdbuf = VK_NULL_HANDLE;
        WLOG("presenter stopped");
    }

    void createSwapchain(uint32_t width, uint32_t height) {
        // Validate surface capabilities BEFORE destroying the old swapchain,
        // so a failed recreation cannot leave the presenter without a
        // swapchain.
        VkSurfaceFormatKHR formats[16];
        uint32_t formatCount = 16;
        VK_CHECK(vkGetPhysicalDeviceSurfaceFormatsKHR(g_presenter.physdev, g_presenter.surface, &formatCount, formats));
        VkSurfaceCapabilitiesKHR caps;
        VK_CHECK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(g_presenter.physdev, g_presenter.surface, &caps));

        // The present path writes into swapchain images with vkCmdCopyImage,
        // so they must be usable as transfer destinations in addition to
        // color attachments. Not every implementation supports that, so check
        // the surface capabilities and fail gracefully instead of requesting
        // unsupported usage.
        const VkImageUsageFlags desiredUsage =
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if ((caps.supportedUsageFlags & desiredUsage) != desiredUsage) {
            WLOGE("surface does not support transfer-destination swapchain images "
                   "(supportedUsageFlags=0x%X)", (unsigned)caps.supportedUsageFlags);
            throw std::runtime_error("swapchain transfer-destination usage unsupported");
        }

        if (g_presenter.swapchain)
            vkDestroySwapchainKHR(g_presenter.device, g_presenter.swapchain, nullptr);

        VkSurfaceFormatKHR chosen = formats[0];
        if (formatCount > 1) {
            for (uint32_t i = 0; i < formatCount; i++) {
                if (formats[i].format == VK_FORMAT_R8G8B8A8_UNORM) {
                    chosen = formats[i];
                    break;
                }
            }
        }
        g_presenter.swapchainFormat = chosen.format;

        uint32_t count = caps.minImageCount + 1;
        if (caps.maxImageCount > 0 && count > caps.maxImageCount)
            count = caps.maxImageCount;
        count = std::min(count, 8u);

        VkSwapchainCreateInfoKHR sci{};
        sci.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        sci.surface = g_presenter.surface;
        sci.minImageCount = count;
        sci.imageFormat = chosen.format;
        sci.imageColorSpace = chosen.colorSpace;
        sci.imageExtent = { width, height };
        sci.imageArrayLayers = 1;
        sci.imageUsage = desiredUsage;
        sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        sci.preTransform = caps.currentTransform;
        sci.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        sci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        sci.clipped = VK_TRUE;

        VK_CHECK(vkCreateSwapchainKHR(g_presenter.device, &sci, nullptr, &g_presenter.swapchain));

        uint32_t imageCount = 0;
        VK_CHECK(vkGetSwapchainImagesKHR(g_presenter.device, g_presenter.swapchain, &imageCount, nullptr));
        std::vector<VkImage> images(imageCount);
        VK_CHECK(vkGetSwapchainImagesKHR(g_presenter.device, g_presenter.swapchain, &imageCount, images.data()));
        g_presenter.swapchainCount = imageCount;
        for (uint32_t i = 0; i < imageCount; i++) {
            g_presenter.swapchainImages[i] = images[i];
            VkImageViewCreateInfo vci{};
            vci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            vci.image = images[i];
            vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
            vci.format = chosen.format;
            vci.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
            VK_CHECK(vkCreateImageView(g_presenter.device, &vci, nullptr, &g_presenter.swapchainViews[i]));
        }

        g_presenter.width = width;
        g_presenter.height = height;
        g_presenter.surfaceValid = true;
        WLOG("swapchain created: %ux%u, %u images, format=%d", width, height, imageCount, (int)chosen.format);
    }

    // One-time command buffer that transitions an imported source image from
    // UNDEFINED -> GENERAL so it can be used as a vkCmdCopyImage SOURCE.  The
    // imported AHB image is created with initialLayout UNDEFINED; reading it
    // in UNDEFINED layout is undefined behavior and crashes some drivers.  We
    // run this barrier once per image (right after import) and wait for it so
    // presentLoop can safely read the image in GENERAL layout every frame.
    // Throws on any failure. On a fence timeout / device loss the command
    // buffer is NOT freed here (the GPU may still be executing it); it is
    // recorded in pendingCmdbuf and handed to the terminal-failure teardown.
    void transitionToGeneral(VkImage image) {
        VkCommandBufferAllocateInfo cabi{};
        cabi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cabi.commandPool = g_presenter.cmdPool;
        cabi.commandBufferCount = 1;
        VkCommandBuffer cmdbuf = VK_NULL_HANDLE;
        if (vkAllocateCommandBuffers(g_presenter.device, &cabi, &cmdbuf) != VK_SUCCESS)
            throw std::runtime_error("transitionToGeneral: vkAllocateCommandBuffers failed");
        VkCommandBufferBeginInfo bci{};
        bci.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        bci.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(cmdbuf, &bci) != VK_SUCCESS) {
            vkFreeCommandBuffers(g_presenter.device, g_presenter.cmdPool, 1, &cmdbuf);
            throw std::runtime_error("transitionToGeneral: vkBeginCommandBuffer failed");
        }
        VkImageMemoryBarrier ib{};
        ib.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        ib.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        ib.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        ib.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        ib.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        ib.srcAccessMask = 0;
        ib.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        ib.image = image;
        ib.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
        vkCmdPipelineBarrier(cmdbuf, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &ib);
        vkEndCommandBuffer(cmdbuf);
        VkSubmitInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmdbuf;
        // A failed submit means nothing was queued: the command buffer was
        // never submitted, so it is safe to free and we can rethrow.
        if (vkQueueSubmit(g_presenter.queue, 1, &si, g_presenter.presentFence) != VK_SUCCESS) {
            vkFreeCommandBuffers(g_presenter.device, g_presenter.cmdPool, 1, &cmdbuf);
            throw std::runtime_error("transitionToGeneral: vkQueueSubmit failed");
        }
        // The barrier must actually complete before presentLoop reads the
        // image in GENERAL layout; a timed-out wait means it may not have.
        VkResult twr = vkWaitForFences(g_presenter.device, 1, &g_presenter.presentFence, VK_TRUE, 5000000000ull);
        if (twr != VK_SUCCESS) {
            // Outstanding submission: keep the command buffer (GPU may still
            // be using it) and mark the presenter for terminal-failure teardown.
            WLOGE("transitionToGeneral fence wait=%d; command buffer kept pending", (int)twr);
            g_presenter.gpuFailure = true;
            g_presenter.pendingCmdbuf = cmdbuf;
            g_presenter.running = false;
            throw std::runtime_error("transitionToGeneral fence wait failed");
        }
        vkResetFences(g_presenter.device, 1, &g_presenter.presentFence);
        vkFreeCommandBuffers(g_presenter.device, g_presenter.cmdPool, 1, &cmdbuf);
    }

    // *outFresh (when non-null) reports whether this call created a new import
    // (false = cache hit on a previously imported buffer).
    VkImage importAhbLocked(const void* ahb, bool* outFresh = nullptr) {
        uintptr_t key = reinterpret_cast<uintptr_t>(ahb);
        auto it = g_presenter.ahbImports.find(key);
        if (it != g_presenter.ahbImports.end()) {
            if (outFresh) *outFresh = false;
            return it->second.image;
        }
        if (outFresh) *outFresh = true;

        AHardwareBuffer* buffer = const_cast<AHardwareBuffer*>(reinterpret_cast<const AHardwareBuffer*>(ahb));
        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(buffer, &desc);

        VkFormat fmt = VK_FORMAT_R8G8B8A8_UNORM;
        switch (desc.format) {
            case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:          fmt = VK_FORMAT_R8G8B8A8_UNORM; break;
            case AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM:      fmt = VK_FORMAT_A2B10G10R10_UNORM_PACK32; break;
            case AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT:     fmt = VK_FORMAT_R16G16B16A16_SFLOAT; break;
            case AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM:           fmt = VK_FORMAT_R8G8B8_UNORM; break;
            default: break;
        }

        // Resources created below are owned by this function until the import
        // is recorded in ahbImports. If any step fails BEFORE that, everything
        // created so far is destroyed here (no GPU work references it yet).
        // Once recorded, ownership moves to ahbImports and no cleanup runs.
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory mem = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        bool recorded = false;
        struct Cleanup {
            VkImage image; VkDeviceMemory mem; VkImageView view; bool recorded;
            ~Cleanup() {
                if (recorded) return;
                if (view) vkDestroyImageView(g_presenter.device, view, nullptr);
                if (image) vkDestroyImage(g_presenter.device, image, nullptr);
                if (mem) vkFreeMemory(g_presenter.device, mem, nullptr);
            }
        } guard{image, mem, view, recorded};

        VkImageCreateInfo ici{};
        ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        ici.imageType = VK_IMAGE_TYPE_2D;
        ici.format = fmt;
        ici.extent = { static_cast<uint32_t>(desc.width), static_cast<uint32_t>(desc.height), 1 };
        ici.mipLevels = 1;
        ici.arrayLayers = 1;
        ici.samples = VK_SAMPLE_COUNT_1_BIT;
        ici.tiling = VK_IMAGE_TILING_OPTIMAL;
        ici.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

        VK_CHECK(vkCreateImage(g_presenter.device, &ici, nullptr, &image));
        guard.image = image;

        VkAndroidHardwareBufferPropertiesANDROID props{};
        props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        vkGetAndroidHardwareBufferPropertiesANDROID(g_presenter.device, buffer, &props);

        VkMemoryRequirements mreq;
        vkGetImageMemoryRequirements(g_presenter.device, image, &mreq);

        uint32_t usable = mreq.memoryTypeBits & props.memoryTypeBits;
        if (!usable) usable = mreq.memoryTypeBits;
        uint32_t memTypeIndex = 0;
        for (uint32_t i = 0; i < 64; ++i) {
            if (usable & (1u << i)) { memTypeIndex = i; break; }
        }

        VkImportAndroidHardwareBufferInfoANDROID importInfo{};
        importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
        importInfo.buffer = buffer;

        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.pNext = &importInfo;
        mai.allocationSize = mreq.size;
        mai.memoryTypeIndex = memTypeIndex;
        VK_CHECK(vkAllocateMemory(g_presenter.device, &mai, nullptr, &mem));
        guard.mem = mem;
        VK_CHECK(vkBindImageMemory(g_presenter.device, image, mem, 0));

        VkImageViewCreateInfo vci{};
        vci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vci.image = image;
        vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vci.format = fmt;
        vci.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
        VK_CHECK(vkCreateImageView(g_presenter.device, &vci, nullptr, &view));
        guard.view = view;

        Presenter::AhbImport imp;
        imp.image = image;
        imp.view = view;
        imp.memory = mem;
        g_presenter.ahbImports[key] = imp;
        guard.recorded = true;  // ownership transferred to ahbImports
        WLOG("imported AHB %p (%dx%d)", ahb, desc.width, desc.height);
        // CRITICAL FIX: transition the imported image to GENERAL now (once),
        // so presentLoop can read it as a copy source.  Reading an image in
        // UNDEFINED layout (the old behavior) is undefined behavior and was
        // crashing the process ~1.2-2.8s after beginSession.
        //
        // Failure semantics:
        // - submit failed / pre-GPU failure: nothing was queued, the import is
        //   safe to destroy; the exception propagates and presentLoop's
        //   releasePoppedFrame destroys it (gpuFailure is not set).
        // - fence timeout / device loss: transitionToGeneral records the
        //   command buffer as pending and sets gpuFailure; the import stays in
        //   ahbImports and is retained by the terminal-failure teardown (the
        //   GPU may still be transitioning it), so it must NOT be destroyed
        //   here or by releasePoppedFrame.
        transitionToGeneral(image);
        return image;
    }

    void presentLoop() {
        int64_t lastPresented = 0;
        using namespace std::chrono_literals;
        while (g_presenter.running) {
            // Pop a source frame (non-blocking-ish, with a short timeout so the
            // loop can also service 'paused' and shutdown promptly).
            int waitMs = std::max(1, static_cast<int>(1000.0 / g_presenter.targetFps.load()));
            uintptr_t frame = lsfgdisplay::pop(waitMs);
            if (!frame) {
                if (g_presenter.paused) std::this_thread::sleep_for(5ms);
                continue;
            }
            // pop() transferred the queue's AHardwareBuffer reference to us.
            // Release it exactly once on every path below, and only after the
            // GPU is done with the buffer.  For a freshly imported buffer
            // (cache miss) the import must be destroyed FIRST, otherwise the
            // import cache keeps a VkImage bound to freed memory (and a later
            // allocator pointer reuse would hit a stale cache entry).
            bool freshImport = false;
            auto releasePoppedFrame = [&]() {
                // On a terminal GPU failure the import and AHB ref are abandoned
                // with the device (the pending command buffer may still be using
                // them); stopPresenter()'s teardown handles that path.
                if (g_presenter.gpuFailure)
                    return;
                if (freshImport) {
                    auto iit = g_presenter.ahbImports.find(frame);
                    if (iit != g_presenter.ahbImports.end()) {
                        destroyAhbImport(iit->second);
                        g_presenter.ahbImports.erase(iit);
                    }
                }
                AHardwareBuffer_release(reinterpret_cast<AHardwareBuffer*>(frame));
            };
            if (!g_presenter.surfaceValid) { releasePoppedFrame(); continue; }

            // Hold this frame's AHB lock for the whole GPU read (import + copy
            // + submit + fence wait) so the producer thread cannot CPU-fill
            // the same buffer while the GPU is reading it.  This fixes the
            // fdsan "close file descriptor 0" abort (backtrace: nativeFillAhb
            // -> AHardwareBuffer_lock -> Gralloc3Mapper::lock) seen on the
            // KVM emulator's gralloc, a producer/consumer AHB race present in
            // v1.6 and v1.7 (non-deterministic, ~1 in 4-5 test runs).
            std::shared_ptr<std::mutex> ahbLock = getAhbLock(frame);
            std::unique_lock<std::mutex> ahbGuard(*ahbLock);

            VkCommandBuffer cmdbuf = VK_NULL_HANDLE;
            try {
                VkImage srcImage = importAhbLocked(reinterpret_cast<const void*>(frame), &freshImport);
                if (!srcImage) { releasePoppedFrame(); continue; }

                // Non-blocking acquire: 0 timeout returns VK_TIMEOUT if the
                // swapchain has no free image right now.  Using UINT64_MAX here
                // was a hard hang (the loop would block forever on a busy / 
                // out-of-date swapchain).  0 is the correct 'poll' value.
                uint32_t imageIndex = 0;
                VkResult ar = vkAcquireNextImageKHR(
                    g_presenter.device, g_presenter.swapchain, 0,
                    g_presenter.imageAcquired, VK_NULL_HANDLE, &imageIndex);
                if (ar == VK_TIMEOUT || ar == VK_NOT_READY) {
                    std::this_thread::sleep_for(2ms);
                    releasePoppedFrame();
                    continue;
                }
                if (ar == VK_SUBOPTIMAL_KHR || ar == VK_ERROR_OUT_OF_DATE_KHR) {
                    createSwapchain(g_presenter.width, g_presenter.height);
                    releasePoppedFrame();
                    continue;
                }
                if (ar != VK_SUCCESS) { WLOGE("acquire=%d", (int)ar); releasePoppedFrame(); continue; }

                // CRITICAL FIX (was the real ~1.2-2.8s crash): the old code
                // (a) called vkResetCommandPool EVERY frame while the previous
                // frame's command buffer was still allocated (spec violation: a
                // command buffer must be freed before its pool is reset), and
                // (b) NEVER freed the command buffer on the success path (the
                // comment said "consumed by the GPU; don't free" - but a command
                // buffer is NOT released by execution; it must be freed manually
                // after the fence signals).  Together those leaked one command
                // buffer per frame; once the pool exhausted, vkAllocateCommandBuffers
                // failed, the code used a null buffer, and vkBeginCommandBuffer
                // segfaulted.  Now: no per-frame reset (the pool is reset once in
                // transitionToGeneral's fence flow and the command buffer is freed
                // every frame after its fence, so the pool never accumulates).
                VkCommandBufferAllocateInfo cabi{};
                cabi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
                cabi.commandPool = g_presenter.cmdPool;
                cabi.commandBufferCount = 1;
                VkResult aRes = vkAllocateCommandBuffers(g_presenter.device, &cabi, &cmdbuf);
                if (aRes != VK_SUCCESS) {
                    WLOGE("vkAllocateCommandBuffers=%d", (int)aRes);
                    std::this_thread::sleep_for(2ms);
                    releasePoppedFrame();
                    continue;
                }
                VkCommandBufferBeginInfo bci{};
                bci.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
                bci.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
                VkResult bRes = vkBeginCommandBuffer(cmdbuf, &bci);
                if (bRes != VK_SUCCESS) {
                    WLOGE("vkBeginCommandBuffer=%d", (int)bRes);
                    vkFreeCommandBuffers(g_presenter.device, g_presenter.cmdPool, 1, &cmdbuf);
                    cmdbuf = VK_NULL_HANDLE;
                    releasePoppedFrame();
                    continue;
                }

                VkImageMemoryBarrier ib{};
                ib.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                ib.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                ib.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
                ib.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                ib.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                ib.srcAccessMask = 0;
                ib.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                ib.image = g_presenter.swapchainImages[imageIndex];
                ib.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
                vkCmdPipelineBarrier(cmdbuf, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &ib);

                AHardwareBuffer_Desc desc{};
                AHardwareBuffer_describe(const_cast<AHardwareBuffer*>(reinterpret_cast<const AHardwareBuffer*>(frame)), &desc);

                // CRITICAL FIX: clamp the copy extent to the MIN of the source
                // AHB and the swapchain image.  The old code used the AHB extent
                // (full resolution) unconditionally; when the AHB is larger than
                // the swapchain image (rotation / resolution change) vkCmdCopyImage
                // reads out of bounds -> driver crash.
                uint32_t cw = g_presenter.width;
                uint32_t ch = g_presenter.height;
                uint32_t cx = std::min(desc.width, cw);
                uint32_t cy = std::min(desc.height, ch);
                VkImageCopy region{};
                region.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
                region.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
                region.srcOffset = { 0, 0, 0 };
                region.dstOffset = { 0, 0, 0 };
                region.extent = { cx, cy, 1 };
                vkCmdCopyImage(cmdbuf, srcImage, VK_IMAGE_LAYOUT_GENERAL,
                    g_presenter.swapchainImages[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

                ib.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
                ib.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
                ib.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                ib.dstAccessMask = 0;
                vkCmdPipelineBarrier(cmdbuf, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &ib);

                vkEndCommandBuffer(cmdbuf);

                VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                VkSubmitInfo si{};
                si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                si.waitSemaphoreCount = 1;
                si.pWaitSemaphores = &g_presenter.imageAcquired;
                si.pWaitDstStageMask = &waitStage;
                si.commandBufferCount = 1;
                si.pCommandBuffers = &cmdbuf;
                if (vkQueueSubmit(g_presenter.queue, 1, &si, g_presenter.presentFence) != VK_SUCCESS) {
                    vkFreeCommandBuffers(g_presenter.device, g_presenter.cmdPool, 1, &cmdbuf);
                    cmdbuf = VK_NULL_HANDLE;
                    releasePoppedFrame();
                    continue;
                }

                VkPresentInfoKHR pi{};
                pi.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
                // CRITICAL FIX (was the root cause of the ~5s freeze + kill):
                // vkQueuePresentKHR must NOT wait on a semaphore that no
                // command buffer signals.  The old code waited on frameReady
                // (created but never signaled) -> the GPU present hung forever
                // -> the app was killed.  Present is allowed to start as soon
                // as the submitted work finishes; the fence below provides the
                // real completion signal.
                pi.waitSemaphoreCount = 0;
                pi.pWaitSemaphores = nullptr;
                pi.swapchainCount = 1;
                static VkSwapchainKHR chains[1];
                chains[0] = g_presenter.swapchain;
                static uint32_t indices[1];
                indices[0] = imageIndex;
                pi.pSwapchains = chains;
                pi.pImageIndices = indices;
                // A failed present must not be counted as a presented frame.
                const auto presentStart = lsfgperf::nowNs();
                VkResult pr = vkQueuePresentKHR(g_presenter.queue, &pi);
                lsfgperf::metrics.record(lsfgperf::PresentCall, lsfgperf::nowNs() - presentStart);
                if (pr != VK_SUCCESS && pr != VK_SUBOPTIMAL_KHR) {
                    WLOGE("vkQueuePresentKHR=%d", (int)pr);
                }

                // Wait (finite timeout) until the GPU is done with this frame
                // before reusing any of its resources on the next iteration.
                // Only VK_SUCCESS confirms GPU completion; on any other result
                // the command buffer and the source AHB may still be in use
                // and must NOT be released here.
                VkResult wr = vkWaitForFences(g_presenter.device, 1, &g_presenter.presentFence, VK_TRUE, 3000000000ull);
                if (wr == VK_TIMEOUT) {
                    // Give the GPU one more bounded chance to finish before
                    // declaring failure. (VK_ERROR_DEVICE_LOST is not retried:
                    // the device is already gone.)
                    wr = vkWaitForFences(g_presenter.device, 1, &g_presenter.presentFence, VK_TRUE, 10000000000ull);
                }
                if (wr != VK_SUCCESS) {
                    // GPU completion could not be confirmed (VK_TIMEOUT or
                    // VK_ERROR_DEVICE_LOST). The command buffer may still be
                    // executing, so it must NOT be freed here; it is recorded
                    // as pending and the frame's import + AHB reference are
                    // abandoned with the device by the terminal-failure
                    // teardown in stopPresenter().
                    if (wr == VK_ERROR_DEVICE_LOST)
                        WLOGE("present fence: device lost; stopping presenter");
                    else
                        WLOGE("present fence timeout after retry; stopping presenter");
                    g_presenter.gpuFailure = true;
                    g_presenter.pendingCmdbuf = cmdbuf;
                    cmdbuf = VK_NULL_HANDLE;
                    g_presenter.running = false;
                    // Do NOT release the frame here: the GPU may still be reading
                    // it. Its import stays in ahbImports and its AHB ref is
                    // abandoned with the device (terminal-failure teardown).
                    break;
                }
                vkResetFences(g_presenter.device, 1, &g_presenter.presentFence);
                // CRITICAL FIX: free the command buffer NOW that its fence has
                // signaled (the GPU is done with it).  The old code set
                // cmdbuf = VK_NULL_HANDLE and never freed it -> per-frame leak
                // -> pool exhaustion -> null-buffer segfault after ~1.2-2.8s.
                vkFreeCommandBuffers(g_presenter.device, g_presenter.cmdPool, 1, &cmdbuf);
                cmdbuf = VK_NULL_HANDLE;
                // The fence wait above guarantees the GPU finished reading the
                // source, so the popped frame's reference can go now.
                releasePoppedFrame();
                if (pr == VK_SUCCESS || pr == VK_SUBOPTIMAL_KHR) {
                    const auto now = lsfgperf::nowNs();
                    if (lastPresented) lsfgperf::metrics.record(lsfgperf::PresentInterval, now - lastPresented);
                    lastPresented = now;
                    g_presenter.framesPresented++;
                }
            } catch (const std::exception& e) {
                WLOGE("present loop: %s", e.what());
                std::this_thread::sleep_for(10ms);
                releasePoppedFrame();
            }
            // Free the command buffer on any early-exit path (acquire timeout,
            // swapchain recreate, submit failure, exception) so it is never
            // leaked.  The success path already freed it above, so cmdbuf is
            // VK_NULL_HANDLE there and this is a no-op.
            if (cmdbuf) vkFreeCommandBuffers(g_presenter.device, g_presenter.cmdPool, 1, &cmdbuf);
        }
    }
} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_lsfg_fork_wsi_WsiBridge_nativeStart(JNIEnv* env, jclass, jlong windowPtr, jint width, jint height, jfloat targetFps) {
    std::lock_guard<std::mutex> lock(g_initLock);
    if (g_presenter.running) return;
    installCrashHandler();  // install once, before any Vulkan/driver work
    try {
        WLOG("starting presenter %dx%d targetFps=%.1f", width, height, static_cast<double>(targetFps));

        // CRITICAL FIX (root cause of the ~1ms-after-start SIGSEGV, fault_addr=0):
        // volk is a *dynamic* loader. Every vk* symbol used above is a function
        // pointer that is only populated when volkInitialize() runs (it dlopen's
        // libvulkan.so and resolves each entry point). The old code skipped
        // volkInitialize() entirely, so on the very first call (vkCreateInstance)
        // the pointer was NULL -> null-call segfault -> process killed before a
        // single frame was ever presented. That is why the overlay "flashed" and
        // the session died immediately in every mode (root / shizuku / nonroot).
        // Initialize exactly once per process.
        static bool volkReady = false;
        if (!volkReady) {
            VkResult vr = volkInitialize();
            if (vr != VK_SUCCESS) {
                WLOGE("volkInitialize failed (=%d): libvulkan.so not found? presenter cannot start", (int)vr);
                return;
            }
            uint32_t vv = volkGetInstanceVersion();
            WLOG("volk initialized, loader API %u.%u.%u", VK_VERSION_MAJOR(vv), VK_VERSION_MINOR(vv), VK_VERSION_PATCH(vv));
            volkReady = true;
        }

        VkApplicationInfo appInfo{};
        appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        appInfo.pApplicationName = "LSFG-Fork";
        appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.pEngineName = "lsfg-wsi";
        appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.apiVersion = VK_API_VERSION_1_1;
        const char* exts[] = { "VK_KHR_surface", "VK_KHR_android_surface" };
        VkInstanceCreateInfo ici{};
        ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        ici.pApplicationInfo = &appInfo;
        ici.enabledExtensionCount = 2;
        ici.ppEnabledExtensionNames = exts;
        VK_CHECK(vkCreateInstance(&ici, nullptr, &g_presenter.instance));
        volkLoadInstance(g_presenter.instance);

        uint32_t deviceCount = 1;
        vkEnumeratePhysicalDevices(g_presenter.instance, &deviceCount, nullptr);
        std::vector<VkPhysicalDevice> devices(deviceCount);
        vkEnumeratePhysicalDevices(g_presenter.instance, &deviceCount, devices.data());
        g_presenter.physdev = devices[0];

        float priority = 1.0f;
        VkDeviceQueueCreateInfo dqci{};
        dqci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        dqci.queueFamilyIndex = 0;
        dqci.queueCount = 1;
        dqci.pQueuePriorities = &priority;
        const char* devExts[] = { "VK_KHR_swapchain", "VK_ANDROID_external_memory_android_hardware_buffer" };
        VkDeviceCreateInfo dci{};
        dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        dci.queueCreateInfoCount = 1;
        dci.pQueueCreateInfos = &dqci;
        dci.enabledExtensionCount = 2;
        dci.ppEnabledExtensionNames = devExts;
        VK_CHECK(vkCreateDevice(g_presenter.physdev, &dci, nullptr, &g_presenter.device));
        volkLoadDevice(g_presenter.device);
        vkGetDeviceQueue(g_presenter.device, 0, 0, &g_presenter.queue);

        VkSemaphoreCreateInfo semCi{};
        semCi.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        VK_CHECK(vkCreateSemaphore(g_presenter.device, &semCi, nullptr, &g_presenter.imageAcquired));
        VK_CHECK(vkCreateSemaphore(g_presenter.device, &semCi, nullptr, &g_presenter.frameReady));
        VkFenceCreateInfo fenceCi{};
        fenceCi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        VK_CHECK(vkCreateFence(g_presenter.device, &fenceCi, nullptr, &g_presenter.presentFence));
        VkCommandPoolCreateInfo poolCi{};
        poolCi.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolCi.queueFamilyIndex = 0;
        VK_CHECK(vkCreateCommandPool(g_presenter.device, &poolCi, nullptr, &g_presenter.cmdPool));

        ANativeWindow* window = reinterpret_cast<ANativeWindow*>(windowPtr);
        g_presenter.window = window;
        VkAndroidSurfaceCreateInfoKHR sci{};
        sci.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        sci.window = window;
        vkCreateAndroidSurfaceKHR(g_presenter.instance, &sci, nullptr, &g_presenter.surface);

        int32_t winW = ANativeWindow_getWidth(window);
        int32_t winH = ANativeWindow_getHeight(window);
        if (winW <= 0 || winH <= 0) {
            winW = static_cast<int32_t>(width);
            winH = static_cast<int32_t>(height);
        }
        g_presenter.targetFps = targetFps > 1.0f ? targetFps : 60.0f;
        createSwapchain(static_cast<uint32_t>(winW), static_cast<uint32_t>(winH));

        lsfgperf::metrics.reset();
        g_presenter.running = true;
        g_presenter.constructed = true;
        g_presenter.thread = std::thread(presentLoop);
        WLOG("presenter started");
    } catch (const std::exception& e) {
        WLOGE("nativeStart failed: %s", e.what());
        // Partially initialized resources are torn down here (the thread was
        // never started, so no join is needed); stopPresenter() remains safe
        // to call later and will be a no-op.
        g_presenter.running = false;
        if (g_presenter.instance || g_presenter.device)
            teardownVulkanResources();
        g_presenter.surfaceValid = false;
        g_presenter.window = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_lsfg_fork_wsi_WsiBridge_nativeStop(JNIEnv*, jclass) {
    ::stopPresenter();
}

JNIEXPORT jboolean JNICALL
Java_com_lsfg_fork_wsi_WsiBridge_nativeIsRunning(JNIEnv*, jclass) {
    return g_presenter.running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_lsfg_fork_wsi_WsiBridge_nativeSetPaused(JNIEnv*, jclass, jboolean paused) {
    g_presenter.paused = paused ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_lsfg_fork_wsi_WsiBridge_nativeSetTargetFps(JNIEnv*, jclass, jfloat fps) {
    if (fps > 1.0f) g_presenter.targetFps = fps;
}

JNIEXPORT jstring JNICALL
Java_com_lsfg_fork_wsi_WsiBridge_nativePerformanceStats(JNIEnv* env, jclass) {
    return env->NewStringUTF(lsfgperf::metrics.summary().c_str());
}

JNIEXPORT jlong JNICALL
Java_com_lsfg_fork_wsi_WsiBridge_nativeAhbPtr(JNIEnv* env, jclass, jobject obj) {
    if (!obj) return 0;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, obj);
    return reinterpret_cast<jlong>(ahb);
}

JNIEXPORT void JNICALL
Java_com_lsfg_fork_wsi_WsiBridge_nativePushFrame(JNIEnv*, jclass, jlong ahbPtr) {
    if (ahbPtr)
        lsfgdisplay::push(static_cast<uintptr_t>(ahbPtr));
}

JNIEXPORT jint JNICALL
Java_com_lsfg_fork_wsi_WsiBridge_nativeQueueSize(JNIEnv*, jclass) {
    return lsfgdisplay::size();
}

/** Number of frames the presenter has actually handed to vkQueuePresentKHR.
 *  Used by the in-app pipeline self-test to report an HONEST pass/fail
 *  (instead of claiming success when the Vulkan path never produced a frame). */
JNIEXPORT jlong JNICALL
Java_com_lsfg_fork_wsi_WsiBridge_nativeFramesPresented(JNIEnv*, jclass) {
    return (jlong)g_presenter.framesPresented.load();
}

/**
 * Fast CPU fill of a HardwareBuffer ring slot from a raw RGBA8888 byte source
 * (e.g. an ImageReader RGBA_8888 plane from a MediaProjection VirtualDisplay).
 * Copies row by row, respecting the source row stride.
 */
JNIEXPORT void JNICALL
Java_com_lsfg_fork_wsi_WsiBridge_nativeFillAhb(
        JNIEnv* env, jclass, jobject ahbObj, jbyteArray bytes, jint srcRowStride) {
    if (!ahbObj || !bytes || srcRowStride <= 0) return;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, ahbObj);
    if (!ahb) return;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    if (desc.width == 0 || desc.height == 0) return;
    // Serialize this CPU fill against the presenter's GPU read of the SAME
    // buffer (see getAhbLock).  The producer must not AHardwareBuffer_lock a
    // buffer the present thread is still importing/copying via Vulkan.
    std::shared_ptr<std::mutex> ahbLock = getAhbLock(reinterpret_cast<uintptr_t>(ahb));
    std::lock_guard<std::mutex> ahbGuard(*ahbLock);
    void* data = nullptr;
    AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY,
                         /*fence*/ 0, /*rect*/ nullptr, &data);
    if (!data) return;
    uint8_t* src = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(bytes, nullptr));
    if (src) {
        const uint32_t rowBytes = desc.width * 4; // RGBA8888 (4 bytes/pixel)
        uint8_t* d = static_cast<uint8_t*>(data);
        for (uint32_t y = 0; y < desc.height; ++y) {
            std::memcpy(d + y * static_cast<size_t>(desc.stride),
                        src + y * static_cast<size_t>(srcRowStride),
                        rowBytes);
        }
        env->ReleasePrimitiveArrayCritical(bytes, src, JNI_ABORT);
    }
    AHardwareBuffer_unlock(ahb, nullptr);
}

JNIEXPORT jlong JNICALL
Java_com_lsfg_fork_wsi_WsiBridge_nativeSurfaceToWindow(JNIEnv* env, jclass, jobject surface) {
    if (!surface) return 0;
    ANativeWindow* w = ANativeWindow_fromSurface(env, surface);
    return reinterpret_cast<jlong>(w);
}

JNIEXPORT void JNICALL
Java_com_lsfg_fork_wsi_WsiBridge_nativeReleaseWindow(JNIEnv*, jclass, jlong windowPtr) {
    if (windowPtr)
        ANativeWindow_release(reinterpret_cast<ANativeWindow*>(windowPtr));
}

} // extern "C"

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK)
        lsfgjlog::init(env, vm);
    return JNI_VERSION_1_6;
}
