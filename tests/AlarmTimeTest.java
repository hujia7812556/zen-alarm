package com.hujia.zenalarm;

import java.time.ZonedDateTime;
import java.time.ZoneId;

/** 验证日界、已过时间与夏令时，防止保存配置后立即误启动。 */
public final class AlarmTimeTest {
    /** 对给定时区的输入和预期执行时间进行比较。 */
    private static void check(String now, String expected, String zone, int hour, int minute) {
        long actual = AlarmTime.next(ZonedDateTime.parse(now).toInstant().toEpochMilli(), ZoneId.of(zone), hour, minute);
        long wanted = ZonedDateTime.parse(expected).toInstant().toEpochMilli();
        if (actual != wanted) throw new AssertionError(now + " -> " + actual + ", expected " + wanted);
    }

    /** 执行与每日 01:00 和改时功能直接相关的边界检查。 */
    public static void main(String[] args) {
        check("2026-10-18T00:59:59+08:00", "2026-10-18T01:00:00+08:00", "Asia/Shanghai", 1, 0);
        check("2026-10-18T01:00:00+08:00", "2026-10-19T01:00:00+08:00", "Asia/Shanghai", 1, 0);
        check("2026-10-18T02:30:00+08:00", "2026-10-19T01:00:00+08:00", "Asia/Shanghai", 1, 0);
        check("2026-12-31T23:59:59+08:00", "2027-01-01T00:00:00+08:00", "Asia/Shanghai", 0, 0);
        check("2026-10-18T02:30:00+08:00", "2026-10-18T03:45:00+08:00", "Asia/Shanghai", 3, 45);
        check("2026-03-07T23:00:00-05:00", "2026-03-08T03:30:00-04:00", "America/New_York", 2, 30);
        System.out.println("PASS: 6 个时间计算边界检查");
    }
}
