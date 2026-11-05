cmake_minimum_required(VERSION 3.22.1)
project("lsfg")

# ---- framegen (compute pipeline, Vulkan-hpp based) -------------------------
add_library(
    lsfg_framegen
    SHARED
    framegen/library.cpp
    framegen/library/dll.cpp
    framegen/pipelines/v3_1.cpp
    framegen/pipeline.cpp
    framegen/lsfgvk.cpp
    framegen/vkhelper.cpp
    framegen/lsfg_jni.cpp
    framegen/ahb_output_ring.cpp
)

target_include_directories(lsfg_framegen PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/framegen
    ${CMAKE_CURRENT_SOURCE_DIR}/framegen/thirdparty
    ${CMAKE_CURRENT_SOURCE_DIR}/framegen/thirdparty/vulkan
    ${CMAKE_CURRENT_SOURCE_DIR}/framegen/shaders
)

set_target_properties(lsfg_framegen PROPERTIES CXX_STANDARD 23 CXX_STANDARD_REQUIRED ON)
# Enable the Android AHardwareBuffer Vulkan types/entry points (importing capture buffers).
target_compile_definitions(lsfg_framegen PRIVATE
    LSFGVK_EXPOSE_VULKAN=1
    VK_USE_PLATFORM_ANDROID_KHR
)

target_link_libraries(lsfg_framegen
    android
    log
    vulkan
    jnigraphics
)

# ---- shared display queue (linked into wsi) --------------------------------
add_library(
    lsfg_display
    STATIC
    lsfg_display.cpp
)
target_include_directories(lsfg_display PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

# ---- wsi presenter (Vulkan swapchain on overlay surface, volk loader) ------
add_library(
    lsfg_wsi
    SHARED
    wsi/wsi.cpp
    wsi/volk.c
)

# Use the NDK's own Vulkan headers (they include the Android extensions).
target_include_directories(lsfg_wsi PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/wsi
)

# Tell volk (and wsi.cpp) to enable the Android platform extensions.
target_compile_definitions(lsfg_wsi PRIVATE VK_USE_PLATFORM_ANDROID_KHR)

set_target_properties(lsfg_wsi PROPERTIES CXX_STANDARD 23 CXX_STANDARD_REQUIRED ON)

target_link_libraries(lsfg_wsi
    lsfg_display
    android
    log
    vulkan
    jnigraphics
)
