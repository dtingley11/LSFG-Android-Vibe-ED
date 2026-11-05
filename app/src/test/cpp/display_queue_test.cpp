#include "lsfg_display.h"
#include <android/hardware_buffer.h>
#include <cassert>
#include <iostream>
int main() {
    using namespace lsfgdisplay;
    AHardwareBuffer buffers[6];
    auto ptr = [&](int i) { return reinterpret_cast<uintptr_t>(&buffers[i]); };
    reset();
    assert(reserve(ptr(0))); assert(!reserve(ptr(0)));
    pushTimed({ptr(0), 100, 100, 110, 0, true}, true);
    auto f = popFrame(1); assert(f.ahb == ptr(0)); assert(f.sourceNs == 100); assert(f.externalOwnership);
    assert(size() == 0); assert(!reserve(ptr(0))); assert(buffers[0].refs == 2);
    clear(); assert(!reserve(ptr(0))); // Clear cannot recycle an in-flight buffer.
    complete(f.ahb); assert(buffers[0].refs == 1); assert(reserve(ptr(0))); cancel(ptr(0));
    for (int i = 0; i < 6; ++i) push(ptr(i));
    assert(size() == 4); assert(dropped() == 2);
    assert(buffers[0].refs == 1 && buffers[1].refs == 1);
    f = popFrame(1); assert(f.ahb == ptr(2)); complete(f.ahb);
    reset(); assert(size() == 0); assert(dropped() == 0);
    for (auto& b : buffers) assert(b.refs == 1);
    assert(!popFrame(1).ahb);
    assert(reserve(ptr(0))); cancel(ptr(0)); assert(reserve(ptr(0))); cancel(ptr(0));
    std::cout << "display queue: bounded backlog, ownership, in-flight reuse and reset passed\n";
}
