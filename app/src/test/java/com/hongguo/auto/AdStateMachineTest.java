package com.hongguo.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hongguo.auto.core.AdSignals;
import com.hongguo.auto.core.AdStateMachine;
import com.hongguo.auto.core.DetectorConfig;

import org.junit.Test;

/**
 * 状态机负责"什么时候上滑"，用假时钟验证各个分支。
 */
public class AdStateMachineTest {

    private static AdSignals ad(Integer countdown) {
        return new AdSignals(true, countdown != null, countdown != null, countdown,
                false, false, false, false, false, "");
    }

    private static AdSignals adWithSkip() {
        return new AdSignals(true, true, true, 5, true, false, false, false, false, "");
    }

    private static AdSignals normal() {
        return new AdSignals(false, false, false, null, false, false, true, false, false, "");
    }

    private static AdSignals prompt() {
        return new AdSignals(false, false, false, null, false, true, false, false, false, "");
    }

    @Test
    public void swipesWhenAdDisappears() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 60000;
        config.minAdDurationMs = 500;
        config.cooldownMs = 1000;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(5), 0).swipe);
        assertFalse(sm.onScan(ad(4), 700).swipe);
        assertTrue(sm.onScan(normal(), 1400).swipe);
    }

    @Test
    public void doesNotSwipeForBriefFlash() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 60000;
        config.minAdDurationMs = 800;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(5), 0).swipe);
        assertFalse(sm.onScan(normal(), 300).swipe);
    }

    @Test
    public void swipesWhenAdCardPersists() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 1200;
        config.cooldownMs = 2500;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(null), 0).swipe);
        assertFalse(sm.onScan(ad(null), 600).swipe);
        assertTrue(sm.onScan(ad(null), 1300).swipe);
    }

    @Test
    public void runningCountdownDelaysSwipe() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 1200;
        config.cooldownMs = 0;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(adWithSkip(), 0).swipe);
        assertFalse(sm.onScan(new AdSignals(true, true, true, 4, true, false, false, false, false, ""), 1000).swipe);
        assertFalse(sm.onScan(new AdSignals(true, true, true, 3, true, false, false, false, false, ""), 2000).swipe);
        // 倒计时停在 3 超过 1200ms，说明广告播放完毕、卡在结束卡片上
        assertTrue(sm.onScan(new AdSignals(true, true, true, 3, true, false, false, false, false, ""), 3300).swipe);
    }

    @Test
    public void continuePromptSwipesImmediately() {
        DetectorConfig config = DetectorConfig.defaults();
        config.cooldownMs = 0;
        AdStateMachine sm = new AdStateMachine(config);
        assertTrue(sm.onScan(prompt(), 0).swipe);
    }

    @Test
    public void cooldownSuppressesRepeatedSwipes() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 500;
        config.cooldownMs = 3000;
        config.postSwipeQuietMs = 200;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(null), 0).swipe);
        assertTrue(sm.onScan(ad(null), 700).swipe);
        assertFalse(sm.onScan(ad(null), 1200).swipe);
        assertFalse(sm.onScan(ad(null), 2000).swipe);
    }

    @Test
    public void maxSwipesPerAdIsHonoured() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 500;
        config.cooldownMs = 500;
        config.postSwipeQuietMs = 100;
        config.maxSwipesPerAd = 2;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(null), 0).swipe);
        assertTrue(sm.onScan(ad(null), 600).swipe);   // 第 1 次
        assertFalse(sm.onScan(ad(null), 1400).swipe); // 上滑后重新计时
        assertTrue(sm.onScan(ad(null), 1900).swipe);  // 第 2 次
        assertFalse(sm.onScan(ad(null), 2700).swipe); // 次数用尽
        assertFalse(sm.onScan(ad(null), 3500).swipe);
    }

    @Test
    public void normalPlaybackNeverSwipes() {
        DetectorConfig config = DetectorConfig.defaults();
        AdStateMachine sm = new AdStateMachine(config);
        for (long t = 0; t < 20000; t += 700) {
            assertFalse(sm.onScan(normal(), t).swipe);
        }
    }

    @Test
    public void resetClearsState() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 500;
        config.cooldownMs = 0;
        config.postSwipeQuietMs = 0;
        AdStateMachine sm = new AdStateMachine(config);
        assertFalse(sm.onScan(ad(null), 0).swipe);
        assertTrue(sm.onScan(ad(null), 600).swipe);
        sm.reset();
        assertFalse(sm.isInAd());
        // 重置后需要重新累计"广告标识持续时间"，不能立刻再滑
        assertFalse(sm.onScan(ad(null), 1000).swipe);
        assertTrue(sm.onScan(ad(null), 1600).swipe);
    }
}
