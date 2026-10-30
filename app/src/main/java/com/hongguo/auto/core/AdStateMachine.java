package com.hongguo.auto.core;

/**
 * 广告状态机：把"每次扫描的结果"翻译成"要不要上滑"。
 *
 * <p>设计目的：原来那版只要看到广告特征就立刻上滑，既容易在广告还在播时误触，也无法判断
 * "广告播完了"。这里改成三个明确的触发条件：
 * <ol>
 *   <li>出现"上滑继续观看"提示 —— 立即上滑；</li>
 *   <li>广告角标/跳过按钮持续 N 毫秒且倒计时不再变化（广告播完后的结束卡片）—— 上滑；</li>
 *   <li>之前处于广告界面，这次扫描广告特征消失（广告播完自动退出）—— 上滑。</li>
 * </ol>
 * 每次都受冷却时间、单段广告最大上滑次数、上滑后静默期的约束。
 */
public final class AdStateMachine {

    public static final class Action {
        public static final Action NONE = new Action(false, "");

        public final boolean swipe;
        public final String reason;

        private Action(boolean swipe, String reason) {
            this.swipe = swipe;
            this.reason = reason;
        }

        static Action swipe(String reason) {
            return new Action(true, reason);
        }
    }

    private DetectorConfig config;

    private boolean inAd;
    private long adStartMs;
    private long lastCountdownChangeMs;
    private Integer lastCountdown;
    private int attempts;
    private long lastSwipeMs = Long.MIN_VALUE / 4;
    private long quietUntilMs;
    private long lastAdSeenMs;

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
        lastSwipeMs = Long.MIN_VALUE / 4;
        quietUntilMs = 0;
        lastAdSeenMs = 0;
    }

    /** 当前是否处于"广告中"状态（给界面显示用）。 */
    public boolean isInAd() {
        return inAd;
    }

    /**
     * @param signals 本次扫描结果
     * @param now     单调时钟（毫秒）
     */
    public Action onScan(AdSignals signals, long now) {
        if (signals == null) {
            return Action.NONE;
        }
        boolean ad = signals.isAd();
        if (ad) {
            lastAdSeenMs = now;
        }

        // 上滑后的静默期内什么都不做，避免一次广告连滑多次
        if (now < quietUntilMs) {
            return Action.NONE;
        }

        // 广告已经消失超过 3 秒，视为这段广告结束，重置这轮状态
        if (!ad && inAd && now - lastAdSeenMs > 3000) {
            inAd = false;
            attempts = 0;
        }

        // 1) "上滑继续观看"——最明确的"广告播完了"
        if (signals.continuePrompt && canSwipe(now)) {
            return doSwipe(now, "出现继续观看提示");
        }

        if (ad) {
            if (!inAd) {
                inAd = true;
                adStartMs = now;
                // 刚上滑完又检测到广告时不重置次数，避免同一条广告被反复上滑
                if (now - lastSwipeMs > 3000) {
                    attempts = 0;
                }
                lastCountdownChangeMs = now;
                lastCountdown = signals.countdownValue;
            }
            if (signals.countdownValue != null && !signals.countdownValue.equals(lastCountdown)) {
                lastCountdown = signals.countdownValue;
                lastCountdownChangeMs = now;
            }

            // 倒计时归零
            if (signals.countdownValue != null && signals.countdownValue == 0 && canSwipe(now)) {
                return doSwipe(now, "倒计时归零");
            }

            // 2) 广告标识持续存在且倒计时不再变化（结束卡片）
            if (config.swipeOnPersist
                    && now - adStartMs >= config.persistSwipeMs
                    && now - lastCountdownChangeMs >= config.persistSwipeMs
                    && canSwipe(now)) {
                return doSwipe(now, "广告标识持续 " + (now - adStartMs) + "ms 未变化");
            }

            // 3) 卡住太久，兜底
            if (now - adStartMs >= config.stuckAdMs
                    && now - lastCountdownChangeMs >= config.stuckAdMs
                    && canSwipe(now)) {
                return doSwipe(now, "广告卡住 " + (now - adStartMs) + "ms");
            }
            return Action.NONE;
        }

        // 4) 广告特征消失——广告播完，自动回到短剧
        if (inAd) {
            long duration = now - adStartMs;
            inAd = false;
            if (config.swipeOnAdEnd && duration >= config.minAdDurationMs && canSwipe(now)) {
                return doSwipe(now, "广告结束（持续 " + duration + "ms）");
            }
        }
        return Action.NONE;
    }

    private boolean canSwipe(long now) {
        return attempts < config.maxSwipesPerAd && now - lastSwipeMs >= config.cooldownMs;
    }

    private Action doSwipe(long now, String reason) {
        attempts++;
        lastSwipeMs = now;
        quietUntilMs = now + config.postSwipeQuietMs;
        inAd = false;
        lastCountdown = null;
        return Action.swipe(reason);
    }
}
