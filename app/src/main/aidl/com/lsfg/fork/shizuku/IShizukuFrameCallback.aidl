package com.lsfg.fork.shizuku;

import android.hardware.HardwareBuffer;

interface IShizukuFrameCallback {
    void onFrame(in HardwareBuffer buffer, long timestampNs);
    void onFrameMetrics(long timestampNs, long frameTimeNs, long pacingJitterNs);
    void onCaptureMetrics(long startedNs, long captureNs, long commandNs, long readNs,
        long decodeNs, long copyNs, String backend);
    void onError(String message);
}
