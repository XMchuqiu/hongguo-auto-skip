package com.hongguo.auto;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务与界面之间共享的运行状态（同一进程，直接静态字段即可）。
 */
public final class ServiceState {

    public static volatile boolean connected;
    public static volatile long connectedAt;
    public static volatile String foregroundPackage = "";
    public static volatile String lastDecision = "尚未检测";
    public static volatile String lastSignals = "";
    public static volatile long lastScanAt;
    public static volatile long lastSwipeAt;
    public static volatile String lastSwipeReason = "";
    public static volatile int scanCount;
    public static volatile String lastDumpPath = "";

    public static final AtomicBoolean dumpRequested = new AtomicBoolean(false);

    private ServiceState() {
    }

    public static void reset() {
        connected = false;
        connectedAt = 0;
        foregroundPackage = "";
        lastDecision = "尚未检测";
        lastSignals = "";
        lastScanAt = 0;
        lastSwipeAt = 0;
        lastSwipeReason = "";
        scanCount = 0;
    }
}
