package com.hongguo.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hongguo.auto.core.AdDetector;
import com.hongguo.auto.core.AdSignals;
import com.hongguo.auto.core.DetectorConfig;
import com.hongguo.auto.core.UiNode;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 用合成节点树验证两个 App 的广告识别规则。
 */
public class AdDetectorTest {

    private static AdSignals scan(boolean legacyComicRule, UiNode... roots) {
        return AdDetector.scan(Arrays.asList(roots), DetectorConfig.defaults(), legacyComicRule);
    }

    private static UiNode hidden(String text, String desc, String id) {
        return new UiNode(text, desc, id, "android.widget.TextView", 0, 0, 0, 0, null);
    }

    private static UiNode box(String text, String desc, String id, int l, int t, int r, int b) {
        return new UiNode(text, desc, id, "android.widget.TextView", l, t, r, b, null);
    }

    // ---------- 红果免费漫剧：旧判据 ----------

    @Test
    public void comicEmptyEpisodeCardIsAd() {
        UiNode card = UiNode.group(UiNode.leaf("", null, "com.kylin.read:id/jer"));
        AdSignals signals = scan(true, card);
        assertTrue(signals.legacyEmptyEpisodeCard);
        assertTrue(signals.isAd());
    }

    @Test
    public void comicEpisodeCardWithTextIsNormal() {
        UiNode page = UiNode.group(UiNode.leaf("第12集", null, "com.kylin.read:id/jer"));
        AdSignals signals = scan(true, page);
        assertFalse(signals.legacyEmptyEpisodeCard);
        assertFalse(signals.isAd());
        assertTrue(signals.episodeMarker);
    }

    @Test
    public void legacyRuleOffMeansNoAd() {
        UiNode card = UiNode.group(UiNode.leaf("", null, "com.kylin.read:id/jer"));
        assertFalse(scan(false, card).isAd());
    }

    @Test
    public void comicAdCardStillDetectedWhenLongTitleVisible() {
        // 广告卡片上仍显示长剧集标题时，不能被"第N集"文本否决
        UiNode page = UiNode.group(
                UiNode.leaf("第12集 总裁的替身新娘", null, null),
                UiNode.leaf("", null, "com.kylin.read:id/jer"));
        AdSignals signals = scan(true, page);
        assertTrue(signals.episodeMarker);
        assertTrue(signals.legacyEmptyEpisodeCard);
        assertTrue(signals.isAd());
    }

    @Test
    public void comicEpisodeIdWithTextIsNormalEvenWithEmptyOtherNodes() {
        UiNode page = UiNode.group(
                UiNode.leaf("", null, null),
                UiNode.leaf("第12集", null, "com.kylin.read:id/jer"));
        assertFalse(scan(true, page).legacyEmptyEpisodeCard);
        assertFalse(scan(true, page).isAd());
    }

    // ---------- 红果免费短剧：广告页 ----------

    @Test
    public void shortDramaAdBadgeWithCountdown() {
        UiNode ad = UiNode.group(
                UiNode.leaf("广告", null, null),
                UiNode.leaf("5s", null, null),
                UiNode.leaf("跳过广告", null, null),
                UiNode.leaf("立即下载", null, null));
        AdSignals signals = scan(false, ad);
        assertTrue(signals.isAd());
        assertTrue(signals.adBadge);
        assertTrue(signals.skipButton);
        assertTrue(signals.countdown);
        assertEquals(Integer.valueOf(5), signals.countdownValue);
    }

    @Test
    public void gameEndCardIsAd() {
        // 短剧广告结束卡片：点击进入游戏 + 原始征途 + 上滑继续看短剧
        UiNode card = UiNode.group(
                UiNode.leaf("点击进入游戏", null, null),
                UiNode.leaf("MMO | 第10名", null, null),
                UiNode.leaf("原始征途", null, null),
                UiNode.leaf("原汁原味，原始征途！", null, null),
                UiNode.leaf("上滑继续看短剧", null, null));
        AdSignals signals = scan(false, card);
        assertTrue(signals.continuePrompt);
        assertTrue(signals.cta);
        assertTrue(signals.isAd());
        assertFalse(signals.hasLiveCountdown());
    }

    @Test
    public void comicEndCardWithOfficialDownload() {
        // 漫剧广告结束卡片：广告角标 + 汽水音乐 + 商家推荐/精彩应用 + 官方下载 + 上滑继续观看短剧
        UiNode card = UiNode.group(
                UiNode.leaf("第25集", null, null),
                UiNode.leaf("广告 | ⋯", null, null),
                UiNode.leaf("汽水音乐", null, null),
                UiNode.leaf("商家推荐", null, null),
                UiNode.leaf("精彩应用", null, null),
                UiNode.leaf("官方下载", null, null),
                UiNode.leaf("上滑继续观看短剧", null, null));
        AdSignals signals = scan(true, card);
        assertTrue(signals.adBadge);
        assertTrue(signals.cta);
        assertTrue(signals.continuePrompt);
        assertTrue(signals.isAd());
        assertFalse(signals.hasLiveCountdown());
    }

    @Test
    public void countdownWithoutUnitNeedsBadge() {
        AdSignals withBadge = scan(false, UiNode.group(
                UiNode.leaf("广告", null, null),
                UiNode.leaf("12", null, null)));
        assertTrue(withBadge.countdown);
        assertEquals(Integer.valueOf(12), withBadge.countdownValue);

        AdSignals withoutBadge = scan(false, UiNode.group(UiNode.leaf("12", null, null)));
        assertFalse(withoutBadge.countdown);
        assertFalse(withoutBadge.isAd());
    }

    @Test
    public void adIdWithCountdownIsAd() {
        UiNode ad = UiNode.group(new UiNode("5s", null, "com.phoenix.read:id/tv_ad_countdown",
                "android.widget.TextView", 900, 60, 1050, 120, null));
        assertTrue(scan(false, ad).isAd());
    }

    @Test
    public void normalEpisodePageIsNotAd() {
        UiNode page = UiNode.group(
                UiNode.leaf("第12集 大结局", null, null),
                UiNode.leaf("红果免费短剧", null, null),
                UiNode.leaf("共80集", null, null));
        AdSignals signals = scan(false, page);
        assertFalse(signals.isAd());
        assertTrue(signals.episodeMarker);
    }

    @Test
    public void promoButtonAloneIsNotAd() {
        // 正文里出现"立即下载"只是推广位，不能因此上滑
        UiNode page = UiNode.group(
                UiNode.leaf("第3集 夫妻的较量", null, null),
                UiNode.leaf("立即下载", null, null));
        AdSignals signals = scan(false, page);
        assertTrue(signals.cta);
        assertFalse(signals.isAd());
    }

    @Test
    public void genericSkipAloneIsNotAd() {
        // "跳过"单独出现（例如播放器的跳过片头）不能当广告
        AdSignals intro = scan(false, UiNode.group(
                UiNode.leaf("第3集", null, null),
                UiNode.leaf("跳过片头", null, null)));
        assertFalse(intro.genericSkip);
        assertFalse(intro.isAd());
    }

    @Test
    public void skipButtonRecordsBounds() {
        UiNode ad = UiNode.group(
                UiNode.leaf("广告", null, null),
                box("跳过广告", null, null, 900, 60, 1050, 140));
        AdSignals signals = scan(false, ad);
        assertTrue(signals.skipButton);
        assertTrue(signals.canTapSkip());
        assertEquals(900, signals.skipBounds[0]);
        assertEquals(140, signals.skipBounds[3]);
    }

    @Test
    public void continuePromptIsAd() {
        AdSignals signals = scan(false, UiNode.group(UiNode.leaf("上滑继续观看", null, null)));
        assertTrue(signals.continuePrompt);
        assertTrue(signals.isAd());
    }

    @Test
    public void adBadgeInContentDescription() {
        UiNode node = new UiNode(null, "广告", null, "android.widget.FrameLayout",
                0, 0, 1080, 2400, null);
        assertTrue(scan(false, node).adBadge);
    }

    @Test
    public void invisibleNodesAreIgnored() {
        assertFalse(scan(false, UiNode.group(hidden("广告", null, null))).isAd());
    }

    @Test
    public void multipleWindowsAreScanned() {
        List<UiNode> roots = Arrays.asList(
                UiNode.group(UiNode.leaf("第3集", null, null)),
                UiNode.group(UiNode.leaf("广告", null, null), UiNode.leaf("5s", null, null)));
        AdSignals signals = AdDetector.scan(roots, DetectorConfig.defaults(), false);
        assertTrue(signals.isAd());
    }

    @Test
    public void customKeywordActsAsStrongSignal() {
        DetectorConfig config = DetectorConfig.defaults();
        config.customKeywords = Collections.singletonList("推广内容");
        AdSignals signals = AdDetector.scan(
                Collections.singletonList(UiNode.group(UiNode.leaf("推广内容", null, null))), config, false);
        assertTrue(signals.isAd());
    }

    @Test
    public void countdownParsing() {
        assertEquals(Integer.valueOf(5), AdDetector.parseCountdown("5s"));
        assertEquals(Integer.valueOf(5), AdDetector.parseCountdown("05秒"));
        assertEquals(Integer.valueOf(3), AdDetector.parseCountdown("剩余3s"));
        assertEquals(Integer.valueOf(8), AdDetector.parseCountdown("广告剩余：8"));
        assertNull(AdDetector.parseCountdown("剩余 12 集"));
        assertNull(AdDetector.parseCountdown("第5集"));
        assertNull(AdDetector.parseCountdown("120"));
        assertNull(AdDetector.parseCountdown(""));
        assertNull(AdDetector.parseCountdown(null));
    }

    @Test
    public void episodeTextVariants() {
        assertTrue(AdDetector.isEpisodeText("第1集"));
        assertTrue(AdDetector.isEpisodeText("第 12 集"));
        assertTrue(AdDetector.isEpisodeText("第二十集"));
        assertTrue(AdDetector.isEpisodeText("全80集"));
        assertTrue(AdDetector.isEpisodeText("更新至第5集"));
        assertFalse(AdDetector.isEpisodeText("广告"));
        assertFalse(AdDetector.isEpisodeText("这是一段很长很长的剧集简介文字内容"));
    }
}
