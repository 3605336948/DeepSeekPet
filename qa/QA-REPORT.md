# dsh-pet 手机版 · 最终 APK QA 报告（T3 / probe-qa）

> 被测包：`D:\AndroidProjects\DeepSeekPet\android\app\build\outputs\apk\release\app-release.apk`
> 版本：`com.dshpet.mobile` versionCode 1 / versionName 1.0.0，**60.82 MB（63,772,578 B），构建时间 2026-10-21 21:14:11**（v4：修 AFD 泄漏 + 触摸异步转发 + B 方案捕获窗 + mobile-bridge 1.0.1）
> 环境：模拟器 Android 15（API 35）/ 1080×2400 @420dpi（dpr=2.625）/ WebView 124.0.6367.219 / headless SwiftShader
> **真机复核（vivo V2359A / Android 16 / 1260×2800 @560dpi dpr=3.5 / WebView 138）：全部 PASS，见 §8。**
> 结论：**QA 通过（PASS）**，可作为交付候选包；模拟器 + 真机双环境验证完成。探针阶段技术结论见同目录 `PROBE-REPORT.md`。

---

## 0. 结论摘要

| # | 验收项（SPEC §3.3 / §4.2） | 结果 | 证据 |
|---|---|---|---|
| 1 | `adb install -r` 覆盖安装（同签名、保留数据） | ✅ PASS | 安装 Success；v3→v4 覆盖安装无卸载 |
| 2 | 悬浮窗授权 + 通知权限 + 前台服务启动 | ✅ PASS | `Background started FGS: Allowed`；服务 `isForeground=true` |
| 3 | 宠物透明悬浮在桌面（**无黑底**） | ✅ PASS | `shots/qa4_01_pet_started.png`、`shots/qa4_18_home_with_pet.png` |
| 4 | 页面正确加载（非白屏/非源码文本/无 JS 错误） | ✅ PASS | `[web] diag`：`version:"1.0.1" innerWidth:261 innerHeight:210 dpr:2.625 hasBridge:true sprites:1 videos:2 configOk:true` |
| 5 | 动画在动 | ✅ PASS | 两帧差 `changed_px=9761`，变化 bbox `[787,1933,906,2077]` 正好是宠物身体区；`shots/qa4_02/03_anim_frame*.png` |
| 6 | 点击宠物有回应 | ✅ PASS | `[web:LOG] [dsh-pet] grab …`；`shots/qa4_08_click_response.png` |
| 7 | 单指拖动跟手 | ✅ PASS | 600ms 上拖 400px：窗口 y `1712 → 1701 → 1218`（逐帧跟手）；`shots/qa4_05/07_after_drag*.png` |
| 8 | 快速甩出 → 抛掷物理 | ✅ PASS | `[dsh-pet] release vx=0 vy=-950 |v|=950`；落地后窗口 y=1974（重力+回弹） |
| 9 | 长按约 0.5s 弹出菜单 | ✅ PASS | 菜单可见且**松手后保持**：`shots/qa4_11_menu_during_hold.png`、`qa4_12_menu_after_release.png`（打开网站/碎碎念/对话/回到初始位置/重载配置/动作） |
| 10 | 菜单项可点（含子菜单） | ✅ PASS | 点「动作」→ 捕获窗 4→5 个（子菜单面板），`shots/qa4_13_submenu.png` |
| 11 | 区域外点击穿透到**其它 App** | ✅ PASS | 点宠物窗口透明余量 → **Chrome 启动**（`mCurrentFocus=com.android.chrome/...FirstRunActivity`，`START u0 … com.android.chrome`）；`shots/qa4_19_passthrough.png`；无 untrusted-touch 拦截日志 |
| 12 | 旋转屏幕宠物不丢 | ✅ PASS | 横屏 `[display] 变化 1080x2400→2400x1080`，宠物+气泡正常；转回竖屏位置正确；`shots/qa4_16_landscape.png`、`qa4_17_portrait_after_rotate.png` |
| 13 | 关闭服务悬浮窗消失 | ✅ PASS | `[svc] onDestroy`；dumpsys 中 `DSHPetOverlay/DSHPetTouch` 窗口为 **NONE**；`shots/qa4_14_service_closed.png` |
| 14 | 重新开启服务恢复悬浮窗 | ✅ PASS | 窗口重新创建，frame=(504,1712) 683×549；`shots/qa4_15_service_reopened.png` |
| 15 | logcat 无 FATAL / JS console error / 资源加载错误 | ✅ PASS（干净轮次） | 见 §3；干净 QA 轮次无 `FATAL EXCEPTION`、无 `net::ERR_FAILED`、无 `JS_ERROR`、无 ANR |
| 16 | Q5 screenX 物理化补丁在真页面生效 | ✅ PASS | `[web:WARNING] [petport] pointer screenX/screenY 已改为物理像素（T3 实测：原生为 CSS 像素屏幕坐标，×dpr 物理化）`；拖动方向/距离与手指一致 |
| 17 | Q4 触摸区域方案（B：捕获窗） | ✅ PASS | `[touchcap] DOWN 捕获 Rect(277,213-406,366) local=64,76` → `[touch] action=0 at 341,289 raw=845,2001` → 页面 pointer 事件；拖拽时整窗租约、菜单时补整窗 |

---

## 1. 测试环境与方法

- 安装/授权：
  ```powershell
  adb install -r D:\AndroidProjects\DeepSeekPet\android\app\build\outputs\apk\release\app-release.apk
  adb shell appops set com.dshpet.mobile SYSTEM_ALERT_WINDOW allow
  adb shell pm grant com.dshpet.mobile android.permission.POST_NOTIFICATIONS
  adb shell am start -n com.dshpet.mobile/.MainActivity   # 点「开启桌宠」
  ```
- 截图：`adb shell screencap -p /sdcard/x.png` + `adb pull`；日志：`adb logcat -d -v time`，过滤 `DSHPet`。
- 模拟器为 **SwiftShader 软件渲染**，帧率/合成性能低于真机；QA 以"功能正确"为准，性能需真机复核。

---

## 2. 关键功能实测细节

### 2.1 渲染 / 透明 / 动画
- 启动后 `[web] diag`（`logs/qa4_01_start.txt`）：
  ```
  "info":{"version":"1.0.1","innerWidth":261,"innerHeight":210,"dpr":2.625,"scale":2.625,
          "hitRect":{...},"bodySource":"pet-hit","spriteCount":1,"configOk":true,"errors":[],
          "android":true,"windowOrigin":{"x":504,"y":1711.5,"cssX":192,"cssY":652}}
  "hasBridge":true,"hasAndroidPet":true,"videos":2,"sprites":1,"configError":""
  ```
  → 页面/CSS 尺寸（窗口 683×549 物理 / dpr2.625 → 260×209 CSS）、bridge、config、sprite 全部正常。
- 宠物区域透出下层（Activity 深色 UI / 桌面壁纸），**无黑底**；按 Lead 决策 B 方案，渲染窗被系统强制 `alpha=0.8`（dumpsys `mShownAlpha=0.8`），宠物呈 80% 不透明（预期，见 §4）。
- 动画：`imgdiff.py diff frame1 frame2` → `changed_px=9761`，bbox 与 hitRect 屏幕位置吻合 → 动画确实在播放。

### 2.2 交互
- 点击：`[touchcap] DOWN 捕获` → 转发 → `[dsh-pet] grab` → 点击回应动画；`setInputBusy` 状态正确上报。
- 拖动/甩：`[dsh-pet] release vx=0 vy=-950 |v|=950`（600ms 上拖 400px），窗口逐帧跟手（1712→1701→1218），松手后按重力/回弹落在 y=1974。
- 长按菜单：桥接层合成 contextmenu → 菜单面板；菜单窗口区域被捕获（`[touchcap] 捕获窗同步: 3 个 … [171,47][566,538]`），菜单**松手后保持**，可点「动作」展开子菜单（捕获窗 4→5 个）。菜单项：打开网站 / 碎碎念 / 对话 / 回到初始位置 / 重载配置 / 动作（手机端按 SPEC 降级，部分入口不可用属预期）。
- 拖拽租约：页面 `dragging` 时上报整窗 rect → `[touchcap] 拖拽租约：整窗可点（rects=2）`，保证手指移出小窗仍不丢手势。

### 2.3 穿透（B 方案，targetSdk 35）
- 渲染窗 `FLAG_NOT_TOUCHABLE`；触摸只由宠物身体/菜单等捕获窗接收；捕获窗外点击直达下层 App。
- 实测：`HOME` 后点宠物窗口透明余量处的 Chrome 图标 → `START u0 … com.android.chrome` 成功启动，logcat **无** `Untrusted touch … Dropping`。
- 对照（探针留档）：A 方案（单窗 + 反射 region）在同场景会被 Android 12+ untrusted-touch 丢弃触摸（详见 `PROBE-REPORT.md` §5.3）。→ **B 方案是正确选择**。

### 2.4 旋转 / 生命周期
- 横屏：`[display] 变化: Geometry{1080x2400 …} → Geometry{2400x1080 dpr=2.625 safe=[0,63,2400,1017]}`，overlay/捕获窗 `finishDrawing of orientation change`，宠物与气泡正常显示；转回竖屏正常。
- 关闭：`[svc] onDestroy`，所有 DSHPet 窗口移除（dumpsys NONE）；重开：窗口重建、宠物回到右下角（frame 504,1712）。

---

## 3. logcat 扫描结果

干净 QA 轮次（13:22:50 之后，探针 App 已停止）：
- `FATAL EXCEPTION`：0
- `JS_ERROR` / `主文档加载错误` / `net::ERR_FAILED`：0
- `ANR in com.dshpet.mobile` / `unresponsive`：0
- `[touchcap] dispatch 慢`：0（异步转发单次派发 <60ms）
- 仅有正常 404：`[web] http 404 https://pet.local/favicon.ico`（无影响，可选映射图标）

**观察项（非阻塞，曾出现 1 次）**：13:21:54 在一次"污染环境"极限测试中出现过 ANR——当时同时运行着**探针 App 的另一个 WebView（2 路视频）**且用 `input swipe` 在宠物贴屏幕边缘时注入了越界坐标（x=1142>1080）。该场景不代表正常使用；干净轮次的拖拽/甩抛/长按/菜单/旋转/穿透均未复现 ANR。建议 T2 正式自测时继续关注 `[touchcap] dispatch 慢` 日志。

---

## 4. 已知行为 / 限制（按重要度）

1. **宠物 80% 不透明（B 方案固有代价，Lead 已确认接受）**：Android 对 `FLAG_NOT_TOUCHABLE` 悬浮窗强制 `alpha≤0.80` 以允许触摸穿透（logcat `setting alpha to 0.80 to let touches pass through`；dumpsys `mShownAlpha=0.8`）。换来的是跨 App 点击穿透（桌面/其它 App 均可正常点）。A 方案可 100% 不透明但跨 App 穿透被系统拦死。
2. **敏感 App 会隐藏悬浮窗**：Settings/银行类 App 声明了隐藏非系统悬浮窗（`mForceHideNonSystemOverlayWindow=true`），进入时桌宠暂时不可见——系统行为，非 bug。
3. **捕获窗 churn（Lead 问询项）**：捕获窗数量随"可交互区域"变化：空闲 1 个；点击 1→2→1；拖拽 1→2→3→2→1；长按菜单 3；子菜单最多 5（上限 `MAX_CAPTURE_WINDOWS=6`）。每次变化是 `addView/removeView`（logcat 可见 Input channel 创建/销毁与 "disposed without first being removed" 警告，属无害 cosmetic），**不是逐帧**（`setBounds` 逐帧只 `updateViewLayout` 移动现有窗）。**结论：可接受，不阻塞交付**；若 T2 想进一步降抖动，可做窗口复用池（按 rect 复用、仅位置变化用 updateViewLayout）+ 对相同 rect 去重/合并。
4. **动画素材**：v4 起 `net::ERR_FAILED` 绝迹（AFD 泄漏已修），106 个 webm/字体/PNG 正常加载。
5. **模拟器性能**：SwiftShader 下拖拽/菜单有可感知延迟；真机（GPU 合成 + VP9 硬/软解）预计流畅，建议真机复核。

---

## 5. Q4 架构决策留档（A/B 实测对比）

| 方案 | targetSdk 35 | 跨 App 穿透 | 宠物不透明度 | 结论 |
|---|---|---|---|---|
| A：单窗 + 反射 `TOUCHABLE_INSETS_REGION` | ❌ 字段 hidden API `max-target-r/denied`（VMRuntime 豁免 `blocked/core-platform-api` 也失败） | ❌ Android 12+ untrusted-touch 丢弃触摸 | 100% | 仅 targetSdk 30 + 接受穿透失效时可用（Lead 批准为兜底，最终未采用） |
| **B：渲染窗 `FLAG_NOT_TOUCHABLE` + 透明捕获窗转发 MotionEvent** | ✅ | ✅（Chrome 启动实测） | 80%（系统强制） | **采用** |

> A 方案的探针实测细节（region 生效、同 App 穿透 OK、跨 App 被 `Dropping untrusted touch event` 拦截）见 `PROBE-REPORT.md` §5.2–5.4；探针工程 `D:\AndroidProjects\PetProbe`。

---

## 6. 证据文件

- 截图（`qa\shots\`）：`qa4_01_pet_started.png`、`qa4_02/03_anim_frame*.png`、`qa4_04_after_click.png`、`qa4_05_after_drag.png`、`qa4_07_after_fast_drag.png`、`qa4_08_click_response.png`、`qa4_11_menu_during_hold.png`、`qa4_12_menu_after_release.png`、`qa4_13_submenu.png`、`qa4_14_service_closed.png`、`qa4_15_service_reopened.png`、`qa4_16_landscape.png`、`qa4_17_portrait_after_rotate.png`、`qa4_18_home_with_pet.png`、`qa4_19_passthrough.png`、`qa4_20_final_clean_home.png`
- 日志（`qa\logs\qa4_*.txt`）：`qa4_01_start.txt`（启动/诊断）、`qa4_02_click.txt`、`qa4_08_clean_fastdrag.txt`（拖拽+甩抛）、`qa4_11_menu.txt`/`qa4_12_menu_hold.txt`（长按菜单）、`qa4_13_submenu.txt`、`qa4_14_close.txt`、`qa4_15/16_rotate_*.txt`、`qa4_17_passthrough.txt`（跨 App 穿透）、`qa4_18_final.txt`（终局扫描）
- 工具：`qa\run-qa.ps1`（安装/启动/截图/日志命令封装）、`qa\imgdiff.py`（帧差/透明度像素统计）

---

## 7. 建议（交付前可选）

1. **真机验证**（必须）：用户手机 ≥ Android 15 上复核——透明无黑底、动画流畅度、拖拽/甩抛手感、长按菜单、跨 App 穿透、锁屏/切后台后悬浮窗存活（前台服务 + 通知）。
2. 可选优化（不阻塞）：捕获窗复用池（§4.3）；`favicon.ico` 映射一个图标或返回 204；考虑在 MainActivity 顶部显示"宠物透明度 80% 为系统穿透限制"的说明文案（避免用户疑惑）。
3. 交付物：APK 已由 Lead 复制到 `dist\`；安装说明沿用 MainActivity 内文案即可。

---

## 8. 真机验证（vivo V2359A / Android 16）—— **PASS**

> 设备：vivo **V2359A**（PD2309M），Android **16**（SDK 36），1260×2800 @560dpi（**dpr=3.5**），WebView **138.0.7204.179**，user build，MTK 平台。
> 方法：USB ADB，`install -r` 覆盖安装 → `appops SYSTEM_ALERT_WINDOW allow` + 通知授权 → MainActivity「开启桌宠」。
> 结论：**真机全项通过；模拟器上最后一个关键风险（真机 GPU/MediaCodec 解码路径下 VP9-alpha 是否仍透明）已排除。**

| # | 真机验收项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 安装 / 悬浮窗+通知授权 / 前台服务 | ✅ `install -r Success`，Activity 225ms 显示，FGS 正常运行 | `logs/real_01_start.txt` |
| 2 | **VP9-alpha 真机透明播放、无黑底** | ✅ 宠物透明悬浮在桌面壁纸/App 上，无黑块（真机 MediaCodec 解码路径保留 alpha） | `shots/real_02_pet_started.png`、`real_17_final_home_with_pet.png` |
| 3 | 页面/配置正确（真机 dpr=3.5 缩放） | ✅ `[web] diag`：version=1.0.1、inner=260×209、dpr=3.5、sprites=1、videos=2、configOk、windowOrigin 正确 | `logs/real_01_start.txt` |
| 4 | screenX 物理化补丁（真机 dpr=3.5） | ✅ `screenXFix:true, reason:"T3 实测：原生为 CSS 像素屏幕坐标，×dpr 物理化"`；拖动方向/距离与手指一致 | `logs/real_01_start.txt` |
| 5 | 拖动 + 甩抛物理 | ✅ `release vx=-819 vy=314 |v|=877`，窗口跟手移动/抛掷 | `logs/real_02_touch.txt` |
| 6 | 点击回应 | ✅ `[dsh-pet] grab` + 点击响应 | `shots/real_05_click.png` |
| 7 | 长按菜单（松手保持） | ✅ 菜单完整显示（打开网站/碎碎念/对话/回到初始位置/重载配置/动作），真机透明合成正确 | `shots/real_06_longpress_menu.png` |
| 8 | 菜单项可点 / 子菜单 | ✅ 捕获窗 3→4 个，子菜单面板展开 | `shots/real_16_menu_item_click.png`、`logs/real_10_menuclick.txt` |
| 9 | **跨 App 真实穿透** | ✅ 点宠物窗口透明余量处的「红果免费短剧」→ `com.phoenix.read` 启动，无 untrusted-touch 拦截 | `shots/real_10_passthrough.png`、`logs/real_04_passthrough.txt` |
| 10 | 旋转屏幕宠物不丢 | ✅ `mCurrentOrientation=1`、`[display] 1260×2800 → 2800×1260`，横屏宠物+气泡正常；转回竖屏正常 | `shots/real_11_landscape.png`、`real_12_portrait_back.png` |
| 11 | 关闭服务窗口消失 / 重开恢复 | ✅ `[svc] onDestroy`，dumpsys 窗口 NONE；重开后悬浮窗重建 | `shots/real_13_service_closed.png`、`real_14_reopened.png`、`logs/real_07_close.txt` |
| 12 | logcat 无 FATAL / ANR / JS error / 资源错误 | ✅ 全部真机日志无 `FATAL EXCEPTION`、无 `ANR in com.dshpet`、无 `net::ERR_FAILED`、无 `JS_ERROR`、无 `[touchcap] dispatch 慢`、无 MediaCodec 错误 | `logs/real_*.txt` |
| 13 | 资源占用 | ✅ TOTAL PSS ≈ **144 MB**（含 WebView + 106 个 webm 素材索引），真机运行流畅 | `dumpsys meminfo` |

**真机备注**：
- 80% 不透明度同模拟器（系统对 `FLAG_NOT_TOUCHABLE` 悬浮窗强制），真机视觉正常、不显突兀。
- vivo 性能框架有 `avc denied ioctl /proc/fas/render` 等日志（vivo perf 服务尝试读取，无害）。
- 真机上存在用户其它第三方悬浮窗（橙色图标），与本 App 无关、不冲突。
- 宠物最终留置于用户桌面运行中（`real_17_final_home_with_pet.png`）。
