#pragma once
#include <cassert>
#include <cstdint>
#define AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM 1
struct AHardwareBuffer_Desc {
    uint32_t width = 0, height = 0, layers = 1, format = 1;
    uint64_t usage = 0;
    uint32_t stride = 0;
};
struct AHardwareBuffer { int refs = 1; AHardwareBuffer_Desc description{}; };
inline void AHardwareBuffer_acquire(AHardwareBuffer* p) { assert(p->refs > 0); ++p->refs; }
inline void AHardwareBuffer_release(AHardwareBuffer* p) { assert(p->refs > 1); --p->refs; }
inline void AHardwareBuffer_describe(const AHardwareBuffer* p, AHardwareBuffer_Desc* d) { *d = p->description; }
