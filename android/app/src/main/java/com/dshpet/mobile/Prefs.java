package com.dshpet.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 用户设置：
 *  - 宠物大小 80–200dp，默认 130dp
 *  - 宠物透明度 30%–80%，默认 80%（Android 12+ 对「不可触摸」悬浮窗限制 alpha≤0.80 才能
 *    让触摸穿透到下层 App，因此 100% 不透明不可行；调低会让宠物更透明）
 *  - 电脑 DeepSeek Harness 地址与「最近一次测试连接是否成功」（connected 为 true 才会启用
 *    余额/碎碎念/对话/工作状态；默认 false = 纯降级，与 v1.1.1 行为一致）
 */
final class Prefs {

    private static final String FILE = "dshpet";
    private static final String KEY_PET_SIZE_DP = "pet_size_dp";
    private static final String KEY_PET_ALPHA_PCT = "pet_alpha_pct";
    private static final String KEY_HARNESS_BASE = "harness_base";
    private static final String KEY_HARNESS_CONNECTED = "harness_connected";

    /** 电脑 DSH 宿主默认地址（USB 连接时配合 adb reverse tcp:3412 tcp:3412） */
    static final String DEFAULT_HARNESS_BASE = "http://127.0.0.1:3412";

    static final int DEFAULT_PET_SIZE_DP = 130;
    static final int MIN_PET_SIZE_DP = 80;
    static final int MAX_PET_SIZE_DP = 200;

    /** 渲染窗不透明度百分比：默认/上限 80%，最低 30% */
    static final int DEFAULT_PET_ALPHA_PCT = 80;
    static final int MIN_PET_ALPHA_PCT = 30;
    static final int MAX_PET_ALPHA_PCT = 80;

    private Prefs() {
    }

    static int getPetSizeDp(Context ctx) {
        int v = DEFAULT_PET_SIZE_DP;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            v = sp.getInt(KEY_PET_SIZE_DP, DEFAULT_PET_SIZE_DP);
        } catch (Throwable ignored) {
        }
        return clampSize(v);
    }

    static void setPetSizeDp(Context ctx, int dp) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            sp.edit().putInt(KEY_PET_SIZE_DP, clampSize(dp)).apply();
        } catch (Throwable ignored) {
        }
    }

    static int clampSize(int dp) {
        if (dp < MIN_PET_SIZE_DP) return MIN_PET_SIZE_DP;
        if (dp > MAX_PET_SIZE_DP) return MAX_PET_SIZE_DP;
        return dp;
    }

    /** 渲染窗不透明度（0.30f–0.80f），默认 0.80f */
    static float getPetAlpha(Context ctx) {
        int pct = DEFAULT_PET_ALPHA_PCT;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            pct = sp.getInt(KEY_PET_ALPHA_PCT, DEFAULT_PET_ALPHA_PCT);
        } catch (Throwable ignored) {
        }
        return clampAlphaPct(pct) / 100f;
    }

    static void setPetAlpha(Context ctx, float alpha) {
        int pct = Math.round(alpha * 100f);
        try {
            SharedPreferences sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            sp.edit().putInt(KEY_PET_ALPHA_PCT, clampAlphaPct(pct)).apply();
        } catch (Throwable ignored) {
        }
    }

    static int clampAlphaPct(int pct) {
        if (pct < MIN_PET_ALPHA_PCT) return MIN_PET_ALPHA_PCT;
        if (pct > MAX_PET_ALPHA_PCT) return MAX_PET_ALPHA_PCT;
        return pct;
    }

    // ---------- 电脑 DeepSeek Harness ----------

    /** 规范化地址：去首尾空白、去掉末尾斜杠；非法/空返回默认地址 */
    static String normalizeHarnessBase(String base) {
        String s = base == null ? "" : base.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return DEFAULT_HARNESS_BASE;
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        return s;
    }

    static String getHarnessBase(Context ctx) {
        String v = DEFAULT_HARNESS_BASE;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            v = sp.getString(KEY_HARNESS_BASE, DEFAULT_HARNESS_BASE);
        } catch (Throwable ignored) {
        }
        return normalizeHarnessBase(v);
    }

    static void setHarnessBase(Context ctx, String base) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_HARNESS_BASE, normalizeHarnessBase(base)).apply();
        } catch (Throwable ignored) {
        }
    }

    /** true = 用户配置过地址且最近一次「测试连接」成功；只有此时才启用 DSH 联动功能 */
    static boolean isHarnessConnected(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            return sp.getBoolean(KEY_HARNESS_CONNECTED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void setHarnessConnected(Context ctx, boolean connected) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            sp.edit().putBoolean(KEY_HARNESS_CONNECTED, connected).apply();
        } catch (Throwable ignored) {
        }
    }
}
