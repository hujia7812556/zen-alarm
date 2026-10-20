package com.hujia.zenalarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** 用明确的前台通知守护已有预约，不以轮询或重复闹钟代替系统调度。 */
public final class ZenGuardService extends Service {
    private static final String CHANNEL = "schedule_guard";
    private static final int NOTIFICATION_ID = 9000;
    private static volatile boolean running;

    /** 返回仍有效的最近预约，固定 01:00 始终参与守护。 */
    static long nextTask(Context c) {
        SharedPreferences p = ZenScheduler.prefs(c);
        long now = System.currentTimeMillis();
        long test = p.getLong("test_at", 0);
        long next = test > now ? test : 0;
        for (ScheduleStore.Slot slot : ScheduleStore.load(c)) {
            long at = p.getLong("next_" + slot.time, 0);
            if (slot.enabled && at > now && (next == 0 || at < next)) next = at;
        }
        return next;
    }

    /** 随配置和预约同步服务状态；启动失败不会取消已经登记的闹钟。 */
    static void sync(Context c) {
        Intent intent = new Intent(c, ZenGuardService.class);
        try {
            if (nextTask(c) == 0 || !ZenScheduler.missing(c).isEmpty()) {
                c.stopService(intent);
                ZenScheduler.prefs(c).edit().remove("guard_error").apply();
                return;
            }
            c.startForegroundService(intent);
        } catch (RuntimeException e) {
            failure(c, e);
        }
    }

    /** 供设置页展示实际运行状态，固定定时不提供关闭守护的入口。 */
    static String status(Context c) {
        String missing = ZenScheduler.missing(c);
        if (!missing.isEmpty()) return "等待完成运行设置：" + missing;
        if (nextTask(c) == 0) return "没有待执行的定时，守护已暂停";
        if (running) return "正在运行 · 下次启动：" + ZenScheduler.format(nextTask(c));
        String error = ZenScheduler.prefs(c).getString("guard_error", "");
        return error.isEmpty() ? "正在启动守护" : "守护未运行：" + error;
    }

    /** 保存可见的失败原因，不通过循环重启掩盖系统拒绝。 */
    private static void failure(Context c, RuntimeException error) {
        String reason = error.getClass().getSimpleName();
        ZenScheduler.prefs(c).edit().putString("guard_error", reason).commit();
        ZenScheduler.record(c, "后台定时守护失败：" + reason);
    }

    /** 建立独立的低优先级通知频道，不改变五分钟提醒的声音与权限。 */
    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "定时守护", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示后台定时守护状态，与启动前五分钟提醒分开设置");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    /** 服务首次启动即进入前台；系统重建时只恢复未来预约，不补执行过期任务。 */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent == null) ZenScheduler.scheduleDaily(this, false);
            long next = nextTask(this);
            if (next == 0 || !ZenScheduler.missing(this).isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
            PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION_ID,
                    new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            String message = "下次启动：" + ZenScheduler.format(next);
            Notification notification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle("超禅定时守护中")
                    .setContentText(message)
                    .setContentIntent(open).setCategory(Notification.CATEGORY_SERVICE)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setShowWhen(false)
                    .setOngoing(true).setOnlyAlertOnce(true)
                    .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE).build();
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID, notification);
            if (!running) ZenScheduler.record(this, intent == null ? "后台定时守护已恢复" : "后台定时守护已启动");
            running = true;
            ZenScheduler.prefs(this).edit().remove("guard_error").apply();
            return START_STICKY;
        } catch (RuntimeException e) {
            running = false;
            failure(this, e);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    /** 界面任务被划掉时保留现有预约，不取消、不重排，也不抢回前台。 */
    @Override public void onTaskRemoved(Intent rootIntent) {
        ZenScheduler.record(this, "界面已移除，后台守护保留现有预约");
        super.onTaskRemoved(rootIntent);
    }

    /** 服务停止后立即清除进程内状态，不自行重新启动已被用户关闭的守护。 */
    @Override public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    /** 守护只接受本应用的显式启动，不提供绑定或外部控制接口。 */
    @Override public IBinder onBind(Intent intent) { return null; }
}
