#pragma once
#include <cstdint>
namespace lsfgdisplay {
struct Frame { uintptr_t ahb = 0; int64_t sourceNs = 0, contentNs = 0, dueNs = 0, queuedNs = 0; bool externalOwnership = false; };
// A producer reservation prevents reuse until the presenter confirms completion.
bool reserve(uintptr_t ahb);
void cancel(uintptr_t ahb);
void push(uintptr_t ahb);
void pushTimed(Frame frame, bool reserved);
Frame popFrame(int timeoutMs);
void complete(uintptr_t ahb);
void clear();
int size();
uint64_t dropped();
void reset();
}
