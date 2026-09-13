package com.deepseek.whalepet;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 峰谷时段判定与文案：
 * <p>
 * - 高峰：周一至周五（北京时间）9:00–12:00 与 14:00–18:00；
 * - 其余时段（含周末全天）按空闲（谷）价。
 * <p>
 * 口径对齐 DeepSeek 2026-09-10 起生效的 V4.1 Flash 计费调整。
 */
public final class PeakPricing {

    /** 高峰时段区间（北京时间，左闭右开）。 */
    private static final int[][] PEAK_HOURS = {{9, 12}, {14, 18}};

    private static final TimeZone BEIJING = TimeZone.getTimeZone("GMT+8");

    public static final String PEAK_DEFAULT = "高峰时段";
    public static final String OFF_DEFAULT = "空闲时段";
    public static final int COLOR_PEAK = 0xFFE0433F;
    public static final int COLOR_OFF = 0xFF2FA24C;

    private PeakPricing() {
    }

    /** 该时刻是否处于高峰时段。入参是 epoch 秒。 */
    public static boolean isPeak(long epochSec) {
        if (!(epochSec > 0)) {
            return false;
        }
        Calendar c = Calendar.getInstance(BEIJING);
        c.setTimeInMillis(epochSec * 1000L);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
            return false;   // 周末全天谷价
        }
        int hour = c.get(Calendar.HOUR_OF_DAY);
        for (int[] span : PEAK_HOURS) {
            if (hour >= span[0] && hour < span[1]) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPeakNow() {
        return isPeak(System.currentTimeMillis() / 1000L);
    }

    /** 峰谷文案，对齐原版 buildGroup1 里的 peakMode 分支。 */
    public static String peakText(String mode, boolean peak) {
        String m = Prefs.normalizePeakMode(mode);
        if ("liangwen".equals(m)) {
            return peak ? "梁文峰" : "梁文谷";
        }
        if ("qiangqiang".equals(m)) {
            return peak ? "!?峰峰?!" : "!?谷谷?!";
        }
        return peak ? PEAK_DEFAULT : OFF_DEFAULT;
    }

    public static int peakColor(boolean peak) {
        return peak ? COLOR_PEAK : COLOR_OFF;
    }
}
