package com.hujia.zenalarm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 严格解析超禅卡片的分钟文案，避免把时间点或其他页面文字当作时长。 */
public final class DurationText {
    private static final Pattern MINUTES = Pattern.compile("^([0-9]{1,4})\\s*(?:分钟|分鐘|minutes?|mins?)$", Pattern.CASE_INSENSITIVE);

    /** 禁止实例化纯文本工具。 */
    private DurationText() {}

    /** 仅接受完整分钟文案；未知格式返回零，不覆盖已读记录。 */
    static int parse(CharSequence text) {
        if (text == null) return 0;
        Matcher matcher = MINUTES.matcher(text.toString().replace('\u00a0', ' ').trim());
        if (!matcher.matches()) return 0;
        int minutes = Integer.parseInt(matcher.group(1));
        return minutes >= 1 && minutes <= 1440 ? minutes : 0;
    }
}
