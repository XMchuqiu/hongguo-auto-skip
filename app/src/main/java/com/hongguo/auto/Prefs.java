package com.hongguo.auto;

import android.content.Context;
import android.content.SharedPreferences;

import com.hongguo.auto.core.DetectorConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 参数读写。键名与原版保持一致，老用户升级后设置不会丢。
 */
public final class Prefs {

    public static final String NAME = "hongguo_prefs";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SWIPE_DURATION = "swipe_duration";
    public static final String KEY_SWIPE_FROM = "swipe_from_y";
    public static final String KEY_SWIPE_TO = "swipe_to_y";
    public static final String KEY_CHECK_INTERVAL = "check_interval";
    public static final String KEY_COOLDOWN = "cooldown";
    /** 广告标识持续多久后判定"播完/卡住"，然后上滑。 */
    public static final String KEY_PERSIST = "persist_ms";
    public static final String KEY_DEBUG = "debug_mode";
    public static final String KEY_KEYWORDS = "custom_keywords";
    public static final String KEY_APP_SHORT = "app_short";
    public static final String KEY_APP_COMIC = "app_comic";

    public static final int DEFAULT_SWIPE_DURATION = 450;
    public static final int DEFAULT_SWIPE_FROM = 78;
    public static final int DEFAULT_SWIPE_TO = 22;
    public static final int DEFAULT_CHECK_INTERVAL = 700;
    public static final int DEFAULT_COOLDOWN = 2500;
    public static final int DEFAULT_PERSIST = 1200;

    private Prefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static DetectorConfig readConfig(Context context, SharedPreferences prefs) {
        DetectorConfig config = DetectorConfig.defaults();
        config.checkIntervalMs = clamp(prefs.getInt(KEY_CHECK_INTERVAL, DEFAULT_CHECK_INTERVAL), 100, 5000);
        config.cooldownMs = clamp(prefs.getInt(KEY_COOLDOWN, DEFAULT_COOLDOWN), 500, 15000);
        config.persistSwipeMs = clamp(prefs.getInt(KEY_PERSIST, DEFAULT_PERSIST), 300, 8000);
        config.swipeDurationMs = clamp(prefs.getInt(KEY_SWIPE_DURATION, DEFAULT_SWIPE_DURATION), 100, 1500);
        config.swipeFromPct = clamp(prefs.getInt(KEY_SWIPE_FROM, DEFAULT_SWIPE_FROM), 20, 95);
        config.swipeToPct = clamp(prefs.getInt(KEY_SWIPE_TO, DEFAULT_SWIPE_TO), 5, 60);
        config.customKeywords = splitKeywords(prefs.getString(KEY_KEYWORDS, ""));
        return config;
    }

    public static boolean isAppEnabled(SharedPreferences prefs, String pkg) {
        if (HongguoAccessibilityService.PKG_COMIC.equals(pkg)) {
            return prefs.getBoolean(KEY_APP_COMIC, true);
        }
        return prefs.getBoolean(KEY_APP_SHORT, true);
    }

    public static List<String> splitKeywords(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    public static void save(SharedPreferences prefs, int swipeDuration, int fromPct, int toPct,
                            int checkInterval, int cooldown, int persist) {
        prefs.edit()
                .putInt(KEY_SWIPE_DURATION, swipeDuration)
                .putInt(KEY_SWIPE_FROM, fromPct)
                .putInt(KEY_SWIPE_TO, toPct)
                .putInt(KEY_CHECK_INTERVAL, checkInterval)
                .putInt(KEY_COOLDOWN, cooldown)
                .putInt(KEY_PERSIST, persist)
                .apply();
    }

    public static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
