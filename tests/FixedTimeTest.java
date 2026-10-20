package com.hujia.zenalarm;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/** 确定性验证固定 01:00、推迟窗口、周限额、重载及跨日确认。 */
public final class FixedTimeTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static int checks;

    /** 将测试使用的明确日期时间转为毫秒，不依赖运行机器的时间。 */
    private static long at(String value) { return ZonedDateTime.parse(value).toInstant().toEpochMilli(); }

    /** 检查一项业务约束并累计检查数量。 */
    private static void check(boolean valid, String name) {
        if (!valid) throw new AssertionError(name);
        checks++;
    }

    /** 比较某一时刻应预约的实际执行时间。 */
    private static void next(String now, String expected, Set<LocalDate> dates) {
        check(FixedTime.next(at(now), ZONE, dates) == at(expected), now + " -> " + expected);
    }

    /** 模拟用户在指定时刻确认页面显示的下一次定时。 */
    private static Set<LocalDate> approve(String now, Set<LocalDate> dates) {
        long time = at(now);
        return FixedTime.postpone(time, ZONE, dates, FixedTime.nextDate(time, ZONE, dates));
    }

    /** 确认非法操作被拒绝，且不修改调用方已经持有的额度记录。 */
    private static void rejected(Runnable action, Set<LocalDate> dates, String name) {
        String original = FixedTime.encode(dates);
        try { action.run(); throw new AssertionError(name + " 未拒绝"); }
        catch (IllegalStateException expected) { check(original.equals(FixedTime.encode(dates)), name); }
    }

    /** 覆盖两次上限、周一刷新、年界、持久化、当天不可叠加和确认跨过执行时间。 */
    public static void main(String[] args) {
        Set<LocalDate> empty = FixedTime.decode("");
        next("2026-10-20T00:59:59+08:00", "2026-10-20T01:00:00+08:00", empty);
        next("2026-10-20T01:00:00+08:00", "2026-10-21T01:00:00+08:00", empty);
        Set<LocalDate> once = approve("2026-10-20T00:10:00+08:00", empty);
        check(empty.isEmpty() && once.size() == 1, "申请使用副本，不提前改变原记录");
        next("2026-10-20T00:59:59+08:00", "2026-10-20T01:30:00+08:00", once);
        next("2026-10-20T01:10:00+08:00", "2026-10-20T01:30:00+08:00", once);
        next("2026-10-20T01:30:00+08:00", "2026-10-21T01:00:00+08:00", once);
        rejected(() -> approve("2026-10-20T01:10:00+08:00", once), once, "同一天不能叠加到 02:00");
        Set<LocalDate> twice = approve("2026-10-20T15:00:00+08:00", once);
        check(FixedTime.remaining(LocalDate.parse("2026-10-22"), twice) == 0, "两次后该周额度为零");
        rejected(() -> approve("2026-10-22T00:10:00+08:00", twice), twice, "拒绝一周第三次");
        Set<LocalDate> reloaded = FixedTime.decode(FixedTime.encode(twice));
        check(reloaded.equals(twice), "重新打开保留次数和日期");
        next("2026-10-21T01:15:00+08:00", "2026-10-21T01:30:00+08:00", reloaded);
        rejected(() -> approve("2026-10-22T00:10:00+08:00", reloaded), reloaded, "重载后不能重获额度");
        Set<LocalDate> sunday = FixedTime.decode("2026-10-20\n2026-10-25");
        check(FixedTime.remaining(LocalDate.parse("2026-10-25"), sunday) == 0, "周日仍属于当前周");
        check(FixedTime.remaining(LocalDate.parse("2026-10-26"), sunday) == 2, "周一进入新的两次额度");
        Set<LocalDate> monday = approve("2026-10-25T15:00:00+08:00", sunday);
        check(monday.contains(LocalDate.parse("2026-10-26")) && FixedTime.remaining(LocalDate.parse("2026-10-26"), monday) == 1,
                "提前申请按下次执行日所属周计数");
        Set<LocalDate> year = FixedTime.decode("2026-12-31\n2027-01-01");
        check(FixedTime.remaining(LocalDate.parse("2027-01-03"), year) == 0, "跨年不重复刷新同一日历周额度");
        check(FixedTime.remaining(LocalDate.parse("2027-01-04"), year) == 2, "跨年后的周一正常重置");
        rejected(() -> FixedTime.postpone(at("2026-10-20T01:00:01+08:00"), ZONE, empty, LocalDate.parse("2026-10-20")),
                empty, "过期确认不能转而消耗次日机会");
        check(FixedTime.next(at("2026-10-20T00:10:00+08:00"), ZONE, once) - 300000 == at("2026-10-20T01:25:00+08:00"),
                "推迟后的五分钟提醒为 01:25");
        check(FixedTime.decode(FixedTime.encode(once) + "\n2026-10-20\ninvalid").equals(once), "去重且不把损坏日期算作新机会");
        System.out.println("PASS: " + checks + " 个固定定时与推迟规则检查");
    }
}
