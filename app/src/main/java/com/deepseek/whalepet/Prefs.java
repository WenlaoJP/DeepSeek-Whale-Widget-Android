package com.deepseek.whalepet;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地配置：API Key / 刷新间隔 / 阈值 / 大小 / 峰谷文案 / 气泡 / 位置 / 记账账本。 */
public final class Prefs {

    private static final String NAME = "whale_pet";

    /** 尺寸倍率区间：下限拉到 0.9×（再小就看不清了），步进 0.1，共 17 档。 */
    public static final float MIN_SCALE = 0.9f;
    public static final float MAX_SCALE = 2.5f;
    public static final float SCALE_STEP = 0.1f;
    /** 刻度总数（1~17）。 */
    public static final int SCALE_TICKS = 17;

    private Prefs() {
    }

    public static SharedPreferences of(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---- 凭据与刷新 ----

    public static String apiKey(Context c) {
        return of(c).getString("api_key", "");
    }

    public static void setApiKey(Context c, String v) {
        of(c).edit().putString("api_key", v == null ? "" : v.trim()).apply();
    }

    public static int refreshMinutes(Context c) {
        return Math.max(1, of(c).getInt("refresh_minutes", 15));
    }

    public static void setRefreshMinutes(Context c, int v) {
        of(c).edit().putInt("refresh_minutes", Math.max(1, v)).apply();
    }

    public static float threshold(Context c) {
        return of(c).getFloat("threshold", 5f);
    }

    public static void setThreshold(Context c, float v) {
        of(c).edit().putFloat("threshold", Math.max(0f, v)).apply();
    }

    // ---- 尺寸（0.6 ~ 2.5） ----

    public static float clampScale(float v) {
        if (Float.isNaN(v)) {
            return 1.5f;
        }
        return Math.min(MAX_SCALE, Math.max(MIN_SCALE, Math.round(v * 10f) / 10f));
    }

    /** 倍率 -> 1~17 显示刻度（步进 0.1）。 */
    public static int scaleToDisplay(float s) {
        int tick = Math.round((clampScale(s) - MIN_SCALE) / SCALE_STEP) + 1;
        return Math.max(1, Math.min(SCALE_TICKS, tick));
    }

    /** 1~17 显示刻度 -> 倍率。 */
    public static float displayToScale(int n) {
        int v = Math.max(1, Math.min(SCALE_TICKS, n));
        return clampScale(MIN_SCALE + (v - 1) * SCALE_STEP);
    }

    public static float scale(Context c) {
        SharedPreferences p = of(c);
        try {
            if (p.contains("scale_x")) {
                return clampScale(p.getFloat("scale_x", 1.5f));
            }
        } catch (Throwable ignored) {
        }
        // 兼容 v1.2.0 之前的旧键（int 百分比），迁移后不再读取
        int legacy = -1;
        try {
            legacy = p.getInt("scale", -1);
        } catch (Throwable ignored) {
        }
        if (legacy > 0) {
            float migrated = clampScale(legacy / 100f);
            p.edit().putFloat("scale_x", migrated).apply();
            return migrated;
        }
        return 1.5f;
    }

    public static void setScale(Context c, float v) {
        of(c).edit().putFloat("scale_x", clampScale(v)).apply();
    }

    // ---- 峰谷文案（default / liangwen / qiangqiang） ----

    public static String normalizePeakMode(String v) {
        return ("liangwen".equals(v) || "qiangqiang".equals(v)) ? v : "default";
    }

    public static String peakMode(Context c) {
        return normalizePeakMode(of(c).getString("peak_mode", "default"));
    }

    public static void setPeakMode(Context c, String v) {
        of(c).edit().putString("peak_mode", normalizePeakMode(v)).apply();
    }

    // ---- 气泡 / 开机启动 ----

    public static boolean showBubble(Context c) {
        return of(c).getBoolean("show_bubble", true);
    }

    public static void setShowBubble(Context c, boolean v) {
        of(c).edit().putBoolean("show_bubble", v).apply();
    }

    // ---- 音效（移植自 whale_v151） ----

    public static boolean soundOn(Context c) {
        return of(c).getBoolean("sound_on", true);
    }

    public static void setSoundOn(Context c, boolean v) {
        of(c).edit().putBoolean("sound_on", v).apply();
    }

    public static boolean autoStart(Context c) {
        return of(c).getBoolean("auto_start", false);
    }

    public static void setAutoStart(Context c, boolean v) {
        of(c).edit().putBoolean("auto_start", v).apply();
    }

    // ---- 隐藏菜单按钮 ----

    public static boolean hideMenu(Context c) {
        return of(c).getBoolean("hide_menu", false);
    }

    public static void setHideMenu(Context c, boolean v) {
        of(c).edit().putBoolean("hide_menu", v).apply();
    }
    // ---- 吸附与翻转（0.3.0 跟进） ----

    /** 吸附贴边后离屏幕边缘的距离（dp，0~200）。 */
    public static int snapEdgeDp(Context c) {
        return Math.max(0, Math.min(200, of(c).getInt("snap_edge_dp", 0)));
    }

    public static void setSnapEdgeDp(Context c, int v) {
        of(c).edit().putInt("snap_edge_dp", Math.max(0, Math.min(200, v))).apply();
    }

    /** 贴左时是否水平镜像翻转（关掉则立绘始终朝右）。 */
    public static boolean mirrorLeft(Context c) {
        return of(c).getBoolean("mirror_left", true);
    }

    public static void setMirrorLeft(Context c, boolean v) {
        of(c).edit().putBoolean("mirror_left", v).apply();
    }

    // ---- 点按角色推进泡泡队列（0.3.0 跟进） ----

    public static boolean tapAdvance(Context c) {
        return of(c).getBoolean("tap_advance", false);
    }

    public static void setTapAdvance(Context c, boolean v) {
        of(c).edit().putBoolean("tap_advance", v).apply();
    }

    // ---- 位置 ----


    public static int posX(Context c) {
        return of(c).getInt("pos_x", -1);
    }

    public static int posY(Context c) {
        return of(c).getInt("pos_y", -1);
    }

    public static void setPos(Context c, int x, int y) {
        of(c).edit().putInt("pos_x", x).putInt("pos_y", y).apply();
    }

    // ---- 小鲸鱼记账账本（JSON 字符串） ----

    public static String ledger(Context c) {
        return of(c).getString("ledger", "");
    }

    public static void setLedger(Context c, String json) {
        of(c).edit().putString("ledger", json == null ? "" : json).apply();
    }
}
