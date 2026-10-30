package com.hongguo.auto;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.hongguo.auto.core.DetectorConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATION = 1001;

    private SharedPreferences prefs;
    private TextView statusText;
    private TextView logText;
    private TextView keywordListText;
    private TextView tipsText;
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

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            updateLogText();
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
        tipsText = findViewById(R.id.tips_text);
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

        enableSwitch.setChecked(prefs.getBoolean(Prefs.KEY_ENABLED, false));
        enableSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked && !isAccessibilityEnabled()) {
                    Toast.makeText(MainActivity.this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
                    buttonView.setChecked(false);
                    prefs.edit().putBoolean(Prefs.KEY_ENABLED, false).apply();
                    return;
                }
                prefs.edit().putBoolean(Prefs.KEY_ENABLED, isChecked).apply();
                Toast.makeText(MainActivity.this, isChecked ? "自动跳过已启用" : "自动跳过已关闭",
                        Toast.LENGTH_SHORT).show();
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

        findViewById(R.id.open_accessibility_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wasAccessibilityEnabled = isAccessibilityEnabled();
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "无法打开无障碍设置: " + t, Toast.LENGTH_LONG).show();
                }
            }
        });

        findViewById(R.id.app_settings_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "无法打开应用设置: " + t, Toast.LENGTH_LONG).show();
                }
            }
        });

        findViewById(R.id.battery_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Throwable t) {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Throwable t2) {
                        Toast.makeText(MainActivity.this, "无法打开电池优化设置: " + t2, Toast.LENGTH_LONG).show();
                    }
                }
            }
        });

        findViewById(R.id.reset_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.save(prefs, Prefs.DEFAULT_SWIPE_DURATION, Prefs.DEFAULT_SWIPE_FROM, Prefs.DEFAULT_SWIPE_TO,
                        Prefs.DEFAULT_CHECK_INTERVAL, Prefs.DEFAULT_COOLDOWN, Prefs.DEFAULT_PERSIST);
                loadPrefsToUi();
                Toast.makeText(MainActivity.this, "已恢复默认参数", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.refresh_log_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateLogText();
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
                exportLogs();
            }
        });
        findViewById(R.id.dump_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ServiceState.connected) {
                    Toast.makeText(MainActivity.this, "无障碍服务未连接，先开启服务", Toast.LENGTH_LONG).show();
                    return;
                }
                ServiceState.dumpRequested.set(true);
                Toast.makeText(MainActivity.this, "已请求抓取，约 1 秒后看日志", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.add_keyword_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String keyword = keywordInput.getText().toString().trim();
                if (keyword.isEmpty()) {
                    Toast.makeText(MainActivity.this, "请输入关键词", Toast.LENGTH_SHORT).show();
                    return;
                }
                String raw = prefs.getString(Prefs.KEY_KEYWORDS, "");
                if (raw.contains(keyword)) {
                    Toast.makeText(MainActivity.this, "关键词已存在", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit().putString(Prefs.KEY_KEYWORDS, raw.isEmpty() ? keyword : raw + "," + keyword).apply();
                keywordInput.setText("");
                updateKeywordList();
                Toast.makeText(MainActivity.this, "已添加: " + keyword, Toast.LENGTH_SHORT).show();
            }
        });
        keywordListText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDeleteKeywordDialog();
            }
        });

        setupSeekBars();
        loadPrefsToUi();
        updateKeywordList();
        updateLogText();
        refreshStatus();
        uiHandler.postDelayed(refreshRunnable, 1000);
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
                             final String prefix, final String suffix, final OnValueChanged onChange) {
        bar.setMax(max - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = min + progress;
                label.setText(prefix + value + suffix);
                if (fromUser) {
                    onChange.onChanged(value);
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
        bindSeekBar(seekSwipeDuration, 100, 1000, labelSwipeDuration, "滑动时长：", " ms", new OnValueChanged() {
            @Override
            public void onChanged(int value) {
            }
        });
        bindSeekBar(seekSwipeFrom, 40, 95, labelSwipeFrom, "滑动起点：", " %", new OnValueChanged() {
            @Override
            public void onChanged(int value) {
            }
        });
        bindSeekBar(seekSwipeTo, 5, 60, labelSwipeTo, "滑动终点：", " %", new OnValueChanged() {
            @Override
            public void onChanged(int value) {
            }
        });
        bindSeekBar(seekCheckInterval, 200, 3000, labelCheckInterval, "检测间隔：", " ms", new OnValueChanged() {
            @Override
            public void onChanged(int value) {
            }
        });
        bindSeekBar(seekCooldown, 500, 10000, labelCooldown, "防抖冷却：", " ms", new OnValueChanged() {
            @Override
            public void onChanged(int value) {
            }
        });
        bindSeekBar(seekPersist, 300, 5000, labelPersist, "广告结束判定：", " ms", new OnValueChanged() {
            @Override
            public void onChanged(int value) {
            }
        });
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
                        Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
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
            sb.append('\n').append("最近上滑：").append(time).append(" · ").append(ServiceState.lastSwipeReason);
        }
        sb.append('\n').append("已安装：").append(installedLabel(HongguoAccessibilityService.PKG_SHORT_DRAMA, "红果免费短剧"))
                .append(" / ").append(installedLabel(HongguoAccessibilityService.PKG_COMIC, "红果免费漫剧"));
        statusText.setText(sb.toString());

        findViewById(R.id.open_accessibility_btn).setEnabled(true);
        if (accessibilityOn != wasAccessibilityEnabled) {
            wasAccessibilityEnabled = accessibilityOn;
            if (accessibilityOn && !enableSwitch.isChecked()) {
                Toast.makeText(this, "无障碍已开启，可以打开上面的开关了", Toast.LENGTH_LONG).show();
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
            List<AccessibilityServiceInfo> list = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
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
        logText.setText(logs.isEmpty() ? "暂无日志" : tail(logs, 120));
    }

    private String tail(String text, int lines) {
        String[] all = text.split("\n");
        if (all.length <= lines) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = all.length - lines; i < all.length; i++) {
            sb.append(all[i]).append('\n');
        }
        return sb.toString();
    }

    private void exportLogs() {
        try {
            String logs = AppLog.getLogs();
            if (logs.isEmpty()) {
                Toast.makeText(this, "暂无日志可导出", Toast.LENGTH_SHORT).show();
                return;
            }
            File dir = new File(getExternalFilesDir(null), "logs");
            if (!dir.exists() && !dir.mkdirs()) {
                Toast.makeText(this, "无法创建目录", Toast.LENGTH_LONG).show();
                return;
            }
            String name = "hongguo_log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            File file = new File(dir, name);
            FileOutputStream out = new FileOutputStream(file);
            out.write(logs.getBytes("UTF-8"));
            out.close();
            Toast.makeText(this, "已导出: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            AppLog.d("Main", "日志已导出: " + file.getAbsolutePath());
        } catch (Throwable t) {
            Toast.makeText(this, "导出失败: " + t, Toast.LENGTH_LONG).show();
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
}
