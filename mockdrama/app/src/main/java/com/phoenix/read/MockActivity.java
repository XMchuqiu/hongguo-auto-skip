package com.phoenix.read;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 测试用的"假红果短剧"：包名故意用 com.phoenix.read，界面模仿广告页
 * （广告角标 + 倒计时 + 跳过广告 + 立即下载），并统计收到的上滑次数。
 */
public class MockActivity extends Activity {

    private static final String TAG = "MockDrama";

    private TextView countdownView;
    private TextView swipeView;
    private TextView episodeView;
    private int remaining = 5;
    private int swipeCount;
    private float downY = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            remaining--;
            if (remaining < 0) {
                remaining = 0;
            }
            countdownView.setText(remaining + "s");
            Log.i(TAG, "countdown=" + remaining);
            if (remaining > 0) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#101014"));

        // 广告角标（右上角）
        TextView badge = new TextView(this);
        badge.setText("广告");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        badge.setBackgroundColor(Color.parseColor("#cc8a1a1a"));
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        badgeLp.setMargins(0, dp(40), dp(16), 0);
        root.addView(badge, badgeLp);

        // 左上角：收到的上滑次数
        swipeView = new TextView(this);
        swipeView.setText("上滑次数: 0");
        swipeView.setTextColor(Color.parseColor("#00ff88"));
        swipeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        FrameLayout.LayoutParams swipeLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        swipeLp.setMargins(dp(16), dp(40), 0, 0);
        root.addView(swipeView, swipeLp);

        // 中间倒计时
        countdownView = new TextView(this);
        countdownView.setText("5s");
        countdownView.setTextColor(Color.WHITE);
        countdownView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        root.addView(countdownView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // 剧集标题（模拟"正常播放"时的集数文本，仅在正常页显示）
        episodeView = new TextView(this);
        episodeView.setText("第3集 测试剧集");
        episodeView.setTextColor(Color.WHITE);
        episodeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        episodeView.setVisibility(android.view.View.GONE);
        FrameLayout.LayoutParams episodeLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        episodeLp.setMargins(dp(16), 0, 0, dp(120));
        root.addView(episodeView, episodeLp);

        // 跳过广告
        Button skip = new Button(this);
        skip.setText("跳过广告");
        FrameLayout.LayoutParams skipLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        skipLp.setMargins(0, 0, dp(16), dp(40));
        root.addView(skip, skipLp);

        // 立即下载
        Button cta = new Button(this);
        cta.setText("立即下载");
        FrameLayout.LayoutParams ctaLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        ctaLp.setMargins(dp(16), 0, 0, dp(40));
        root.addView(cta, ctaLp);

        setContentView(root);
        handler.postDelayed(tick, 1000);
        Log.i(TAG, "mock ad page shown");
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
                if (downY >= 0 && event.getY() - downY < -dp(80)) {
                    swipeCount++;
                    swipeView.setText("上滑次数: " + swipeCount);
                    Log.i(TAG, "SWIPE_DETECTED count=" + swipeCount + " dy=" + (event.getY() - downY));
                }
                downY = -1;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
