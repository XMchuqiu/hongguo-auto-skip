package com.hongguo.auto;

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
    public static volatile long lastDumpAt;
    public static volatile int lastDumpWindows;
    public static volatile int lastDumpNodes;
    public static volatile int lastDumpLines;

    /** 抓取请求的到期时间（毫秒时间戳），0 表示没有待处理请求。 */
    private static volatile long dumpDueAt;

    private ServiceState() {
    }

    /**
     * 请求抓取当前页面节点。延迟执行是为了让用户有时间切回红果的广告页，
     * 否则抓到的是本应用自己的界面。
     */
    public static boolean requestDump(long delayMs) {
        if (dumpDueAt > 0) {
            return false;
        }
        dumpDueAt = System.currentTimeMillis() + Math.max(0, delayMs);
        return true;
    }

    public static boolean hasPendingDump() {
        return dumpDueAt > 0;
    }

    public static long dumpDueInMs() {
        long due = dumpDueAt;
        return due <= 0 ? -1 : Math.max(0, due - System.currentTimeMillis());
    }

    /** 到点则消费一次抓取请求。 */
    public static boolean consumeDumpIfDue() {
        long due = dumpDueAt;
        if (due > 0 && System.currentTimeMillis() >= due) {
            dumpDueAt = 0;
            return true;
        }
        return false;
    }

    public static void cancelDump() {
        dumpDueAt = 0;
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
        lastDumpAt = 0;
        lastDumpWindows = 0;
        lastDumpNodes = 0;
        lastDumpLines = 0;
        dumpDueAt = 0;
    }
}

