package com.hongguo.auto.core;

/**
 * 广告状态机：把"每次扫描的结果"翻译成"要不要点一下 / 上滑 / 切后台再回来"。
 *
 * <p>触发顺序（都受冷却、次数、滑后静默约束）：
 * <ol>
 *   <li>有明确的"跳过广告/关闭广告"按钮 → 直接点掉；</li>
 *   <li>倒计时归零 → 上滑；</li>
 *   <li>"上滑继续看短剧"且**连续一段时间**没有倒计时 → 广告结束卡片，上滑；</li>
 *   <li>广告角标持续存在且倒计时不再变化 → 上滑；</li>
 *   <li>广告长时间卡住 → 上滑兜底；</li>
 *   <li>广告播了很久、没有跳过按钮、怎么都过不去 → 切后台再回来（红果回到前台会丢掉广告）。</li>
 * </ol>
 */
public final class AdStateMachine {

    public static final class Action {
        public static final Action NONE = new Action(false, false, false, "", 0, null);

        public final boolean swipe;
        public final boolean tap;
        public final boolean escape;
        public final String reason;
        /** 上滑力度档位 0/1/2，越大越强力。 */
        public final int variant;
        /** 点击目标的屏幕坐标 [left, top, right, bottom]，仅 tap 时有值。 */
        public final int[] bounds;

        private Action(boolean swipe, boolean tap, boolean escape, String reason, int variant, int[] bounds) {
            this.swipe = swipe;
            this.tap = tap;
            this.escape = escape;
            this.reason = reason;
            this.variant = variant;
            this.bounds = bounds;
        }

        public boolean isAction() {
            return swipe || tap || escape;
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
    private boolean escaped;
    private long noCountdownSince = -1;
    private long lastActionMs = Long.MIN_VALUE / 4;
    private long quietUntilMs;
    private long lastAdSeenMs;
    private boolean lastHadLiveCountdown;
    private boolean everHadLiveCountdown;

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
        escaped = false;
        noCountdownSince = -1;
        lastActionMs = Long.MIN_VALUE / 4;
        quietUntilMs = 0;
        lastAdSeenMs = 0;
        lastHadLiveCountdown = false;
        everHadLiveCountdown = false;
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
            resetSession();
        }

        if (ad) {
            if (!inAd) {
                inAd = true;
                adStartMs = now;
                if (now - lastActionMs > 3000) {
                    resetSession();
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
            if (liveNow) {
                everHadLiveCountdown = true;
            }
            if (lastHadLiveCountdown && !liveNow) {
                attempts = 0;
                swipeVariant = 0;
                lastCountdownChangeMs = now;
            }
            lastHadLiveCountdown = liveNow;

            if (liveNow) {
                noCountdownSince = -1;
            } else if (noCountdownSince < 0) {
                noCountdownSince = now;
            }

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

            // 3) 结束卡片：有"上滑继续看短剧"，且连续一段时间没有倒计时
            //    （倒计时偶尔不渲染时不至于误判，等它稳定下来再滑）
            if (signals.continuePrompt && !liveNow && noCountdownSince >= 0
                    && now - noCountdownSince >= config.endCardConfirmMs && canAct(now)) {
                return doSwipe(now, "广告结束卡片（上滑继续）");
            }

            // 4) 广告角标持续存在且倒计时不再变化
            //    同样要求"没有倒计时"这件事稳定够久，否则广告浮层被裁到屏幕外时会误判
            // 这条广告出现过倒计时，就要求"没有倒计时"持续够久；从没出现过倒计时的静态广告卡片
            // 只需要 persistSwipeMs 即可
            int persistNeedMs = everHadLiveCountdown
                    ? Math.max(config.persistSwipeMs, config.endCardConfirmMs)
                    : config.persistSwipeMs;
            if (config.swipeOnPersist
                    && !liveNow
                    && now - adStartMs >= persistNeedMs
                    && now - lastCountdownChangeMs >= persistNeedMs
                    && canAct(now)) {
                return doSwipe(now, "广告标识持续 " + (now - adStartMs) + "ms 未变化");
            }

            // 5) 卡住太久，兜底
            if (now - adStartMs >= config.stuckAdMs
                    && now - lastCountdownChangeMs >= config.stuckAdMs
                    && canAct(now)) {
                return doSwipe(now, "广告卡住 " + (now - adStartMs) + "ms");
            }

            // 6) 长广告、没有跳过按钮、怎么都过不去 → 切后台再回来（用户在真机上验证有效）
            if (config.escapeEnabled && !escaped && liveNow
                    && now - adStartMs >= config.escapeAfterMs) {
                escaped = true;
                inAd = false;
                lastActionMs = now;
                quietUntilMs = now + config.postSwipeQuietMs;
                return new Action(false, false, true, "广告 " + ((now - adStartMs) / 1000) + "s 仍未结束，切后台再回来", 0, null);
            }
            return Action.NONE;
        }

        // 7) 广告特征消失
        if (inAd) {
            long duration = now - adStartMs;
            inAd = false;
            if (config.swipeOnAdEnd && duration >= config.minAdDurationMs && canAct(now)) {
                return doSwipe(now, "广告消失（持续 " + duration + "ms）");
            }
        }
        return Action.NONE;
    }

    private void resetSession() {
        attempts = 0;
        swipeVariant = 0;
        skipTapped = false;
        escaped = false;
        noCountdownSince = -1;
        everHadLiveCountdown = false;
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
        return new Action(true, false, false, reason, variant, null);
    }

    private Action doTap(long now, int[] bounds, String reason) {
        lastActionMs = now;
        quietUntilMs = now + config.postSwipeQuietMs;
        return new Action(false, true, false, reason, 0, bounds);
    }
}


