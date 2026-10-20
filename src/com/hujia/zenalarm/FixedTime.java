package com.hujia.zenalarm;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;
import java.util.TreeSet;

/** 计算不可关闭的每日 01:00，以及按执行日期统计的每周两次半小时推迟。 */
public final class FixedTime {
    static final int TIME = 60;
    static final int WEEKLY_LIMIT = 2;

    /** 禁止实例化纯规则工具。 */
    private FixedTime() {}

    /** 读取持久化的已推迟日期，去重且不把历史记录随跨周或重开清空。 */
    static Set<LocalDate> decode(String value) {
        Set<LocalDate> dates = new TreeSet<>();
        for (String token : value.split("\\n")) {
            if (token.isEmpty()) continue;
            try { dates.add(LocalDate.parse(token)); }
            catch (DateTimeParseException ignored) { /* 损坏日期不猜测、不转移到其他日期。 */ }
        }
        return dates;
    }

    /** 用稳定顺序保存日期，进程重建或重启后继续使用同一份次数记录。 */
    static String encode(Set<LocalDate> dates) {
        StringBuilder value = new StringBuilder();
        for (LocalDate date : new TreeSet<>(dates)) {
            if (value.length() > 0) value.append('\n');
            value.append(date);
        }
        return value.toString();
    }

    /** 以周一为一周起点，跨月和跨年也使用实际日历周。 */
    static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** 返回指定执行日期所属周的剩余机会，不借用上一周或下一周的额度。 */
    static int remaining(LocalDate date, Set<LocalDate> postponed) {
        LocalDate week = weekStart(date);
        int used = 0;
        for (LocalDate approved : postponed) if (weekStart(approved).equals(week)) used++;
        return Math.max(0, WEEKLY_LIMIT - used);
    }

    /** 只计算未来执行时间；已推迟的当天在 01:00 后仍保留 01:30，不补执行过期任务。 */
    static long next(long now, ZoneId zone, Set<LocalDate> postponed) {
        LocalDate date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        while (true) {
            long at = date.atTime(1, postponed.contains(date) ? 30 : 0).atZone(zone).toInstant().toEpochMilli();
            if (at > now) return at;
            date = date.plusDays(1);
        }
    }

    /** 取得下一次固定任务的本地执行日期，供展示、次数和申请共同使用。 */
    static LocalDate nextDate(long now, ZoneId zone, Set<LocalDate> postponed) {
        return Instant.ofEpochMilli(next(now, zone, postponed)).atZone(zone).toLocalDate();
    }

    /** 申请仅推迟下一次任务，拒绝同日叠加和一周第三次，并保持输入记录不变。 */
    static Set<LocalDate> postpone(long now, ZoneId zone, Set<LocalDate> postponed, LocalDate expectedDate) {
        LocalDate date = nextDate(now, zone, postponed);
        if (!date.equals(expectedDate)) throw new IllegalStateException("定时时间已变化，请重新确认推迟");
        if (postponed.contains(date)) throw new IllegalStateException("本次已推迟至 01:30，不能叠加推迟");
        if (remaining(date, postponed) == 0) throw new IllegalStateException("该周的两次推迟机会已用完");
        Set<LocalDate> approved = new TreeSet<>(postponed);
        approved.add(date);
        return approved;
    }
}
