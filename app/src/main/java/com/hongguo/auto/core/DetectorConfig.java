package com.hongguo.auto.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 检测参数快照。这个类不依赖 Android 框架，方便在电脑上做单元测试。
 */
public final class DetectorConfig {

    /** 轮询间隔（毫秒）。手机上无障碍事件经常被 ROM 丢弃，所以主要靠轮询。 */
    public int checkIntervalMs = 700;
    /** 两次上滑之间的最小间隔。 */
    public int cooldownMs = 2500;
    /** 广告标识需要持续出现多久才判定"广告已经播完/卡住"，然后上滑。 */
    public int persistSwipeMs = 1200;
    /** 广告标识至少出现这么久，才允许"广告消失后上滑"，避免闪现误触。 */
    public int minAdDurationMs = 800;
    /** "上滑继续观看"要连续这么多毫秒都没有倒计时，才当作广告结束卡片（防止倒计时偶尔不渲染导致误判）。 */
    public int endCardConfirmMs = 600;
    /** 广告超过这么久还跳不过去，就切后台再回来（红果会在回到前台时丢掉广告）。 */
    public int escapeAfterMs = 15000;
    /** 是否允许自动切后台再回来。 */
    public boolean escapeEnabled = true;
    /** 上滑之后的静默期，避免一次广告连滑好几次。 */
    public int postSwipeQuietMs = 4000;
    /** 同一段广告最多上滑几次。 */
    public int maxSwipesPerAd = 3;
    /** 倒计时长时间不变的最长时间，超过则强制上滑。 */
    public int stuckAdMs = 45000;

    /** 上滑手势时长。 */
    public int swipeDurationMs = 450;
    /** 上滑起点（屏幕高度百分比）。 */
    public int swipeFromPct = 78;
    /** 上滑终点（屏幕高度百分比）。 */
    public int swipeToPct = 22;

    /** 广告标识消失（=广告播完）后是否上滑。 */
    public boolean swipeOnAdEnd = true;
    /** 广告标识持续存在时是否上滑（兼容"广告结束卡片需要手动上滑"的界面）。 */
    public boolean swipeOnPersist = true;

    /** 用户自定义的额外广告关键词。 */
    public List<String> customKeywords = Collections.emptyList();

    public static DetectorConfig defaults() {
        return new DetectorConfig();
    }

    public DetectorConfig copy() {
        DetectorConfig c = new DetectorConfig();
        c.checkIntervalMs = checkIntervalMs;
        c.cooldownMs = cooldownMs;
        c.persistSwipeMs = persistSwipeMs;
        c.minAdDurationMs = minAdDurationMs;
        c.endCardConfirmMs = endCardConfirmMs;
        c.escapeAfterMs = escapeAfterMs;
        c.escapeEnabled = escapeEnabled;
        c.postSwipeQuietMs = postSwipeQuietMs;
        c.maxSwipesPerAd = maxSwipesPerAd;
        c.stuckAdMs = stuckAdMs;
        c.swipeDurationMs = swipeDurationMs;
        c.swipeFromPct = swipeFromPct;
        c.swipeToPct = swipeToPct;
        c.swipeOnAdEnd = swipeOnAdEnd;
        c.swipeOnPersist = swipeOnPersist;
        c.customKeywords = new ArrayList<>(customKeywords);
        return c;
    }

    /** 参数指纹，用于判断是否需要重建状态机。 */
    public String signature() {
        return checkIntervalMs + "|" + cooldownMs + "|" + persistSwipeMs + "|" + minAdDurationMs + "|"
                + postSwipeQuietMs + "|" + maxSwipesPerAd + "|" + stuckAdMs + "|" + endCardConfirmMs + "|" + escapeAfterMs + "|" + escapeEnabled + "|"
                + swipeFromPct + "|" + swipeToPct + "|" + swipeDurationMs + "|"
                + swipeOnAdEnd + "|" + swipeOnPersist + "|" + customKeywords.hashCode();
    }

    /**
     * 把参数收进合理范围。最关键的一条：**起点必须明显高于终点**，
     * 否则手势方向会变成"往下滑"（红果会回到上一集）。
     */
    public void sanitize() {
        checkIntervalMs = clampRange(checkIntervalMs, 300, 5000);
        cooldownMs = clampRange(cooldownMs, 600, 20000);
        persistSwipeMs = clampRange(persistSwipeMs, 400, 8000);
        minAdDurationMs = clampRange(minAdDurationMs, 200, 20000);
        endCardConfirmMs = clampRange(endCardConfirmMs, 200, 3000);
        postSwipeQuietMs = clampRange(postSwipeQuietMs, 0, 30000);
        maxSwipesPerAd = clampRange(maxSwipesPerAd, 1, 10);
        stuckAdMs = clampRange(stuckAdMs, 5000, 180000);
        swipeDurationMs = clampRange(swipeDurationMs, 100, 2000);
        escapeAfterMs = clampRange(escapeAfterMs, 5000, 60000);

        swipeFromPct = clampRange(swipeFromPct, 30, 95);
        swipeToPct = clampRange(swipeToPct, 2, 70);
        if (swipeFromPct - swipeToPct < 15) {
            swipeToPct = Math.max(2, swipeFromPct - 15);
            if (swipeFromPct - swipeToPct < 15) {
                swipeFromPct = Math.min(95, swipeToPct + 15);
            }
        }
    }

    private static int clampRange(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}

