package com.hongguo.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.hongguo.auto.core.DetectorConfig;

import org.junit.Test;

/**
 * 参数自检：最要紧的是保证上滑方向永远"从下往上"。
 */
public class DetectorConfigTest {

    @Test
    public void crossingDirectionIsFixed() {
        DetectorConfig config = DetectorConfig.defaults();
        config.swipeFromPct = 40;
        config.swipeToPct = 44; // 用户把终点设到起点下方 → 会变成下滑、回到上一集
        config.sanitize();
        assertTrue(config.swipeFromPct - config.swipeToPct >= 15);
    }

    @Test
    public void equalValuesAreFixed() {
        DetectorConfig config = DetectorConfig.defaults();
        config.swipeFromPct = 50;
        config.swipeToPct = 50;
        config.sanitize();
        assertTrue(config.swipeFromPct - config.swipeToPct >= 15);
    }

    @Test
    public void valuesOutOfRangeAreClamped() {
        DetectorConfig config = DetectorConfig.defaults();
        config.swipeFromPct = 120;
        config.swipeToPct = -30;
        config.checkIntervalMs = 10;
        config.cooldownMs = 0;
        config.swipeDurationMs = 99999;
        config.sanitize();
        assertTrue(config.swipeFromPct <= 95);
        assertTrue(config.swipeToPct >= 2);
        assertTrue(config.swipeFromPct - config.swipeToPct >= 15);
        assertTrue(config.checkIntervalMs >= 300);
        assertTrue(config.cooldownMs >= 600);
        assertTrue(config.swipeDurationMs <= 2000);
    }

    @Test
    public void defaultsSurviveSanitize() {
        DetectorConfig config = DetectorConfig.defaults();
        config.sanitize();
        assertEquals(78, config.swipeFromPct);
        assertEquals(22, config.swipeToPct);
        assertEquals(700, config.checkIntervalMs);
    }
}
