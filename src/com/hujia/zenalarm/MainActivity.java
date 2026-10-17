package com.hujia.zenalarm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** 简洁的定时首页及设置子页面，区分自动读取和用户主动打开超禅。 */
public final class MainActivity extends Activity {
    private static final int HOME = 0, SETTINGS = 1, RECORDS = 2, PERMISSIONS = 3, TOOLS = 4, ABOUT = 5;
    private static final int GREEN = Color.rgb(35, 104, 85), INK = Color.rgb(28, 49, 42);
    private static final int MUTED = Color.rgb(103, 119, 109), PALE = Color.rgb(236, 243, 237);
    private LinearLayout body;
    private ScrollView scroll;
    private int page = HOME;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable automaticRead = () -> refreshDuration(false);
    private boolean skipAutomaticRead;
    private boolean resumed;

    /** 创建统一页面容器，恢复子页面并接入系统返回手势。 */
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) page = savedInstanceState.getInt("page", HOME);
        skipAutomaticRead = savedInstanceState != null || getIntent().getBooleanExtra(ZenDurationReader.RETURN_FROM_READ, false);
        getIntent().removeExtra(ZenDurationReader.RETURN_FROM_READ);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(243, 246, 241));
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(16), dp(24), dp(28));
        scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
    }

    /** 保存正在浏览的设置子页面，重建后不意外跳去超禅。 */
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("page", page); super.onSaveInstanceState(state); }

    /** 返回时刷新显示；手动浏览超禅返回后直接使用刚读取的结果，不再次跳转。 */
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        boolean manualReturn = ZenDurationReader.finishManualVisit(this);
        boolean skip = skipAutomaticRead || manualReturn;
        skipAutomaticRead = false;
        if (skip) ZenDurationReader.cancelRequest();
        ZenScheduler.scheduleDaily(this); render();
        SharedPreferences p = ZenScheduler.prefs(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !p.getBoolean("notification_asked", false)) {
            p.edit().putBoolean("notification_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11); return;
        }
        if (!skip && page == HOME && ZenDurationReader.enabled(this)) handler.postDelayed(automaticRead, 300);
    }

    /** 消费一次自动返回标记，避免 onResume 形成往返循环。 */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent.getBooleanExtra(ZenDurationReader.RETURN_FROM_READ, false)) skipAutomaticRead = true;
        intent.removeExtra(ZenDurationReader.RETURN_FROM_READ);
    }

    /** 离开页面时取消尚未开始的自动读取。 */
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(automaticRead); super.onPause(); }

    /** 授权回调只更新预约和当前页面。 */
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 11) { ZenScheduler.scheduleDaily(this); render(); }
    }

    /** 为 Android 12 保留传统返回键行为。 */
    @Override public void onBackPressed() { navigateBack(); }

    /** 子页面返回设置，设置返回首页；首页返回桌面。 */
    private void navigateBack() {
        if (page == HOME) finish();
        else showPage(page == SETTINGS ? HOME : SETTINGS);
    }

    /** 在应用内切换页面，不触发自动读取或重建闹钟。 */
    private void showPage(int target) {
        handler.removeCallbacks(automaticRead); page = target; render(); scroll.scrollTo(0, 0);
    }

    /** 将逻辑像素转换为设备像素。 */
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** 创建统一文本样式。 */
    private TextView text(String value, int size, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(INK); v.setLineSpacing(dp(3), 1);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }

    /** 创建次要说明文字。 */
    private TextView muted(String value, int size) { TextView v = text(value, size, false); v.setTextColor(MUTED); return v; }

    /** 添加纵向控件并保留间距。 */
    private void add(LinearLayout parent, View view, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(bottom); parent.addView(view, p);
    }

    /** 创建圆角背景。 */
    private GradientDrawable background(int color, int radius) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg;
    }

    /** 创建统一白色卡片。 */
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16)); card.setBackground(background(Color.WHITE, 18)); return card;
    }

    /** 创建无浮雕阴影的按钮。 */
    private Button button(String label, boolean primary, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setTextSize(15); b.setAllCaps(false);
        b.setMinHeight(dp(48)); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setStateListAnimator(null); b.setElevation(0); b.setTextColor(primary ? Color.WHITE : GREEN);
        b.setBackground(background(primary ? GREEN : PALE, 12)); b.setOnClickListener(listener); return b;
    }

    /** 创建适合工具栏和更多菜单的文字按钮。 */
    private Button action(String label, View.OnClickListener listener) {
        Button b = button(label, false, listener); b.setBackgroundColor(Color.TRANSPARENT); return b;
    }

    /** 创建首页标题和设置入口，子页面使用返回按钮。 */
    private void header(String title) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        if (page != HOME) {
            Button back = action("‹", v -> navigateBack()); back.setTextSize(28); back.setContentDescription("返回");
            row.addView(back, new LinearLayout.LayoutParams(dp(42), dp(52)));
        }
        row.addView(text(title, page == HOME ? 28 : 25, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (page == HOME) row.addView(action("设置", v -> showPage(SETTINGS)), new LinearLayout.LayoutParams(dp(64), dp(48)));
        add(body, row, 18);
    }

    /** 创建设置页的可点击入口。 */
    private View entry(String title, String detail, View.OnClickListener listener) {
        LinearLayout card = card(); LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        add(labels, text(title, 17, true), 4); add(labels, muted(detail, 13), 0);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(muted("›", 25));
        card.addView(row); card.setOnClickListener(listener); return card;
    }

    /** 根据当前导航显示首页或设置子页。 */
    private void render() {
        body.removeAllViews();
        if (page == HOME) renderHome();
        else if (page == SETTINGS) renderSettings();
        else if (page == RECORDS) renderRecords();
        else if (page == PERMISSIONS) renderPermissions();
        else if (page == TOOLS) renderTools();
        else renderAbout();
    }

    /** 首页仅展示启动状态、时长、每日列表和添加入口。 */
    private void renderHome() {
        header("超禅定时");
        SharedPreferences p = ZenScheduler.prefs(this);
        LinearLayout summary = card();
        Switch master = new Switch(this); master.setText("定时总开关"); master.setTextSize(16); master.setTextColor(INK);
        master.setChecked(p.getBoolean("enabled", true));
        master.setOnCheckedChangeListener((v, checked) -> { ZenScheduler.setEnabled(this, checked); render(); });
        add(summary, master, 14);
        long next = ZenScheduler.next(this); String missing = ZenScheduler.missing(this);
        String status = !p.getBoolean("enabled", true) ? "所有定时已暂停" : !missing.isEmpty() ? "等待完成运行设置"
                : next > 0 ? shortTime(next) : "还没有开启的定时";
        add(summary, muted("下次启动", 12), 3); add(summary, text(status, 23, true), 0); add(body, summary, 14);
        if (!missing.isEmpty()) add(body, entry("需要完成运行设置", missing, v -> showPage(PERMISSIONS)), 14);
        else if (lastStartFailed()) add(body, entry("最近一次启动异常", "查看执行记录", v -> showPage(RECORDS)), 14);
        add(body, durationCard(), 22);
        add(body, text("每日定时", 17, true), 10);
        List<ScheduleStore.Slot> slots = ScheduleStore.load(this);
        if (slots.isEmpty()) add(body, muted("添加一个每天启动的时间", 14), 14);
        for (ScheduleStore.Slot slot : slots) add(body, slotCard(slot), 10);
        add(body, button("＋ 添加定时", true, v -> chooseTime(null)), 0);
    }

    /** 检查最近一条启动结果，提醒记录不会掩盖启动失败。 */
    private boolean lastStartFailed() {
        for (String line : ZenScheduler.prefs(this).getString("log", "").split("\n")) {
            if (line.contains("已发出超禅启动请求")) return false;
            if (line.contains("启动请求失败") || line.contains("未启动：") || line.contains("跳过已过期")) return true;
        }
        return false;
    }

    /** 显示最近时长及原生入口，详细读取工具位于设置中。 */
    private View durationCard() {
        LinearLayout card = card(); LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        SharedPreferences snapshot = ZenDurationReader.prefs(this);
        int minutes = snapshot.getInt("minutes", 0); long readAt = snapshot.getLong("read_at", 0);
        LinearLayout label = new LinearLayout(this); label.setOrientation(LinearLayout.VERTICAL);
        add(label, muted("超禅时长", 13), 5); add(label, text(minutes > 0 ? minutes + " 分钟" : "尚未读取", 27, true), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(button("打开超禅", false, v -> openZen()), new LinearLayout.LayoutParams(dp(112), dp(48)));
        add(card, row, 12);
        add(card, muted(readAt > 0 ? "最近读取 " + shortTime(readAt) : "时长沿用超禅内的设置", 12), 0);
        String status = snapshot.getString("read_status", "");
        if (!ZenDurationReader.enabled(this)) {
            TextView hint = muted("时长读取未开启 · 去设置 ›", 12); hint.setPadding(0, dp(8), 0, 0);
            hint.setOnClickListener(v -> showPage(PERMISSIONS)); add(card, hint, 0);
        } else if (!status.isEmpty() && !status.equals("已自动更新") && !status.equals("已更新")) {
            TextView hint = muted(status, 12); hint.setPadding(0, dp(8), 0, 0); add(card, hint, 0);
        }
        return card;
    }

    /** 定时卡片突出时间和开关，编辑点击时间，删除放入更多菜单。 */
    private View slotCard(ScheduleStore.Slot slot) {
        LinearLayout card = card(); LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView time = text(ZenScheduler.clockTime(slot.time), 33, true); time.setContentDescription("修改 " + ZenScheduler.clockTime(slot.time));
        time.setOnClickListener(v -> chooseTime(slot)); row.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        Switch enabled = new Switch(this); enabled.setContentDescription(ZenScheduler.clockTime(slot.time) + " 定时开关"); enabled.setChecked(slot.enabled);
        enabled.setOnCheckedChangeListener((v, checked) -> {
            List<ScheduleStore.Slot> slots = ScheduleStore.load(this); slots.removeIf(s -> s.time == slot.time);
            slots.add(new ScheduleStore.Slot(slot.time, checked)); ZenScheduler.saveSlots(this, slots); render();
        });
        row.addView(enabled);
        Button more = action("⋮", v -> slotMenu(v, slot)); more.setTextSize(23); more.setContentDescription(ZenScheduler.clockTime(slot.time) + " 更多操作");
        LinearLayout.LayoutParams moreLayout = new LinearLayout.LayoutParams(dp(40), dp(48)); moreLayout.leftMargin = dp(8); row.addView(more, moreLayout);
        add(card, row, 3); add(card, muted(slot.enabled ? "每天 · 提前 5 分钟提醒" : "已关闭", 13), 0); return card;
    }

    /** 在单条定时的更多菜单中提供编辑和删除。 */
    private void slotMenu(View anchor, ScheduleStore.Slot slot) {
        PopupMenu menu = new PopupMenu(this, anchor); menu.getMenu().add("编辑时间"); menu.getMenu().add("删除定时");
        menu.setOnMenuItemClickListener(item -> {
            if ("编辑时间".contentEquals(item.getTitle())) chooseTime(slot); else deleteSlot(slot); return true;
        }); menu.show();
    }

    /** 设置页集中提供记录、权限、工具和版本入口。 */
    private void renderSettings() {
        header("设置");
        add(body, entry("执行记录", "查看最近的提醒和启动情况", v -> showPage(RECORDS)), 12);
        add(body, entry("权限管理", "通知、定时运行和时长读取", v -> showPage(PERMISSIONS)), 12);
        add(body, entry("测试与工具", "测试启动、重新读取时长", v -> showPage(TOOLS)), 12);
        add(body, entry("关于", "超禅定时 0.5.0", v -> showPage(ABOUT)), 0);
    }

    /** 展示最近六条记录，隐藏与日常使用无关的进程编号。 */
    private void renderRecords() {
        header("执行记录"); add(body, muted("最近 6 条 · 启动请求已发出不等同于已进入超禅", 12), 16);
        String log = ZenScheduler.prefs(this).getString("log", "");
        if (log.isEmpty()) { add(body, muted("还没有执行记录", 15), 0); return; }
        for (String line : log.split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("  ", 2); LinearLayout item = card();
            if (parts.length == 2) add(item, muted(parts[0], 12), 8);
            String description = parts[parts.length - 1].replaceAll(" uid=\\d+", "")
                    .replace("locked=true", "已锁屏").replace("locked=false", "未锁屏")
                    .replace("interactive=true", "亮屏").replace("interactive=false", "息屏");
            TextView value = text(description, 14, false); value.setTextIsSelectable(true); add(item, value, 0); add(body, item, 10);
        }
    }

    /** 权限仅在专门页面集中显示，首页只突出会阻止运行的问题。 */
    private void renderPermissions() {
        header("权限管理");
        add(body, entry("提醒通知", ZenScheduler.notificationsAllowed(this) ? "已允许 · 每次提前 5 分钟提醒" : "未开启 · 点击设置", v -> notificationSettings()), 10);
        add(body, entry("精确闹钟", ZenScheduler.exactAllowed(this) ? "已允许" : "未允许 · 点击设置", v -> openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)), 10);
        add(body, entry("锁屏时启动", ZenScheduler.backgroundAllowed(this) ? "悬浮窗权限已允许" : "需要允许悬浮窗权限", v -> openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)), 10);
        add(body, entry("后台运行", ZenScheduler.batteryAllowed(this) ? "已加入电池优化白名单" : "建议加入电池优化白名单", v -> openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)), 10);
        add(body, entry("超禅时长读取", ZenDurationReader.enabled(this) ? "已开启 · 只读超禅页面" : "未开启 · 不影响定时启动", v -> {
            if (ZenDurationReader.enabled(this)) startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); else explainReader();
        }), 12);
        add(body, muted("悬浮窗授权用于锁屏时启动超禅，应用不会显示悬浮球。时长读取是可选功能。", 12), 0);
    }

    /** 将会实际启动超禅的测试与读取重试集中在工具页。 */
    private void renderTools() {
        header("测试与工具");
        LinearLayout readCard = card(); add(readCard, text("刷新超禅时长", 17, true), 6);
        add(readCard, muted("打开首页读取最新时长，完成后自动返回。", 13), 10);
        String readStatus = ZenDurationReader.prefs(this).getString("read_status", "");
        if (!readStatus.isEmpty()) add(readCard, muted(readStatus, 12), 10);
        add(readCard, button("重新读取", false, v -> {
            if (!ZenDurationReader.enabled(this)) explainReader(); else refreshDuration(true);
        }), 0); add(body, readCard, 14);
        LinearLayout testCard = card(); add(testCard, text("测试自动启动", 17, true), 6);
        add(testCard, muted("60 秒后实际进入超禅，时长沿用原生设置。", 13), 12);
        long testAt = ZenScheduler.prefs(this).getLong("test_at", 0);
        if (testAt > System.currentTimeMillis()) {
            add(testCard, muted("测试预约：" + ZenScheduler.format(testAt), 13), 8);
            add(testCard, button("取消本次测试", false, v -> { ZenScheduler.cancelTest(this); render(); }), 0);
        } else add(testCard, button("60 秒后测试一次", false, v -> confirmTest()), 0);
        add(body, testCard, 0);
    }

    /** 在关于页展示版本、运行方式和明确边界。 */
    private void renderAbout() {
        header("关于"); LinearLayout card = card();
        add(card, text("超禅定时", 24, true), 6); add(card, muted("0.5.0 · 个人使用版", 14), 18);
        add(card, text("完全本地运行\n无需 Tasker、Shizuku 或 Root", 15, false), 18);
        add(card, muted("定时启动使用系统精确闹钟；所有定时沿用原生超禅的时长。时长展示来自最近一次页面读取。", 13), 12);
        add(card, muted("重启后首次解锁会恢复预约。强行停止应用后，请重新打开。系统或超禅更新后可能需要重新适配。", 13), 0);
        add(body, card, 0);
    }

    /** 用紧凑的日期时间展示预约和读取时间。 */
    private String shortTime(long when) {
        return DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA).format(Instant.ofEpochMilli(when).atZone(ZoneId.systemDefault()));
    }

    /** 主动打开超禅时不自动返回，用户回来后再刷新卡片。 */
    private void openZen() {
        handler.removeCallbacks(automaticRead); skipAutomaticRead = true;
        String error = ZenDurationReader.openForBrowsing(this);
        if (!error.isEmpty()) { skipAutomaticRead = false; Toast.makeText(this, error, Toast.LENGTH_LONG).show(); }
    }

    /** 正常进入首页自动读取一次，工具页也可主动重试。 */
    private void refreshDuration(boolean manual) {
        if (!resumed) return;
        handler.removeCallbacks(automaticRead); skipAutomaticRead = true;
        String error = ZenDurationReader.readAndReturn(this);
        if (!error.isEmpty()) {
            skipAutomaticRead = false; ZenDurationReader.prefs(this).edit().putString("read_status", error).apply();
            if (manual) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        }
        render();
    }

    /** 说明只读范围并引导系统授权，不影响原有定时。 */
    private void explainReader() {
        new AlertDialog.Builder(this).setTitle("读取超禅设置的时长")
                .setMessage("请在系统无障碍中开启“超禅时长读取”。仅读取禅定空间首页超禅卡片的分钟数并保存在本机，不点击开始、不修改设置，也不读取其他应用内容。")
                .setNegativeButton("暂不开启", null).setPositiveButton("去开启", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show();
    }

    /** 添加或修改时间，拒绝重复时分并保留该条原有开关。 */
    private void chooseTime(ScheduleStore.Slot original) {
        LocalTime soon = LocalTime.now().plusMinutes(10); int value = original == null ? soon.getHour() * 60 + soon.getMinute() : original.time;
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER);
        NumberPicker hour = new NumberPicker(this); hour.setMinValue(0); hour.setMaxValue(23); hour.setValue(value / 60);
        NumberPicker minute = new NumberPicker(this); minute.setMinValue(0); minute.setMaxValue(59); minute.setValue(value % 60);
        NumberPicker.Formatter formatter = n -> String.format(Locale.ROOT, "%02d", n); hour.setFormatter(formatter); minute.setFormatter(formatter);
        row.addView(hour, new LinearLayout.LayoutParams(dp(100), dp(180))); row.addView(text(":", 24, true));
        row.addView(minute, new LinearLayout.LayoutParams(dp(100), dp(180)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(original == null ? "添加每日定时" : "修改每日定时")
                .setView(row).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            hour.clearFocus(); minute.clearFocus(); int time = hour.getValue() * 60 + minute.getValue();
            List<ScheduleStore.Slot> slots = ScheduleStore.load(this); if (original != null) slots.removeIf(s -> s.time == original.time);
            if (ScheduleStore.find(slots, time) != null) { Toast.makeText(this, "这个时间已经有定时了", Toast.LENGTH_SHORT).show(); return; }
            slots.add(new ScheduleStore.Slot(time, original == null || original.enabled)); ZenScheduler.saveSlots(this, slots); dialog.dismiss(); render();
        })); dialog.show();
    }

    /** 删除前显示具体时间，确认后取消该条提醒和启动。 */
    private void deleteSlot(ScheduleStore.Slot slot) {
        new AlertDialog.Builder(this).setMessage("删除每天 " + ZenScheduler.clockTime(slot.time) + " 的定时？")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> {
                    List<ScheduleStore.Slot> slots = ScheduleStore.load(this); slots.removeIf(s -> s.time == slot.time);
                    ZenScheduler.saveSlots(this, slots); render();
                }).show();
    }

    /** 打开系统通知设置。 */
    private void notificationSettings() { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }

    /** 经确认才创建真实启动测试，不修改每日列表。 */
    private void confirmTest() {
        String missing = ZenScheduler.missing(this);
        if (!missing.isEmpty()) { new AlertDialog.Builder(this).setMessage(missing).setPositiveButton("知道了", null).show(); return; }
        new AlertDialog.Builder(this).setTitle("测试自动启动")
                .setMessage("60 秒后将进入超禅，时长沿用超禅里当前选择的值。本次测试不发送五分钟提醒。")
                .setNegativeButton("取消", null).setPositiveButton("开始测试", (d, w) -> {
                    try { ZenScheduler.test(this); } catch (RuntimeException e) { ZenScheduler.record(this, "测试预约失败：" + e.getClass().getSimpleName()); }
                    render();
                }).show();
    }

    /** 打开对应系统授权页，缺少入口时回退到应用详情。 */
    private void openSettings(String action) {
        try { startActivity(new Intent(action, Uri.parse("package:" + getPackageName()))); }
        catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
        catch (RuntimeException e) { Toast.makeText(this, "请在系统应用管理中为超禅定时授权", Toast.LENGTH_LONG).show(); }
    }
}
