package com.hujia.zenalarm;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;

/** 接收系统闹钟、重启与时区变更，直接调用已验证的超禅入口。 */
public final class AlarmReceiver extends BroadcastReceiver {
    /** 处理一次有效预约；失效、重复或明显迟到的广播不会意外锁定手机。 */
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        boolean daily = ZenScheduler.DAILY.equals(action);
        boolean test = ZenScheduler.TEST.equals(action);
        boolean reminder = ZenScheduler.REMIND.equals(action);
        if (!daily && !test && !reminder) {
            ZenScheduler.scheduleDaily(c);
            return;
        }
        SharedPreferences p = ZenScheduler.prefs(c);
        int time = intent.getIntExtra("time", -1);
        ScheduleStore.Slot slot = ScheduleStore.find(ScheduleStore.load(c), time);
        if (!test && (slot == null || !slot.enabled || !p.getBoolean("enabled", true))) return;
        long expected = p.getLong(test ? "test_at" : "next_" + time, 0);
        long supplied = intent.getLongExtra("scheduled_at", 0);
        if (expected == 0 || supplied != expected) return;
        if (reminder) {
            if (System.currentTimeMillis() < expected && ZenScheduler.missing(c).isEmpty())
                ZenScheduler.remind(c, time, expected);
            return;
        }
        long delay = System.currentTimeMillis() - expected;
        if (test) ZenScheduler.cancelTest(c);
        else ZenScheduler.scheduleSlot(c, slot);
        // 拒绝旧广播和严重延迟后的补执行，避免在用户意外的时刻进入超禅。
        if (delay < -2000 || delay > 10 * 60 * 1000L) {
            ZenScheduler.record(c, "跳过已过期的" + (test ? "测试" : "每日") + "任务");
            return;
        }
        String missing = ZenScheduler.missing(c);
        if (!missing.isEmpty()) {
            ZenScheduler.record(c, "未启动：" + missing);
            return;
        }
        boolean locked = ((KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked();
        boolean interactive = ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isInteractive();
        String state = (test ? "测试" : "每日 " + ZenScheduler.clockTime(time)) + " locked=" + locked + " interactive=" + interactive
                + " uid=" + android.os.Process.myUid();
        try {
            Intent start = new Intent().setClassName(ZenScheduler.ZEN_PACKAGE,
                    "com.oneplus.brickmode.activity.HomeActivity");
            // 清理超禅已有页面任务，使其重新读取启动参数；不修改系统锁屏状态。
            start.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            start.putExtra("showPromptDialog", true);
            start.putExtra("from_where", "super_zen");
            start.putExtra("zen_id", 0);
            c.startActivity(start);
            ZenScheduler.record(c, "已发出超禅启动请求；" + state);
        } catch (RuntimeException e) {
            ZenScheduler.record(c, "启动请求失败：" + e.getClass().getSimpleName() + "；" + state);
        }
    }
}
