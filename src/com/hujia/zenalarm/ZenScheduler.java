package com.hujia.zenalarm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.provider.Settings;
import java.time.ZoneId;
import java.time.LocalDate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 管理固定 01:00、自定义定时及提醒，并维持前台守护。 */
public final class ZenScheduler {
    static final String DAILY = "com.hujia.zenalarm.DAILY";
    static final String REMIND = "com.hujia.zenalarm.REMIND";
    static final String TEST = "com.hujia.zenalarm.TEST";
    static final String ZEN_PACKAGE = "com.oneplus.brickmode";
    static final String CHANNEL = "before_zen";
    static final long REMINDER_LEAD = 5 * 60 * 1000L;

    /** 禁止实例化调度工具。 */
    private ZenScheduler() {}

    /** 返回保留旧版设置的私有存储。 */
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("schedule", Context.MODE_PRIVATE); }

    /** 获取系统闹钟服务。 */
    private static AlarmManager alarms(Context c) { return (AlarmManager) c.getSystemService(Context.ALARM_SERVICE); }

    /** 获取系统通知服务。 */
    private static NotificationManager notifications(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** 建立提醒通道，重复调用不覆盖用户的声音与通知偏好。 */
    static void createChannel(Context c) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "超禅启动前提醒", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("在每个开启的定时前 5 分钟提醒");
        notifications(c).createNotificationChannel(channel);
    }

    /** 同时检查通知总权限和通道开关。 */
    static boolean notificationsAllowed(Context c) {
        NotificationChannel channel = notifications(c).getNotificationChannel(CHANNEL);
        return notifications(c).areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    /** 检查精确闹钟权限。 */
    static boolean exactAllowed(Context c) { return alarms(c).canScheduleExactAlarms(); }

    /** 检查后台启动所需授权，应用自身不显示悬浮窗。 */
    static boolean backgroundAllowed(Context c) { return Settings.canDrawOverlays(c); }

    /** 检查系统电池优化白名单。 */
    static boolean batteryAllowed(Context c) {
        return ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** 检查原生禅定空间是否存在。 */
    static boolean zenInstalled(Context c) {
        try { c.getPackageManager().getPackageInfo(ZEN_PACKAGE, 0); return true; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return false; }
    }

    /** 返回阻止启动的原因；关闭通知不影响已设置的超禅启动。 */
    static String missing(Context c) {
        if (!zenInstalled(c)) return "未检测到禅定空间";
        if (!exactAllowed(c)) return "请允许精确闹钟";
        if (!backgroundAllowed(c)) return "请允许后台启动所需的悬浮窗权限";
        return "";
    }

    /** 按时分和用途分配独立身份，防止多个定时互相覆盖。 */
    private static PendingIntent pending(Context c, String action, int time, long target) {
        int code = TEST.equals(action) ? 2 : 100 + time * 2 + (REMIND.equals(action) ? 1 : 0);
        Intent intent = new Intent(c, AlarmReceiver.class).setAction(action);
        intent.putExtra("time", time).putExtra("scheduled_at", target);
        return PendingIntent.getBroadcast(c, code, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 登记可在息屏时触发的精确闹钟，提醒与启动分别预约。 */
    private static void set(Context c, String action, int time, long when, long target) {
        PendingIntent show = PendingIntent.getActivity(c, 10, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        alarms(c).setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), pending(c, action, time, target));
    }

    /** 移除旧版单时间闹钟，升级后避免同一时间触发两次。 */
    private static void cancelLegacy(Context c) {
        PendingIntent old = PendingIntent.getBroadcast(c, 1, new Intent(c, AlarmReceiver.class).setAction(DAILY),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (old != null) { alarms(c).cancel(old); old.cancel(); }
        prefs(c).edit().remove("next_at").apply();
    }

    /** 取消指定时间的启动与提醒，并撤下对应通知。 */
    static void cancelSlot(Context c, int time) {
        alarms(c).cancel(pending(c, DAILY, time, 0));
        alarms(c).cancel(pending(c, REMIND, time, 0));
        notifications(c).cancel(time);
        prefs(c).edit().remove("next_" + time).apply();
    }

    /** 取消测试预约，不影响每日列表。 */
    static void cancelTest(Context c) {
        alarms(c).cancel(pending(c, TEST, -1, 0));
        prefs(c).edit().remove("test_at").apply();
        ZenGuardService.sync(c);
    }

    /** 预约一条定时，返回是否登记成功；固定项不受旧版总开关影响。 */
    static boolean scheduleSlot(Context c, ScheduleStore.Slot slot) {
        return scheduleSlot(c, slot, System.currentTimeMillis());
    }

    /** 以不早于已触发预约的时间计算下一轮，避免极早投递导致同一次重复预约。 */
    static boolean scheduleSlot(Context c, ScheduleStore.Slot slot, long now) {
        if (!slot.enabled || !missing(c).isEmpty()) { cancelSlot(c, slot.time); return false; }
        long target = slot.time == FixedTime.TIME ? FixedTime.next(now, ZoneId.systemDefault(), postponements(c))
                : AlarmTime.next(now, ZoneId.systemDefault(), slot.time / 60, slot.time % 60);
        try {
            // 同一身份直接替换闹钟，避免先取消固定预约再登记时留下空档。
            long previous = prefs(c).getLong("next_" + slot.time, 0);
            set(c, DAILY, slot.time, target, target);
            prefs(c).edit().putLong("next_" + slot.time, target).commit();
            alarms(c).cancel(pending(c, REMIND, slot.time, 0));
            if (previous != target) notifications(c).cancel(slot.time);
            if (prefs(c).getLong("reminded_" + slot.time, 0) != target && notificationsAllowed(c)) {
                long reminder = Math.max(now + 1000, target - REMINDER_LEAD);
                if (reminder < target) set(c, REMIND, slot.time, reminder, target);
            }
            return true;
        } catch (SecurityException e) { record(c, "预约失败：请检查精确闹钟授权"); return false; }
    }

    /** 重启、更新、权限变化及返回页面时恢复全部定时。 */
    static void scheduleDaily(Context c) {
        scheduleDaily(c, true);
    }

    /** 重建服务时避免再次启动自己，普通恢复入口在预约完成后同步守护。 */
    static void scheduleDaily(Context c, boolean syncGuard) {
        createChannel(c);
        if (ScheduleStore.migrate(c)) cancelTest(c);
        cancelLegacy(c);
        for (ScheduleStore.Slot slot : ScheduleStore.load(c)) scheduleSlot(c, slot);
        restoreTest(c);
        if (syncGuard) ZenGuardService.sync(c);
    }

    /** 系统恢复或重新打开时仅恢复尚未到期的测试，错过的测试直接清除。 */
    private static void restoreTest(Context c) {
        long when = prefs(c).getLong("test_at", 0);
        if (when == 0) return;
        if (when <= System.currentTimeMillis()) {
            alarms(c).cancel(pending(c, TEST, -1, 0));
            prefs(c).edit().remove("test_at").apply();
        } else if (missing(c).isEmpty()) {
            try { set(c, TEST, -1, when, when); }
            catch (SecurityException e) { record(c, "恢复测试预约失败：请检查精确闹钟授权"); }
        }
    }

    /** 修改列表前取消旧身份，删除和改时不会留下孤立闹钟。 */
    static void saveSlots(Context c, List<ScheduleStore.Slot> slots) {
        for (ScheduleStore.Slot slot : ScheduleStore.load(c)) if (slot.time != FixedTime.TIME) cancelSlot(c, slot.time);
        ScheduleStore.save(c, slots);
        scheduleDaily(c);
    }

    /** 读取按执行日期保留的推迟记录，不在重新打开或跨周时删除历史额度。 */
    static Set<LocalDate> postponements(Context c) {
        return FixedTime.decode(prefs(c).getString("fixed_postponements", ""));
    }

    /** 持久化一次推迟并更新实际系统闹钟；失败则恢复原记录和原预约，不消耗机会。 */
    static void postponeFixed(Context c, LocalDate expectedDate) {
        String missing = missing(c);
        if (!missing.isEmpty()) throw new IllegalStateException(missing);
        long now = System.currentTimeMillis();
        Set<LocalDate> original = postponements(c);
        Set<LocalDate> approved = FixedTime.postpone(now, ZoneId.systemDefault(), original, expectedDate);
        SharedPreferences p = prefs(c);
        boolean saved = p.edit().putString("fixed_postponements", FixedTime.encode(approved)).commit();
        if (!saved || !scheduleSlot(c, new ScheduleStore.Slot(FixedTime.TIME, true), now)) {
            p.edit().putString("fixed_postponements", FixedTime.encode(original)).commit();
            scheduleSlot(c, new ScheduleStore.Slot(FixedTime.TIME, true), now);
            ZenGuardService.sync(c);
            throw new IllegalStateException("推迟未成功，请检查运行设置后重试");
        }
        ZenGuardService.sync(c);
        record(c, "固定定时已推迟：" + format(p.getLong("next_" + FixedTime.TIME, 0)));
    }

    /** 保留原有一分钟启动测试入口，不发送五分钟提醒。 */
    static void test(Context c) {
        if (!missing(c).isEmpty()) throw new IllegalStateException(missing(c));
        long when = System.currentTimeMillis() + 60000L;
        set(c, TEST, -1, when, when);
        prefs(c).edit().putLong("test_at", when).commit();
        ZenGuardService.sync(c);
    }

    /** 为有效定时发送通知，同一次启动不重复提醒。 */
    static void remind(Context c, int time, long target) {
        if (!notificationsAllowed(c)) { record(c, "启动前提醒未显示：请开启通知"); return; }
        if (prefs(c).getLong("reminded_" + time, 0) == target) return;
        PendingIntent open = PendingIntent.getActivity(c, 10, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        String startTime = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(target));
        String message = "将于 " + startTime + " 自动启动，时长沿用超禅内的设置。";
        Notification notification = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("超禅即将开始").setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setCategory(Notification.CATEGORY_REMINDER).setContentIntent(open).setAutoCancel(true)
                .setTimeoutAfter(Math.max(1000, target - System.currentTimeMillis())).build();
        try {
            notifications(c).notify(time, notification);
            prefs(c).edit().putLong("reminded_" + time, target).commit();
            record(c, "已发送启动前提醒：" + clockTime(time));
        } catch (SecurityException e) { record(c, "启动前提醒未显示：通知权限不足"); }
    }

    /** 汇总最近的启动预约，不把提醒时间当成启动时间。 */
    static long next(Context c) {
        long next = 0;
        for (ScheduleStore.Slot slot : ScheduleStore.load(c)) {
            long at = prefs(c).getLong("next_" + slot.time, 0);
            if (at > 0 && (next == 0 || at < next)) next = at;
        }
        return next;
    }

    /** 将一天中的分钟格式化为时分。 */
    static String clockTime(int time) { return String.format(Locale.ROOT, "%02d:%02d", time / 60, time % 60); }

    /** 以本机时区显示日期时间。 */
    static String format(long when) { return new SimpleDateFormat("MM月dd日 HH:mm:ss", Locale.CHINA).format(new Date(when)); }

    /** 保留最近六条执行记录，不将发出请求误报为已进入超禅。 */
    static void record(Context c, String message) {
        String line = format(System.currentTimeMillis()) + "  " + message;
        String[] lines = (line + "\n" + prefs(c).getString("log", "")).split("\n");
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < Math.min(6, lines.length); i++) {
            if (i > 0) kept.append('\n');
            kept.append(lines[i]);
        }
        prefs(c).edit().putString("log", kept.toString()).commit();
        android.util.Log.i("ZenAlarm", line);
    }
}
