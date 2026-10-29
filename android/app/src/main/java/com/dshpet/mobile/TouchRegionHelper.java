package com.dshpet.mobile;

import android.graphics.Region;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 悬浮窗「局部可点、余量穿透」的实现。
 *
 * 原理：framework 的 ViewTreeObserver 内部 insets 回调允许窗口声明一个可触摸 Region，
 * 只有 Region 内的触摸会被派发给本窗口，其余透明像素由系统直接传给下层应用（与 Electron 的
 * setIgnoreMouseEvents(false) 语义一致，但精度到区域而不是整窗）。
 *
 * 该 API（OnComputeInternalInsetsListener / InternalInsetsInfo）**不在公开 SDK 里**（android.jar 无此类），
 * 只能用反射访问；Android 的 non-SDK 限制在不同版本/厂商上可能拒绝反射。失败时本类返回 null，
 * 调用方退化为「整窗可点」并打印日志（SPEC §3.2 允许的兜底）。
 */
final class TouchRegionHelper {

    private static final String TAG = "DSHPet";
    /** ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION（framework 内部常量，值固定为 3） */
    private static final int TOUCHABLE_INSETS_REGION = 3;

    private final View view;

    private Object listener;
    private Method removeMethod;
    private Method setTouchableInsets;
    private Field touchableRegionField;

    private final Object lock = new Object();
    private final Region pending = new Region();
    private boolean havePending;
    private volatile boolean broken;
    private volatile boolean sawCallback;

    private TouchRegionHelper(View view) {
        this.view = view;
    }

    /** 安装；返回 null 表示本机不可用（调用方退化整窗可点） */
    static TouchRegionHelper install(View view) {
        TouchRegionHelper h = new TouchRegionHelper(view);
        if (h.doInstall()) {
            return h;
        }
        // 反射被 non-SDK 限制拦下时，尝试 VMRuntime.setHiddenApiExemptions 豁免一次再试
        if (tryBypassHiddenApi()) {
            TouchRegionHelper h2 = new TouchRegionHelper(view);
            if (h2.doInstall()) {
                Log.i(TAG, "[touch] 已用 hidden-api 豁免启用触摸区域");
                return h2;
            }
        }
        Log.w(TAG, "[touch] 触摸区域 API 不可用（non-SDK 限制/厂商裁剪）→ 退化整窗可点，透明余量会吞下层应用点击");
        return null;
    }

    private boolean doInstall() {
        try {
            Class<?> listenerCls = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            Class<?> infoCls = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            setTouchableInsets = infoCls.getMethod("setTouchableInsets", int.class);
            touchableRegionField = infoCls.getField("touchableRegion");

            Class<?> vtoCls = Class.forName("android.view.ViewTreeObserver");
            Method add = vtoCls.getMethod("addOnComputeInternalInsetsListener", listenerCls);
            removeMethod = vtoCls.getMethod("removeOnComputeInternalInsetsListener", listenerCls);

            final TouchRegionHelper self = this;
            listener = Proxy.newProxyInstance(listenerCls.getClassLoader(), new Class<?>[]{listenerCls},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("onComputeInternalInsets".equals(name) && args != null && args.length == 1) {
                                self.applyInsets(args[0]);
                                return null;
                            }
                            if ("equals".equals(name)) return proxy == args[0];
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("toString".equals(name)) return "DshPetTouchInsetsListener";
                            return null;
                        }
                    });

            ViewTreeObserver vto = view.getViewTreeObserver();
            if (vto == null || !vto.isAlive()) return false;
            add.invoke(vto, listener);
            Log.i(TAG, "[touch] 触摸区域 API 已启用（ViewTreeObserver.OnComputeInternalInsetsListener）");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[touch] 安装失败: " + t);
            return false;
        }
    }

    /** 反射回调：把当前待生效 Region 写进窗口内部 insets，并把触摸模式切到 TOUCHABLE_INSETS_REGION */
    private void applyInsets(Object info) {
        if (broken || info == null) return;
        sawCallback = true;
        try {
            Region region = null;
            synchronized (lock) {
                if (havePending) region = new Region(pending);
            }
            if (region == null || region.isEmpty()) {
                // 无区域声明：不做任何设置 → framework 默认整窗可触摸（安全兜底）
                return;
            }
            if (touchableRegionField != null) {
                Object f = touchableRegionField.get(info);
                if (f instanceof Region) {
                    ((Region) f).set(region);
                }
            }
            if (setTouchableInsets != null) {
                setTouchableInsets.invoke(info, TOUCHABLE_INSETS_REGION);
            }
        } catch (Throwable t) {
            broken = true;
            Log.w(TAG, "[touch] 运行期设置触摸区域失败，退化整窗可点: " + t);
        }
    }

    /** 新增/更新可触摸区域（物理像素、窗口内坐标） */
    void setRegion(Region region) {
        if (broken) return;
        synchronized (lock) {
            pending.set(region);
            havePending = true;
        }
        requestRecompute();
    }

    /** 取消区域声明（整窗可点） */
    void clearRegion() {
        if (broken) return;
        synchronized (lock) {
            pending.setEmpty();
            havePending = false;
        }
        requestRecompute();
    }

    boolean isBroken() {
        return broken;
    }

    boolean hasSeenCallback() {
        return sawCallback;
    }

    /** 触发一次 relayout，让系统重新计算窗口内部 insets（内部 insets 只在窗口 relayout 时计算） */
    private void requestRecompute() {
        if (broken) return;
        try {
            view.requestLayout();
        } catch (Throwable t) {
            broken = true;
            Log.w(TAG, "[touch] requestLayout 失败: " + t);
        }
    }

    void dispose() {
        try {
            if (removeMethod != null && listener != null) {
                ViewTreeObserver vto = view.getViewTreeObserver();
                if (vto != null && vto.isAlive()) removeMethod.invoke(vto, listener);
            }
        } catch (Throwable ignored) {
        }
        listener = null;
        removeMethod = null;
        setTouchableInsets = null;
        touchableRegionField = null;
        broken = true;
    }

    /**
     * 启动期诊断：把触摸区域 API 的可达性打进 logcat，便于对比不同 targetSdk 下的 hidden API 策略。
     * 不依赖窗口，纯反射探测。
     */
    static void probeAbility() {
        String directField = "?";
        String directMethod = "?";
        String bypass = "?";
        String afterField = "?";
        String afterMethod = "?";
        try {
            Class<?> infoCls = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            try {
                infoCls.getField("touchableRegion");
                directField = "ok";
            } catch (Throwable t) {
                directField = t.getClass().getSimpleName();
            }
            try {
                infoCls.getMethod("setTouchableInsets", int.class);
                directMethod = "ok";
            } catch (Throwable t) {
                directMethod = t.getClass().getSimpleName();
            }
            boolean bypassOk = tryBypassHiddenApi();
            bypass = bypassOk ? "ok" : "fail";
            Class<?> infoCls2 = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            try {
                infoCls2.getField("touchableRegion");
                afterField = "ok";
            } catch (Throwable t) {
                afterField = t.getClass().getSimpleName();
            }
            try {
                infoCls2.getMethod("setTouchableInsets", int.class);
                afterMethod = "ok";
            } catch (Throwable t) {
                afterMethod = t.getClass().getSimpleName();
            }
        } catch (Throwable t) {
            Log.w(TAG, "[touch-probe] 探测异常: " + t);
        }
        Log.i(TAG, "[touch-probe] targetSdk=" + android.os.Build.VERSION.SDK_INT
                + " 直读: field=" + directField + " method=" + directMethod
                + " 豁免=" + bypass + " 豁免后: field=" + afterField + " method=" + afterMethod);
    }

    /**
     * 经典 hidden-api 豁免：VMRuntime.setHiddenApiExemptions(["L"])。
     * 仅当直接反射被拦时尝试；仍失败就返回 false（调用方退化整窗）。
     */
    private static boolean tryBypassHiddenApi() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntime, "getRuntime", (Object) null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(vmRuntime, "setHiddenApiExemptions",
                    new Class[]{String[].class});
            Object runtime = getRuntime.invoke(null);
            setExemptions.invoke(runtime, new Object[]{new String[]{"L"}});
            Log.i(TAG, "[touch] hidden-api 豁免已设置");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[touch] hidden-api 豁免失败: " + t);
            return false;
        }
    }
}
