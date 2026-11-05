#include "lsfg_display.h"
#include <android/hardware_buffer.h>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <map>
#include <mutex>
namespace {
std::mutex mutex;
std::condition_variable cv;
std::deque<lsfgdisplay::Frame> queue;
std::map<uintptr_t, unsigned> busy;
uint64_t drops = 0;
constexpr size_t capacity = 4;
void unmark(uintptr_t p) { auto it = busy.find(p); if (it != busy.end() && --it->second == 0) busy.erase(it); }
int64_t nowNs() { return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
}
namespace lsfgdisplay {
bool reserve(uintptr_t p) { std::lock_guard lock(mutex); if (!p || busy.find(p) != busy.end()) return false; busy[p] = 1; return true; }
void cancel(uintptr_t p) { std::lock_guard lock(mutex); unmark(p); }
void push(uintptr_t p) { pushTimed({p}, false); }
void pushTimed(Frame f, bool reserved) {
    if (!f.ahb) return;
    AHardwareBuffer_acquire(reinterpret_cast<AHardwareBuffer*>(f.ahb));
    { std::lock_guard lock(mutex);
      if (!reserved) ++busy[f.ahb];
      f.queuedNs = nowNs();
      if (queue.size() >= capacity) {
          auto old = queue.front(); queue.pop_front(); ++drops; unmark(old.ahb);
          AHardwareBuffer_release(reinterpret_cast<AHardwareBuffer*>(old.ahb));
      }
      queue.push_back(f);
    }
    cv.notify_one();
}
Frame popFrame(int timeoutMs) {
    std::unique_lock lock(mutex);
    cv.wait_for(lock, std::chrono::milliseconds(timeoutMs > 0 ? timeoutMs : 1), [] { return !queue.empty(); });
    if (queue.empty()) return {};
    auto f = queue.front(); queue.pop_front(); return f;
}
void complete(uintptr_t p) {
    { std::lock_guard lock(mutex); unmark(p); }
    AHardwareBuffer_release(reinterpret_cast<AHardwareBuffer*>(p));
}
void clear() {
    std::lock_guard lock(mutex);
    for (auto f : queue) { unmark(f.ahb); AHardwareBuffer_release(reinterpret_cast<AHardwareBuffer*>(f.ahb)); }
    queue.clear();
}
int size() { std::lock_guard lock(mutex); return int(queue.size()); }
uint64_t dropped() { std::lock_guard lock(mutex); return drops; }
void reset() { clear(); std::lock_guard lock(mutex); drops = 0; }
}
