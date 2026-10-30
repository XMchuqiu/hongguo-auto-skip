package com.hongguo.auto.core;

/**
 * 一次扫描得到的广告特征汇总。
 */
public final class AdSignals {

    /** 页面上出现"广告"角标。 */
    public final boolean adBadge;
    /** 出现"跳过广告"之类按钮。 */
    public final boolean skipButton;
    /** 出现倒计时（含"s/秒"单位）。 */
    public final boolean countdown;
    /** 倒计时读数（秒），可能为 null。 */
    public final Integer countdownValue;
    /** 落地页行为召唤词（立即下载/去游戏…）。 */
    public final boolean cta;
    /** "上滑继续观看"之类提示——代表广告已经播完，需要用户滑一下。 */
    public final boolean continuePrompt;
    /** 页面上有"第N集"这类正常播放标记。 */
    public final boolean episodeMarker;
    /** 控件 id 看起来像广告控件。 */
    public final boolean idHint;
    /** 红果免费漫剧的旧特征：集数控件（id=jer）存在，但文本里没有"集"。 */
    public final boolean legacyEmptyEpisodeCard;
    /** 给日志用的说明。 */
    public final String detail;

    public AdSignals(boolean adBadge, boolean skipButton, boolean countdown, Integer countdownValue,
                     boolean cta, boolean continuePrompt, boolean episodeMarker, boolean idHint,
                     boolean legacyEmptyEpisodeCard, String detail) {
        this.adBadge = adBadge;
        this.skipButton = skipButton;
        this.countdown = countdown;
        this.countdownValue = countdownValue;
        this.cta = cta;
        this.continuePrompt = continuePrompt;
        this.episodeMarker = episodeMarker;
        this.idHint = idHint;
        this.legacyEmptyEpisodeCard = legacyEmptyEpisodeCard;
        this.detail = detail == null ? "" : detail;
    }

    public static AdSignals none() {
        return new AdSignals(false, false, false, null, false, false, false, false, false, "");
    }

    /**
     * 是否处于广告界面。这里用结构化规则而不是加权打分，便于解释和测试：
     * 有广告角标 / 跳过按钮 / 继续观看提示，都算广告界面；
     * 只有倒计时时必须同时有落地页特征，避免把普通的秒数文本当成广告。
     */
    public boolean isAd() {
        if (continuePrompt) {
            return true;
        }
        if (adBadge) {
            return true;
        }
        if (skipButton) {
            return true;
        }
        if (legacyEmptyEpisodeCard) {
            return true;
        }
        if (countdown && (cta || idHint)) {
            return true;
        }
        return cta && idHint;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (adBadge) sb.append("广告角标 ");
        if (skipButton) sb.append("跳过按钮 ");
        if (countdown) sb.append("倒计时=").append(countdownValue).append("s ");
        if (cta) sb.append("落地页词 ");
        if (continuePrompt) sb.append("继续观看提示 ");
        if (episodeMarker) sb.append("第N集 ");
        if (idHint) sb.append("广告控件id ");
        if (legacyEmptyEpisodeCard) sb.append("空集数卡片 ");
        String s = sb.toString().trim();
        if (s.isEmpty()) {
            s = "无广告特征";
        }
        return detail.isEmpty() ? s : s + " (" + detail + ")";
    }
}
