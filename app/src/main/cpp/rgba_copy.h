#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
namespace lsfgpixels {
inline void copyRows(uint8_t* dst, size_t dstStride, const uint8_t* src, size_t srcStride, size_t width, size_t height) {
    for (size_t y = 0; y < height; ++y) std::memcpy(dst + y * dstStride, src + y * srcStride, width * 4);
}
inline void copyScaled(uint32_t* dst, size_t stridePixels, size_t width, size_t height,
                       const uint32_t* src, size_t sw, size_t sh, bool opaque) {
    for (size_t y = 0; y < height; ++y) {
        auto* row = dst + y * stridePixels;
        auto* source = src + (y * sh / height) * sw;
        for (size_t x = 0; x < width; ++x) {
            const auto pixel = source[x * sw / width];
            row[x] = opaque ? pixel | 0xff000000u : pixel;
        }
    }
}
}
