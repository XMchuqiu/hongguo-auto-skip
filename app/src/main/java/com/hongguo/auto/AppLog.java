package com.hongguo.auto;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * 环形日志，界面上可以查看/导出；同时写 logcat。
 */
public final class AppLog {

    private static final int MAX_LINES = 500;
    private static final ArrayDeque<String> LOGS = new ArrayDeque<>();
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private AppLog() {
    }

    public static synchronized void d(String tag, String message) {
        String line = SDF.format(new Date()) + " [" + tag + "] " + message;
        LOGS.addLast(line);
        while (LOGS.size() > MAX_LINES) {
            LOGS.removeFirst();
        }
        Log.d(tag, message);
    }

    public static synchronized String getLogs() {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = LOGS.iterator();
        while (it.hasNext()) {
            sb.append(it.next()).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        LOGS.clear();
    }
}
