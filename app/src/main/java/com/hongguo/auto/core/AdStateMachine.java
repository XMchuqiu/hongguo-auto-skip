package com.hongguo.auto.core;

/**
 * 广告状态机：把"每次扫描的结果"翻译成"要不要点一下 / 上滑"。
 *
 * <p>触发顺序（都受冷却、次数、滑后静默约束）：
 * <ol>
 *   <li>有明确的"跳过广告/关闭广告"按钮 → 直接点掉（最省事，也最像人工操作）；</li>
 *   <li>倒计时归零 → 上滑；</li>
 *   <li>出现"上滑继续看短剧"且页面上没有正在走的倒计时 → 说明到了广告结束卡片，上滑；</li>
 *   <li>广告角标持续存在且倒计时不再变化 → 上滑；</li>
 *   <li>广告长时间卡住 → 上滑兜底；</li>
 *   <li>之前处于广告界面，这次广告特征整体消失 → 上滑。</li>
 * </ol>
 *
 * <p>关键修正：旧逻辑把"上滑继续看短剧"直接当成广告结束，但红果在**广告播放期间**
 * 也会一直显示这行提示，于是提前上滑、白滑一次，广告照样播完。
 */
public final class AdStateMachine {

    public static final class Action {
        public static final Action NONE = new Action(false, false, "", 0, null);

        public final boolean swipe;
        public final boolean tap;
        public final String reason;
        /** 上滑力度档位 0/1/2，越大越强力（起点更高、终点更低、更慢）。 */
        public final int variant;
        /** 点击目标的屏幕坐标 [left, top, right, bottom]，仅 tap 时有值。 */
        public final int[] bounds;

        private Action(boolean swipe, boolean tap, String reason, int variant, int[] bounds) {
            this.swipe = swipe;
            this.tap = tap;
            this.reason = reason;
            this.variant = variant;
            this.bounds = bounds;
        }

        public boolean isAction() {
            return swipe || tap;
        }
    }

    private DetectorConfig config;

    private boolean inAd;
    private long adStartMs;
    private long lastCountdownChangeMs;
    private Integer lastCountdown;
    private int attempts;
    private int swipeVariant;
    private boolean skipTapped;
    private long lastActionMs = Long.MIN_VALUE / 4;
    private long quietUntilMs;
    private long lastAdSeenMs;
    private boolean lastHadLiveCountdown;

    public AdStateMachine(DetectorConfig config) {
        this.config = config == null ? DetectorConfig.defaults() : config;
    }

    public void setConfig(DetectorConfig config) {
        if (config != null) {
            this.config = config;
        }
    }

    public void reset() {
        inAd = false;
        adStartMs = 0;
        lastCountdownChangeMs = 0;
        lastCountdown = null;
        attempts = 0;
        swipeVariant = 0;
        skipTapped = false;
        lastActionMs = Long.MIN_VALUE / 4;
        quietUntilMs = 0;
        lastAdSeenMs = 0;
        lastHadLiveCountdown = false;
    }

    public boolean isInAd() {
        return inAd;
    }

    public Action onScan(AdSignals signals, long now) {
        if (signals == null) {
            return Action.NONE;
        }
        boolean ad = signals.isAd();
        if (ad) {
            lastAdSeenMs = now;
        }

        if (now < quietUntilMs) {
            return Action.NONE;
        }

        if (!ad && inAd && now - lastAdSeenMs > 3000) {
            inAd = false;
            attempts = 0;
            swipeVariant = 0;
            skipTapped = false;
        }

        if (ad) {
            if (!inAd) {
                inAd = true;
                adStartMs = now;
                if (now - lastActionMs > 3000) {
                    attempts = 0;
                    swipeVariant = 0;
                    skipTapped = false;
                }
                lastCountdownChangeMs = now;
                lastCountdown = signals.countdownValue;
            }

            if (signals.countdownValue != null && !signals.countdownValue.equals(lastCountdown)) {
                lastCountdown = signals.countdownValue;
                lastCountdownChangeMs = now;
            }

            // 倒计时从"有"变成"没有"=广告播完切到结束卡片，前面白滑过也重新给机会
            boolean liveNow = signals.hasLiveCountdown();
            if (lastHadLiveCountdown && !liveNow) {
                attempts = 0;
                swipeVariant = 0;
                lastCountdownChangeMs = now;
            }
            lastHadLiveCountdown = liveNow;

            // 1) 有明确的跳过/关闭按钮 → 点掉
            if (!skipTapped && signals.canTapSkip() && canAct(now)
                    && (signals.skipButton || signals.adBadge || signals.countdown || signals.cta)) {
                skipTapped = true;
                return doTap(now, signals.skipBounds, "点击跳过按钮");
            }

            // 2) 倒计时归零
            if (signals.countdownValue != null && signals.countdownValue == 0 && canAct(now)) {
                return doSwipe(now, "倒计时归零");
            }

            // 3) 广告结束卡片：有"上滑继续看短剧"，且没有正在走的倒计时
            if (signals.continuePrompt && !signals.hasLiveCountdown() && canAct(now)) {
                return doSwipe(now, "广告结束卡片（上滑继续）");
            }

            // 4) 广告标识持续存在且倒计时不再变化
            if (config.swipeOnPersist
                    && !signals.hasLiveCountdown()
                    && now - adStartMs >= config.persistSwipeMs
                    && now - lastCountdownChangeMs >= config.persistSwipeMs
                    && canAct(now)) {
                return doSwipe(now, "广告标识持续 " + (now - adStartMs) + "ms 未变化");
            }

            // 5) 卡住太久，兜底
            if (now - adStartMs >= config.stuckAdMs
                    && now - lastCountdownChangeMs >= config.stuckAdMs
                    && canAct(now)) {
                return doSwipe(now, "广告卡住 " + (now - adStartMs) + "ms");
            }
            return Action.NONE;
        }

        // 6) 广告特征消失
        if (inAd) {
            long duration = now - adStartMs;
            inAd = false;
            if (config.swipeOnAdEnd && duration >= config.minAdDurationMs && canAct(now)) {
                return doSwipe(now, "广告消失（持续 " + duration + "ms）");
            }
        }
        return Action.NONE;
    }

    private boolean canAct(long now) {
        return attempts < config.maxSwipesPerAd && now - lastActionMs >= config.cooldownMs;
    }

    private Action doSwipe(long now, String reason) {
        int variant = swipeVariant;
        if (swipeVariant < 2) {
            swipeVariant++;
        }
        attempts++;
        lastActionMs = now;
        quietUntilMs = now + config.postSwipeQuietMs;
        inAd = false;
        lastCountdown = null;
        return new Action(true, false, reason, variant, null);
    }

    private Action doTap(long now, int[] bounds, String reason) {
        lastActionMs = now;
        quietUntilMs = now + config.postSwipeQuietMs;
        return new Action(false, true, reason, 0, bounds);
    }
}
