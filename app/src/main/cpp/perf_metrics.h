#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>

namespace lsfgperf {
inline int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
enum Stage { Import, Allocate, SourceUpload, Submit, Wait, PrepassGpu, MainGpu,
    Output, QueueAge, PresentInterval, PresentCall, Latency, CpuCopy, StageCount };
inline constexpr const char* names[] = {"import", "allocate", "sourceUpload", "submitCpu",
    "fenceWaitCpu", "prepassGpu", "mainGpu", "output", "queueAge", "presentInterval",
    "presentCall", "captureToPresentRequest", "cpuCopy"};
struct Window {
    std::array<double, 256> values{};
    size_t next = 0, size = 0;
    uint64_t count = 0;
};
class Metrics {
    std::mutex mutex;
    std::array<Window, StageCount> windows;
public:
    void record(Stage stage, int64_t ns) {
        if (ns < 0) return;
        std::lock_guard<std::mutex> guard(mutex);
        auto& w = windows[stage];
        w.values[w.next] = ns / 1e6;
        w.next = (w.next + 1) % w.values.size();
        w.size = std::min(w.size + 1, w.values.size());
        ++w.count;
    }
    void reset() { std::lock_guard<std::mutex> guard(mutex); windows = {}; }
    std::string summary() {
        std::lock_guard<std::mutex> guard(mutex);
        std::string out;
        for (size_t i = 0; i < StageCount; ++i) {
            auto& w = windows[i];
            if (!w.size) continue;
            auto sorted = w.values;
            std::sort(sorted.begin(), sorted.begin() + w.size);
            auto at = [&](double p) { return sorted[static_cast<size_t>(std::ceil(p * w.size)) - 1]; };
            char text[180];
            std::snprintf(text, sizeof(text), "%s=%llu,%.3f,%.3f,%.3f;", names[i],
                static_cast<unsigned long long>(w.count), at(.50), at(.95), at(.99));
            out += text;
        }
        return out;
    }
};
inline __attribute__((visibility("hidden"))) Metrics metrics;
class Scope {
    Stage stage;
    int64_t start = nowNs();
public:
    explicit Scope(Stage s) : stage(s) {}
    ~Scope() { metrics.record(stage, nowNs() - start); }
};
}
