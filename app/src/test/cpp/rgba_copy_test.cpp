#include "rgba_copy.h"
#include <array>
#include <cassert>
#include <iostream>
int main() {
    const std::array<uint32_t, 6> source{1, 2, 99, 3, 4, 99};
    std::array<uint32_t, 8> dest{};
    lsfgpixels::copyRows(reinterpret_cast<uint8_t*>(dest.data()), 16,
        reinterpret_cast<const uint8_t*>(source.data()), 12, 2, 2);
    assert(dest[0] == 1 && dest[1] == 2 && dest[4] == 3 && dest[5] == 4);
    assert(dest[2] == 0 && dest[3] == 0 && dest[6] == 0);
    const uint32_t packed[]{1, 2, 3, 4};
    lsfgpixels::copyScaled(dest.data(), 4, 2, 2, packed, 2, 2, true);
    assert(dest[0] == 0xff000001u && dest[5] == 0xff000004u);
    uint32_t smaller{}; lsfgpixels::copyScaled(&smaller, 1, 1, 1, packed, 2, 2, false);
    assert(smaller == 1);
    std::cout << "RGBA copy: padded byte/pixel strides, scaling and RGBX alpha passed\n";
}
