package com.dshpet.mobile;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

/**
 * 触摸穿透实现 —— 方案 B（T3 探针在 Android 15 / targetSdk 35 实测通过）。
 *
 * 背景：framework 的 TOUCHABLE_INSETS_REGION 是隐藏 API，targetSdk 35 下 touchableRegion 字段
 * 被 non-SDK 策略拒绝（max-target-r, denied），且即便降 targetSdk 生效，Android 12+ 的
 * untrusted-touch（遮挡不透明度）策略仍会丢弃「穿过悬浮窗落到下层 App」的点击。
 *
 * 方案 B（全公共 API）：
 *  - 渲染窗（WebView 所在的大窗）加 FLAG_NOT_TOUCHABLE：它只负责显示，任何触摸都不拦截、
 *    也不参与遮挡判定，所以下层 App 的点击完全正常；
 *  - 对页面上报的每个可交互矩形（宠物身体 / 菜单面板 / 拖拽时的整窗）各建一个透明小窗
 *    （TYPE_APPLICATION_OVERLAY），位置 = 渲染窗屏幕坐标 + 矩形偏移；
 *  - 小窗收到 MotionEvent 后原样复制、offsetLocation(rect.left, rect.top) 换算到渲染窗坐标系，
 *    调 webView.dispatchTouchEvent(copy) 注入页面——WebView 内 pointerdown/move/up、pointer capture、
 *    长按定时器、screenX/screenY 语义都与真实触摸一致（T3 实测 rawX/rawY 保留真实屏幕坐标）；
 *  - 拖拽中页面会把「整窗矩形」加进 regions（保手），Android 手势一旦 DOWN 落在某个小窗，
 *    后续 MOVE/UP 会一直发给它（即使手指移出小窗），所以甩抛不会丢事件；
 *  - 菜单/聊天打开（inputBusy）时补一个整窗小窗，保证菜单外的点击也能进页面（关闭菜单）；
 *  - 小窗必须 setFitInsetsTypes(0)，否则 y 会被状态栏 inset 偏移（T3 实测 +63px）；
 *  - 重建/移除小窗时保护「当前正持有手势的小窗」，否则拖拽中途会被系统 CANCEL。
 */
final class TouchCaptureManager {

    private static final String TAG = "DSHPet";
    private static final int MAX_CAPTURE_WINDOWS = 6;

    private final Context context;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WebView renderWebView;

    private final List<CaptureWindow> windows = new ArrayList<>();
    private CaptureWindow activeWindow;
    private List<Rect> pageRects = new ArrayList<>();
    private boolean inputBusy;
    private String lastKey = "";
    private boolean disposed;

    // 触摸派发：绝不阻塞输入线程（同步 webView.dispatchTouchEvent 在慢设备/模拟器上会
    // 超过 5s → InputDispatcher ANR「DSHPetTouch is unresponsive」）。
    // 全部 post 到主线程执行；MOVE 事件攒到下一轮只发最新一个（DOWN/UP/CANCEL 不合并，保顺序）。
    private MotionEvent pendingMove;
    private boolean movePosted;

    private int renderX;
    private int renderY;
    private int renderW = 1;
    private int renderH = 1;

    TouchCaptureManager(Context context, WindowManager wm, WebView renderWebView) {
        this.context = context;
        this.wm = wm;
        this.renderWebView = renderWebView;
    }

    // =====================================================================
    // 对外接口
    // =====================================================================

    /** 渲染窗（WebView）位置/尺寸变化：小窗逐帧跟随 */
    void setRenderBounds(int x, int y, int w, int h) {
        if (disposed) return;
        boolean sizeChanged = (w != renderW || h != renderH);
        renderX = x;
        renderY = y;
        renderW = Math.max(1, w);
        renderH = Math.max(1, h);
        if (sizeChanged) {
            reconcileIfNeeded();
        } else {
            updatePositions();
        }
    }

    /**
     * 页面上报的可交互区域（物理像素、渲染窗内坐标）。
     * rects 为空/未上报时退化：整窗可点（保证宠物一定点得到）。
     */
    void setRegions(List<Rect> rects, boolean dragging) {
        if (disposed) return;
        pageRects = rects == null ? new ArrayList<Rect>() : new ArrayList<>(rects);
        reconcileIfNeeded();
    }

    /** 菜单/聊天打开时补整窗捕获（页面外的点击也能进来关菜单） */
    void setInputBusy(boolean busy) {
        if (disposed) return;
        inputBusy = busy;
        reconcileIfNeeded();
    }

    int windowCount() {
        return windows.size();
    }

    boolean hasActiveGesture() {
        return activeWindow != null;
    }

    void dispose() {
        disposed = true;
        main.removeCallbacksAndMessages(null);
        if (pendingMove != null) {
            try {
                pendingMove.recycle();
            } catch (Throwable ignored) {
            }
            pendingMove = null;
        }
        for (CaptureWindow cw : new ArrayList<>(windows)) {
            removeWindow(cw);
        }
        windows.clear();
        activeWindow = null;
    }

    // =====================================================================
    // 期望区域 & 协调
    // =====================================================================

    private List<Rect> buildDesired() {
        List<Rect> out = new ArrayList<>();
        for (Rect r : pageRects) {
            if (r == null || r.width() <= 0 || r.height() <= 0) continue;
            boolean dup = false;
            for (Rect e : out) {
                if (e.equals(r)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(new Rect(r));
        }
        // 空区域 或 菜单/聊天打开：整窗都要能收到触摸（否则菜单外点击穿透、菜单关不掉）
        boolean needFull = out.isEmpty() || inputBusy;
        if (needFull) {
            Rect full = new Rect(0, 0, renderW, renderH);
            boolean has = false;
            for (Rect e : out) {
                if (e.equals(full)) {
                    has = true;
                    break;
                }
            }
            if (!has) out.add(full);
        }
        if (out.size() > MAX_CAPTURE_WINDOWS) {
            // 极少见（多级级联菜单）：合并成外接矩形，牺牲一点穿透精度换窗口数量安全
            Rect union = new Rect(out.get(0));
            for (Rect r : out) union.union(r);
            Log.w(TAG, "[touchcap] 区域过多(" + out.size() + ")，合并为外接矩形 " + union);
            out.clear();
            out.add(union);
        }
        return out;
    }

    private void reconcileIfNeeded() {
        if (disposed) return;
        List<Rect> desired = buildDesired();
        String key = keyOf(desired);
        if (!key.equals(lastKey)) {
            lastKey = key;
            reconcile(desired);
        }
        updatePositions();
    }

    private void reconcile(List<Rect> desired) {
        boolean[] used = new boolean[windows.size()];
        // 记录旧列表，便于后面找「未使用」的窗口
        List<CaptureWindow> old = new ArrayList<>(windows);
        List<CaptureWindow> next = new ArrayList<>();

        for (Rect r : desired) {
            CaptureWindow found = null;
            for (int i = 0; i < old.size(); i++) {
                if (!used[i] && old.get(i).rect.equals(r)) {
                    found = old.get(i);
                    used[i] = true;
                    break;
                }
            }
            if (found == null) {
                found = addWindow(r);
            }
            next.add(found);
        }
        for (int i = 0; i < old.size(); i++) {
            if (used[i]) continue;
            CaptureWindow cw = old.get(i);
            if (cw == activeWindow) {
                // 正在持有手势的窗口不能销毁：保留到手势结束（否则系统 CANCEL 掉拖拽）
                next.add(cw);
                Log.d(TAG, "[touchcap] 保留手势中的小窗 " + cw.rect);
            } else {
                removeWindow(cw);
            }
        }
        windows.clear();
        windows.addAll(next);
        Log.i(TAG, "[touchcap] 捕获窗同步: " + windows.size() + " 个 " + describeWindows());
    }

    private CaptureWindow addWindow(Rect r) {
        CaptureWindow cw = new CaptureWindow();
        cw.rect = new Rect(r);
        cw.view = new CaptureView(context, this);
        cw.lp = newLayoutParams(Math.max(1, r.width()), Math.max(1, r.height()));
        cw.lp.x = renderX + r.left;
        cw.lp.y = renderY + r.top;
        try {
            wm.addView(cw.view, cw.lp);
        } catch (Throwable t) {
            Log.e(TAG, "[touchcap] addView 失败 " + r + ": " + t);
            cw.view = null;
        }
        return cw;
    }

    private void removeWindow(CaptureWindow cw) {
        if (cw == null || cw.view == null) return;
        try {
            wm.removeView(cw.view);
        } catch (Throwable t) {
            Log.w(TAG, "[touchcap] removeView 失败: " + t);
        }
        cw.view = null;
    }

    private void updatePositions() {
        if (disposed) return;
        for (CaptureWindow cw : new ArrayList<>(windows)) {
            if (cw.view == null || cw.lp == null) continue;
            // 手势进行中：只保证「正在持有手势的捕获窗」逐帧跟手；其它窗等手势结束再统一补位
            // （减少主线程 WindowManager IPC，模拟器/低端机上这直接决定会不会 ANR）
            if (activeWindow != null && cw != activeWindow) continue;
            int nx = renderX + cw.rect.left;
            int ny = renderY + cw.rect.top;
            if (cw.lp.x == nx && cw.lp.y == ny) continue;
            cw.lp.x = nx;
            cw.lp.y = ny;
            try {
                wm.updateViewLayout(cw.view, cw.lp);
            } catch (Throwable t) {
                Log.w(TAG, "[touchcap] updateViewLayout 失败: " + t);
            }
        }
    }

    private WindowManager.LayoutParams newLayoutParams(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.width = w;
        lp.height = h;
        lp.alpha = 1f;
        lp.setTitle("DSHPetTouch");
        try {
            lp.setFitInsetsTypes(0); // 必须：否则 lp.y 会被状态栏 inset 偏移（T3 实测 +63px）
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        return lp;
    }

    // =====================================================================
    // 触摸转发
    // =====================================================================

    private boolean handleTouch(View source, MotionEvent event) {
        if (disposed || renderWebView == null) return false;
        CaptureWindow cw = findWindow(source);
        if (cw == null) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            activeWindow = cw;
            flushPendingMove(); // 新事件序列开始前，把上一轮残留的 MOVE 发掉
            Log.i(TAG, "[touchcap] DOWN 捕获 " + cw.rect + " local=" + (int) event.getX() + "," + (int) event.getY());
        }
        MotionEvent copy;
        try {
            copy = MotionEvent.obtain(event);
            // 小窗局部坐标 → 渲染窗（WebView）坐标：加上该矩形在渲染窗内的偏移
            copy.offsetLocation(cw.rect.left, cw.rect.top);
        } catch (Throwable t) {
            Log.e(TAG, "[touchcap] 事件复制失败: " + t);
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            // MOVE 合并：主线程慢时只保证最新位置，避免事件队列堆积
            if (pendingMove != null) {
                try {
                    pendingMove.recycle();
                } catch (Throwable ignored) {
                }
            }
            pendingMove = copy;
            if (!movePosted) {
                movePosted = true;
                main.post(dispatchMoveRunnable);
            }
        } else {
            flushPendingMove();
            // DOWN/UP/CANCEL 也异步派发：立即 return 让 InputDispatcher 收到 ack，杜绝输入 ANR
            main.post(new Runnable() {
                @Override
                public void run() {
                    dispatchToWebView(copy);
                }
            });
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activeWindow = null;
            // 手势结束后再按最新区域收敛一次（清掉拖拽时新增的整窗捕获窗，并补位其它捕获窗）
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    reconcileIfNeeded();
                    updatePositions();
                }
            }, 120);
        }
        return true; // DOWN 必须消费，否则后续 MOVE/UP 不会再来（Android 手势归本窗）
    }

    private final Runnable dispatchMoveRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (TouchCaptureManager.this) {
                movePosted = false;
            }
            MotionEvent m = pendingMove;
            pendingMove = null;
            if (m != null) dispatchToWebView(m);
        }
    };

    /** 把已排队的 MOVE 立刻放到主线程队首之前派发（保持 DOWN/MOVE/UP 的顺序） */
    private void flushPendingMove() {
        if (pendingMove == null) return;
        MotionEvent m = pendingMove;
        pendingMove = null;
        main.removeCallbacks(dispatchMoveRunnable);
        synchronized (this) {
            movePosted = false;
        }
        final MotionEvent fm = m;
        main.post(new Runnable() {
            @Override
            public void run() {
                dispatchToWebView(fm);
            }
        });
    }

    private void dispatchToWebView(MotionEvent ev) {
        if (ev == null) return;
        if (disposed) {
            try {
                ev.recycle();
            } catch (Throwable ignored) {
            }
            return;
        }
        int action = ev.getActionMasked();
        long t0 = System.nanoTime();
        try {
            WebView wv = renderWebView;
            if (wv != null) wv.dispatchTouchEvent(ev);
        } catch (Throwable t) {
            Log.e(TAG, "[touchcap] 转发触摸失败: " + t);
        } finally {
            try {
                ev.recycle();
            } catch (Throwable ignored) {
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (ms > 60) {
                Log.w(TAG, "[touchcap] dispatch 慢: action=" + action + " " + ms + "ms");
            }
        }
    }

    private CaptureWindow findWindow(View source) {
        for (CaptureWindow cw : windows) {
            if (cw.view == source) return cw;
        }
        return null;
    }

    private String keyOf(List<Rect> rects) {
        StringBuilder sb = new StringBuilder();
        for (Rect r : rects) {
            sb.append(r.left).append(',').append(r.top).append(',')
                    .append(r.right).append(',').append(r.bottom).append(';');
        }
        return sb.toString();
    }

    private String describeWindows() {
        StringBuilder sb = new StringBuilder();
        for (CaptureWindow cw : windows) {
            sb.append(cw.rect.toShortString()).append(' ');
        }
        return sb.toString();
    }

    private static final class CaptureWindow {
        Rect rect;
        View view;
        WindowManager.LayoutParams lp;
    }

    /** 透明且只负责把触摸转发给 WebView 的小窗视图 */
    private static final class CaptureView extends View {
        private final TouchCaptureManager manager;

        CaptureView(Context context, TouchCaptureManager manager) {
            super(context);
            this.manager = manager;
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return manager.handleTouch(this, event);
        }
    }
}
