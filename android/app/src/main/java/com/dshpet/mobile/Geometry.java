package com.dshpet.mobile;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.util.Log;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 屏幕几何（单位：物理像素 = Android 的 device px）。
 *
 * 形状与 dsh-pet electron-helper/main.js 的 deskGeometry() 严格一致：
 *   { hull:{x,y,width,height}, areas:[...], workAreas:[...], panels:[...], primaryIndex, topInsetRatio }
 *
 * 约定（见 dsh-pet DESIGN §3.5）：
 *  - 页面内 DOM 用 CSS px；页面 URL 带 scale=<dpr>，sprite 通过 toScreen()=×dpr 把 CSS 换算成物理像素后
 *    经 setBounds 上报；
 *  - 本机只有一块屏：hull = 整屏；areas/workAreas = 去掉状态栏/导航栏的安全区；panels = 整屏。
 */
final class Geometry {

    private static final String TAG = "DSHPet";

    /** APK 内页面/资源统一使用的虚拟同源 origin（由 shouldInterceptRequest 拦截，全部走 assets） */
    static final String ORIGIN = "https://pet.local";
    static final String PREFIX = "/dsh-pet-7340";
    static final String CONFIG_URL = ORIGIN + PREFIX + "/config";

    /** config.json 里 __MARGIN_X__ / __MARGIN_Y__ 的运行时取值（CSS px，与 dsh-pet 桌面版默认同量级） */
    static final int MARGIN_X_CSS = 24;
    static final int MARGIN_Y_CSS = 100;

    final int screenW;
    final int screenH;
    final float density; // dpr
    final int safeLeft;
    final int safeTop;
    final int safeRight;
    final int safeBottom;

    private Geometry(int screenW, int screenH, float density, int l, int t, int r, int b) {
        this.screenW = Math.max(1, screenW);
        this.screenH = Math.max(1, screenH);
        this.density = density > 0.01f ? density : 1f;
        this.safeLeft = Math.max(0, l);
        this.safeTop = Math.max(0, t);
        this.safeRight = Math.min(this.screenW, Math.max(this.safeLeft + 1, r));
        this.safeBottom = Math.min(this.screenH, Math.max(this.safeTop + 1, b));
    }

    static Geometry compute(Context ctx) {
        float density = 1f;
        try {
            density = ctx.getResources().getDisplayMetrics().density;
        } catch (Throwable t) {
            Log.w(TAG, "[geom] density 读取失败: " + t);
        }
        if (!(density > 0.01f)) density = 1f;

        int w = 0;
        int h = 0;
        int sl = 0;
        int st = 0;
        int sr = 0;
        int sb = 0;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            WindowMetrics metrics = wm.getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            w = bounds.width();
            h = bounds.height();
            WindowInsets insets = metrics.getWindowInsets();
            Insets bars = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            sl = bars.left;
            st = bars.top;
            sr = bars.right;
            sb = bars.bottom;
        } catch (Throwable t) {
            Log.w(TAG, "[geom] WindowMetrics/Insets 读取失败，退化到整屏: " + t);
        }
        if (w <= 0 || h <= 0) {
            try {
                w = ctx.getResources().getDisplayMetrics().widthPixels;
                h = ctx.getResources().getDisplayMetrics().heightPixels;
            } catch (Throwable ignored) {
            }
        }
        if (w <= 0 || h <= 0) {
            w = 1080;
            h = 2400;
        }
        return new Geometry(w, h, density, sl, st, w - sr, h - sb);
    }

    /** 桌面几何 JSON（物理像素），供 window.__petApplyDisplays 使用 */
    String geoJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("hull", rectJson(0, 0, screenW, screenH));
            JSONArray areas = new JSONArray();
            areas.put(rectJson(safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop));
            JSONArray workAreas = new JSONArray();
            workAreas.put(rectJson(safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop));
            JSONArray panels = new JSONArray();
            panels.put(rectJson(0, 0, screenW, screenH));
            root.put("areas", areas);
            root.put("workAreas", workAreas);
            root.put("panels", panels);
            root.put("primaryIndex", 0);
            root.put("topInsetRatio", 0);
            return root.toString();
        } catch (Throwable t) {
            Log.e(TAG, "[geom] geoJson 构建失败: " + t);
            return "{\"hull\":{\"x\":0,\"y\":0,\"width\":" + screenW + ",\"height\":" + screenH + "},"
                    + "\"areas\":[],\"workAreas\":[],\"panels\":[],\"primaryIndex\":0,\"topInsetRatio\":0}";
        }
    }

    /**
     * 页面 URL（全部参数 URLEncode）。与 dsh-pet electron main.js 的 loadFile query 对齐：
     * configUrl/scale/petIndex/workAreaX/Y/W/H/areas/panels/workAreas/primaryIndex/openTarget。
     * 额外带 petSize（仅调试占位页使用，真实页面忽略未知参数）。
     */
    String pageUrl(int petSizeDp) {
        StringBuilder sb = new StringBuilder(ORIGIN).append(PREFIX).append("/?");
        appendParam(sb, "configUrl", CONFIG_URL);
        appendParam(sb, "scale", String.valueOf(density));
        appendParam(sb, "petSize", String.valueOf(petSizeDp));
        appendParam(sb, "petIndex", "0");
        appendParam(sb, "openTarget", "site");
        appendParam(sb, "workAreaX", "0");
        appendParam(sb, "workAreaY", "0");
        appendParam(sb, "workAreaW", String.valueOf(screenW));
        appendParam(sb, "workAreaH", String.valueOf(screenH));
        try {
            JSONArray areas = new JSONArray();
            areas.put(rectJson(safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop));
            JSONArray panels = new JSONArray();
            panels.put(rectJson(0, 0, screenW, screenH));
            appendParam(sb, "areas", areas.toString());
            appendParam(sb, "workAreas", areas.toString());
            appendParam(sb, "panels", panels.toString());
        } catch (Throwable t) {
            Log.e(TAG, "[geom] pageUrl 几何参数构建失败: " + t);
        }
        appendParam(sb, "primaryIndex", "0");
        appendParam(sb, "topInsetRatio", "0");
        return sb.toString();
    }

    /** 初始窗口尺寸（物理像素）——与 electron main.js 的 petWindowSize() 同公式，避免首帧尺寸跳变 */
    static int[] initialWindowSizePx(int petSizeDp, float density) {
        float size = petSizeDp;
        float stageH = size * 9f / 16f;
        float bottomPad = stageH * 30f / 360f;
        int margin = Math.round(size * 0.5f);
        int cssW = Math.round(size) + margin * 2;
        int cssH = Math.round(stageH + bottomPad) + margin * 2;
        return new int[]{Math.round(cssW * density), Math.round(cssH * density)};
    }

    private static JSONObject rectJson(int x, int y, int w, int h) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("x", x);
        o.put("y", y);
        o.put("width", w);
        o.put("height", h);
        return o;
    }

    private static void appendParam(StringBuilder sb, String name, String value) {
        sb.append(name).append('=').append(Uri.encode(value == null ? "" : value)).append('&');
    }

    @Override
    public String toString() {
        return "Geometry{" + screenW + "x" + screenH + " dpr=" + density
                + " safe=[" + safeLeft + "," + safeTop + "," + safeRight + "," + safeBottom + "]}";
    }
}
