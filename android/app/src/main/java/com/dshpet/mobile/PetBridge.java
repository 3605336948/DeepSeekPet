package com.dshpet.mobile;

import android.util.Log;
import android.webkit.JavascriptInterface;

/**
 * 注入到页面 window.AndroidPet 的 JS 桥（与 T1 的 mobile-bridge.js 契约一一对应，见 SPEC §2.3）：
 *
 *   setBounds(x, y, width, height, boxX, boxY, size, bottomPad, vx, vy)  物理像素，逐帧调用
 *   setInteractive(flag)        光标在不在宠物身体上（手机无 hover，仅记录）
 *   setInputBusy(busy)          拖拽中 / 菜单开 / 弹窗开
 *   setInputRegions(jsonString) {"dragging":bool,"rects":[{x,y,width,height}]} 物理像素、窗口内坐标
 *   openUrl(url)                外部链接交给系统浏览器
 *   harnessAvailable() / harnessFetch(method,path,body,cbId) / setChatMode(open)
 *                               电脑 DeepSeek Harness 联动（v1.1.2，见 PetOverlayService）
 *
 * 这些方法在 WebView 的 JavaBridge 线程被调用，全部转投主线程处理。
 */
final class PetBridge {

    static final String NAME = "AndroidPet";
    private static final String TAG = "DSHPet";

    private final PetOverlayService service;

    PetBridge(PetOverlayService service) {
        this.service = service;
    }

    @JavascriptInterface
    public void setBounds(double x, double y, double width, double height,
                          double boxX, double boxY, double size, double bottomPad,
                          double vx, double vy) {
        service.onJsSetBounds(x, y, width, height);
    }

    @JavascriptInterface
    public void setInteractive(boolean flag) {
        service.onJsSetInteractive(flag);
    }

    @JavascriptInterface
    public void setInputBusy(boolean busy) {
        service.onJsSetInputBusy(busy);
    }

    @JavascriptInterface
    public void setInputRegions(String json) {
        service.onJsSetInputRegions(json);
    }

    @JavascriptInterface
    public void openUrl(String url) {
        service.onJsOpenUrl(url);
    }

    /** 当前悬浮窗物理位置/尺寸 + dpr/屏幕尺寸（JSON 字符串）——给 screenX 修正等用途 */
    @JavascriptInterface
    public String getWindowOrigin() {
        return service.getWindowOriginJson();
    }

    /** 诊断信息（JSON 字符串） */
    @JavascriptInterface
    public String getInfo() {
        return service.getInfoJson();
    }

    /** 页面日志 → logcat（QA 排查用） */
    @JavascriptInterface
    public void log(String msg) {
        Log.i(TAG, "[js] " + msg);
    }

    // =====================================================================
    // 电脑 DeepSeek Harness 联动（v1.1.2）
    // =====================================================================

    /** "1" = 已配置且最近一次测试连接成功；"0" = 降级（页面应直接返回降级 JSON、不发 HTTP） */
    @JavascriptInterface
    public String harnessAvailable() {
        return service.harnessAvailable();
    }

    /**
     * 代页面请求电脑上的 DSH 宿主（fetch 代理；静态资源仍走 assets，不经过这里）。
     *
     * @param method GET/POST
     * @param path   已含 /dsh-pet-7340 前缀，如 /dsh-pet-7340/state
     * @param body   请求体文本（GET 可为空）；非空时按 application/json 发送
     * @param cbId   回调 id，Android 完成后调用 window.__petportHarnessDone(cbId, payloadJson)
     */
    @JavascriptInterface
    public void harnessFetch(String method, String path, String body, String cbId) {
        service.harnessFetch(method, path, body, cbId);
    }

    /** 对话面板开/关：open=true 临时让渲染窗可取焦点并弹出输入法，false 恢复不可聚焦 */
    @JavascriptInterface
    public void setChatMode(boolean open) {
        service.setChatMode(open);
    }
}
