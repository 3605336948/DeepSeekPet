package com.dshpet.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 控制台：权限、开启/关闭桌宠、宠物大小、操作说明与降级说明。
 * 纯程序化 UI（无 AndroidX、无 XML 布局），全中文。
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 1001;

    private static final int BG = 0xFF14161B;
    private static final int CARD = 0xFF1E222B;
    private static final int TEXT = 0xFFE6EDF3;
    private static final int SUB = 0xFF9BA7B4;
    private static final int ACCENT = 0xFF4DA3FF;
    private static final int OK = 0xFF4CAF50;
    private static final int WARN = 0xFFFFB020;
    private static final int BAD = 0xFFE05B4B;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView overlayStatus;
    private TextView notifStatus;
    private TextView serviceStatus;
    private TextView sizeLabel;
    private TextView alphaLabel;
    private TextView harnessStatus;
    private EditText harnessBaseInput;
    private TextView diagText;
    private SeekBar sizeSeek;
    private SeekBar alphaSeek;
    private int petSizeDp;
    private int petAlphaPct;
    private boolean resumed;
    private volatile boolean harnessTesting;

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            refresh();
            ui.postDelayed(this, 1200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        } catch (Throwable ignored) {
        }
        petSizeDp = Prefs.getPetSizeDp(this);
        petAlphaPct = Math.round(Prefs.getPetAlpha(this) * 100f);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
        ui.removeCallbacks(refreshTick);
        ui.postDelayed(refreshTick, 1200);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(refreshTick);
    }

    // =====================================================================
    // UI 构建
    // =====================================================================

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(22), pad, dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = tv("DeepSeek 桌宠", 28, TEXT, true);
        root.addView(title);
        TextView subtitle = tv("dsh-pet v0.3.7 · Android 透明悬浮窗外壳", 13, SUB, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        root.addView(subtitle, subLp);
        root.addView(vspace(14));

        // ---- 运行状态 ----
        LinearLayout statusCard = card();
        statusCard.addView(sectionTitle("运行状态"));
        overlayStatus = tv("悬浮窗权限：检查中", 14, TEXT, false);
        notifStatus = tv("通知权限：检查中", 14, TEXT, false);
        serviceStatus = tv("桌宠服务：检查中", 14, TEXT, false);
        statusCard.addView(overlayStatus);
        statusCard.addView(notifStatus);
        statusCard.addView(serviceStatus);

        LinearLayout permRow = new LinearLayout(this);
        permRow.setOrientation(LinearLayout.HORIZONTAL);
        permRow.setPadding(0, dp(10), 0, 0);
        Button overlayBtn = button("去授权悬浮窗", false);
        overlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gotoOverlayPermission();
            }
        });
        Button notifBtn = button("通知权限", false);
        notifBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestNotifPermission();
            }
        });
        permRow.addView(overlayBtn, weightLp(1));
        permRow.addView(hspace(8));
        permRow.addView(notifBtn, weightLp(1));
        statusCard.addView(permRow);

        LinearLayout runRow = new LinearLayout(this);
        runRow.setOrientation(LinearLayout.HORIZONTAL);
        runRow.setPadding(0, dp(10), 0, 0);
        Button startBtn = button("开启桌宠", true);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPet();
            }
        });
        Button stopBtn = button("关闭桌宠", false);
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPet();
            }
        });
        runRow.addView(startBtn, weightLp(1));
        runRow.addView(hspace(8));
        runRow.addView(stopBtn, weightLp(1));
        statusCard.addView(runRow);
        root.addView(statusCard);
        root.addView(vspace(12));

        // ---- 宠物大小 ----
        LinearLayout sizeCard = card();
        sizeCard.addView(sectionTitle("宠物大小"));
        sizeLabel = tv("当前：130 dp", 16, TEXT, true);
        sizeCard.addView(sizeLabel);
        sizeSeek = new SeekBar(this);
        sizeSeek.setMax(Prefs.MAX_PET_SIZE_DP - Prefs.MIN_PET_SIZE_DP);
        sizeSeek.setProgress(petSizeDp - Prefs.MIN_PET_SIZE_DP);
        sizeSeek.setPadding(0, dp(8), 0, dp(2));
        sizeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                petSizeDp = Prefs.MIN_PET_SIZE_DP + progress;
                sizeLabel.setText("当前：" + petSizeDp + " dp");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Prefs.setPetSizeDp(MainActivity.this, petSizeDp);
                if (PetOverlayService.isRunning()) {
                    Intent i = new Intent(MainActivity.this, PetOverlayService.class)
                            .setAction(PetOverlayService.ACTION_RESTART);
                    try {
                        startService(i);
                        Toast.makeText(MainActivity.this, "已应用新大小，桌宠重启中…", Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Toast.makeText(MainActivity.this, "重启失败：" + t, Toast.LENGTH_LONG).show();
                    }
                }
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                }, 800);
            }
        });
        sizeCard.addView(sizeSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sizeHint = tv("范围 80–200dp，默认 130dp。运行中修改会自动重启桌宠生效。", 12, SUB, false);
        sizeCard.addView(sizeHint);
        root.addView(sizeCard);
        root.addView(vspace(12));

        // ---- 宠物透明度 ----
        LinearLayout alphaCard = card();
        alphaCard.addView(sectionTitle("宠物透明度"));
        alphaLabel = tv(alphaLabelText(petAlphaPct), 16, TEXT, true);
        alphaCard.addView(alphaLabel);
        alphaSeek = new SeekBar(this);
        alphaSeek.setMax(Prefs.MAX_PET_ALPHA_PCT - Prefs.MIN_PET_ALPHA_PCT);
        alphaSeek.setProgress(petAlphaPct - Prefs.MIN_PET_ALPHA_PCT);
        alphaSeek.setPadding(0, dp(8), 0, dp(2));
        alphaSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                petAlphaPct = Prefs.MIN_PET_ALPHA_PCT + progress;
                alphaLabel.setText(alphaLabelText(petAlphaPct));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                applyPetAlpha(petAlphaPct);
            }
        });
        alphaCard.addView(alphaSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView alphaHint = tv("范围 30%–80%，默认 80%。80% 是 Android 允许「点击穿透到其它应用」的最高不透明度"
                + "（再高会挡住下层 App 的点击）；调低会让大肥鱼更透明。运行中修改立即生效。", 12, SUB, false);
        alphaCard.addView(alphaHint);
        root.addView(alphaCard);
        root.addView(vspace(12));

        // ---- 连接电脑 DeepSeek Harness ----
        LinearLayout harnessCard = card();
        harnessCard.addView(sectionTitle("连接电脑 DeepSeek Harness"));
        harnessCard.addView(bullet("连接电脑上正在运行的 DeepSeek Harness 后，余额 / 碎碎念 / 对话 / 工作状态可用。"));
        harnessCard.addView(tutorialLink("USB 连接教程（点击查看）", TUTORIAL_USB));
        harnessCard.addView(tutorialLink("Wi-Fi 连接教程（点击查看）", TUTORIAL_WIFI));
        harnessCard.addView(bullet("未连接时自动降级为独立模式，与之前行为完全一致（不假装成功）。"));
        harnessBaseInput = new EditText(this);
        harnessBaseInput.setSingleLine(true);
        harnessBaseInput.setText(Prefs.getHarnessBase(this));
        harnessBaseInput.setHint("http://127.0.0.1:3412");
        harnessBaseInput.setHintTextColor(SUB);
        harnessBaseInput.setTextColor(TEXT);
        harnessBaseInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        harnessBaseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0xFF2A3140);
        inputBg.setCornerRadius(dp(8));
        harnessBaseInput.setBackground(inputBg);
        harnessBaseInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(6);
        harnessCard.addView(harnessBaseInput, inputLp);
        harnessStatus = tv("", 13, SUB, false);
        LinearLayout.LayoutParams harnessStatusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        harnessStatusLp.topMargin = dp(8);
        harnessCard.addView(harnessStatus, harnessStatusLp);
        LinearLayout harnessRow = new LinearLayout(this);
        harnessRow.setOrientation(LinearLayout.HORIZONTAL);
        harnessRow.setPadding(0, dp(8), 0, 0);
        Button harnessConnectBtn = button("保存并连接", true);
        harnessConnectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testAndConnectHarness();
            }
        });
        Button harnessDisconnectBtn = button("断开（降级）", false);
        harnessDisconnectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnectHarness();
            }
        });
        harnessRow.addView(harnessConnectBtn, weightLp(1));
        harnessRow.addView(hspace(8));
        harnessRow.addView(harnessDisconnectBtn, weightLp(1));
        harnessCard.addView(harnessRow);
        root.addView(harnessCard);
        root.addView(vspace(12));

        // ---- 操作说明 ----
        LinearLayout helpCard = card();
        helpCard.addView(sectionTitle("操作说明"));
        helpCard.addView(bullet("单指按住拖动：宠物跟着手指走，松手停住"));
        helpCard.addView(bullet("快速甩出松手：按速度抛掷（重力 + 落地反弹 + 屏幕边缘反弹）"));
        helpCard.addView(bullet("轻点一下：播放点击回应动画"));
        helpCard.addView(bullet("长按约 0.5 秒：弹出菜单（打开网站 / 回到初始位置 / 重载配置）"));
        helpCard.addView(bullet("只有宠物身体可点，周围透明区域会穿透到下层应用"));
        root.addView(helpCard);
        root.addView(vspace(12));

        // ---- 手机版功能说明 ----
        LinearLayout degradeCard = card();
        degradeCard.addView(sectionTitle("手机版功能说明"));
        degradeCard.addView(bullet("余额 / 碎碎念 / 对话 / 工作状态：连接电脑上的 DeepSeek Harness 后可用（见上方「连接电脑 DeepSeek Harness」卡片；手机版不弹系统通知）。"));
        degradeCard.addView(bullet("未连接时自动降级为独立模式：动画链、拖拽抛掷物理、点击回应、长按菜单、桌面漫游全部可用（不假装成功）。"));
        root.addView(degradeCard);
        root.addView(vspace(12));

        // ---- 素材来源 / 许可 ----
        LinearLayout licenseCard = card();
        licenseCard.addView(sectionTitle("素材来源与许可"));
        licenseCard.addView(bullet("动画素材、字体、表情包与交互逻辑来自 dsh-pet v0.3.7（MIT License）。"));
        licenseCard.addView(bullet("本 App 仅是其 Android 透明悬浮窗外壳：页面与素材原样打包，未修改宠物行为逻辑。"));
        licenseCard.addView(bullet("宠物形象「大肥鱼」及全部文案来自 dsh-pet 原配置。"));
        root.addView(licenseCard);
        root.addView(vspace(12));

        // ---- 诊断 ----
        LinearLayout diagCard = card();
        diagCard.addView(sectionTitle("诊断"));
        diagText = tv("", 12, SUB, false);
        diagText.setTypeface(Typeface.MONOSPACE);
        diagText.setTextIsSelectable(true);
        diagCard.addView(diagText);
        root.addView(diagCard);

        root.addView(vspace(16));
        TextView footer = tv("版本 1.1.4 (versionCode 6) · 本地自签名 · package com.dshpet.mobile", 11, SUB, false);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(footer, flp);
        return scroll;
    }

    // =====================================================================
    // 状态刷新
    // =====================================================================

    private void refresh() {
        boolean overlayOk = Settings.canDrawOverlays(this);
        overlayStatus.setText("悬浮窗权限：" + (overlayOk ? "已授权" : "未授权（必须授权才能显示桌宠）"));
        overlayStatus.setTextColor(overlayOk ? OK : BAD);

        boolean notifOk;
        if (Build.VERSION.SDK_INT >= 33) {
            notifOk = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            notifStatus.setText("通知权限：" + (notifOk ? "已授权" : "未授权（前台服务通知不显示，但桌宠仍可运行）"));
            notifStatus.setTextColor(notifOk ? OK : WARN);
        } else {
            notifStatus.setText("通知权限：Android 13 以下无需申请");
            notifStatus.setTextColor(OK);
        }

        boolean running = PetOverlayService.isRunning();
        String err = PetOverlayService.getLastError();
        serviceStatus.setText("桌宠服务：" + (running ? "运行中" : "未运行")
                + (TextUtils.isEmpty(err) ? "" : "（" + err + "）"));
        serviceStatus.setTextColor(running ? OK : (TextUtils.isEmpty(err) ? SUB : BAD));

        Geometry g = Geometry.compute(this);
        StringBuilder sb = new StringBuilder();
        sb.append("屏幕：").append(g.screenW).append("×").append(g.screenH)
                .append(" 物理像素 / 密度 ").append(g.density).append("\n");
        sb.append("安全区：").append(g.safeLeft).append(",").append(g.safeTop)
                .append(" – ").append(g.safeRight).append(",").append(g.safeBottom).append("\n");
        sb.append("宠物大小：").append(petSizeDp).append(" dp\n");
        sb.append("宠物透明度：").append(petAlphaPct).append("%（30–80%；80% 为系统穿透上限）\n");
        sb.append("DSH Harness：").append(Prefs.isHarnessConnected(this) ? "已连接 " : "未连接（降级） ")
                .append(Prefs.getHarnessBase(this)).append("\n");
        String info = PetOverlayService.getInfoJson();
        if (!TextUtils.isEmpty(info) && !"{}".equals(info)) {
            sb.append("服务信息：").append(info).append("\n");
        }
        if (!TextUtils.isEmpty(err)) {
            sb.append("最近错误：").append(err).append("\n");
        }
        diagText.setText(sb.toString());
        refreshHarnessStatus();
    }

    // =====================================================================
    // 连接教程（USB / Wi-Fi 点击弹窗）
    // =====================================================================

    private static final String TUTORIAL_USB =
            "USB 连接教程（手机 ↔ 电脑 DeepSeek Harness）\n\n"
                    + "1. 手机打开「开发者选项 → USB 调试」，用数据线连到电脑，手机上允许 USB 调试。\n"
                    + "2. 电脑上运行 tools\\harness-connect.ps1（自动执行 adb reverse tcp:3412 tcp:3412 并自检；也可手动执行该命令）。\n"
                    + "3. 回到手机 App，地址填 http://127.0.0.1:3412，点「保存并连接」。\n"
                    + "4. 状态显示「已连接」后，余额 / 碎碎念 / 对话 / 工作状态即可使用；断开请点「断开（降级）」。\n\n"
                    + "通用说明：连接态 = 最近一次「测试连接」成功；未连接/断开后自动降级（不假装成功）；电脑 DSH 关闭后需重新连接。";

    private static final String TUTORIAL_WIFI =
            "Wi-Fi 连接教程（手机 ↔ 电脑 DeepSeek Harness）\n\n"
                    + "1. 手机和电脑连接同一个 Wi-Fi（同一路由器）。\n"
                    + "2. 电脑上双击运行 tools\\harness-wifi-proxy.bat，保持窗口打开，记下窗口里显示的地址（例如 http://192.168.2.9:3413）。\n"
                    + "3. 回到手机 App，把该地址填入地址框，点「保存并连接」。\n"
                    + "4. 状态显示「已连接」后，余额 / 碎碎念 / 对话 / 工作状态即可使用。\n"
                    + "5. 排查：确认电脑 DSH 正在运行、代理窗口没有闪退/关闭、电脑防火墙已放行 3413、"
                    + "地址里是电脑 WLAN 的 IPv4（电脑 ipconfig 查看）。\n\n"
                    + "通用说明：连接态 = 最近一次「测试连接」成功；未连接/断开后自动降级；电脑 DSH 关闭后需重新连接。\n"
                    + "安全：代理只监听电脑局域网网卡并转发到本机 127.0.0.1:3412，不上传外网；仅限自家可信局域网使用，用完关掉代理窗口。";

    private TextView tutorialLink(String text, final String tutorial) {
        TextView v = new TextView(this);
        v.setText("· " + text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTextColor(ACCENT);
        v.setPaintFlags(v.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        v.setPadding(0, dp(3), 0, dp(3));
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v2) {
                try {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("连接教程")
                            .setMessage(tutorial)
                            .setPositiveButton("知道了", null)
                            .show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "教程打开失败：" + t, Toast.LENGTH_LONG).show();
                }
            }
        });
        return v;
    }

    // =====================================================================
    // 连接电脑 DeepSeek Harness
    // =====================================================================

    private void refreshHarnessStatus() {
        if (harnessStatus == null) return;
        if (harnessTesting) {
            harnessStatus.setText("状态：正在测试连接 " + Prefs.getHarnessBase(this) + " …");
            harnessStatus.setTextColor(WARN);
            return;
        }
        if (Prefs.isHarnessConnected(this)) {
            harnessStatus.setText("状态：已连接（" + Prefs.getHarnessBase(this) + "）");
            harnessStatus.setTextColor(OK);
        } else {
            harnessStatus.setText("状态：未连接 · 降级模式（余额/碎碎念/对话/工作状态不可用）");
            harnessStatus.setTextColor(SUB);
        }
    }

    /** 点「保存并连接」：先测 3s（GET {base}/dsh-pet-7340/state），成功才启用并重启桌宠让页面重载 config */
    private void testAndConnectHarness() {
        if (harnessTesting) return;
        String base = Prefs.normalizeHarnessBase(harnessBaseInput == null
                ? "" : harnessBaseInput.getText().toString());
        if (harnessBaseInput != null) harnessBaseInput.setText(base);
        harnessTesting = true;
        refreshHarnessStatus();
        final String target = base;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String error = probeHarness(target);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        harnessTesting = false;
                        Prefs.setHarnessBase(MainActivity.this, target);
                        if (error == null) {
                            Prefs.setHarnessConnected(MainActivity.this, true);
                            Toast.makeText(MainActivity.this, "已连接电脑 DSH：" + target, Toast.LENGTH_SHORT).show();
                        } else {
                            Prefs.setHarnessConnected(MainActivity.this, false);
                            Toast.makeText(MainActivity.this, "连接失败：" + error, Toast.LENGTH_LONG).show();
                        }
                        refreshHarnessStatus();
                        // 连接态变化 → 重启桌宠让页面按新配置重载（未连接时=与 v1.1.1 完全一致的降级）
                        restartPetIfRunning();
                        ui.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                refresh();
                            }
                        }, 900);
                    }
                });
            }
        }, "dsh-harness-test").start();
    }

    /** 断开：立即降级并重启桌宠；返回 null=成功，否则为错误描述 */
    private void disconnectHarness() {
        Prefs.setHarnessConnected(this, false);
        Toast.makeText(this, "已断开电脑 DSH，切换为降级模式", Toast.LENGTH_SHORT).show();
        refreshHarnessStatus();
        restartPetIfRunning();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 900);
    }

    private void restartPetIfRunning() {
        if (!PetOverlayService.isRunning()) return;
        try {
            startService(new Intent(this, PetOverlayService.class)
                    .setAction(PetOverlayService.ACTION_RESTART));
        } catch (Throwable t) {
            Log.w("DSHPet", "[harness] 重启桌宠失败: " + t);
            Toast.makeText(this, "重启桌宠失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /** 3 秒超时探测电脑 DSH：GET {base}/dsh-pet-7340/state；成功返回 null */
    private static String probeHarness(String base) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(base + "/dsh-pet-7340/state");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return "HTTP " + code;
            InputStream in = conn.getInputStream();
            try {
                byte[] buf = new byte[2048];
                int n;
                int total = 0;
                do {
                    n = in.read(buf);
                    if (n > 0) total += n;
                } while (n > 0 && total < 64 * 1024);
                if (total <= 0) return "响应为空";
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : (": " + t.getMessage()));
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // =====================================================================
    // 动作
    // =====================================================================

    private void gotoOverlayPermission() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Throwable t2) {
                Toast.makeText(this, "无法打开权限设置页，请手动到系统设置中授予悬浮窗权限",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Android 13 以下无需通知权限", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "通知权限已授权", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            refresh();
        }
    }

    private void startPet() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授权悬浮窗权限（显示在其他应用上层）", Toast.LENGTH_LONG).show();
            gotoOverlayPermission();
            return;
        }
        if (PetOverlayService.isRunning()) {
            Toast.makeText(this, "桌宠已在运行", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, PetOverlayService.class)
                .setAction(PetOverlayService.ACTION_START);
        try {
            startForegroundService(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "启动失败：" + t, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "正在开启桌宠…", Toast.LENGTH_SHORT).show();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 900);
    }

    private void stopPet() {
        try {
            stopService(new Intent(this, PetOverlayService.class));
        } catch (Throwable t) {
            Toast.makeText(this, "关闭失败：" + t, Toast.LENGTH_LONG).show();
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 500);
    }

    // =====================================================================
    // 宠物透明度
    // =====================================================================

    private String alphaLabelText(int pct) {
        if (pct >= Prefs.MAX_PET_ALPHA_PCT) {
            return "当前：" + pct + "%（系统穿透上限）";
        }
        return "当前：" + pct + "%";
    }

    private void applyPetAlpha(int pct) {
        Prefs.setPetAlpha(this, pct / 100f);
        if (PetOverlayService.isRunning()) {
            Intent i = new Intent(this, PetOverlayService.class)
                    .setAction(PetOverlayService.ACTION_SET_ALPHA)
                    .putExtra(PetOverlayService.EXTRA_ALPHA, pct / 100f);
            try {
                startService(i); // 服务已在运行：普通 startService 即可，无需 startForegroundService
                Toast.makeText(this, "已应用透明度 " + pct + "%", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "透明度应用失败：" + t, Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "已保存透明度 " + pct + "%（开启桌宠后生效）", Toast.LENGTH_SHORT).show();
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 500);
    }

    // =====================================================================
    // 小程序化 UI 工具
    // =====================================================================

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView tv(String text, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setLineSpacing(dp(2), 1f);
        return v;
    }

    private TextView sectionTitle(String text) {
        TextView v = tv(text, 15, ACCENT, true);
        v.setPadding(0, 0, 0, dp(8));
        return v;
    }

    private TextView bullet(String text) {
        TextView v = tv("· " + text, 13, SUB, false);
        v.setPadding(0, dp(2), 0, dp(2));
        return v;
    }

    private LinearLayout card() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        ll.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(0.6f)), 0xFF2A3140);
        ll.setBackground(bg);
        return ll;
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(primary ? 0xFF0B1220 : TEXT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? ACCENT : 0xFF2A3140);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(6), dp(9), dp(6), dp(9));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(42));
        b.setStateListAnimator(null);
        return b;
    }

    private View vspace(int px) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px));
        return v;
    }

    private View hspace(int px) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(px, 1));
        return v;
    }

    private LinearLayout.LayoutParams weightLp(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        return lp;
    }
}
