package com.hujia.zenalarm;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 保存多个每日时间，以一天中的分钟数作为稳定标识，禁止重复时间。 */
final class ScheduleStore {
    /** 一条每日定时；同一时间仅保存一次。 */
    static final class Slot implements Comparable<Slot> {
        final int time;
        final boolean enabled;

        /** 创建定时，时间范围为 00:00 至 23:59。 */
        Slot(int time, boolean enabled) {
            if (time < 0 || time >= 1440) throw new IllegalArgumentException("时间无效");
            this.time = time;
            this.enabled = time == FixedTime.TIME || enabled;
        }

        /** 按时分排序，方便浏览。 */
        @Override public int compareTo(Slot other) { return Integer.compare(time, other.time); }
    }

    /** 禁止实例化存储工具。 */
    private ScheduleStore() {}

    /** 首次升级保留旧版时分；固定 01:00 由统一存取规则始终补齐并开启。 */
    static boolean migrate(Context c) {
        SharedPreferences p = ZenScheduler.prefs(c);
        if (p.contains("slots")) return false;
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot(p.getInt("hour", 1) * 60 + p.getInt("minute", 0), true));
        save(c, slots);
        return true;
    }

    /** 读取全部定时，固定 01:00 始终存在且启用，其他条目保持原设置。 */
    static List<Slot> load(Context c) {
        List<Slot> slots = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(ZenScheduler.prefs(c).getString("slots", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                int time = item.getInt("time");
                if (time >= 0 && time < 1440 && find(slots, time) == null)
                    slots.add(new Slot(time, item.optBoolean("enabled", true)));
            }
        } catch (JSONException e) { android.util.Log.e("ZenAlarm", "定时记录读取失败", e); }
        if (find(slots, FixedTime.TIME) == null) slots.add(new Slot(FixedTime.TIME, true));
        Collections.sort(slots);
        return slots;
    }

    /** 找到指定时分对应的定时。 */
    static Slot find(List<Slot> slots, int time) {
        for (Slot slot : slots) if (slot.time == time) return slot;
        return null;
    }

    /** 一次写入完整列表；即便调用方遗漏或关闭 01:00，也在存储层恢复固定项。 */
    static void save(Context c, List<Slot> slots) {
        List<Slot> normalized = new ArrayList<>();
        normalized.add(new Slot(FixedTime.TIME, true));
        for (Slot slot : slots) if (find(normalized, slot.time) == null) normalized.add(slot);
        JSONArray array = new JSONArray();
        try {
            for (Slot slot : normalized) {
                JSONObject item = new JSONObject();
                item.put("time", slot.time);
                item.put("enabled", slot.enabled);
                array.put(item);
            }
        } catch (JSONException e) { throw new IllegalStateException(e); }
        ZenScheduler.prefs(c).edit().putString("slots", array.toString()).commit();
    }
}
