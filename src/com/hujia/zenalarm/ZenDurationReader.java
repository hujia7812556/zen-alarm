package com.hujia.zenalarm;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/** 只读禅定空间首页的超禅卡片，不点击、不改设置，也不参与定时启动。 */
public final class ZenDurationReader extends AccessibilityService {
    static final String RETURN_FROM_READ = "com.hujia.zenalarm.RETURN_FROM_READ";
    private static final String CARD = ZenScheduler.ZEN_PACKAGE + ":id/super_zen_item";
    private static final String TIME = ZenScheduler.ZEN_PACKAGE + ":id/itemTime";
    private static ZenDurationReader connected;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable read = this::readVisibleDuration;
    private final Runnable poll = this::pollRequest;
    private long deadline;
    private long requestStarted;
    private long candidateSince;
    private int candidate;
    private boolean sawZen;

    /** 保存本次窗口识别结果；只读取包名和超禅专属卡片。 */
    private static final class Snapshot {
        final String packageName;
        final int minutes;

        /** 构造一次窗口识别结果。 */
        Snapshot(String packageName, int minutes) { this.packageName = packageName; this.minutes = minutes; }

        /** 判断窗口是否属于禅定空间。 */
        boolean isZen() { return ZenScheduler.ZEN_PACKAGE.equals(packageName); }
    }

    /** 将读取结果独立保存，避免页面刷新影响原有闹钟配置。 */
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("zen_duration", Context.MODE_PRIVATE); }

    /** 查询本服务是否由用户在系统中启用。 */
    static boolean enabled(Context c) {
        AccessibilityManager manager = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ComponentName name = ComponentName.unflattenFromString(service.getId());
            if (new ComponentName(c, ZenDurationReader.class).equals(name)) return true;
        }
        return false;
    }

    /** 由前台页面发起一次读取，成功后自动回到调用应用；返回非空文字表示未开始。 */
    static String readAndReturn(Context c) {
        if (connected == null) return "时长读取服务尚未就绪，请稍后重试";
        if (((KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked()) return "解锁后才能读取超禅时长";
        if (!idle(c)) return "超禅正在运行或状态不可读，本次暂不刷新";
        ZenDurationReader service = connected;
        service.handler.removeCallbacks(service.poll);
        service.requestStarted = SystemClock.elapsedRealtime();
        service.deadline = service.requestStarted + 8000;
        service.candidate = 0; service.candidateSince = 0; service.sawZen = false;
        prefs(c).edit().putString("read_status", "正在读取最新时长…").apply();
        try {
            openZenHome(c);
            service.handler.postDelayed(service.poll, 500);
            return "";
        } catch (RuntimeException e) {
            service.finishRequest("无法打开禅定空间", false);
            return "无法打开禅定空间";
        }
    }

    /** 用户自行返回时撤销尚未完成的请求，防止稍后再次抢回前台。 */
    static void cancelRequest() {
        if (connected != null && connected.deadline != 0) connected.finishRequest("本次读取已取消", false);
    }

    /** 用户主动打开原生页面时仅旁观读取，不安排自动返回。 */
    static String openForBrowsing(Context c) {
        cancelRequest();
        if (!idle(c)) return "超禅正在运行，暂时无法打开设置";
        prefs(c).edit().putLong("manual_visit_at", System.currentTimeMillis()).putBoolean("manual_fresh", false).apply();
        try { openZenHome(c); return ""; }
        catch (RuntimeException e) {
            prefs(c).edit().remove("manual_visit_at").remove("manual_fresh").apply();
            return "无法打开禅定空间";
        }
    }

    /** 用户返回后消费本次浏览结果，未读到新页面时明确保留旧值。 */
    static boolean finishManualVisit(Context c) {
        SharedPreferences p = prefs(c);
        long started = p.getLong("manual_visit_at", 0);
        if (started == 0) return false;
        boolean fresh = enabled(c) && p.getBoolean("manual_fresh", false) && p.getLong("read_at", 0) >= started;
        p.edit().remove("manual_visit_at").remove("manual_fresh")
                .putString("read_status", fresh ? "已更新" : "本次未刷新，显示上次记录").apply();
        return true;
    }

    /** 不打断正在进行的超禅；状态读取失败时同样不尝试切换页面。 */
    private static boolean idle(Context c) {
        try { return Settings.Secure.getInt(c.getContentResolver(), "op_breath_mode_status", 0) == 0; }
        catch (RuntimeException e) { return false; }
    }

    /** 只打开首页；明确不携带自动启动参数，并移除可能遗留的旧页面状态。 */
    static void openZenHome(Context c) {
        Intent intent = new Intent().setClassName(ZenScheduler.ZEN_PACKAGE, ZenScheduler.ZEN_PACKAGE + ".activity.HomeActivity");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("showPromptDialog", false);
        c.startActivity(intent);
    }

    /** 连接后尝试读取当前可见卡片，其他应用的页面会被拒绝。 */
    @Override protected void onServiceConnected() { connected = this; handler.postDelayed(read, 400); }

    /** 仅响应禅定空间的窗口变化，延迟合并连续刷新事件。 */
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !ZenScheduler.ZEN_PACKAGE.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) return;
        // 手动浏览时立即捕获返回首页后的变化，避免快速返回本应用错过新的时长。
        if (prefs(this).getLong("manual_visit_at", 0) > 0) readVisibleDuration();
        handler.removeCallbacks(read);
        handler.postDelayed(read, 400);
    }

    /** 只从超禅专属卡片取唯一时长，轻禅与运行倒计时不会成为读取来源。 */
    private void readVisibleDuration() {
        if (deadline != 0) return;
        Snapshot snapshot = snapshot();
        boolean manual = prefs(this).getLong("manual_visit_at", 0) > 0;
        if (manual && snapshot.isZen()) prefs(this).edit().putBoolean("manual_fresh", snapshot.minutes > 0).apply();
        if (snapshot.minutes > 0) {
            save(snapshot.minutes);
            prefs(this).edit().putString("read_status", "已更新").apply();
        }
    }

    /** 读取当前窗口；未知结构或页面切换时返回空结果，不猜测分钟数。 */
    private Snapshot snapshot() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return new Snapshot("", 0);
            String packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
            if (!ZenScheduler.ZEN_PACKAGE.equals(packageName)) return new Snapshot(packageName, 0);
            List<AccessibilityNodeInfo> cards = root.findAccessibilityNodeInfosByViewId(CARD);
            if (cards.size() != 1 || !cards.get(0).isVisibleToUser()) return new Snapshot(packageName, 0);
            List<AccessibilityNodeInfo> times = cards.get(0).findAccessibilityNodeInfosByViewId(TIME);
            if (times.size() != 1 || !times.get(0).isVisibleToUser()) return new Snapshot(packageName, 0);
            return new Snapshot(packageName, DurationText.parse(times.get(0).getText()));
        } catch (RuntimeException ignored) {
            return new Snapshot("", 0);
        }
    }

    /** 保存已实际读取的分钟数及时间，不改变原生设置或每日闹钟。 */
    private void save(int minutes) {
        prefs(this).edit().putInt("minutes", minutes).putLong("read_at", System.currentTimeMillis()).apply();
    }

    /** 等待首页文字稳定后返回；用户离开或超禅启动时取消，最多等待八秒。 */
    private void pollRequest() {
        if (deadline == 0) return;
        long now = SystemClock.elapsedRealtime();
        Snapshot snapshot = snapshot();
        if (!idle(this)) { finishRequest("超禅正在运行，本次读取已取消", false); return; }
        if (snapshot.isZen()) sawZen = true;
        else if (!snapshot.packageName.isEmpty() && (sawZen || now - requestStarted >= 1500)) {
            finishRequest("已离开超禅，本次读取已取消", false); return;
        }
        if (snapshot.minutes > 0) {
            if (candidate != snapshot.minutes) { candidate = snapshot.minutes; candidateSince = now; }
            // 首次显示后再确认一次，避免把初始化时的临时文字当作最新设置。
            if (now - requestStarted >= 800 && now - candidateSince >= 500) {
                save(candidate); finishRequest("已自动更新", true); return;
            }
        } else { candidate = 0; candidateSince = 0; }
        if (now >= deadline) {
            finishRequest("未能读到最新时长，保留上次记录", snapshot.isZen()); return;
        }
        handler.postDelayed(poll, 500);
    }

    /** 先结束请求再返回，防止新窗口事件重复触发跳转。 */
    private void finishRequest(String status, boolean returnToApp) {
        deadline = 0;
        handler.removeCallbacks(poll);
        prefs(this).edit().putString("read_status", status).apply();
        if (!returnToApp) return;
        Intent intent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(RETURN_FROM_READ, true);
        try { startActivity(intent); }
        catch (RuntimeException e) { prefs(this).edit().putString("read_status", "读取结束，请返回超禅定时查看").apply(); }
    }

    /** 系统中断时停止尚未执行的读取。 */
    @Override public void onInterrupt() { handler.removeCallbacks(read); finishRequest("读取已中断", false); }

    /** 解绑时清理回调，服务不自行重启或申请保活。 */
    @Override public void onDestroy() {
        handler.removeCallbacks(read); handler.removeCallbacks(poll);
        if (connected == this) connected = null;
        super.onDestroy();
    }
}
