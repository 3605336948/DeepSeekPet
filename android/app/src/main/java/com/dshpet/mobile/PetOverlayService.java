package com.dshpet.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 桌宠前台服务：透明悬浮窗 + WebView（dsh-pet 移动版页面）。
 *
 * 完全复刻 dsh-pet Electron 宿主那一层：
 *  - 前台服务常驻（通知可关闭桌宠）；
 *  - WindowManager.addView(TYPE_APPLICATION_OVERLAY, TRANSLUCENT, NOT_FOCUSABLE|LAYOUT_NO_LIMITS)；
 *  - 页面内 sprite 每帧调用 AndroidPet.setBounds → 本服务 updateViewLayout 移动/缩放窗口
 *    （窗口 = 宠物包围盒 + 四周 0.5×size 透明余量，与 Electron 完全一致）；
 *  - AndroidPet.setInputRegions → 触摸捕获窗（见 TouchCaptureManager）：
 *    渲染窗本身 FLAG_NOT_TOUCHABLE（不挡下层 App），页面上报的每个可交互矩形各建一个透明小窗，
 *    收到 MotionEvent 后换算坐标转发给 WebView（T3 探针在 Android 15/targetSdk35 实测通过）；
 *  - 屏幕旋转/分辨率变化 → 重算几何并 evaluateJavascript("window.__petApplyDisplays(<json>)")。
 */
public class PetOverlayService extends Service {

    private static final String TAG = "DSHPet";

    public static final String ACTION_START = "com.dshpet.mobile.action.START";
    public static final String ACTION_STOP = "com.dshpet.mobile.action.STOP";
    /** 用户改了宠物大小：销毁重建页面（大小配置在页面加载 URL 里，只能重载） */
    public static final String ACTION_RESTART = "com.dshpet.mobile.action.RESTART";
    /** 用户调整宠物透明度：运行中热更新渲染窗 alpha（extra: EXTRA_ALPHA float，0.30–0.80） */
    public static final String ACTION_SET_ALPHA = "com.dshpet.mobile.action.SET_ALPHA";
    public static final String EXTRA_ALPHA = "alpha";

    private static final String CHANNEL_ID = "dshpet_overlay";
    private static final int NOTIF_ID = 7340;
    private static final int REQ_MAIN = 1;
    private static final int REQ_STOP = 2;
    /**
     * 渲染窗不透明度上限：Android 12+ 对 FLAG_NOT_TOUCHABLE 的系统悬浮窗，
     * alpha > 0.80 会被系统强制降到 0.80 才允许「触摸穿透到下层 App」（安全策略）。
     * 用户可在 30%–80% 之间自选（见 Prefs.getPetAlpha），见 showOverlay 注释。
     */
    private static final float MAX_RENDER_WINDOW_ALPHA = 0.80f;

    private static volatile boolean sRunning;
    private static volatile String sLastError;
    /** 最近一次服务诊断信息（JSON），供 MainActivity 展示；由服务在状态变化时刷新 */
    private static volatile String sInfoJson = "{}";

    public static boolean isRunning() {
        return sRunning;
    }

    public static String getLastError() {
        return sLastError;
    }

    /** 服务诊断信息（JSON 字符串；未运行时为 "{}"） */
    public static String getInfoJson() {
        return sInfoJson;
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private WebView webView;
    private WindowManager.LayoutParams lp;
    private volatile Geometry geom;
    private AssetServer assetServer;
    private TouchCaptureManager touchCapture;
    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;

    private boolean overlayAdded;
    private boolean destroyed;
    private int petSizeDp;
    /** 渲染窗当前不透明度（0.30–0.80），用户可在控制台调整 */
    private volatile float renderAlpha = Prefs.DEFAULT_PET_ALPHA_PCT / 100f;
    private int recreateCount;

    // 窗口当前位置/尺寸（物理像素、屏幕坐标）——给 AndroidPet.getWindowOrigin() 用
    private volatile int winX;
    private volatile int winY;
    private volatile int winW;
    private volatile int winH;

    // setBounds 逐帧合并（JS 线程 → 主线程，一帧最多一次 updateViewLayout）
    private final Object boundsLock = new Object();
    private double pendingX;
    private double pendingY;
    private double pendingW;
    private double pendingH;
    private boolean boundsScheduled;
    /** 页面是否已经上报过至少一次 setBounds（聊天上移等操作必须等窗口有真实落点） */
    private volatile boolean hasPageBounds;

    // 交互状态（页面经 bridge 上报，仅记录/诊断用）
    private volatile boolean interactive = true;
    private volatile boolean inputBusy;
    private volatile int captureWindowCount;
    private volatile int captureRects;

    // ===== 电脑 DeepSeek Harness 联动（v1.1.2）=====
    /** 电脑 DSH 宿主 HTTP 时间参数（对话可能走 LLM，read 超时放宽到 90s） */
    private static final int HARNESS_CONNECT_TIMEOUT_MS = 8000;
    private static final int HARNESS_READ_TIMEOUT_MS = 90000;
    /** /state 每秒轮询，失败日志节流，避免拔掉反代后刷屏 */
    private static final long HARNESS_ERROR_LOG_INTERVAL_MS = 5000L;
    private final ExecutorService harnessExecutor = Executors.newFixedThreadPool(4, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "dsh-harness-http");
            t.setDaemon(true);
            return t;
        }
    });
    /** 对话面板打开：渲染窗临时可取焦点（只为弹输入法；FLAG_NOT_TOUCHABLE 始终保留） */
    private volatile boolean chatMode;
    /** 聊天时窗口临时上移量（物理像素）：避免输入法把聊天输入框整个盖住 */
    private volatile int chatRaiseOffset;
    /** 聊天时窗口临时左移量（物理像素）：宠物在屏幕右边缘时避免聊天面板/发送键被裁掉 */
    private volatile int chatShiftX;
    /** 页面最近一次上报的窗口 y / x（不含聊天偏移） */
    private volatile int lastPageY;
    private volatile int lastPageX;
    private volatile long lastHarnessErrorLogMs;

    private final Runnable applyBoundsRunnable = new Runnable() {
        @Override
        public void run() {
            applyPendingBounds();
        }
    };

    private final Runnable diagnosticsRunnable = new Runnable() {
        @Override
        public void run() {
            dumpPageDiagnostics();
        }
    };

    private final Runnable displayDebounceRunnable = new Runnable() {
        @Override
        public void run() {
            onDisplayMetricsChanged();
        }
    };

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        sLastError = null;
        createNotificationChannel();
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Throwable t) {
            Log.e(TAG, "[svc] startForeground 失败: " + t);
        }

        petSizeDp = Prefs.getPetSizeDp(this);
        renderAlpha = Prefs.getPetAlpha(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        if (!Settings.canDrawOverlays(this)) {
            sLastError = "缺少悬浮窗权限：请在设置中允许「显示在其他应用上层」";
            Log.e(TAG, "[svc] " + sLastError);
            stopSelf();
            return;
        }
        try {
            startForegroundWithType();
        } catch (Throwable t) {
            Log.e(TAG, "[svc] startForeground(type) 失败: " + t);
        }

        registerDisplayListener();
        showOverlay("首次启动");
    }

    private void startForegroundWithType() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "[svc] 收到关闭指令");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            Log.i(TAG, "[svc] 收到重启指令（宠物大小/配置变化）");
            main.post(new Runnable() {
                @Override
                public void run() {
                    stopOverlayAndRebuild("设置变化");
                }
            });
            return START_STICKY;
        }
        if (ACTION_SET_ALPHA.equals(action)) {
            // 运行中调整透明度：只改 lp.alpha + updateViewLayout，不重建 WebView/窗口。
            // 注意 flags 必须保持 FLAG_NOT_TOUCHABLE（否则跨 App 穿透失效）。
            float requested = intent == null
                    ? renderAlpha : intent.getFloatExtra(EXTRA_ALPHA, renderAlpha);
            float clamped = Math.max(Prefs.MIN_PET_ALPHA_PCT / 100f,
                    Math.min(MAX_RENDER_WINDOW_ALPHA, requested));
            renderAlpha = clamped;
            Log.i(TAG, "[overlay] 透明度更新 alpha=" + clamped);
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (destroyed || lp == null || webView == null || !overlayAdded) return;
                    lp.alpha = renderAlpha;
                    try {
                        wm.updateViewLayout(webView, lp);
                    } catch (Throwable t) {
                        Log.w(TAG, "[overlay] 透明度更新失败: " + t);
                    }
                    refreshInfoCache();
                }
            });
            return START_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        sRunning = false;
        sInfoJson = "{}";
        Log.i(TAG, "[svc] onDestroy");
        // 先收起输入法/关掉聊天焦点态，再拆窗口
        if (chatMode) {
            try {
                exitChatMode();
            } catch (Throwable ignored) {
            }
            chatMode = false;
        }
        try {
            harnessExecutor.shutdownNow();
        } catch (Throwable ignored) {
        }
        main.removeCallbacksAndMessages(null);
        if (displayManager != null && displayListener != null) {
            try {
                displayManager.unregisterDisplayListener(displayListener);
            } catch (Throwable ignored) {
            }
        }
        destroyOverlay();
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(NOTIF_ID);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        scheduleDisplayRecompute();
    }

    // =====================================================================
    // 悬浮窗创建 / 销毁
    // =====================================================================

    private void showOverlay(String reason) {
        if (destroyed) return;
        if (!Settings.canDrawOverlays(this)) {
            sLastError = "缺少悬浮窗权限：请在设置中允许「显示在其他应用上层」";
            Log.e(TAG, "[overlay] 无悬浮窗权限，无法显示");
            main.post(new Runnable() {
                @Override
                public void run() {
                    stopSelf();
                }
            });
            return;
        }
        Geometry g = Geometry.compute(this);
        geom = g;
        Log.i(TAG, "[overlay] 创建悬浮窗（" + reason + "）：" + g);

        int[] initSize = Geometry.initialWindowSizePx(petSizeDp, g.density);
        int initX = g.safeRight - initSize[0] - Math.round(Geometry.MARGIN_X_CSS * g.density);
        int initY = g.safeBottom - initSize[1] - Math.round(Geometry.MARGIN_Y_CSS * g.density);
        if (initX < g.safeLeft) initX = g.safeLeft;
        if (initY < g.safeTop) initY = g.safeTop;

        lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                // 方案 B：渲染窗只负责显示，绝不接收触摸（否则会遮挡下层 App 的点击，
                // 且 Android 12+ 会因 untrusted-touch 把穿过悬浮窗的点击丢掉）；
                // 真正的触摸由 TouchCaptureManager 的小窗捕获后转发给 WebView。
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = initX;
        lp.y = initY;
        lp.width = initSize[0];
        lp.height = initSize[1];
        // Android 12+：带 FLAG_NOT_TOUCHABLE 的系统悬浮窗如果 alpha > 0.80，系统会强制降到 0.80
        // 才允许「触摸穿透到下层 App」（安全策略，防点击劫持）。B 方案必须接受这个上限：
        // 用户可在 30%–80% 之间自选（默认 80%=略透出背景；调低更透明）。
        lp.alpha = renderAlpha;
        lp.setTitle("DSHPetOverlay");
        // T3 实测：不关掉「适配系统 insets」时 lp.y 会被状态栏 inset 偏移（+63px），
        // 导致页面 setBounds 给的物理像素与屏幕坐标对不上 → 宠物整体下移一截。
        // 置 0 = 本窗口完全不适配任何 insets，坐标就是纯屏幕坐标。
        try {
            lp.setFitInsetsTypes(0);
        } catch (Throwable t) {
            Log.w(TAG, "[overlay] setFitInsetsTypes(0) 失败: " + t);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        winX = initX;
        winY = initY;
        winW = initSize[0];
        winH = initSize[1];

        assetServer = new AssetServer(this, petSizeDp);
        try {
            webView = createWebView();
        } catch (Throwable t) {
            sLastError = "WebView 创建失败: " + t;
            Log.e(TAG, "[overlay] " + sLastError, t);
            main.post(new Runnable() {
                @Override
                public void run() {
                    stopSelf();
                }
            });
            return;
        }

        try {
            wm.addView(webView, lp);
            overlayAdded = true;
        } catch (Throwable t) {
            sLastError = "悬浮窗添加失败: " + t;
            Log.e(TAG, "[overlay] " + sLastError, t);
            destroyWebViewOnly();
            main.post(new Runnable() {
                @Override
                public void run() {
                    stopSelf();
                }
            });
            return;
        }

        // 方案 B 触摸层：渲染窗已 FLAG_NOT_TOUCHABLE，触摸由小窗捕获并转发进 WebView。
        // 页面上报首个 setInputRegions 之前先整窗捕获（保证宠物一定可点），上报后收敛为身体/菜单区域。
        touchCapture = new TouchCaptureManager(this, wm, webView);
        touchCapture.setRenderBounds(winX, winY, winW, winH);
        captureWindowCount = touchCapture.windowCount();

        String url = g.pageUrl(petSizeDp);
        Log.i(TAG, "[overlay] load " + url);
        try {
            webView.loadUrl(url);
        } catch (Throwable t) {
            sLastError = "页面加载失败: " + t;
            Log.e(TAG, "[overlay] " + sLastError, t);
        }

        sRunning = true;
        refreshInfoCache();
        scheduleDiagnostics(3000);
    }

    private void stopOverlayAndRebuild(String reason) {
        if (destroyed) return;
        petSizeDp = Prefs.getPetSizeDp(this);
        Log.i(TAG, "[overlay] 重建（" + reason + "），petSizeDp=" + petSizeDp);
        destroyOverlay();
        showOverlay(reason);
    }

    private void recreateAfterCrash(String reason) {
        if (destroyed) return;
        recreateCount++;
        if (recreateCount > 3) {
            sLastError = "WebView 多次崩溃，桌宠已停止（" + reason + "）";
            Log.e(TAG, "[overlay] " + sLastError);
            stopSelf();
            return;
        }
        Log.w(TAG, "[overlay] WebView 异常，重建 #" + recreateCount + "（" + reason + "）");
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                destroyOverlay();
                showOverlay("WebView 重建 #" + recreateCount);
            }
        }, 500);
    }

    private void destroyOverlay() {
        chatMode = false;
        chatRaiseOffset = 0;
        chatShiftX = 0;
        if (touchCapture != null) {
            touchCapture.dispose();
            touchCapture = null;
        }
        captureWindowCount = 0;
        if (webView != null) {
            try {
                webView.stopLoading();
            } catch (Throwable ignored) {
            }
            try {
                webView.loadUrl("about:blank");
            } catch (Throwable ignored) {
            }
            if (overlayAdded) {
                try {
                    wm.removeView(webView);
                } catch (Throwable t) {
                    Log.w(TAG, "[overlay] removeView 失败: " + t);
                }
            }
            overlayAdded = false;
            destroyWebViewOnly();
        }
        overlayAdded = false;
    }

    private void destroyWebViewOnly() {
        if (webView == null) return;
        try {
            webView.removeAllViews();
            webView.destroy();
        } catch (Throwable t) {
            Log.w(TAG, "[overlay] webView.destroy 失败: " + t);
        }
        webView = null;
    }

    private WebView createWebView() {
        WebView wv = new WebView(this);
        wv.setBackgroundColor(Color.TRANSPARENT);
        wv.setVerticalScrollBarEnabled(false);
        wv.setHorizontalScrollBarEnabled(false);
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        wv.setLongClickable(false);
        wv.setHapticFeedbackEnabled(false);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // 宠物动画 <video> 自动播放
        s.setTextZoom(100); // 忽略系统字体缩放，保证页面 CSS 尺寸与 Electron 一致
        // T3 探针实测：true + 页面 viewport meta(width=device-width) 时 CSS px = 物理px/dpr；
        // 设为 false 时布局宽度口径不一致，会造成 innerWidth 与窗口尺寸错位。
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setSaveFormData(false);
        try {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                s.setAlgorithmicDarkeningAllowed(false);
            } catch (Throwable ignored) {
            }
        }

        wv.addJavascriptInterface(new PetBridge(this), PetBridge.NAME);
        wv.setWebViewClient(new PetWebViewClient());
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if (cm != null) {
                    Log.i(TAG, "[web:" + cm.messageLevel() + "] " + cm.message()
                            + " (" + cm.sourceId() + ":" + cm.lineNumber() + ")");
                }
                return true;
            }
        });

        // 触摸诊断（不影响正常事件：返回 false 交给 WebView 继续处理）
        wv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN
                        || action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    Log.i(TAG, "[touch] action=" + action + " at " + (int) event.getX() + "," + (int) event.getY()
                            + " raw=" + (int) event.getRawX() + "," + (int) event.getRawY());
                }
                return false;
            }
        });
        return wv;
    }

    // =====================================================================
    // WebViewClient：assets 拦截 + 错误日志
    // =====================================================================

    private final class PetWebViewClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                if (assetServer == null) return null;
                return assetServer.respond(request);
            } catch (Throwable t) {
                Log.e(TAG, "[asset] 拦截异常: " + t);
                return null;
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request == null ? null : request.getUrl();
            if (uri == null) return true;
            if ("pet.local".equals(uri.getHost())) return false; // 页面内部导航：放行（由 assets 拦截）
            try {
                Intent it = new Intent(Intent.ACTION_VIEW, uri);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            } catch (Throwable t) {
                Log.w(TAG, "[web] 外部链接打开失败: " + uri + " (" + t + ")");
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.i(TAG, "[web] onPageFinished " + url);
            scheduleDiagnostics(2500);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                Log.e(TAG, "[web] 主文档加载错误: " + error.getErrorCode() + " " + error.getDescription()
                        + " url=" + request.getUrl());
                sLastError = "页面加载失败: " + error.getDescription();
            } else {
                Log.w(TAG, "[web] 资源加载错误: " + (request == null ? "?" : request.getUrl())
                        + " " + error.getDescription());
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            Log.w(TAG, "[web] http " + (errorResponse == null ? "?" : errorResponse.getStatusCode())
                    + " " + (request == null ? "?" : request.getUrl()));
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            boolean crash = detail != null && detail.didCrash();
            Log.e(TAG, "[web] RENDER PROCESS GONE crash=" + crash);
            recreateAfterCrash(crash ? "renderer 崩溃" : "renderer 被系统回收");
            return true;
        }
    }

    // =====================================================================
    // JS 桥回调（WebView JavaBridge 线程 → 主线程）
    // =====================================================================

    void onJsSetBounds(double x, double y, double width, double height) {
        if (!isFinite(x) || !isFinite(y) || !isFinite(width) || !isFinite(height)) return;
        boolean schedule;
        synchronized (boundsLock) {
            pendingX = x;
            pendingY = y;
            pendingW = width;
            pendingH = height;
            schedule = !boundsScheduled;
            if (schedule) boundsScheduled = true;
        }
        if (schedule) main.post(applyBoundsRunnable);
    }

    private void applyPendingBounds() {
        double x;
        double y;
        double w;
        double h;
        synchronized (boundsLock) {
            x = pendingX;
            y = pendingY;
            w = pendingW;
            h = pendingH;
            boundsScheduled = false;
        }
        if (webView == null || lp == null || !overlayAdded || destroyed) return;
        int pageX = (int) Math.round(x);
        int pageY = (int) Math.round(y);
        hasPageBounds = true;
        lastPageX = pageX;
        lastPageY = pageY;
        // 聊天模式下窗口整体上移/左移，让聊天面板浮在键盘上方且不被屏幕右边缘裁掉
        int nx = pageX - chatShiftX;
        int ny = pageY - chatRaiseOffset;
        int nw = Math.max(1, (int) Math.round(w));
        int nh = Math.max(1, (int) Math.round(h));
        if (nx == winX && ny == winY && nw == winW && nh == winH) return; // 与 Electron 主进程一样做去重
        winX = nx;
        winY = ny;
        winW = nw;
        winH = nh;
        lp.x = nx;
        lp.y = ny;
        lp.width = nw;
        lp.height = nh;
        try {
            wm.updateViewLayout(webView, lp);
        } catch (Throwable t) {
            Log.e(TAG, "[overlay] updateViewLayout 失败: " + t);
        }
        TouchCaptureManager tc = touchCapture;
        if (tc != null) {
            tc.setRenderBounds(winX, winY, winW, winH);
            captureWindowCount = tc.windowCount();
        }
        refreshInfoCache();
    }

    void onJsSetInteractive(boolean flag) {
        if (interactive != flag) {
            interactive = flag;
            Log.i(TAG, "[bridge] setInteractive=" + flag);
        }
    }

    void onJsSetInputBusy(boolean busy) {
        if (inputBusy != busy) {
            inputBusy = busy;
            Log.i(TAG, "[bridge] setInputBusy=" + busy);
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                TouchCaptureManager tc = touchCapture;
                if (tc != null) {
                    // 菜单/聊天打开时补整窗捕获：保证菜单外的点击也能进页面（关闭菜单）
                    tc.setInputBusy(inputBusy);
                    captureWindowCount = tc.windowCount();
                    refreshInfoCache();
                }
            }
        });
    }

    /** 页面上报可触摸区域：物理像素、渲染窗内坐标 → 触摸捕获窗（TouchCaptureManager） */
    void onJsSetInputRegions(String json) {
        if (json == null) return;
        final List<Rect> rects = new ArrayList<>();
        boolean dragging = false;
        try {
            JSONObject o = new JSONObject(json);
            dragging = o.optBoolean("dragging", false);
            JSONArray arr = o.optJSONArray("rects");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.optJSONObject(i);
                    if (r == null) continue;
                    double x = r.optDouble("x", Double.NaN);
                    double y = r.optDouble("y", Double.NaN);
                    double w = r.optDouble("width", Double.NaN);
                    double h = r.optDouble("height", Double.NaN);
                    if (!isFinite(x) || !isFinite(y) || !isFinite(w) || !isFinite(h) || w <= 0 || h <= 0) continue;
                    rects.add(new Rect((int) Math.floor(x), (int) Math.floor(y),
                            (int) Math.ceil(x + w), (int) Math.ceil(y + h)));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "[bridge] setInputRegions JSON 解析失败，退化整窗可点: " + t);
            rects.clear();
        }
        final boolean draggingF = dragging;
        main.post(new Runnable() {
            @Override
            public void run() {
                TouchCaptureManager tc = touchCapture;
                if (tc == null) return;
                tc.setRegions(rects, draggingF);
                captureWindowCount = tc.windowCount();
                captureRects = rects.size();
                if (draggingF) {
                    Log.i(TAG, "[touchcap] 拖拽租约：整窗可点（rects=" + rects.size() + "）");
                }
                refreshInfoCache();
            }
        });
    }

    void onJsOpenUrl(String url) {
        if (url == null) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.w(TAG, "[bridge] openUrl 忽略非 http(s) URL: " + url);
            return;
        }
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "[bridge] openUrl 失败: " + t);
        }
    }

    // =====================================================================
    // 电脑 DeepSeek Harness 联动（v1.1.2）：fetch 代理 + 对话输入法
    // =====================================================================

    /** "1" = 已配置且最近一次测试连接成功；"0" = 降级（页面应直接返回降级 JSON、不发 HTTP） */
    String harnessAvailable() {
        try {
            return Prefs.isHarnessConnected(this) ? "1" : "0";
        } catch (Throwable t) {
            return "0";
        }
    }

    /**
     * 代页面请求电脑上的 DSH 宿主（JS fetch 代理）；静态资源仍走 assets，不经过这里。
     * 完成（或失败）后主线程回调 window.__petportHarnessDone(cbId, payloadJson)。
     */
    void harnessFetch(String method, String path, String body, String cbId) {
        if (path == null || cbId == null || cbId.isEmpty()) return;
        final String m = (method == null || method.trim().isEmpty())
                ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        final String p = path.startsWith("/") ? path : "/" + path;
        final String b = body;
        final String id = cbId;
        if (!Prefs.isHarnessConnected(this)) {
            dispatchHarnessResult(id, false, -1, null, "harness not connected");
            return;
        }
        try {
            harnessExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    doHarnessFetch(id, m, p, b);
                }
            });
        } catch (Throwable t) {
            dispatchHarnessResult(id, false, -1, null, "submit failed: " + t);
        }
    }

    private void doHarnessFetch(String cbId, String method, String path, String body) {
        long t0 = SystemClock.elapsedRealtime();
        String base = Prefs.getHarnessBase(this);
        HttpURLConnection conn = null;
        int status = -1;
        String respBody = null;
        String error = null;
        try {
            URL url = new URL(base + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(HARNESS_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(HARNESS_READ_TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            conn.setUseCaches(false);
            boolean hasBody = body != null && !body.isEmpty()
                    && !"GET".equals(method) && !"HEAD".equals(method);
            if (hasBody) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(data.length);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(data);
                    os.flush();
                } finally {
                    try {
                        os.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            respBody = readAllText(is);
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : (": " + t.getMessage()));
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        long ms = SystemClock.elapsedRealtime() - t0;
        boolean ok = error == null;
        int len = respBody == null ? 0 : respBody.length();
        if (!ok) {
            long now = SystemClock.elapsedRealtime();
            boolean isState = path.contains("/state");
            if (!isState || now - lastHarnessErrorLogMs > HARNESS_ERROR_LOG_INTERVAL_MS) {
                lastHarnessErrorLogMs = now;
                Log.w(TAG, "[harness] " + method + " " + path + " 失败(" + ms + "ms): " + error);
            }
        } else if (path.contains("/state")) {
            // 页面每秒轮询：debug 级，避免 logcat 噪音
            Log.d(TAG, "[harness] " + method + " " + path + " → " + status + " (" + len + "B, " + ms + "ms)");
        } else {
            // 不打印对话/碎碎念正文，只记录路径/状态/长度/耗时
            Log.i(TAG, "[harness] " + method + " " + path + " → " + status + " (" + len + "B, " + ms + "ms)");
        }
        dispatchHarnessResult(cbId, ok, status, respBody, error);
    }

    private static String readAllText(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        try {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 回调页面：window.__petportHarnessDone(cbId, payloadJson)（payload 用 JSON 字符串安全转义） */
    private void dispatchHarnessResult(String cbId, boolean ok, int status, String body, String error) {
        if (destroyed || webView == null || cbId == null) return;
        String payload;
        try {
            JSONObject o = new JSONObject();
            if (ok) {
                o.put("ok", true);
                o.put("status", status);
                o.put("body", body == null ? "" : body);
            } else {
                o.put("ok", false);
                o.put("error", error == null ? "unknown error" : error);
            }
            payload = o.toString();
        } catch (Throwable t) {
            payload = "{\"ok\":false,\"error\":\"payload build failed\"}";
        }
        final String js = "window.__petportHarnessDone && window.__petportHarnessDone("
                + JSONObject.quote(cbId) + "," + JSONObject.quote(payload) + ");";
        main.post(new Runnable() {
            @Override
            public void run() {
                if (webView == null || destroyed) return;
                try {
                    webView.evaluateJavascript(js, null);
                } catch (Throwable t) {
                    Log.w(TAG, "[harness] 回调 JS 失败: " + t);
                }
            }
        });
    }

    /**
     * 对话面板开/关：open=true 临时让渲染窗可聚焦并弹出输入法；false 恢复。
     * 渲染窗必须始终保留 FLAG_NOT_TOUCHABLE（跨 App 穿透），捕获窗逻辑不变。
     */
    void setChatMode(boolean open) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || lp == null || webView == null || !overlayAdded) return;
                if (chatMode == open) return;
                chatMode = open;
                if (open) {
                    enterChatMode();
                } else {
                    exitChatMode();
                }
                refreshInfoCache();
            }
        });
    }

    private void enterChatMode() {
        try {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            // 不让输入法推挤/缩放窗口（窗口尺寸是页面布局基准，改不得）
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;
            wm.updateViewLayout(webView, lp);
        } catch (Throwable t) {
            Log.w(TAG, "[harness] chatMode 开：窗口焦点切换失败: " + t);
            return;
        }
        try {
            webView.setFocusable(true);
            webView.setFocusableInTouchMode(true);
        } catch (Throwable ignored) {
        }
        requestFocusAndShowIme();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (chatMode) requestFocusAndShowIme();
            }
        }, 600);
        // 输入法弹出后把窗口上移，保证聊天输入框不被键盘盖住（键盘动画/insets 可能延迟，多测几次取最新值）
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (chatMode) applyChatRaise();
            }
        }, 900);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (chatMode) applyChatRaise();
            }
        }, 1800);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (chatMode) applyChatRaise();
            }
        }, 2800);
        Log.i(TAG, "[harness] chatMode=true：渲染窗临时可取焦点 + 请求输入法（NOT_TOUCHABLE 保留）");
    }

    /** 计算并应用聊天窗口上移量：窗口底边抬到输入法上沿之上（拿不到 IME insets 时用经验值兜底） */
    private void applyChatRaise() {
        if (!chatMode || webView == null || destroyed || geom == null) return;
        if (!hasPageBounds) return; // 页面还没上报过落点，先不动窗口
        int screenH = geom.screenH;
        int imeH = 0;
        String imeSource = "heuristic";
        try {
            android.view.WindowInsets insets = webView.getRootWindowInsets();
            if (insets != null) {
                int bottom = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                if (bottom > 0) {
                    imeH = bottom;
                    imeSource = "insets";
                }
            }
        } catch (Throwable ignored) {
        }
        if (imeH <= 0) {
            // 拿不到 IME insets（overlay 窗口常见）
            imeH = 0;
        }
        // 经验兜底：overlay 窗口的 IME insets 往往偏小/抖动（实测 313–549px，而键盘实际约 870px），
        // 取「insets 与屏幕高度 38%」的较大值，保证聊天输入框一定浮在键盘上方。
        int heuristic = Math.round(screenH * 0.38f);
        if (imeH < heuristic) {
            imeH = heuristic;
            imeSource = imeSource + "+heuristic";
        }
        int targetBottom = screenH - imeH - Math.round(8 * (geom.density > 0 ? geom.density : 1f));
        int pageY = lastPageY;
        int currentBottom = pageY + winH;
        int raise = currentBottom > targetBottom ? (currentBottom - targetBottom) : 0;
        if (raise < 0) raise = 0;
        if (raise > pageY) raise = pageY; // 窗口顶边不越过屏幕顶部
        // 横向：宠物贴屏幕右边缘时，聊天面板会有一部分在屏幕外（发送键可能点不到）→ 临时左移
        int margin = Math.round(8 * (geom.density > 0 ? geom.density : 1f));
        int pageX = lastPageX;
        int shiftX = 0;
        if (pageX + winW > geom.screenW - margin) {
            shiftX = pageX + winW - (geom.screenW - margin);
        }
        if (shiftX > pageX) shiftX = pageX;
        if (shiftX < 0) shiftX = 0;
        if (raise == chatRaiseOffset && shiftX == chatShiftX) return;
        chatRaiseOffset = raise;
        chatShiftX = shiftX;
        Log.i(TAG, "[harness] chat 窗口临时位移 raise=" + raise + "px shiftX=" + shiftX
                + "px（imeH=" + imeH + " via " + imeSource + "，page=" + pageX + "," + pageY
                + "，windowH=" + winH + "）");
        // 页面可能没在发 setBounds（宠物静止）：用最近一次上报坐标重新落窗
        main.post(applyBoundsRunnable);
    }

    private void requestFocusAndShowIme() {
        if (!chatMode || webView == null || destroyed) return;
        try {
            webView.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[harness] 显示输入法失败: " + t);
        }
    }

    private void exitChatMode() {
        // 1) 让页面主动失焦：Blink 会向输入法发出 hide 请求（部分厂商 IME 只认这个，不认 IMM.hide）
        blurWebViewActiveElement();
        // 2) 取消聊天窗口上移/左移，窗口回到页面指定位置
        if (chatRaiseOffset != 0 || chatShiftX != 0) {
            chatRaiseOffset = 0;
            chatShiftX = 0;
            if (!destroyed) main.post(applyBoundsRunnable);
        }
        // 3) 窗口还处于可聚焦时先 hide 一次 + 清焦点
        hideImeNow();
        // 4) 恢复窗口 flags：不可聚焦；softInputMode 显式 HIDDEN，避免系统记住上次弹出状态
        try {
            if (lp != null && webView != null && overlayAdded) {
                lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
                wm.updateViewLayout(webView, lp);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[harness] chatMode 关：恢复窗口 flags 失败: " + t);
        }
        // 5) 厂商 IME 异步/顽固：多拍「页面失焦 + IMM.hide + clearFocus」，覆盖 100ms–1800ms
        final long[] delays = new long[]{100L, 400L, 1000L, 1800L};
        for (final long delay : delays) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (chatMode || destroyed) return;
                    blurWebViewActiveElement();
                    hideImeNow();
                }
            }, delay);
        }
        Log.i(TAG, "[harness] chatMode=false：恢复 FLAG_NOT_FOCUSABLE + 隐藏输入法（页面失焦 + 100/400/1000/1800ms 重试）");
    }

    /** 让 WebView 页面主动失焦（document.activeElement.blur()）——Blink 会因此向 IME 请求隐藏 */
    private void blurWebViewActiveElement() {
        WebView wv = webView;
        if (wv == null || destroyed) return;
        try {
            wv.evaluateJavascript(
                    "(function(){try{var a=document.activeElement;if(a&&a.blur)a.blur();}catch(e){}})();",
                    null);
        } catch (Throwable ignored) {
        }
    }

    /** 立刻隐藏输入法并清掉 WebView 焦点（部分 IME 需窗口失去焦点后才收起） */
    private void hideImeNow() {
        WebView wv = webView;
        if (wv == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(wv.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
        try {
            wv.clearFocus();
            wv.setFocusable(false);
            wv.setFocusableInTouchMode(false);
        } catch (Throwable ignored) {
        }
    }

    String getWindowOriginJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("x", winX);
            o.put("y", winY);
            o.put("width", winW);
            o.put("height", winH);
            Geometry g = geom;
            o.put("scale", g == null ? 1d : (double) g.density);
            o.put("pxW", g == null ? 0 : g.screenW);
            o.put("pxH", g == null ? 0 : g.screenH);
            if (g != null) {
                o.put("safeLeft", g.safeLeft);
                o.put("safeTop", g.safeTop);
                o.put("safeRight", g.safeRight);
                o.put("safeBottom", g.safeBottom);
            }
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    private void refreshInfoCache() {
        try {
            sInfoJson = buildInfoJson();
        } catch (Throwable ignored) {
        }
    }

    String buildInfoJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", sRunning);
            o.put("petSizeDp", petSizeDp);
            Geometry g = geom;
            if (g != null) {
                o.put("dpr", (double) g.density);
                o.put("screen", new JSONArray().put(g.screenW).put(g.screenH));
                o.put("safe", new JSONArray().put(g.safeLeft).put(g.safeTop)
                        .put(g.safeRight).put(g.safeBottom));
            }
            o.put("window", new JSONArray().put(winX).put(winY).put(winW).put(winH));
            o.put("alphaPct", Math.round(renderAlpha * 100f));
            o.put("touchMode", "capture-windows");
            o.put("captureWindows", captureWindowCount);
            o.put("captureRects", captureRects);
            o.put("interactive", interactive);
            o.put("inputBusy", inputBusy);
            o.put("harnessConnected", Prefs.isHarnessConnected(this));
            o.put("harnessBase", Prefs.getHarnessBase(this));
            o.put("chatMode", chatMode);
            if (assetServer != null) {
                o.put("assetRequests", assetServer.requestCount);
                o.put("assetMiss", assetServer.missCount);
            }
            o.put("placeholder", assetServer == null || assetServer.isPlaceholder());
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    // =====================================================================
    // 显示器变化 → 重算几何并推给页面
    // =====================================================================

    private void registerDisplayListener() {
        try {
            displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            displayListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) scheduleDisplayRecompute();
                }
            };
            displayManager.registerDisplayListener(displayListener, main);
        } catch (Throwable t) {
            Log.w(TAG, "[display] 注册监听失败: " + t);
        }
    }

    private void scheduleDisplayRecompute() {
        main.removeCallbacks(displayDebounceRunnable);
        main.postDelayed(displayDebounceRunnable, 350);
    }

    private void onDisplayMetricsChanged() {
        if (destroyed) return;
        Geometry old = geom;
        Geometry g = Geometry.compute(this);
        if (old != null && old.screenW == g.screenW && old.screenH == g.screenH
                && Math.abs(old.density - g.density) < 0.001f) {
            return; // 尺寸/密度都没变（可能只是别的 display 变化）
        }
        Log.i(TAG, "[display] 变化: " + old + " → " + g);
        geom = g;
        if (old != null && Math.abs(old.density - g.density) >= 0.001f) {
            // 密度（dpr）变了：页面 URL 的 scale 参数已过期，必须重载页面
            stopOverlayAndRebuild("屏幕密度变化");
            return;
        }
        String geoJson = g.geoJson();
        String js = "window.__petApplyDisplays && window.__petApplyDisplays(" + JSONObject.quote(geoJson) + ");";
        evaluateJs(js);
        // 兜底：页面若没响应（JS 异常/bridge 缺失），把窗口夹回屏内，避免宠物被留在屏幕外
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                ensureWindowVisible();
            }
        }, 900);
    }

    private void ensureWindowVisible() {
        if (destroyed || webView == null || lp == null || !overlayAdded) return;
        Geometry g = geom;
        if (g == null) return;
        boolean bad = winX > g.screenW - 24 || winY > g.screenH - 24
                || winX + winW < 24 || winY + winH < 24;
        if (!bad) return;
        int nx = Math.max(0, Math.min(winX, g.screenW - 48));
        int ny = Math.max(0, Math.min(winY, g.screenH - 48));
        synchronized (boundsLock) {
            pendingX = nx;
            pendingY = ny;
            pendingW = winW;
            pendingH = winH;
            boundsScheduled = true;
        }
        Log.w(TAG, "[display] 窗口在屏外（" + winX + "," + winY + "），兜底夹回 " + nx + "," + ny);
        main.post(applyBoundsRunnable);
    }

    private void evaluateJs(String js) {
        if (webView == null || destroyed) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                try {
                    webView.evaluateJavascript(js, null);
                } catch (Throwable t) {
                    Log.w(TAG, "[web] evaluateJavascript 失败: " + t);
                }
            }
        });
    }

    // =====================================================================
    // 诊断
    // =====================================================================

    private void scheduleDiagnostics(long delayMs) {
        main.removeCallbacks(diagnosticsRunnable);
        main.postDelayed(diagnosticsRunnable, delayMs);
    }

    private void dumpPageDiagnostics() {
        if (webView == null || destroyed) return;
        String js = "(function(){try{"
                + "var info=null;try{info=window.__petportInfo?JSON.parse(window.__petportInfo()):null}catch(e){}"
                + "return JSON.stringify({info:info,debug:window.__dshPetDebug||null,"
                + "inner:[window.innerWidth,window.innerHeight],dpr:window.devicePixelRatio,"
                + "hasBridge:!!window.petBridge,hasAndroidPet:!!window.AndroidPet,"
                + "videos:document.querySelectorAll('video').length,"
                + "sprites:document.querySelectorAll('.pet-sprite').length,"
                + "configError:(document.getElementById('pet-error')||{}).textContent||''});"
                + "}catch(e){return 'diag-error:'+e}})()";
        try {
            webView.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    Log.i(TAG, "[web] diag " + truncate(value, 4000));
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "[web] 诊断执行失败: " + t);
        }
        Log.i(TAG, "[svc] info " + sInfoJson);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + " chars)";
    }

    // =====================================================================
    // 通知
    // =====================================================================

    private void createNotificationChannel() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "桌宠悬浮窗",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持「大肥鱼」悬浮在桌面上的前台服务");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.w(TAG, "[notif] 创建通知渠道失败: " + t);
        }
    }

    private Notification buildNotification() {
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, REQ_MAIN, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, PetOverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, REQ_STOP, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("DeepSeek 桌宠")
                .setContentText("大肥鱼正在桌面上陪伴你")
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_pet),
                        "关闭桌宠", stopPi).build());

        // 小图标优先用 dsh-pet 原版 logo（assets/logo.png，由 T1 提供）；缺失时用内置小鱼剪影
        Bitmap logo = loadAssetBitmap("logo.png");
        if (logo != null) {
            b.setSmallIcon(android.graphics.drawable.Icon.createWithBitmap(logo));
            b.setLargeIcon(logo);
        } else {
            b.setSmallIcon(R.drawable.ic_stat_pet);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private Bitmap loadAssetBitmap(String path) {
        try {
            android.content.res.AssetManager am = getAssets();
            java.io.InputStream in = am.open(path);
            try {
                return BitmapFactory.decodeStream(in);
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
