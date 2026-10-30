package com.hongguo.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import com.hongguo.auto.core.AdDetector;
import com.hongguo.auto.core.AdSignals;
import com.hongguo.auto.core.AdStateMachine;
import com.hongguo.auto.core.DetectorConfig;
import com.hongguo.auto.core.UiNode;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 无障碍服务：检测广告是否播完，然后上滑继续播放。
 *
 * <p>与旧版的关键差异：
 * <ul>
 *   <li>检测放在独立工作线程里轮询，不再依赖 ROM 是否把无障碍事件派发给我们；</li>
 *   <li>同时扫描所有窗口（getWindows），广告在悬浮窗/独立窗口里时也能识别；</li>
 *   <li>不在主线程做节点遍历，也不 Thread.sleep，避免丢事件和 ANR；</li>
 *   <li>同时支持红果免费短剧（com.phoenix.read）和红果免费漫剧（com.kylin.read）。</li>
 * </ul>
 */
public class HongguoAccessibilityService extends AccessibilityService {

    public static final String PKG_SHORT_DRAMA = "com.phoenix.read";
    public static final String PKG_COMIC = "com.kylin.read";

    private static final String[] TARGET_PACKAGES = {
            PKG_SHORT_DRAMA, PKG_COMIC, "com.hongguo.reader", "com.phoenix.read.next"
    };

    /** 常见广告 SDK 的包名前缀：广告用独立窗口展示时，包名不是红果本身。 */
    private static final String[] AD_SDK_PREFIXES = {
            "com.bytedance", "com.ss.android", "com.qq.e", "com.kwad", "com.mbridge",
            "com.unity3d.ads", "com.google.android.gms", "com.topon", "com.anythink",
            "com.pangle", "com.baidu.mobads", "com.tencent.gdt", "com.miui.ads"
    };

    private static final String TAG = "Service";
    private static final String CHANNEL_ID = "hongguo_auto_channel";
    private static final int NOTIFY_ID = 1001;

    private SharedPreferences prefs;
    private NotificationManager notificationManager;
    private HandlerThread workerThread;
    private Handler worker;
    private volatile boolean running;

    private final AdStateMachine stateMachine = new AdStateMachine(DetectorConfig.defaults());
    private String configSignature = "";
    private long lastScanAt;
    private long lastKickAt;
    private long lastTargetSeenAt;
    private String lastTargetPackage = "";
    private String lastDecisionLogged = "";
    private long lastDecisionLogAt;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        prefs = Prefs.get(this);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        applyServiceInfo();
        startWorker();
        ServiceState.connected = true;
        ServiceState.connectedAt = System.currentTimeMillis();
        DetectorConfig cfg = Prefs.readConfig(this, prefs);
        AppLog.d(TAG, "服务已连接");
        AppLog.d(TAG, "参数: 间隔=" + cfg.checkIntervalMs + "ms 冷却=" + cfg.cooldownMs + "ms 持续=" + cfg.persistSwipeMs
                + "ms 滑动=" + cfg.swipeDurationMs + "ms " + cfg.swipeFromPct + "%->" + cfg.swipeToPct + "%");
        toast("红果自动跳过服务已启动");
    }

    private void applyServiceInfo() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                return;
            }
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_SCROLLED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.notificationTimeout = 50;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            setServiceInfo(info);
            AppLog.d(TAG, "serviceInfo 已刷新: eventTypes=" + info.eventTypes + " flags=" + info.flags);
        } catch (Throwable t) {
            AppLog.d(TAG, "刷新 serviceInfo 失败: " + t);
        }
    }

    private void startWorker() {
        if (running) {
            return;
        }
        workerThread = new HandlerThread("hongguo-detect");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        running = true;
        worker.post(tickRunnable);
    }

    private void stopWorker() {
        running = false;
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        worker = null;
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                scanOnce(SystemClock.uptimeMillis());
            } catch (Throwable t) {
                AppLog.d(TAG, "扫描异常: " + t);
            }
            if (running && worker != null) {
                worker.postDelayed(this, pollIntervalMs());
            }
        }
    };

    /** 前台是目标应用时 400ms 轮询一次，否则放慢省电。 */
    private long pollIntervalMs() {
        return System.currentTimeMillis() - lastTargetSeenAt < 60000 ? 400 : 1500;
    }

    private final Runnable kickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                scanOnce(SystemClock.uptimeMillis());
            } catch (Throwable t) {
                AppLog.d(TAG, "事件触发扫描异常: " + t);
            }
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        String pkg = packageName == null ? "" : packageName.toString();
        if (isTargetPackage(pkg)) {
            lastTargetPackage = pkg;
            lastTargetSeenAt = System.currentTimeMillis();
        }
        // 事件只用来"尽快扫一次"，真正的节奏由工作线程的轮询保证
        long now = SystemClock.uptimeMillis();
        if (running && now - lastKickAt > 150) {
            lastKickAt = now;
            Handler handler = worker;
            if (handler != null) {
                handler.post(kickRunnable);
            }
        }
    }

    private void scanOnce(long now) {
        if (prefs == null || !prefs.getBoolean(Prefs.KEY_ENABLED, false)) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null && !powerManager.isInteractive()) {
            return; // 息屏时不扫描，省电
        }
        DetectorConfig config = Prefs.readConfig(this, prefs);
        if (!config.signature().equals(configSignature)) {
            configSignature = config.signature();
            stateMachine.setConfig(config);
            AppLog.d(TAG, "参数已更新");
        }
        if (now - lastScanAt < config.checkIntervalMs) {
            return;
        }
        lastScanAt = now;
        ServiceState.scanCount++;

        if (ServiceState.dumpRequested.compareAndSet(true, false)) {
            dumpCurrentScreen();
        }

        List<NodeScanner.RawWindow> windows = NodeScanner.collectRawWindows(this);
        String foreground = resolveForeground(windows);

        ServiceState.foregroundPackage = foreground;
        if (!isTargetPackage(foreground)) {
            return;
        }
        if (!Prefs.isAppEnabled(prefs, foreground)) {
            ServiceState.lastDecision = "该应用已在设置中关闭";
            return;
        }

        // 只有前台确实是红果（或广告 SDK 窗口）时才转换节点树，避免在其它应用里白耗电
        List<UiNode> roots = new ArrayList<>();
        int[] area = null;
        for (NodeScanner.RawWindow window : windows) {
            if (window.root == null || window.pkg == null) {
                continue;
            }
            boolean target = isTargetPackage(window.pkg);
            boolean adSdk = isAdSdkPackage(window.pkg);
            if (!target && !adSdk) {
                continue;
            }
            UiNode root = NodeScanner.toUiNode(window.root);
            if (root == null) {
                continue;
            }
            roots.add(root);
            if (target) {
                area = mergeArea(area, root);
            }
        }
        if (roots.isEmpty()) {
            ServiceState.lastDecision = "未取到窗口节点";
            return;
        }

        boolean legacyComicRule = PKG_COMIC.equals(foreground);
        AdSignals signals = AdDetector.scan(roots, config, legacyComicRule);
        AdStateMachine.Action action = stateMachine.onScan(signals, now);

        ServiceState.lastScanAt = System.currentTimeMillis();
        ServiceState.lastSignals = signals.describe();
        ServiceState.lastDecision = (stateMachine.isInAd() ? "广告中" : "正常播放") + " · " + signals.describe();

        logDecision(now, foreground, signals, action);
        if (action.swipe) {
            performSwipe(config, area, foreground, action.reason);
        }
    }

    private void logDecision(long now, String pkg, AdSignals signals, AdStateMachine.Action action) {
        String line = pkg + " " + signals.describe();
        if (action.swipe) {
            return; // 上滑时会单独打日志
        }
        if (!line.equals(lastDecisionLogged) || now - lastDecisionLogAt > 10000) {
            lastDecisionLogged = line;
            lastDecisionLogAt = now;
            AppLog.d(TAG, "检测: " + line);
        }
    }

    private String resolveForeground(List<NodeScanner.RawWindow> windows) {
        String active = null;
        for (NodeScanner.RawWindow window : windows) {
            if (window.active && window.pkg != null && !window.pkg.isEmpty()) {
                active = window.pkg;
                break;
            }
        }
        if (active == null || active.isEmpty()) {
            for (NodeScanner.RawWindow window : windows) {
                if (window.pkg != null && isTargetPackage(window.pkg)) {
                    active = window.pkg;
                    break;
                }
            }
        }
        if (active == null) {
            active = "";
        }
        if (isTargetPackage(active)) {
            lastTargetPackage = active;
            lastTargetSeenAt = System.currentTimeMillis();
            return active;
        }
        // 广告用独立窗口/广告 SDK 展示时，包名不是红果本身，沿用最近的目标包
        if ((isAdSdkPackage(active) && System.currentTimeMillis() - lastTargetSeenAt < 30000)
                || System.currentTimeMillis() - lastTargetSeenAt < 2000) {
            return lastTargetPackage;
        }
        return active;
    }

    private int[] mergeArea(int[] area, UiNode root) {
        if (root == null || root.width() <= 0 || root.height() <= 0) {
            return area;
        }
        if (area == null) {
            return new int[]{root.left, root.top, root.right, root.bottom};
        }
        area[0] = Math.min(area[0], root.left);
        area[1] = Math.min(area[1], root.top);
        area[2] = Math.max(area[2], root.right);
        area[3] = Math.max(area[3], root.bottom);
        return area;
    }

    private void performSwipe(DetectorConfig config, int[] area, String pkg, String reason) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int left = 0;
        int top = 0;
        int right = metrics.widthPixels;
        int bottom = metrics.heightPixels;
        if (area != null && area[2] - area[0] > 100 && area[3] - area[1] > 200) {
            left = area[0];
            top = area[1];
            right = area[2];
            bottom = area[3];
        }
        int height = bottom - top;
        float x = (left + right) / 2f;
        float fromY = top + height * config.swipeFromPct / 100f;
        float toY = top + height * config.swipeToPct / 100f;

        Path path = new Path();
        path.moveTo(x, fromY);
        path.lineTo(x, toY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(80, config.swipeDurationMs)))
                .build();

        ServiceState.lastSwipeAt = System.currentTimeMillis();
        ServiceState.lastSwipeReason = reason;
        AppLog.d(TAG, "上滑: pkg=" + pkg + " 原因=" + reason + " 区域=" + left + "," + top + "~" + right + "," + bottom
                + " 坐标=" + (int) x + "," + (int) fromY + "->" + (int) toY + " 时长=" + config.swipeDurationMs + "ms");

        boolean dispatched;
        try {
            dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription description) {
                    AppLog.d(TAG, "上滑完成");
                }

                @Override
                public void onCancelled(GestureDescription description) {
                    AppLog.d(TAG, "上滑被系统取消，改用滚动指令兜底");
                    Handler handler = worker;
                    if (handler != null) {
                        handler.post(fallbackScroll);
                    }
                }
            }, null);
        } catch (Throwable t) {
            AppLog.d(TAG, "dispatchGesture 异常: " + t);
            dispatched = false;
        }
        if (!dispatched) {
            Handler handler = worker;
            if (handler != null) {
                handler.post(fallbackScroll);
            }
        } else {
            showNotification("已自动跳过广告");
        }
    }

    /** 手势被 ROM 拒绝时的兜底：直接让容器滚动一屏。 */
    private final Runnable fallbackScroll = new Runnable() {
        @Override
        public void run() {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) {
                    AppLog.d(TAG, "滚动兜底失败：取不到根节点");
                    return;
                }
                boolean ok = root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                AppLog.d(TAG, "滚动兜底: " + ok);
                if (!ok) {
                    List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId("android:id/content");
                    if (list != null) {
                        for (AccessibilityNodeInfo node : list) {
                            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                                AppLog.d(TAG, "滚动兜底(content) 成功");
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                AppLog.d(TAG, "滚动兜底异常: " + t);
            }
        }
    };

    /** 用户点"抓取当前页面"时，把所有窗口节点导出到日志和文件，方便反馈问题。 */
    private void dumpCurrentScreen() {
        try {
            List<NodeScanner.WindowInfo> windows = NodeScanner.snapshot(NodeScanner.collectRawWindows(this));
            String dump = NodeScanner.dumpWindows(this, windows);
            String[] lines = dump.split("\n");
            AppLog.d(TAG, "===== 页面节点开始 =====");
            int limit = Math.min(lines.length, 120);
            for (int i = 0; i < limit; i++) {
                AppLog.d(TAG, lines[i]);
            }
            if (lines.length > limit) {
                AppLog.d(TAG, "... 共 " + lines.length + " 行，完整内容见文件");
            }
            AppLog.d(TAG, "===== 页面节点结束 =====");

            File dir = getExternalFilesDir("dumps");
            if (dir != null) {
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                String name = "dump_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
                File file = new File(dir, name);
                FileOutputStream out = new FileOutputStream(file);
                out.write(dump.getBytes("UTF-8"));
                out.close();
                ServiceState.lastDumpPath = file.getAbsolutePath();
                AppLog.d(TAG, "节点已保存: " + file.getAbsolutePath());
            }
        } catch (Throwable t) {
            AppLog.d(TAG, "抓取节点失败: " + t);
        }
    }

    private boolean isTargetPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        for (String target : TARGET_PACKAGES) {
            if (target.equals(pkg) || pkg.startsWith(target + ".")) {
                return true;
            }
        }
        return false;
    }

    private boolean isAdSdkPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        for (String prefix : AD_SDK_PREFIXES) {
            if (pkg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "红果自动跳过",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("自动跳过广告时提示");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void showNotification(String text) {
        if (notificationManager == null) {
            return;
        }
        try {
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class), pendingFlags);
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }
            Notification notification = builder
                    .setSmallIcon(android.R.drawable.ic_media_ff)
                    .setContentTitle("红果自动跳过")
                    .setContentText(text)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
            notificationManager.notify(NOTIFY_ID, notification);
        } catch (Throwable t) {
            AppLog.d(TAG, "通知发送失败: " + t);
        }
    }

    private void toast(String message) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onInterrupt() {
        AppLog.d(TAG, "服务被中断");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        AppLog.d(TAG, "服务已解绑");
        ServiceState.connected = false;
        stopWorker();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        AppLog.d(TAG, "服务已销毁");
        ServiceState.connected = false;
        stopWorker();
        super.onDestroy();
    }
}
