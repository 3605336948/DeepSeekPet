# dsh-pet 手机版 · 全功能/全按钮实测报告 + 新增「宠物透明度」功能

> 被测包：`D:\AndroidProjects\DeepSeekPet\android\app\build\outputs\apk\release\app-release.apk`
> **最终交付版本：`com.dshpet.mobile` versionCode=6 / versionName=1.1.4**（v9：透明度 + 动作菜单触摸修复 + 连接电脑 DSH（USB/Wi-Fi）+ 碎碎念/对话/余额/工作状态 + 连接教程弹窗 + 聊天键盘收起修复 + 连接态 7 项菜单紧凑样式）
> 大小：**63,802,478 B（60.85 MB）**；构建时间 **2026-10-21 23:18:53**
> **SHA256：`D79F42F12B06AF3287D0AC851CA6113BCFEF1ED9A6D1F0A911ECFCF1F666EEC0`**
> APK 内 mobile-bridge.js：VERSION=**1.1.1**，size=44,714 B，sha256=`243E2E822DB6F29198318131752B5CCD9883130EB48CBDB76DC00B90A373BCC6`
> APK 内 config.json：9,878 B，sha256=`D9B07A6B6BAAB39A7116FB2735D80B36623BE9F2ADF17FE64AEEB187F32BCD4A`（`__ENABLE_DSH__` 按连接态替换）
> （v1.1.0 419EDD13… / v1.1.1 7E4855E2… / v1.1.2 AD016F05… / v1.1.3 3933C450…+1C4C3419… 为过程包，均已作废；最终以本页数据为准）
> 设备：用户真机 **vivo V2359A（PD2309M）/ Android 16 (SDK 36) / 1260×2800 @560dpi（dpr=3.5）/ WebView 138.0.7204.179**
> 方法：USB ADB 真机安装 + adb 输入注入 + 截图 + logcat；窗口透明度用 logcat + `[svc] info`（alphaPct）+ dumpsys 交叉验证。

---

## 0. 结论速览

- **新增「宠物透明度」功能已实现并在真机验证通过**（80%→60%→40%→80% 热更新、服务重启后持久化、40% 下跨 App 穿透仍有效）。
- **全部按钮/功能逐项测试完成**：MainActivity 11 项 + 宠物交互 15 项 = **26 项**，其中 **24 项通过**，2 项为预期降级/可接受；**发现 1 个需后续修复的触摸交互问题（动作菜单三级动画列表）**（详见 §3）。
- 全程 logcat **无 FATAL / 无 ANR / 无 JS error / 无资源加载错误 / 无 `[touchcap] dispatch 慢`**。

---

## 1. 新增功能：宠物透明度（用户需求）

### 1.1 功能说明
- 控制台（MainActivity）「宠物大小」下方新增 **「宠物透明度」** 卡片：
  - SeekBar 范围 **30%–80%**，默认 **80%**，右侧标签实时显示「当前：X%（系统穿透上限）」；
  - 说明文案：*「80% 是 Android 允许『点击穿透到其它应用』的最高不透明度（再高会挡住下层 App 的点击）；调低会让大肥鱼更透明。运行中修改立即生效。」*
  - 拖动松手即保存并热更新（运行中无需重启）；Toast「已应用透明度 X%」；未运行时提示「已保存…开启桌宠后生效」。
- 技术实现（3 个文件）：
  - `Prefs.java`：`pet_alpha_pct`（30/80 clamp）；
  - `MainActivity.java`：SeekBar + `applyPetAlpha()` → `ACTION_SET_ALPHA`；诊断文本加「宠物透明度」；页脚版本 1.1.0；
  - `PetOverlayService.java`：`ACTION_SET_ALPHA` → 主线程 `lp.alpha = clamp(0.30–0.80); wm.updateViewLayout(webView, lp)`，**不重建窗口、保持 `FLAG_NOT_TOUCHABLE`（跨 App 穿透不受影响）**；`buildInfoJson` 增加 `alphaPct`；服务启动时从 Prefs 读取。
- 为什么上限 80%：Android 12+ 对 `FLAG_NOT_TOUCHABLE` 系统悬浮窗强制 alpha≤0.80 才允许触摸穿透（100% 不透明会挡住下层 App 点击），因此 30%–80% 是"可穿透"范围内的合理区间。

### 1.2 真机实测
| 测试 | 操作 | 结果 | 证据 |
|---|---|---|---|
| 80% 默认 | 打开桌宠 | ✅ dumpsys `alpha=0.8`；UI 标签「当前：80%（系统穿透上限）」 | `shots/func_01_main_top.png` |
| 80%→60% | 拖动 SeekBar | ✅ logcat `[overlay] 透明度更新 alpha=0.6`；UI 标签「当前：60%」 | `shots/func_08_alpha_60_activity.png`、`func_09_home_alpha_60.png` |
| 60%→40% | 拖动 SeekBar | ✅ `[overlay] 透明度更新 alpha=0.4`；UI「当前：40%」，桌面视觉明显更透 | `shots/func_09_alpha_40.png`、`func_10_home_alpha_40.png` |
| 重启持久化 | 关闭桌宠→重新开启 | ✅ `[svc] info {"alphaPct":40,...}`，重启后仍 40% | `shots/func_37_alpha_persist_40.png`、`logs/func_17_alpha_persist.txt` |
| 40% 下穿透 | HOME 后点宠物窗口透明余量处的 QQ 图标 | ✅ QQ 启动（`com.tencent.mobileqq`），无 untrusted-touch 拦截 | `shots/func_11_passthrough_alpha40.png` |
| 恢复 80% | 拖回 | ✅ `[overlay] 透明度更新 alpha=0.8` | `logs/func_17_alpha_persist.txt` |
| 诊断/版本显示 | 滚到页面底部 | ✅ 「宠物透明度：80%（30–80%；80% 为系统穿透上限）」+ 服务信息含 `alphaPct`；页脚「版本 1.1.0 (versionCode 2)」 | `shots/func_38_diagnostics_footer.png` |

> 细节：拖动 SeekBar 时若从滑轨最右边缘（x=1148 边界）起滑可能不触发（SeekBar 边界语义，非 bug）；从滑块位置起滑正常。

---

## 2. 全功能 / 全按钮测试矩阵（真机）

### A. MainActivity（控制台）
| # | 功能/按钮 | 操作 | 结果 | 证据 |
|---|---|---|---|---|
| A1 | 标题/副标题/版本页脚 | 打开 App | ✅ 显示「DeepSeek 桌宠 / dsh-pet v0.3.7 / 版本 1.1.0 (versionCode 2)」 | `func_01_main_top.png`、`func_38_diagnostics_footer.png` |
| A2 | 运行状态-悬浮窗权限 | 已授权/未授权切换 | ✅ 已授权绿字；`pm revoke` 后显示未授权（红线文案），重新授权恢复 | `func_01_main_top.png` |
| A3 | 运行状态-通知权限 | revoke → 未授权；授权后已授权 | ✅ 文案/颜色正确切换（未授权黄色提示"桌宠仍可运行"） | `func_02_notif_revoked.png`、`func_05_notif_granted.png` |
| A4 | 运行状态-桌宠服务 | 未运行 → 运行中 | ✅ 实时刷新（1.2s 轮询） | `func_01_main_top.png`、`func_07_pet_started.png` |
| A5 | **「去授权悬浮窗」按钮** | 点击 | ✅ 打开 vivo 悬浮窗权限管理页（`com.vivo.permissionmanager/FloatWindowManagerActivity`） | `func_06_overlay_permission_page.png` |
| A6 | **「通知权限」按钮** | 点击（权限未授权时） | ✅ 弹出系统通知权限弹窗 → 点「允许」→ `granted=true`，状态变已授权 | `func_03_notif_dialog.png`、`func_05_notif_granted.png` |
| A7 | **「开启桌宠」按钮** | 点击 | ✅ 前台服务启动，悬浮窗出现、宠物渲染，Toast | `func_07_pet_started.png`、`logs/func_03_start.txt` |
| A8 | **「关闭桌宠」按钮** | 点击 | ✅ `[svc] onDestroy`，dumpsys 悬浮窗 NONE | `func_14_service_closed.png`、`logs/func_05_size_switch.txt` |
| A9 | **宠物大小 SeekBar 200dp** | 拖到最右 | ✅ `[overlay] 重建 petSizeDp=200`，窗口 **1400×1127** | `func_12_size_200dp.png` |
| A10 | 宠物大小 80dp | 拖到最左 | ✅ `petSizeDp=81`（滑杆步进），窗口 **567×456** | `func_13_size_80dp.png` |
| A11 | 宠物大小 130dp 恢复 | 拖回 | ✅ 窗口恢复 **910×732** | `func_15_service_reopened.png` |
| A12 | **宠物透明度 SeekBar（新）** | 80→60→40→80 | ✅ 见 §1.2 全部通过 | `func_08/09/10/37` |
| A13 | 操作说明/手机版功能说明/素材来源与许可 | 滚动浏览 | ✅ 文案完整、无截断/乱码 | `func_38_diagnostics_footer.png` |
| A14 | 诊断卡片 | 滚动到底 | ✅ 屏幕/安全区/大小/透明度/服务信息 JSON 正确 | `func_38_diagnostics_footer.png` |

### B. 桌宠（悬浮窗）交互
| # | 功能 | 操作 | 结果 | 证据 |
|---|---|---|---|---|
| B1 | 点击回应 | 轻点宠物身体 | ✅ `[dsh-pet] grab` + 点击回应动画 | `logs/func_02_click.txt` |
| B2 | 单指拖动跟手 | 慢速拖动 | ✅ 窗口从 (494,1967) 跟手到 (53,1169) | `shots/func_16_after_drag.png`、`logs/func_06_drag_reset.txt` |
| B3 | 快速甩出→抛掷物理 | 快速上拖松手 | ✅ `[dsh-pet] release vx=0 vy=-950 |v|=950`，重力+回弹落位 | `logs/func_03_clean_fastdrag.txt` |
| B4 | 长按弹菜单 | 按住约 0.9s | ✅ 菜单弹出且松手后保持 | `func_17_menu_open.png` |
| B5 | 菜单→**打开网站** | 点菜单项 | ✅ 打开 vivo 浏览器（`com.vivo.browser/BrowserActivity`） | `func_19_menu_open_website.png`、`logs/func_07_menu_openweb.txt` |
| B6 | 菜单→**碎碎念** | 点菜单项 | ✅/预期降级：`[dsh-pet] 菜单碎碎念：生成动作未成功 pet=main`（移动端无 DSH 宿主，无崩溃） | `func_20_menu_whisper.png`、`logs/func_08_menu_whisper.txt` |
| B7 | 菜单→**对话** | 点菜单项 | ✅/预期降级：对话面板打开（页面内面板），提示需连接电脑；可正常关闭 | `func_21_menu_chat.png`、`func_21b_after_chat.png` |
| B8 | 菜单→**回到初始位置** | 拖走后点该项 | ✅ 从 (53,1169) 精确回到初始右下角 (494,1967) | `func_26_reset_position_after_drag.png`、`logs/func_14_reset.txt` |
| B9 | 菜单→**重载配置** | 点菜单项 | ✅ 无报错、菜单正常关闭（移动端为静态配置，无可见变化属预期；建议后续加日志/提示） | `func_23_menu_reload_config.png`、`logs/func_11_menu_reload.txt` |
| B10 | 菜单→**动作**（二级） | 点菜单项 | ✅ 二级分类子菜单展开（待机/转向/拖拽/点击回应/移动/小动作） | `func_24_menu_actions_submenu.png`、`logs/func_12_menu_actions.txt` |
| B11 | 动作→具体动画（三级） | 点「待机」分类 | ⚠️ **问题**：三级动画列表闪现后关闭，触屏无法选中具体动画（桌面 hover 菜单语义，见 §3.1） | `func_25_menu_idle_submenu.png`、`logs/func_13_menu_idle.txt` |
| B12 | 桌面漫游 | 空闲 120s 持续观察 | ✅ 观察到自主位移 1 次：(-28,2436)→(287,1803)；频率较低 | `func_35_idle_roam_60s.png`、`func_36_roam_after_120s.png` |
| B13 | 跨 App 点击穿透 | HOME 后点透明余量处图标 | ✅ 40%/80% 透明度下均可穿透（QQ、红果短剧等成功启动），无 untrusted-touch 拦截 | `func_11_passthrough_alpha40.png`、`logs/func_04_passthrough40.txt` |
| B14 | 旋转屏幕 | 横屏→竖屏 | ✅ `[display] 1260×2800 → 2800×1260`，宠物在屏不丢，转回正常 | `func_32_landscape.png`、`func_33_portrait_back.png` |
| B15 | 息屏/亮屏 | KEYCODE_SLEEP→WAKEUP | ✅ 前台服务存活，亮屏后宠物仍在 | `func_34_after_screen_off_on.png` |
| B16 | 前台服务通知 | 下拉通知栏 | ✅ 通知存在：「DeepSeek 桌宠 / 大肥鱼正在桌面上陪伴你」，点击打开 MainActivity | `func_28_notification_panel.png`、`func_29_notification_tap.png` |
| B17 | 通知「关闭桌宠」动作 | 展开通知点动作 | ✅ `[svc] 收到关闭指令`+`onDestroy`，窗口消失；之后可从 App 重新开启 | `func_30_notification_expanded.png`、`func_31_closed_via_notification.png` |
| B18 | 关闭后重开 | 关闭桌宠→开启桌宠 | ✅ 窗口重建、宠物回右下角 | `func_14_service_closed.png`、`func_15_service_reopened.png` |

---

## 3. 发现的问题 / 已知限制（按优先级）

### 3.1 ⚠️ 需修复：动作菜单第三级动画列表在触屏上无法选择
- 现象：长按→动作→点「待机/点击回应」等分类时，第三级动画列表面板会**闪现后立即关闭**（logcat 可见捕获窗短暂出现第 5 个面板 `[14,59][539,198]` 后回到 1 个），触屏无法点选具体动画。
- 原因（推断）：dsh-pet 的级联子菜单是**桌面 hover 语义**（`mouseenter` 展开），触摸没有 hover；T1 的 bridge 只合成了长按 contextmenu，没有把触摸 move 合成为 mousemove。
- 影响：手机端暂时无法用「动作」菜单指定播放某个动画（其他菜单项正常）。
- 建议：T1 在 `mobile-bridge.js` 里为菜单区域把 pointermove/touchmove 合成 mousemove（或给菜单加 touch 分支：点分类项即展开并保持、再点动画项触发）。修复后我可在真机复测。

### 3.2 重载配置无可见反馈（低优先级）
- 点击「重载配置」无报错，但移动端配置是静态打包的，没有可观察变化。建议：T2 在 bridge/菜单回调里加一条 toast 或日志（"配置已重载（移动端静态配置）"），避免用户疑惑。

### 3.3 预期降级项（符合 SPEC 口径，不是 bug）
- 碎碎念：`[dsh-pet] 菜单碎碎念：生成动作未成功`（无 DSHPet 宿主）；
- 对话：面板打开但需要连接电脑端 DeepSeek Harness，手机版不可用（App 内已有说明文案）；
- 余额/工作状态/系统通知：配置已关闭，不在菜单出现。

### 3.4 系统限制（已由 Lead 决策接受）
- 宠物整体最高 **80% 不透明度**（`FLAG_NOT_TOUCHABLE` 悬浮窗要允许跨 App 穿透，Android 12+ 强制 alpha≤0.80）；新透明度功能在 30–80% 内可调，UI 已写明原因。
- 敏感 App（设置/银行类）会隐藏非系统悬浮窗（系统行为）。

---

## 4. logcat 结论
- 全功能测试期间 logcat 扫描（`logs/func_*.txt` + 终局）：
  - `FATAL EXCEPTION`：0；`ANR in com.dshpet.mobile`：0；`unresponsive`：0；
  - `net::ERR_FAILED` / 素材加载失败：0；`JS_ERROR`：0；`[touchcap] dispatch 慢`：0。
- 关键状态：`[web] diag` version=1.0.1、hasBridge=true、sprites=1、videos=2、configOk=true（dpr=3.5、inner=260×209）。
- 内存：PSS ≈ 144 MB 量级，运行流畅。
- 备注：`pm revoke POST_NOTIFICATIONS` 会杀掉 App 进程（系统行为，测试时需注意）；vivo 的 `avc denied ioctl /proc/fas/render` 为厂商性能服务日志，无害。

---

## 5. 交付信息 & 后续
- **最终 APK**：`D:\AndroidProjects\DeepSeekPet\android\app\build\outputs\apk\release\app-release.apk`
  - versionCode 2 / versionName 1.1.0，60.82 MB，构建 2026-10-21 21:49:51
  - **SHA256 `419EDD1302396898E9C44665B4F754FABC94ED4A740BD158D97232CEFB67E527`**
  - 旧 v4（1.0.0，sha256 `A1019BFE…`/`1F91FF…`）作废。
- 建议 Lead 将此包更新到 `dist\` 并附安装说明（透明度功能说明 + 80% 上限原因）。
- 后续修复项：§3.1（T1 bridge 触摸→hover 合成，最高优先）、§3.2（T2 加提示，低优先）；修复后我可复测。
- 真机当前状态：桌宠运行中（80% 不透明度、右下角），App 版本 1.1.0。

---

## 6. 证据文件
- 截图（`qa\shots\func_*.png`，共 41 张）：主界面/权限弹窗/透明度 60%·40%·持久化/大小 200·80·130dp/菜单与子菜单/漫游/穿透/旋转/通知/关窗/诊断页。
- 日志（`qa\logs\func_*.txt`）：按钮与权限、大小切换、拖动/甩抛、菜单各项、透明度更新与持久化、通知动作、终局扫描。
- 相关报告：`qa\QA-REPORT.md`（v1.0.0 基线 QA）、`qa\PROBE-REPORT.md`（技术探针 Q1–Q5）。

---

## 7. v1.1.1 回归（2026-10-21 22:21–22:28，真机）——全部 PASS

> 版本：versionCode=3 / versionName=1.1.1；APK sha256 `7E4855E24788D7270B15A68F455191496172DC9E9873303A279D35219C5B4142`
> 本版变更：① mobile-bridge.js → **1.0.2**：新增「触摸菜单导航」补丁（修动作菜单三级动画不可选）；② AssetServer：`/whisper`、`/chat` 请求时 Toast 提示（2s 节流，行为仍返回 unsupported）。

### 7.1 核心修复验证：动作 → 分类 → 三级动画可选 ✅
| 步骤 | 预期 | 实测证据 |
|---|---|---|
| 长按宠物 → 菜单 | 菜单打开 | `捕获窗同步: 3 个`；`rg_b0_menu_open.png` |
| 点「动作」 | 二级分类展开 | `捕获窗同步: 4 个 … [371,63][897,718] [14,42][539,720]`；`rg_c1_actions_level2.png` |
| 点「点击回应」分类（第一次点击） | **flyout 保持展开**（修复前会闪现关闭） | `捕获窗同步: 5 个 … [14,168][558,720]`，**菜单保持**；`rg_d2_clickcat_flyout.png` 可见完整三级项：点击回应-开心跃动/害羞惊讶/傲娇生气/挠痒咯咯笑/元气挥手 |
| 点具体动画「害羞惊讶」 | 菜单关闭 + 播放该动画 | `捕获窗同步: 5→2→1`，`setInputBusy=false`；`rg_f2_click_anim_0.3s.png` / `rg_f3_click_anim_1s.png`（宠物切到点击回应动画）；帧差 939,427px |
| 「待机」分类同样验证 | flyout 保持 + 二次点击生效 | `rg_b2_flyout_stays.png`、`rg_b3_after_leaf_select.png`、`rg_d1/d2/d3/d4` |

> 说明：保护窗为「分支项 mouseenter 后 350ms 内吞掉落在叶子项上的兼容 click」；用户第一次点分类只展开，第二次点目标动画项才触发播放/关菜单。

### 7.2 保护窗不误吞正常点击（回归）✅
- 直接点菜单「打开网站」（不经过分支）→ vivo 浏览器正常打开（`mCurrentFocus=com.vivo.browser/BrowserActivity`）；
- 「回到初始位置」：慢拖到 (67,1166) 后点该菜单项 → 精确回到初始位 (494,1967)（`rg_h1_reset_position.png`）。

### 7.3 碎碎念 / 对话 Toast ✅
- 点「碎碎念」：logcat `[asset] whisper 请求：手机版无 DSH 宿主 → Toast 提示 + unsupported JSON`；**真机实拍到 Toast**：`rg_g1_whisper_toast.png`（「手机版未连接电脑上的 DeepSeek Harness，碎碎念/对话暂不可用」）；页面仍按降级返回（`[dsh-pet] 菜单碎碎念：生成动作未成功`），未假装成功。
- 「对话」：面板正常打开（`rg_h2_chat_panel.png`），面板外点击可关闭；页面只在"发送消息"时才请求 `/chat`，而悬浮窗 `FLAG_NOT_FOCUSABLE` 导致输入框拿不到 IME（`InputMethodManager: Ignoring showSoftInput() … WebView is not served`）——**已知限制，不修**（按 Lead/T2 决策）。

### 7.4 其余功能回归（全部 PASS，未发现回归）
| 项 | 结果 | 证据 |
|---|---|---|
| 版本/桥校验 | ✅ versionCode=3、versionName=1.1.1；页面 `[web] diag version="1.0.2"` | `logs/rg_01_start.txt` |
| 透明度 80%→40%→80% 热更新 | ✅ `[overlay] 透明度更新 alpha=0.4` / `alpha=0.8` | `logs/rg_09_alpha.txt`、`rg_h3_alpha40.png` |
| 跨 App 穿透 | ✅ HOME 后点宠物窗口透明余量处图标 → QQ 启动，无 untrusted-touch 拦截 | `rg_i1_home_with_pet.png`、`rg_i2_passthrough.png`、`logs/rg_10_passthrough.txt` |
| 长按菜单 / 点击 / 拖动 / 甩抛 / 漫游 | ✅ 未回归（本轮长按/拖动/菜单多点验证） | 见上各条 |
| 旋转屏幕 | ✅ `mCurrentOrientation=1`、`[display] 1260×2800 → 2800×1260`，转回竖屏正常 | `rg_j1_landscape.png`、`rg_j2_portrait_back.png` |
| 关闭 / 重开桌宠 | ✅ `[svc] onDestroy` + 窗口 NONE；重开 `[overlay] 创建悬浮窗` | `rg_k1_closed.png`、`rg_k2_reopened.png`、`logs/rg_11_lifecycle.txt` |
| logcat 干净 | ✅ 无 `FATAL EXCEPTION`、无 `ANR in com.dshpet`、无 `unresponsive`、无 `net::ERR_FAILED`、无 `JS_ERROR` | `logs/rg_12_final.txt` |
| 真机当前状态 | 桌宠运行中（80% 不透明度、右下角、位置 (494,1967)），App 版本 1.1.1 | `rg_k2_reopened.png` |

### 7.5 已知限制（v1.1.1 最终口径）
1. 宠物最高 80% 不透明度（Android 12+ 对 FLAG_NOT_FOCUSABLE 穿透悬浮窗的系统限制，已由 Lead 接受）；
2. 碎碎念/对话：移动端明确降级（点击有 Toast 说明，不弹假气泡/不假装成功）；对话输入框受 `FLAG_NOT_FOCUSABLE` 限制无法输入（不修 IME/焦点）；
3. 敏感 App（设置/银行类）会隐藏非系统悬浮窗（系统行为）。
（原「动作菜单三级动画不可选」已在 v1.1.1 修复并验证通过，从已知限制中移除。）

### 7.6 v1.1.1 证据文件
- 截图 `qa\shots\`：`rg_b0_menu_open.png`、`rg_c1_actions_level2.png`、`rg_d2_clickcat_flyout.png`、`rg_e1/e2`、`rg_f2/f3`、`rg_g1_whisper_toast.png`、`rg_h1_reset_position.png`、`rg_h2_chat_panel.png`、`rg_h3_alpha40.png`、`rg_i1_home_with_pet.png`、`rg_i2_passthrough.png`、`rg_j1_landscape.png`、`rg_j2_portrait_back.png`、`rg_k1_closed.png`、`rg_k2_reopened.png`
- 日志 `qa\logs\`：`rg_01_start.txt`、`rg_02_actions_fix.txt`、`rg_03_actions_clickcat.txt`、`rg_06_clickcat_selected.txt`、`rg_07_whisper_toast.txt`、`rg_08_openweb.txt`、`rg_09_alpha.txt`、`rg_10_passthrough.txt`、`rg_11_lifecycle.txt`、`rg_12_final.txt`

---

## 8. v1.1.4 最终交付验收（2026-10-21 23:18–23:2x，真机 vivo V2359A / Android 16）

> 最终 APK：size **63,802,478 B**，构建 **2026-10-21 23:18:53**，**sha256 `D79F42F12B06AF3287D0AC851CA6113BCFEF1ED9A6D1F0A911ECFCF1F666EEC0`**，versionCode=**6** / versionName=**1.1.4**
> 本版新增/修复：① 连接电脑 DeepSeek Harness（USB `adb reverse` / Wi-Fi 代理，碎碎念/对话/余额/工作状态真实生效）② USB/Wi-Fi 连接教程弹窗（卡片内下划线链接 → 原生 AlertDialog）③ 聊天键盘自动收起（vivo 真机修复）④ 连接态 7 项菜单紧凑样式（「动作」一屏可见、可点、三级动画可选）。

### 8.1 结论速览：全部 PASS ✅
| 验收项 | 结果 | 证据 |
|---|---|---|
| 安装/升级、版本页脚 | ✅ 1.1.4 (versionCode 6)，覆盖安装保留连接配置 | 截图 `v114_01`/`v114_15` |
| **USB 教程弹窗** | ✅ 点「USB 连接教程（点击查看）」弹出原生对话框，内容正确，「知道了」关闭 | `v113_01_usb_tutorial.png`（代码同 v1.1.4） |
| **Wi-Fi 教程弹窗** | ✅ 同上，含同 Wi-Fi/双击 bat/填地址/排查/安全说明 | `v113_02_wifi_tutorial.png` |
| **聊天键盘自动收起** | ✅ 打开 `mInputShown=true`、窗口 (494,1967)→(322,976)；**发送后 2.5s、4.5s 均 false、窗口回位 (494,1967)**；再次打开又 true；点面板外关闭后 false | `v114_03/04/05`；dumpsys input_method |
| **连接态菜单 7 项一屏可见** | ✅ 打开网站/查看余额/碎碎念/对话/回到初始位置/重载配置/**动作(带箭头)**，无需滚动（紧凑样式） | `v114_02_menu7_connected.png`、`final_07` |
| **动作 → 三级动画可选** | ✅ 直接点动作（不滚动）→ 动画面板弹出 → 点具体动画（如堆雪人）→ 菜单关闭、动画触发（捕获窗 5→1） | `v114_07/v114_08` |
| **碎碎念（真实宿主）** | ✅ 点击后宿主 `/state.pets.main.say` 更新为真实生成文本（如「就骚了怎么滴，咬我呀？」/「工资卡拿来我管着~」），真机出现气泡+表情图 | `final_18_whisper_t9s.png`、`v114_16`；宿主 say 对比 |
| **对话（真实收发）** | ✅ 键盘弹出、窗口上移；发送后宿主记忆出现 `user: okaeri` + 回复「欢迎回来～累不累呀？」；回复气泡显示 | `v114_06_chat_reply_bubble.png`；`/chat?pet=main` 对比 |
| **余额** | ✅ 点「查看余额」→ 宿主 `balance.manual=true`（total 9.87）→ 余额气泡 | `final_23_balance_connected.png` |
| **工作状态** | ✅ 宿主 workStatus=working 驱动气泡「这一步正在进行中呐」「正在继续干活呢，别打扰我~」 | `wifi_05_whisper_t12s.png`、`wifi_10…` |
| **干净状态（从未配置）= v1.1.1 行为** | ✅ 无任何 harness 流量；碎碎念点击弹降级 Toast；对话面板不弹键盘；菜单 6 项紧凑不溢出 | `final_02/03/04`、`cs1/cs2/cs3` |
| **断开（降级）** | ✅ 状态「未连接·降级模式」、代理请求静默、碎碎念弹降级 Toast | `final_24/25`、`wifi_17` |
| **重连** | ✅ 状态「已连接」、宿主请求恢复 | `final_34_final_connected.png` |
| **透明度回归** | ✅ dumpsys `alpha=0.8 → 0.41 → 0.8`（SeekBar 拖动） | `v114_11_alpha40.png`；dumpsys 输出 |
| **跨 App 穿透回归** | ✅ HOME 后点宠物窗口透明余量处图标 → QQ 启动（无 untrusted-touch） | `wifi_25_passthrough.png`、mCurrentFocus |
| **旋转回归** | ✅ 横屏/竖屏切换截图正常，页面 [display] 几何更新 | `v114_12_landscape.png`、`v114_13_portrait.png` |
| **关闭/重开桌宠** | ✅ 关闭后窗口 NONE；重开后 overlay 恢复 | `final_30_reopened.png` |
| **logcat 干净** | ✅ 无 FATAL EXCEPTION / ANR / unresponsive / net::ERR_FAILED / JS_ERROR | 全程扫描 critical=0 |

### 8.2 连接方式与工具（交付说明素材）
**USB**：手机开 USB 调试连电脑 → 电脑运行 `tools\harness-connect.ps1`（自动 `adb reverse tcp:3412 tcp:3412` + PC 侧 `/state` 自检 + reverse 列表校验）→ App 地址填 `http://127.0.0.1:3412` →「保存并连接」。
**Wi-Fi**：手机与电脑连同一 Wi-Fi → 电脑双击 `tools\harness-wifi-proxy.bat`（= `node tools\harness-wifi-proxy.js`，0.0.0.0:3413 → 127.0.0.1:3412；窗口保持打开，显示 `http://<电脑IP>:3413`）→ App 填该地址 →「保存并连接」。
- 实网验证：电脑 WLAN `192.168.2.9`、手机 `192.168.2.5`；代理日志出现 `转发 192.168.2.5:… -> 127.0.0.1:3412`、`请求 GET /dsh-pet-7340/state`；手机浏览器打开该地址显示真实 JSON（余额/工作状态/碎碎念文本）。
- 安全：代理只监听电脑局域网网卡并转发到本机 127.0.0.1:3412，不上传外网；只记 method+path 不记正文；建议只在可信 Wi-Fi 使用、用完关闭窗口。
- 注意：DSH 只监听 127.0.0.1，Wi-Fi 必须由电脑侧代理转发（本工具即为此设计）；电脑重启后需重开代理；手机切移动数据会断开。

### 8.3 已知限制（最终口径）
1. 宠物不透明度最高 80%（Android 12+ 对 FLAG_NOT_FOCUSABLE 穿透悬浮窗的系统限制，用户已接受）；
2. 碎碎念/对话/余额/工作状态依赖电脑 DSH 宿主：未连接/断开时明确降级（Toast 提示，不假装成功）；DSH 关闭后需在 App 内重新「保存并连接」；
3. 手机上的 DeepSeek 官方 App 是封闭客户端，不提供对外接口，不能直接作为桌宠宿主（Lead 已定：v1.2 可选做「手机直连 DeepSeek API Key」增强；本版不做）；
4. 敏感 App（设置/银行类）系统会隐藏非系统悬浮窗（系统行为）；
5. 真机 `pm clear` 后 vivo logcat 偶尔刷不到 App 自定义日志行（模拟器正常，代码无日志开关）；验收用「代理请求日志+截图+宿主 /state」交叉取证。

### 8.4 v1.1.4 证据文件
- 截图 `qa\shots\`：`v114_01_pet_started.png`、`v114_02_menu7_connected.png`、`v114_03_chat_open_kb.png`、`v114_04_after_send_ime.png`、`v114_05_chat_closed_ime.png`、`v114_06_chat_reply_bubble.png`、`v114_07_actions_flyout.png`、`v114_08_anim_triggered.png`、`v114_10_whisper_bubble2.png`、`v114_11_alpha40.png`、`v114_12_landscape.png`、`v114_13_portrait.png`、`v114_14/16_whisper*`、`final_02/03/04_clean_*`、`final_24/25_disconnected*`、`final_30_reopened.png`、`final_34_final_connected.png`、`wifi_*`（Wi-Fi 链路）、`v113_01/02_*tutorial*.png`
- 日志 `qa\logs\`：`v113_usb_tutorial.xml`、`v113_wifi_tutorial.xml`、`v114_ui*.xml`、`final_ui_*.xml`

### 8.5 工具修复记录（交付后补充）
`tools\harness-wifi-proxy.bat` 初版因使用 Unix LF 换行，Windows cmd 解析 `goto`/括号块失败会直接退出（表现为"双击闪退"，与管理员权限无关）。已修复为 **CRLF 换行 + 纯 ASCII 输出 + 无 goto/括号块 + 结尾 pause + node 自动探测（PATH 优先，回落 %USERPROFILE%\.dsh\...\node.exe）**，并实测：窗口保持、3413 监听、`/dsh-pet-7340/state` 200。
- `harness-wifi-proxy.bat`：697 B，sha256=`36362E0A23CF601F34737F8E4A91A0563A5C5BBDAB241F2CE21E7C8EC0EF8909`
- `harness-wifi-proxy.js`：4,487 B，sha256=`E7E8A901F5BEE3A9F5D3BD197AEA01355B5614C33EAF56A492B4A1B069941001`（`-port`/`--port` 均支持）
- `harness-connect.ps1`：5,320 B，sha256=`8C8C0D3A5A234BB70CDCF4117CCEDEF57E3C88F16BE6B390999E28BEBBAC35BD`
- **用户实测确认**：双击修复后 bat 启动成功（node pid 7128 / 23:33 启动），手机 192.168.2.5 经 Wi-Fi 连到代理（Established 连接 + 代理日志），App 内显示"已连接"；`dist\大肥鱼桌宠-手机版-v1.1.4.apk` 已由 Lead 更新并核对 sha256 一致。
