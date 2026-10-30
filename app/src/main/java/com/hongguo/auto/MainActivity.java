package com.hongguo.auto;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.hongguo.auto.core.AdDetector;
import com.hongguo.auto.core.DetectorConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATION = 1001;
    private static final String LOG_FOLDER = "红果自动跳过";

    private SharedPreferences prefs;
    private TextView statusText;
    private TextView logText;
    private TextView keywordListText;
    private TextView keywordHint;
    private Switch enableSwitch;
    private Switch appShortSwitch;
    private Switch appComicSwitch;
    private Switch debugSwitch;
    private EditText keywordInput;
    private SeekBar seekSwipeDuration;
    private SeekBar seekSwipeFrom;
    private SeekBar seekSwipeTo;
    private SeekBar seekCheckInterval;
    private SeekBar seekCooldown;
    private SeekBar seekPersist;
    private TextView labelSwipeDuration;
    private TextView labelSwipeFrom;
    private TextView labelSwipeTo;
    private TextView labelCheckInterval;
    private TextView labelCooldown;
    private TextView labelPersist;
    private final Handler uiHandler = new Handler();
    private boolean wasAccessibilityEnabled;
    private Uri lastExportUri;
    private String lastExportPath = "";
    private long logTouchPauseUntil;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            if (isLogAutoRefreshEnabled()) {
                updateLogText();
            }
            uiHandler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);

        statusText = findViewById(R.id.status_text);
        logText = findViewById(R.id.log_text);
        keywordListText = findViewById(R.id.keyword_list_text);
        keywordHint = findViewById(R.id.keyword_hint);
        enableSwitch = findViewById(R.id.enable_switch);
        appShortSwitch = findViewById(R.id.app_short_switch);
        appComicSwitch = findViewById(R.id.app_comic_switch);
        debugSwitch = findViewById(R.id.debug_switch);
        keywordInput = findViewById(R.id.keyword_input);

        seekSwipeDuration = findViewById(R.id.seek_swipe_duration);
        seekSwipeFrom = findViewById(R.id.seek_swipe_from);
        seekSwipeTo = findViewById(R.id.seek_swipe_to);
        seekCheckInterval = findViewById(R.id.seek_check_interval);
        seekCooldown = findViewById(R.id.seek_cooldown);
        seekPersist = findViewById(R.id.seek_persist);
        labelSwipeDuration = findViewById(R.id.label_swipe_duration);
        labelSwipeFrom = findViewById(R.id.label_swipe_from);
        labelSwipeTo = findViewById(R.id.label_swipe_to);
        labelCheckInterval = findViewById(R.id.label_check_interval);
        labelCooldown = findViewById(R.id.label_cooldown);
        labelPersist = findViewById(R.id.label_persist);

        requestNotificationPermission();
        setupLogScroll();
        setupButtons();
        setupSwitches();

        setupSeekBars();
        loadPrefsToUi();
        updateKeywordList();
        updateLogText();
        refreshStatus();
        uiHandler.postDelayed(refreshRunnable, 1000);
    }

    /** 日志区域自己吃掉滑动手势，避免被外层 ScrollView 抢走，从而实现手动上下翻阅。 */
    private void setupLogScroll() {
        ScrollView logScroll = findViewById(R.id.log_scroll);
        if (logScroll != null) {
            logScroll.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    // 手指在日志上时暂停自动刷新，否则刷新会把阅读位置顶回去
                    logTouchPauseUntil = System.currentTimeMillis() + 8000;
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return false;
                }
            });
        }
    }

    private void setupButtons() {
        findViewById(R.id.open_accessibility_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wasAccessibilityEnabled = isAccessibilityEnabled();
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    toast("无法打开无障碍设置: " + t);
                }
            }
        });

        findViewById(R.id.reset_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.save(prefs, Prefs.DEFAULT_SWIPE_DURATION, Prefs.DEFAULT_SWIPE_FROM, Prefs.DEFAULT_SWIPE_TO,
                        Prefs.DEFAULT_CHECK_INTERVAL, Prefs.DEFAULT_COOLDOWN, Prefs.DEFAULT_PERSIST);
                loadPrefsToUi();
                toast("已恢复默认参数");
            }
        });

        findViewById(R.id.refresh_log_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateLogText();
                toast("已刷新");
            }
        });
        findViewById(R.id.clear_log_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLog.clear();
                updateLogText();
            }
        });
        findViewById(R.id.export_log_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLogs(true);
            }
        });
        findViewById(R.id.full_log_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFullLogDialog();
            }
        });
        findViewById(R.id.open_log_dir_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLogLocation();
            }
        });

        findViewById(R.id.dump_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ServiceState.connected) {
                    toast("无障碍服务未连接，先开启服务");
                    return;
                }
                if (ServiceState.requestDump(5000)) {
                    toast("5 秒后抓取：请立刻切回红果的广告页");
                } else {
                    toast("已有一个抓取任务在等待执行");
                }
            }
        });

        findViewById(R.id.app_settings_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    toast("无法打开应用设置: " + t);
                }
            }
        });

        findViewById(R.id.battery_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Throwable t2) {
                        toast("无法打开电池优化设置: " + t2);
                    }
                }
            }
        });

        findViewById(R.id.add_keyword_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String keyword = keywordInput.getText().toString().trim();
                if (keyword.isEmpty()) {
                    toast("请输入关键词");
                    return;
                }
                String raw = prefs.getString(Prefs.KEY_KEYWORDS, "");
                if (raw.contains(keyword)) {
                    toast("关键词已存在");
                    return;
                }
                prefs.edit().putString(Prefs.KEY_KEYWORDS, raw.isEmpty() ? keyword : raw + "," + keyword).apply();
                keywordInput.setText("");
                updateKeywordList();
                toast("已添加: " + keyword);
            }
        });
        keywordListText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDeleteKeywordDialog();
            }
        });
    }

    private void setupSwitches() {
        enableSwitch.setChecked(prefs.getBoolean(Prefs.KEY_ENABLED, false));
        enableSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked && !isAccessibilityEnabled()) {
                    toast("请先开启无障碍服务");
                    buttonView.setChecked(false);
                    prefs.edit().putBoolean(Prefs.KEY_ENABLED, false).apply();
                    return;
                }
                prefs.edit().putBoolean(Prefs.KEY_ENABLED, isChecked).apply();
                toast(isChecked ? "自动跳过已启用" : "自动跳过已关闭");
                refreshStatus();
            }
        });

        appShortSwitch.setChecked(prefs.getBoolean(Prefs.KEY_APP_SHORT, true));
        appShortSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(Prefs.KEY_APP_SHORT, isChecked).apply();
            }
        });
        appComicSwitch.setChecked(prefs.getBoolean(Prefs.KEY_APP_COMIC, true));
        appComicSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(Prefs.KEY_APP_COMIC, isChecked).apply();
            }
        });
        debugSwitch.setChecked(prefs.getBoolean(Prefs.KEY_DEBUG, false));
        debugSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(Prefs.KEY_DEBUG, isChecked).apply();
            }
        });
    }

    private boolean isLogAutoRefreshEnabled() {
        return System.currentTimeMillis() > logTouchPauseUntil;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }

    private interface OnValueChanged {
        void onChanged(int value);
    }

    private void bindSeekBar(SeekBar bar, final int min, final int max, final TextView label,
                             final String prefix, final String suffix) {
        bar.setMax(max - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = min + progress;
                label.setText(prefix + value + suffix);
                if (fromUser) {
                    saveFromUi();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void setupSeekBars() {
        bindSeekBar(seekSwipeDuration, 100, 1000, labelSwipeDuration, "滑动时长：", " ms");
        bindSeekBar(seekSwipeFrom, 40, 95, labelSwipeFrom, "滑动起点：", " %");
        bindSeekBar(seekSwipeTo, 5, 60, labelSwipeTo, "滑动终点：", " %");
        bindSeekBar(seekCheckInterval, 200, 3000, labelCheckInterval, "检测间隔：", " ms");
        bindSeekBar(seekCooldown, 500, 10000, labelCooldown, "防抖冷却：", " ms");
        bindSeekBar(seekPersist, 300, 5000, labelPersist, "广告结束判定：", " ms");
    }

    private void loadPrefsToUi() {
        DetectorConfig config = Prefs.readConfig(this, prefs);
        seekSwipeDuration.setProgress(config.swipeDurationMs - 100);
        seekSwipeFrom.setProgress(config.swipeFromPct - 40);
        seekSwipeTo.setProgress(config.swipeToPct - 5);
        seekCheckInterval.setProgress(config.checkIntervalMs - 200);
        seekCooldown.setProgress(config.cooldownMs - 500);
        seekPersist.setProgress(config.persistSwipeMs - 300);
        labelSwipeDuration.setText("滑动时长：" + config.swipeDurationMs + " ms");
        labelSwipeFrom.setText("滑动起点：" + config.swipeFromPct + " %");
        labelSwipeTo.setText("滑动终点：" + config.swipeToPct + " %");
        labelCheckInterval.setText("检测间隔：" + config.checkIntervalMs + " ms");
        labelCooldown.setText("防抖冷却：" + config.cooldownMs + " ms");
        labelPersist.setText("广告结束判定：" + config.persistSwipeMs + " ms");
    }

    private void saveFromUi() {
        Prefs.save(prefs,
                seekSwipeDuration.getProgress() + 100,
                seekSwipeFrom.getProgress() + 40,
                seekSwipeTo.getProgress() + 5,
                seekCheckInterval.getProgress() + 200,
                seekCooldown.getProgress() + 500,
                seekPersist.getProgress() + 300);
    }

    private void showDeleteKeywordDialog() {
        final String raw = prefs.getString(Prefs.KEY_KEYWORDS, "");
        if (raw.isEmpty()) {
            return;
        }
        final String[] parts = raw.split(",");
        String[] items = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            items[i] = "删除：" + parts[i];
        }
        new AlertDialog.Builder(this)
                .setTitle("自定义关键词")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        List<String> list = new ArrayList<>(Arrays.asList(parts));
                        list.remove(which);
                        prefs.edit().putString(Prefs.KEY_KEYWORDS, TextUtils.join(",", list)).apply();
                        updateKeywordList();
                        toast("已删除");
                    }
                })
                .show();
    }

    private void updateKeywordList() {
        String raw = prefs.getString(Prefs.KEY_KEYWORDS, "");
        if (raw.isEmpty()) {
            keywordListText.setText("暂无自定义关键词（点击可删除，内置关键词已自动生效）");
        } else {
            keywordListText.setText("已添加（点击删除）：\n" + raw.replace(",", "\n"));
        }
        if (keywordHint != null) {
            keywordHint.setText(buildKeywordHint());
        }
    }

    private String buildKeywordHint() {
        StringBuilder sb = new StringBuilder();
        sb.append("保存后如何生效：\n");
        sb.append("1) 只要页面上任意控件的文字或描述**包含**这个词，就判定为广告页，出现即触发；\n");
        sb.append("2) 匹配是“包含”关系，填短词即可，例如填「跳过」能命中「跳过广告 5s」；\n");
        sb.append("3) 建议填广告页上真实出现的词：跳过、关闭广告、官方下载、立即安装、点击进入游戏、商家推荐、上滑继续看短剧……\n");
        sb.append("4) 别填会出现在正常剧情里的词（如角色名、剧名），否则正常播放也会被当成广告；\n");
        sb.append("5) 填「第」这类字会误伤集数显示，不要用。\n\n");
        sb.append("内置已覆盖：");
        sb.append(TextUtils.join("、", AdDetector.CTA_KEYWORDS));
        sb.append("；另外“跳过广告/关闭广告”类按钮会直接点击，“上滑继续看短剧”只在没有倒计时时才当作广告结束。");
        return sb.toString();
    }

    private void refreshStatus() {
        boolean accessibilityOn = isAccessibilityEnabled();
        boolean serviceConnected = ServiceState.connected;
        boolean enabled = prefs.getBoolean(Prefs.KEY_ENABLED, false);

        StringBuilder sb = new StringBuilder();
        if (serviceConnected) {
            sb.append("服务状态：无障碍已连接 ✓");
        } else if (accessibilityOn) {
            sb.append("服务状态：无障碍已授权，服务未连接 ✗（到设置里关一次再开，或重启手机）");
        } else {
            sb.append("服务状态：无障碍未授权 ✗");
        }
        sb.append('\n').append("自动跳过：").append(enabled ? "已启用" : "未启用");
        sb.append('\n').append("检测次数：").append(ServiceState.scanCount);
        if (!ServiceState.foregroundPackage.isEmpty()) {
            sb.append('\n').append("当前前台：").append(ServiceState.foregroundPackage);
        }
        if (!ServiceState.lastDecision.isEmpty()) {
            sb.append('\n').append("最近判定：").append(ServiceState.lastDecision);
        }
        if (ServiceState.lastSwipeAt > 0) {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(ServiceState.lastSwipeAt));
            sb.append('\n').append("最近动作：").append(time).append(" · ").append(ServiceState.lastSwipeReason);
        }
        sb.append('\n').append("已安装：").append(installedLabel(HongguoAccessibilityService.PKG_SHORT_DRAMA, "红果免费短剧"))
                .append(" / ").append(installedLabel(HongguoAccessibilityService.PKG_COMIC, "红果免费漫剧"));
        statusText.setText(sb.toString());

        if (accessibilityOn != wasAccessibilityEnabled) {
            wasAccessibilityEnabled = accessibilityOn;
            if (accessibilityOn && !enableSwitch.isChecked()) {
                toast("无障碍已开启，可以打开上面的开关了");
            }
        }
    }

    private String installedLabel(String pkg, String name) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return name + "✓";
        } catch (Throwable t) {
            return name + "✗";
        }
    }

    private boolean isAccessibilityEnabled() {
        try {
            ComponentName component = new ComponentName(this, HongguoAccessibilityService.class);
            AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (manager == null) {
                return false;
            }
            List<AccessibilityServiceInfo> list =
                    manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo info : list) {
                if (component.equals(ComponentName.unflattenFromString(info.getId()))) {
                    return true;
                }
            }
        } catch (Throwable t) {
            AppLog.d("Main", "读取无障碍状态失败: " + t);
        }
        return false;
    }

    private void updateLogText() {
        String logs = AppLog.getLogs();
        if (logs.isEmpty()) {
            logText.setText("暂无日志");
            return;
        }
        String[] all = logs.split("\n");
        int keep = Math.min(all.length, 120);
        StringBuilder sb = new StringBuilder();
        for (int i = all.length - keep; i < all.length; i++) {
            sb.append(all[i]).append('\n');
        }
        logText.setText(sb.toString());
    }

    private void showFullLogDialog() {
        final TextView view = new TextView(this);
        view.setText(AppLog.getLogs().isEmpty() ? "暂无日志" : AppLog.getLogs());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
                .setTitle("运行日志（可上下滑动）")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .setNeutralButton("导出", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        exportLogs(true);
                    }
                })
                .show();
    }

    /** 把日志写到「下载/红果自动跳过/」，并记住位置，便于"打开日志存储位置"。 */
    private Uri exportLogs(boolean showToast) {
        String logs = AppLog.getLogs();
        if (logs.isEmpty()) {
            if (showToast) {
                toast("暂无日志可导出");
            }
            return null;
        }
        String name = "hongguo_log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + LOG_FOLDER);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IllegalStateException("insert 返回 null");
                }
                OutputStream out = getContentResolver().openOutputStream(uri);
                out.write(logs.getBytes("UTF-8"));
                out.close();
                lastExportUri = uri;
                lastExportPath = "Download/" + LOG_FOLDER + "/" + name;
                AppLog.d("Main", "日志已导出: " + lastExportPath);
            } else {
                File dir = new File(getExternalFilesDir(null), "logs");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建目录");
                }
                File file = new File(dir, name);
                FileOutputStream out = new FileOutputStream(file);
                out.write(logs.getBytes("UTF-8"));
                out.close();
                lastExportUri = null;
                lastExportPath = file.getAbsolutePath();
                AppLog.d("Main", "日志已导出: " + lastExportPath);
            }
            if (showToast) {
                toast("已导出到 " + lastExportPath);
            }
            return lastExportUri;
        } catch (Throwable t) {
            if (showToast) {
                toast("导出失败: " + t);
            }
            return null;
        }
    }

    /** 打开日志存储位置：优先在系统文件管理器里打开该文件夹，其次打开文件，最后复制路径。 */
    private void openLogLocation() {
        if (lastExportUri == null || lastExportPath.isEmpty()) {
            exportLogs(false);
        }
        boolean opened = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Uri folder = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:" + Environment.DIRECTORY_DOWNLOADS + "/" + LOG_FOLDER);
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
                opened = true;
            } catch (Throwable t) {
                AppLog.d("Main", "打开文件夹失败: " + t);
            }
        }
        if (!opened && lastExportUri != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(lastExportUri, "text/plain")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                opened = true;
            } catch (Throwable t) {
                AppLog.d("Main", "打开日志文件失败: " + t);
            }
        }
        if (!opened) {
            copyToClipboard(lastExportPath);
            new AlertDialog.Builder(this)
                    .setTitle("日志存储位置")
                    .setMessage("已复制路径到剪贴板：\n\n" + lastExportPath
                            + "\n\n如果没有文件管理器能打开，可以在电脑上用 adb 拉取，或点“导出”后用系统文件应用进入 Download/" + LOG_FOLDER)
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("日志路径", text));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            AppLog.d("Main", "通知权限: " + granted);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}

