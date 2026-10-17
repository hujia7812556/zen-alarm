package com.hujia.zenalarm;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 计算本地时区下一次执行时间，不补执行已经错过的时间。 */
public final class AlarmTime {
    /** 禁止实例化纯计算工具。 */
    private AlarmTime() {}

    /** 返回严格晚于当前时间的下一次每日闹钟，跨日时按日历而非固定 24 小时计算。 */
    public static long next(long now, ZoneId zone, int hour, int minute) {
        ZonedDateTime current = Instant.ofEpochMilli(now).atZone(zone);
        ZonedDateTime target = current.toLocalDate().atTime(hour, minute).atZone(zone);
        if (!target.toInstant().isAfter(current.toInstant())) {
            target = current.toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone);
        }
        return target.toInstant().toEpochMilli();
    }
}
