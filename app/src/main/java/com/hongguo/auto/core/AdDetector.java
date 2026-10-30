package com.hongguo.auto.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 广告特征识别。纯逻辑，不依赖 Android，可单元测试。
 */
public final class AdDetector {

    /** 广告落地页的行为召唤词。 */
    public static final String[] CTA_KEYWORDS = {
            "立即下载", "极速下载", "点击下载", "下载游戏", "下载应用", "立即安装", "安装游戏",
            "去游戏", "点击进入游戏", "进入游戏", "进入直播间", "了解详情", "查看详情",
            "领取奖励", "看视频得", "商家推荐", "精彩应用", "广告主", "立即体验", "打开应用"
    };

    /** 需要用户滑一下才能继续的信号。 */
    public static final String[] CONTINUE_PROMPTS = {
            "上滑继续", "继续观看", "滑动继续", "上滑查看", "上滑看下一集", "继续播放下一集", "点击继续"
    };

    /** 跳过广告按钮。 */
    public static final String[] SKIP_KEYWORDS = {"跳过广告", "跳过此广告", "关闭广告", "跳过"};

    private static final String[] AD_BADGE_EXACT = {"广告", "广告中", "广告位", "赞助", "推广"};

    private static final Pattern EPISODE =
            Pattern.compile("第\\s*[0-9０-９一二三四五六七八九十百千零两]{1,6}\\s*集");
    private static final Pattern EPISODE_TOTAL =
            Pattern.compile("(全|共|更新至|已更新)\\s*[0-9]{1,4}\\s*集");
    private static final Pattern COUNTDOWN_UNIT =
            Pattern.compile("^(\\d{1,3})\\s*(s|S|秒|″)$");
    private static final Pattern COUNTDOWN_PHRASE =
            Pattern.compile("(?:剩余|倒计时|广告剩余|还剩)\\s*[:：]?\\s*(\\d{1,3})\\s*(?:s|S|秒)?");
    private static final Pattern BARE_NUMBER = Pattern.compile("^(\\d{1,2})$");

    private AdDetector() {
    }

    /**
     * 扫描若干窗口根节点。
     *
     * @param roots           根节点列表（活动窗口 + 各悬浮窗）
     * @param config          参数
     * @param legacyComicRule 是否启用红果免费漫剧的旧特征（集数控件 id=jer 文本为空）
     */
    public static AdSignals scan(List<UiNode> roots, DetectorConfig config, boolean legacyComicRule) {
        Collector collector = new Collector(config, legacyComicRule);
        if (roots != null) {
            for (int i = 0; i < roots.size(); i++) {
                collector.visit(roots.get(i), 0);
            }
        }
        return collector.build();
    }

    public static boolean isEpisodeText(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty() || t.length() > 20) {
            return false;
        }
        return EPISODE.matcher(t).find() || EPISODE_TOTAL.matcher(t).find();
    }

    public static boolean isAdBadge(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return false;
        }
        for (String badge : AD_BADGE_EXACT) {
            if (t.equals(badge)) {
                return true;
            }
        }
        // "广告 5s"、"广告|跳过" 这类短标签
        return t.startsWith("广告") && t.length() <= 10;
    }

    public static Integer parseCountdown(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty() || t.length() > 14) {
            return null;
        }
        Matcher m = COUNTDOWN_UNIT.matcher(t);
        if (m.matches()) {
            return toInt(m.group(1));
        }
        m = COUNTDOWN_PHRASE.matcher(t);
        if (m.find()) {
            // "剩余 12 集" 这种不是倒计时
            if (m.end() < t.length() && (t.charAt(m.end()) == '集' || t.charAt(m.end()) == '话')) {
                return null;
            }
            return toInt(m.group(1));
        }
        return null;
    }

    public static boolean containsAny(String text, String[] keywords) {
        if (text == null || text.isEmpty() || keywords == null) {
            return false;
        }
        for (String k : keywords) {
            if (k != null && !k.isEmpty() && text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    static boolean idLooksLikeAd(String lowerId) {
        if (lowerId == null || lowerId.isEmpty()) {
            return false;
        }
        return lowerId.contains("/ad") || lowerId.contains("ad_") || lowerId.contains("_ad")
                || lowerId.contains("advert") || lowerId.contains("countdown")
                || lowerId.contains("skip") || lowerId.contains("adtime");
    }

    private static Integer toInt(String s) {
        try {
            return Integer.valueOf(Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static final class Collector {
        private final boolean legacyComicRule;
        private final String[] customKeywords;

        private boolean adBadge;
        private boolean customHit;
        private boolean skip;
        private boolean countdown;
        private Integer countdownValue;
        private boolean ctaSeen;
        private boolean continuePrompt;
        private boolean episode;
        private boolean idHint;
        private boolean jerFound;
        private String jerText = "";
        private String detail = "";
        private final List<Integer> bareNumbers = new ArrayList<>();
        private int visited;

        Collector(DetectorConfig config, boolean legacyComicRule) {
            this.legacyComicRule = legacyComicRule;
            List<String> keywords = new ArrayList<>();
            if (config != null && config.customKeywords != null) {
                for (String k : config.customKeywords) {
                    if (k != null && !k.trim().isEmpty()) {
                        keywords.add(k.trim());
                    }
                }
            }
            this.customKeywords = keywords.toArray(new String[0]);
        }

        void visit(UiNode node, int depth) {
            if (node == null || depth > 60 || visited > 4000) {
                return;
            }
            visited++;
            if (node.isVisible()) {
                examine(node);
            }
            for (int i = 0; i < node.children.size(); i++) {
                visit(node.children.get(i), depth + 1);
            }
        }

        private void examine(UiNode node) {
            String text = trim(node.text);
            String desc = trim(node.desc);
            String id = node.viewId == null ? "" : node.viewId.toLowerCase(Locale.ROOT);

            if (isAdBadge(text) || isAdBadge(desc)) {
                adBadge = true;
                note("角标=" + (text.isEmpty() ? desc : text));
            }
            if (containsAny(text, SKIP_KEYWORDS) || containsAny(desc, SKIP_KEYWORDS)) {
                skip = true;
                note("跳过=" + (text.isEmpty() ? desc : text));
            }
            if (customKeywords.length > 0) {
                if (containsAny(text, customKeywords) || containsAny(desc, customKeywords)) {
                    customHit = true;
                    note("自定义词=" + (text.isEmpty() ? desc : text));
                }
            }
            Integer countdown = parseCountdown(text);
            if (countdown == null) {
                countdown = parseCountdown(desc);
            }
            if (countdown != null) {
                this.countdown = true;
                this.countdownValue = countdown;
            }
            Integer bare = parseBareNumber(text);
            if (bare != null) {
                bareNumbers.add(bare);
            }
            if (containsAny(text, CTA_KEYWORDS) || containsAny(desc, CTA_KEYWORDS)) {
                ctaSeen = true;
            }
            if (containsAny(text, CONTINUE_PROMPTS) || containsAny(desc, CONTINUE_PROMPTS)) {
                continuePrompt = true;
                note("继续提示=" + (text.isEmpty() ? desc : text));
            }
            if (isEpisodeText(text)) {
                episode = true;
            }
            if (idLooksLikeAd(id)) {
                idHint = true;
            }
            if (legacyComicRule && "jer".equals(node.idName())) {
                jerFound = true;
                jerText = text;
            }
        }

        private Integer parseBareNumber(String s) {
            if (s == null) {
                return null;
            }
            Matcher m = BARE_NUMBER.matcher(s.trim());
            if (m.matches()) {
                return toInt(m.group(1));
            }
            return null;
        }

        private void note(String s) {
            if (detail.length() < 120) {
                if (detail.length() > 0) {
                    detail = detail + ",";
                }
                detail = detail + s;
            }
        }

        AdSignals build() {
            boolean countdownSeen = countdown;
            Integer value = countdownValue;
            // 只有裸数字时，必须有角标/跳过按钮佐证才认作倒计时
            if (!countdownSeen && !bareNumbers.isEmpty() && (adBadge || skip)) {
                countdownSeen = true;
                value = bareNumbers.get(0);
            }
            // 与旧版语义保持一致：max 剧的集数控件存在、但文本里没有"集"→ 广告卡片。
            // 这里刻意不看页面上其它"第N集"文本，否则会漏掉带长标题的广告页。
            boolean legacyCard = legacyComicRule && jerFound && !jerText.contains("集");
            // 自定义关键词=用户明确认为这是广告，按强信号处理
            boolean badge = adBadge || customHit;
            return new AdSignals(badge, skip, countdownSeen, value, ctaSeen, continuePrompt,
                    episode, idHint, legacyCard, detail);
        }
    }
}
