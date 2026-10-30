package com.hongguo.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hongguo.auto.core.AdSignals;
import com.hongguo.auto.core.AdStateMachine;
import com.hongguo.auto.core.DetectorConfig;

import org.junit.Test;

/**
 * 状态机负责"什么时候点/上滑"，用假时钟验证各个分支。
 */
public class AdStateMachineTest {

    /** 广告角标 + 倒计时，没有跳过按钮。 */
    private static AdSignals ad(Integer countdown) {
        return new AdSignals(true, false, countdown != null, countdown,
                false, false, false, false, false, "");
    }

    /** 广告页：角标 + 倒计时 + 底部"上滑继续看短剧"（真实场景就是这样）。 */
    private static AdSignals adWithPrompt(Integer countdown) {
        return new AdSignals(true, false, countdown != null, countdown,
                true, true, false, true, false, "");
    }

    /** 广告结束卡片：没有倒计时，只有落地页按钮 + "上滑继续看短剧"。 */
    private static AdSignals endCard() {
        return new AdSignals(false, false, false, null, true, true, false, false, false, "");
    }

    private static AdSignals normal() {
        return new AdSignals(false, false, false, null, false, false, true, false, false, "");
    }

    private static AdSignals withSkipButton(int[] bounds) {
        return new AdSignals(true, true, false, bounds, true, 30, true, false, false, false, false, "");
    }

    @Test
    public void swipesWhenAdDisappears() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 60000;
        config.minAdDurationMs = 500;
        config.cooldownMs = 1000;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(5), 0).isAction());
        assertFalse(sm.onScan(ad(4), 700).isAction());
        assertTrue(sm.onScan(normal(), 1400).swipe);
    }

    @Test
    public void doesNotSwipeForBriefFlash() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 60000;
        config.minAdDurationMs = 800;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(5), 0).isAction());
        assertFalse(sm.onScan(normal(), 300).isAction());
    }

    @Test
    public void swipesWhenAdCardPersists() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 1200;
        config.cooldownMs = 2500;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(null), 0).isAction());
        assertFalse(sm.onScan(ad(null), 600).isAction());
        assertTrue(sm.onScan(ad(null), 1300).swipe);
    }

    @Test
    public void tickingCountdownNeverSwipesUntilZero() {
        DetectorConfig config = DetectorConfig.defaults();
        config.cooldownMs = 500;
        config.persistSwipeMs = 1200;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(21), 0).isAction());
        assertFalse(sm.onScan(ad(20), 1000).isAction());
        assertFalse(sm.onScan(ad(19), 2000).isAction());
        assertFalse(sm.onScan(ad(19), 3000).isAction());
        assertTrue(sm.onScan(ad(0), 4000).swipe);
    }

    @Test
    public void continuePromptDuringPlaybackDoesNotSwipe() {
        // 真实日志里的 bug：广告还剩 21s，但底部已经显示"上滑继续看短剧"
        DetectorConfig config = DetectorConfig.defaults();
        config.cooldownMs = 500;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(adWithPrompt(21), 0).isAction());
        assertFalse(sm.onScan(adWithPrompt(20), 1000).isAction());
        assertFalse(sm.onScan(adWithPrompt(19), 2000).isAction());
    }

    @Test
    public void continuePromptAfterCountdownGoneSwipes() {
        DetectorConfig config = DetectorConfig.defaults();
        config.cooldownMs = 500;
        config.endCardConfirmMs = 600;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(adWithPrompt(21), 0).isAction());
        assertFalse(sm.onScan(adWithPrompt(20), 1000).isAction());
        // 倒计时消失 = 广告播完切结束卡片，但要连续一段时间没有倒计时才动手
        assertFalse(sm.onScan(endCard(), 2200).isAction());
        assertTrue(sm.onScan(endCard(), 2900).swipe);
    }

    @Test
    public void endCardNeedsStableNoCountdown() {
        DetectorConfig config = DetectorConfig.defaults();
        config.cooldownMs = 0;
        config.endCardConfirmMs = 600;
        AdStateMachine sm = new AdStateMachine(config);
        assertFalse(sm.onScan(endCard(), 0).isAction());
        assertTrue(sm.onScan(endCard(), 700).swipe);
    }

    @Test
    public void escapesWhenAdNeverEnds() {
        DetectorConfig config = DetectorConfig.defaults();
        config.cooldownMs = 500;
        config.escapeAfterMs = 5000;
        config.escapeEnabled = true;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(60), 0).isAction());
        assertFalse(sm.onScan(ad(58), 2000).isAction());
        assertFalse(sm.onScan(ad(56), 4000).isAction());
        AdStateMachine.Action action = sm.onScan(ad(54), 6000);
        assertTrue(action.escape);
        assertFalse(action.swipe);
        // 一次广告只切一次后台
        assertFalse(sm.onScan(ad(52), 20000).escape);
    }

    @Test
    public void escapeCanBeDisabled() {
        DetectorConfig config = DetectorConfig.defaults();
        config.escapeAfterMs = 1000;
        config.escapeEnabled = false;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(60), 0).isAction());
        assertFalse(sm.onScan(ad(50), 5000).isAction());
        assertFalse(sm.onScan(ad(40), 9000).isAction());
    }

    @Test
    public void tapsSkipButtonBeforeSwiping() {
        DetectorConfig config = DetectorConfig.defaults();
        AdStateMachine sm = new AdStateMachine(config);

        AdStateMachine.Action action = sm.onScan(withSkipButton(new int[]{900, 60, 1050, 140}), 0);
        assertTrue(action.tap);
        assertFalse(action.swipe);
        assertEquals(900, action.bounds[0]);
        assertEquals(140, action.bounds[3]);

        // 静默期内不重复点
        assertFalse(sm.onScan(withSkipButton(new int[]{900, 60, 1050, 140}), 200).isAction());
        // 静默期后也不会重复点同一个按钮
        assertFalse(sm.onScan(withSkipButton(new int[]{900, 60, 1050, 140}), 6000).tap);
    }

    @Test
    public void swipeVariantEscalates() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 500;
        config.cooldownMs = 500;
        config.postSwipeQuietMs = 100;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(null), 0).isAction());
        AdStateMachine.Action a1 = sm.onScan(ad(null), 600);
        assertTrue(a1.swipe);
        assertEquals(0, a1.variant);

        assertFalse(sm.onScan(ad(null), 1400).isAction());
        AdStateMachine.Action a2 = sm.onScan(ad(null), 1900);
        assertTrue(a2.swipe);
        assertEquals(1, a2.variant);

        assertFalse(sm.onScan(ad(null), 2700).isAction());
        AdStateMachine.Action a3 = sm.onScan(ad(null), 3200);
        assertTrue(a3.swipe);
        assertEquals(2, a3.variant);
    }

    @Test
    public void cooldownSuppressesRepeatedSwipes() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 500;
        config.cooldownMs = 3000;
        config.postSwipeQuietMs = 200;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(null), 0).isAction());
        assertTrue(sm.onScan(ad(null), 700).swipe);
        assertFalse(sm.onScan(ad(null), 1200).isAction());
        assertFalse(sm.onScan(ad(null), 2000).isAction());
    }

    @Test
    public void maxSwipesPerAdIsHonoured() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 500;
        config.cooldownMs = 500;
        config.postSwipeQuietMs = 100;
        config.maxSwipesPerAd = 2;
        AdStateMachine sm = new AdStateMachine(config);

        assertFalse(sm.onScan(ad(null), 0).isAction());
        assertTrue(sm.onScan(ad(null), 600).swipe);   // 第 1 次
        assertFalse(sm.onScan(ad(null), 1400).isAction());
        assertTrue(sm.onScan(ad(null), 1900).swipe);  // 第 2 次
        assertFalse(sm.onScan(ad(null), 2700).isAction());
        assertFalse(sm.onScan(ad(null), 3500).isAction());
    }

    @Test
    public void normalPlaybackNeverSwipes() {
        DetectorConfig config = DetectorConfig.defaults();
        AdStateMachine sm = new AdStateMachine(config);
        for (long t = 0; t < 20000; t += 700) {
            assertFalse(sm.onScan(normal(), t).isAction());
        }
    }

    @Test
    public void resetClearsState() {
        DetectorConfig config = DetectorConfig.defaults();
        config.persistSwipeMs = 500;
        config.cooldownMs = 0;
        config.postSwipeQuietMs = 0;
        AdStateMachine sm = new AdStateMachine(config);
        assertFalse(sm.onScan(ad(null), 0).isAction());
        assertTrue(sm.onScan(ad(null), 600).swipe);
        sm.reset();
        assertFalse(sm.isInAd());
        assertFalse(sm.onScan(ad(null), 1000).isAction());
        assertTrue(sm.onScan(ad(null), 1600).swipe);
    }
}

