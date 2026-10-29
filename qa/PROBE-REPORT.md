# dsh-pet 手机版 · T3 技术探针报告（PetProbe）

> 作者：T3 probe-qa　｜　工程：`D:\AndroidProjects\PetProbe`（包名 `com.petprobe`，纯 Java 最小验证工程）
> 日期：2026-10-21　｜　对应 SPEC：`D:\AndroidProjects\DeepSeekPet\SPEC.md` §4.1 五个高风险问题
> 所有结论均为**模拟器实测**（截图 + logcat + dumpsys 证据），原始日志在 `qa\logs\`，截图在 `qa\shots\`。

---

## 0. 结论速查表

| # | 问题 | 结论 | 风险 |
|---|------|------|------|
| Q1 | VP9-alpha webm 在 WebView 能否透明播放 | ✅ **可以，无黑底**（file:// 与 shouldInterceptRequest 两路均实测通过） | 无（素材无需转码） |
| Q2 | 前台服务 + application context 建 WebView + 透明 overlay | ✅ **可行**（不崩、可见、透明；不需要 createWindowContext 兜底） | 低 |
| Q3 | overlay 负坐标/出屏是否被 clamp | ⚠️ **带 `FLAG_LAYOUT_NO_LIMITS` 不 clamp；去掉会被 clamp**；另发现默认 `fitInsetsTypes` 会让窗口坐标相对状态栏偏移 +63，**必须 `lp.setFitInsetsTypes(0)`** | 中（配置错误会导致宠物位置/贴边异常） |
| Q4 | `OnComputeInternalInsetsListener` TOUCHABLE_INSETS_REGION 是否生效 | ⚠️ **API 不在公开 SDK**；targetSdk 35 下反射必失败（hidden API `max-target-r/denied`），targetSdk 30 下反射成功且 region 生效；**但 A 方案跨 App 穿透被 Android 12+ untrusted-touch 拦截**。✅ **B 方案（渲染窗 FLAG_NOT_TOUCHABLE + 触摸捕获窗转发）在 targetSdk 35 下全部实测通过（含跨 App 穿透）→ 推荐 B** | 高（选型决定，见 §5） |
| Q5 | PointerEvent clientX/screenX 语义 | ❗ **`screenX/screenY = 物理屏幕坐标 / dpr`（CSS 屏幕像素），不是物理像素**；`window.screenX/Y` 恒 0。dsh-pet 期望物理像素 → **bridge 需 ×dpr 补丁**（公式见 §6） | 高（不补丁则拖拽速度差 dpr²，甩抛/拖拽必坏） |
| 附 | 自动播放 / dpr / viewport | `setMediaPlaybackRequiresUserGesture(false)` 下 muted+autoplay 正常；**`setUseWideViewPort(true)` 必须开**，否则 viewport meta 失效（实测 dpr=2.625，800px 窗口 innerWidth=305 CSS、screen.width=412） | — |
| 附 | 跨 App 点击穿透（Android 12+ untrusted touch） | A 方案被系统拦截并丢弃触摸（`Dropping untrusted touch event`）；**B 方案可以通过**（实测点桌面 Play Store 图标成功启动 vending） | 高（直接决定体验） |

---

## 1. 环境（实测确认）

| 项 | 值 |
|---|---|
| 模拟器 | `emulator-5554`，AVD `DSH_API35`，headless，`-gpu swiftshader_indirect` |
| 系统 | Android 15（API 35），1080×2400 @420dpi → **dpr=2.625**；状态栏 63px、导航栏 63px、安全区 [0,63]-[1080,2337] |
| WebView | `com.google.android.webview 124.0.6367.219` |
| 构建 | AGP 8.3.0 + Gradle 8.4 + JDK17（`C:\Program Files (x86)\Android\openjdk\jdk-17.0.14`），`--offline` 可构建 |
| 素材 | `dsh-pet v0.3.7 assets/webm/待机呼吸休闲.webm`（VP9-alpha，441,437 B，640×360） |

> 注：模拟器为软件 GPU（SwiftShader）+ 平台软件 VP9 解码；真机 GPU 路径理论上一致（Chromium 合成），但真机仍需 QA 复核。

---

## 2. Q1 — VP9-alpha 透明播放 ✅ PASS

**做法**：Activity 根布局放 40px 品红/青棋盘格 View，上面叠一个透明 WebView（800×700px，位置 (40,250)）；页面 `html,body{background:transparent}`，同时播放两个 `待机呼吸休闲.webm`：
- 视频 A：相对路径 → `file:///android_asset/webm/idle.webm`（当页面以 file:// 加载时）
- 视频 B：绝对 `https://probe.local/webm/idle.webm` → 由 `shouldInterceptRequest` 从 assets 返回（支持 Range 206）
另有 PNG-alpha 对照（`meme.png`，file/https 两路）。

**证据**：
- 截图 `shots/q1_inline_alpha.png`：两个视频区域均透出棋盘格，宠物之外**无黑底**。
- 像素统计（Pillow，视频区域 341×192 物理像素内）：
  - `videoA(file)`: black=6 (0.0%), magenta=37.2%, cyan=36.7%
  - `videoB(https)`: black=1 (0.0%), magenta=36.8%, cyan=37.4%
  - 四角采样均为棋盘格纯色；对照区（窗口外）magenta/cyan 各 50%。
- 播放状态（`logs/q1_inline_logcat.txt`）：
  - `ALPHA_EVT vidA playing {paused=false,rs=4,t=0.02,size=640x360,err=null}`
  - `ALPHA_EVT vidB playing {... size=640x360 ...}`（https 拦截路径）；currentTime 持续递增至数秒。
  - `ALPHA_CANPLAY vp9=probably vp8=probably vp09.00.10.08=probably`
- 拦截证据：`PROBE_REQ #2 https://probe.local/webm/idle.webm range=bytes=0-` → `PROBE_RESP webm/idle.webm mime=video/webm code=206 body=441437/441437`。

**结论**：**用户素材可直接沿用，不需要转码/换播放层**（SPEC §5 的 ffmpeg 备选方案不需要）。

---

## 3. Q2 — 前台服务 + application context WebView + 透明 overlay ✅ PASS

**做法**：`ProbeOverlayService`（`foregroundServiceType="specialUse"`）在 `onCreate` 里 `startForeground`；用 **`getApplicationContext()`** 创建 `WebView` 和 `WindowManager`，`addView` 一个 `TYPE_APPLICATION_OVERLAY` 窗口：
`PixelFormat.TRANSLUCENT`，flags=`NOT_FOCUSABLE|LAYOUT_NO_LIMITS|HARDWARE_ACCELERATED`，gravity=TOP|LEFT，800×700 @ (100,300)，页面为透明 alpha 页（2 个视频）。

**证据**：
- `PROBE_WEBVIEW_OK ctx=applicationContext wm=android.view.WindowManagerImpl`、`PROBE_WINDOW_ADDED`，**无崩溃/无白屏**。
- 截图 `shots/q2_overlay_alpha_https.png`：overlay 窗口叠在 Activity 之上，**透明区露出下层棋盘格/视频，无黑底**；两个视频在服务 WebView 中也正常播放（日志 `ALPHA_EVT ... playing`，同进程）。
- 前台通知正常（`isForeground=true`，通知渠道 probe），`am start-foreground-service` 从 shell 启动成功。

**结论**：主路线可行，**不需要** `createWindowContext()` 兜底（仍建议保留 try/catch 兜底代码）。

---

## 4. Q3 — overlay 窗口位置语义（负坐标/出屏 clamp）

**做法**：start 后记录 `webView.getLocationOnScreen()`（窗口根 View）与 `dumpsys window` 的 frame；分别测试：
(a) `FLAG_LAYOUT_NO_LIMITS` + `updateViewLayout(-150,1800)`；
(b) 去掉该 flag 后同位置；
(c) `lp.setFitInsetsTypes(0)` 前后，同一 lp 坐标的屏幕位置。

**实测数据**（`logs/q3_full.txt`）：

| 场景 | requested lp (x,y) | 实际 frame / locOnScreen | 结论 |
|---|---|---|---|
| 默认 fitInsets + NO_LIMITS | (100,300) | (100,**363**) | 默认 fitTypes 使窗口坐标相对**状态栏下方**，y +63 |
| 默认 fitInsets + NO_LIMITS | (-150,1800) | (**-150**, 1863) | **不 clamp，可出屏**（负 x 生效） |
| **去掉 NO_LIMITS** | (-150,1800) | (**0**, 1637) | 被 clamp：x→0，y→2337-700=1637（限制在安全区内） |
| **setFitInsetsTypes(0)** | (100,300) | (100,**300**) | 精确对齐 |
| setFitInsetsTypes(0) | (0,0) / (100,1050) | (0,0) / (100,1050) | 精确对齐，**lp 坐标 == 屏幕坐标** |

- 截图：`shots/q3_move_neg_nolimits.png`（窗口部分移出左/下边界仍显示）、`shots/q3_move_neg_flagoff.png`（被拉回屏幕内）。
- dumpsys 证据：`mAttrs={(-150,1800)(800x700) ... fl=NOT_FOCUSABLE LAYOUT_NO_LIMITS ...}`、`Frames: frame=[100,300][900,1000]`。

**给 T2 的硬性要求**：
1. **必须保留 `FLAG_LAYOUT_NO_LIMITS`**，否则负坐标/出屏被系统 clamp（宠物贴边/甩抛出屏会异常）。
2. **必须 `layoutParams.setFitInsetsTypes(0)`**（API 30+），否则窗口坐标系整体相对状态栏偏移 63px（`setBounds` 的物理像素与屏幕坐标对不上，宠物位置/触摸区域/穿透都会错位）。
3. `getLocationOnScreen()` 是窗口 frame 的屏幕位置，Q5 换算可用它验证。

---

## 5. Q4 — 触摸区域 / 点击穿透（报告最重要的一节）

### 5.1 公开 SDK 事实：这些 API 不在 SDK 里
`android.view.ViewTreeObserver$OnComputeInternalInsetsListener`、`InternalInsetsInfo`、`TOUCHABLE_INSETS_REGION` 均**不在公开 SDK**（`android-35/android.jar` 中不存在；直接引用编译失败，`javap` 确认）。必须反射。

### 5.2 A 方案（单窗口 + 反射 TOUCHABLE_INSETS_REGION）：**不可用于 targetSdk 35**

实测 hidden API 策略（logcat `Accessing hidden ...`）：

| 成员 | targetSdk=35 | targetSdk=30 |
|---|---|---|
| `ViewTreeObserver.addOnComputeInternalInsetsListener(...)` | unsupported, reflection, **allowed** | allowed |
| `removeOnComputeInternalInsetsListener(...)` | allowed | allowed |
| `InternalInsetsInfo.setTouchableInsets(I)V` | allowed | allowed |
| `InternalInsetsInfo.touchableRegion` 字段 | **max-target-r, reflection, denied**（NoSuchFieldException） | **allowed** |
| `VMRuntime.setHiddenApiExemptions` | **blocked, core-platform-api, denied**（豁免兜底无效） | 同左 |

→ **targetSdk 35 下 A 方案 100% 失败**；targetSdk 30 下反射成功（`PROBE_INSETS_REFLECT_OK constRegion=3`），`TOUCHABLE_INSETS_APPLY` 被框架正常回调。

**A 方案在 targetSdk 30 下的功能实测（region 生效）**：
- 窗口 800×950 @ (100,1050)，region = 窗口内 (200,100)-(500,400) → 屏幕 (300,1150)-(600,1450)。
- 区域内点击 (400,1250)：WebView JS 收到 `TOUCH_EVT pointerdown cx=114.29 cy=76.19`（= (400-100)/2.625 / (1250-1050)/2.625，精确）。
- 区域外点击 (700,1700)、(700,1650)：**穿透到下层 Activity 按钮** `PASS_BUTTON_CLICK count=1/2`。
- 截图 `shots/q4_touch_region_after_taps.png`（红框=窗口、绿框=region、WebView 收到触摸文本、下层按钮 count=2）。

### 5.3 A 方案的硬伤：**跨 App 穿透被 Android 12+ 拦截**
场景：overlay 窗口（A 方案，region 只覆盖宠物身体）在桌面上方，点击窗口内、region 外（应穿透到桌面图标）：

```
W/InputDispatcher: Untrusted touch due to occlusion by com.petprobe/10209
                   (obscuring opacity = 1.00, maximum allowed = 0.80)
D/InputDispatcher: Stack of obscuring windows during untrusted touch (173.0, 1266.0):
D/InputDispatcher:     * [TOUCHED] package=com.google.android.apps.nexuslauncher ... mode=BLOCK_UNTRUSTED
W/InputDispatcher: Dropping untrusted touch event due to com.petprobe/10209
```
→ 触摸被系统丢弃，**桌面/其它 App 收不到点击**（同 App 内 Activity 不受影响）。原因：overlay 窗口 alpha=1.0 且在触摸点仍有"部分可触摸区域"，被判定为遮挡窗口；`maximum allowed = 0.80`。

### 5.4 B 方案（双窗口公共 API）：**targetSdk 35 下全部通过 ✅ 推荐**
结构：
1. **渲染窗**：现有 `TYPE_APPLICATION_OVERLAY` 大窗（宠物 + 气泡 + 菜单整个 WebView），加 **`FLAG_NOT_TOUCHABLE`**（dumpsys 确认 `touchable region=SkRegion()`，输入事件不进入）。
2. **触摸捕获窗**：为每个 input rect（宠物身体/菜单等）各建一个透明小 `TYPE_APPLICATION_OVERLAY` 窗（`NOT_FOCUSABLE`、`TRANSLUCENT`、**`setFitInsetsTypes(0)`**），`onTouchEvent` 里把 `MotionEvent` 复制并 `offsetLocation(rect.left, rect.top)` 后调用 **`webView.dispatchTouchEvent(copy)`** 转发给渲染窗里的 WebView。
3. 捕获窗位置 = 渲染窗屏幕坐标 + rect；随 `setBounds` 逐帧同步 `updateViewLayout`。

实测结果（`logs/q5c_fallback_taps.txt`、`q7_fallback_cross2.txt`）：
- 捕获窗内点击 → `PROBE_CATCHER_TOUCH` → `PROBE_CATCHER_FORWARD handled=true` → WebView JS 收到 `pointerdown cx=133.33 cy=95.24`（坐标正确，仅探针里捕获窗漏了 fitInsets(0) 导致 63px 偏移，T2 必须加）。
- 捕获窗外的渲染窗区域点击 → **穿透成功**：点击桌面 Play Store 图标 → `mCurrentFocus=com.android.vending/...`、`START u0 ... vending`、**无 untrusted-touch 日志**；同一位置 A 方案会被丢弃。
- 手势连续性：DOWN 落到捕获窗后，MOVE/UP 一直发给该窗口（即使手指移出捕获窗），拖拽/甩抛可行；转发事件保留 `rawX/rawY` → screenX 语义与真实触摸一致（见 §6）。
- 截图：`shots/q7_home_with_overlay.png`（渲染窗透明覆盖桌面）、`shots/q7_fallback_crossapp2.png`（穿透点开 Play Store 后）。

### 5.5 选型结论（给 Lead/T2）
- **采用 B（双窗口公共 API）**：保住 targetSdk 35 + 跨 App 穿透体验；A 还需要 targetSdk 降 30 且跨 App 穿透仍被拦，仅作为最后兜底。
- 若坚持 A：只能接受"透明余量区域点击被系统丢弃"（宠物窗口 2×size 范围内点击桌面无效），对桌宠体验是硬伤。
- 退化方案 C（整窗可点）：透明余量会吞掉下层点击（T2 第一版 APK 当前就是此状态，见 §8），不建议交付。

---

## 6. Q5 — PointerEvent clientX/screenX 语义（dsh-pet 拖拽/甩抛依赖）❗

**实测**（overlay 窗口 fitInsets(0) 放 (100,200)，800×700；`input tap 300 400`；日志 `logs/q5_pointer.txt`）：

```json
{"type":"pointerdown","pt":"touch","id":2,
 "cx":76.19047546386719,"cy":76.19047546386719,
 "sx":114.28571319580078,"sy":152.38095092773438,
 "px":76.19,"py":76.19,"dpr":2.625,
 "iw":305,"ih":267,"wsx":0,"wsy":0,
 "vvOL":0,"vvOT":0,"vvScale":1}
```
- `clientX = (300-100)/2.625 = 76.1905`（窗口内 CSS px）✅
- **`screenX = 300/2.625 = 114.2857`**（= 物理屏幕坐标 / dpr）✅
- `screenY = 400/2.625 = 152.381`✅
- `window.screenX/screenY = 0`（WebView 内恒 0，不可用于换算）
- `pageX=pX=clientX`（无滚动）；`visualViewport.offsetLeft/Top=0, scale=1`
- 转发触摸（B 方案）事件同样：`sy=1813/2.625=690.67`（raw 屏幕坐标保留）✅

**与 dsh-pet 期望的差异**：
dsh-pet（sprite.js `onPointerDown/Move/Up`）把 `e.screenX` 当**物理像素**用：`toLocal(e.screenX) = screenX / CONFIG.scale` 得到 CSS 屏幕坐标（Electron 语义）。Android WebView 给的是 **CSS 屏幕坐标 = 物理/dpr**，直接喂给 `toLocal` 会再除一次 dpr → 拖拽增量缩小为 1/dpr²（实测 2.625² ≈ 6.9 倍慢），拖拽/甩抛必坏。

**正确换算（给 T1 mobile-bridge.js / T2）**：
```
screenX_physical = native_screenX * dpr        // 本机实测：114.2857 * 2.625 = 300 ✓
screenY_physical = native_screenY * dpr
// 等价写法（若原生语义异常时兜底）：screenX_physical = windowOriginX + clientX * dpr
```
- 建议 bridge 在页面脚本加载前（mobile-bridge.js 在 constants.js 之前）给 `MouseEvent.prototype.screenX/screenY` 装 getter 覆盖（PointerEvent 继承 MouseEvent，contextmenu 的 MouseEvent 也覆盖），返回 `原生值 * dpr`。
- 检测原生是否已正确：`Math.abs(nativeScreenX*dpr - (getWindowOriginX()+clientX*dpr)) < 2` → 本机为 CSS 屏坐标，需要 ×dpr（原生值明显小）。
- 窗口逐帧移动时 screenX 稳定：指针固定、窗口右移 Δ 时 `clientX` 恰好减少 Δ/dpr，`nativeScreenX = (winX+clientX*dpr)/dpr` 不变 → 增量式拖拽语义正确。

---

## 7. 其它实测结论（T2 直接可用）

1. **WebView 必须 `setUseWideViewPort(true)`**（配合页面 viewport meta）：否则 viewport meta 不生效，页面按 980px 布局（实测贴明文时 `innerWidth=980`）。实测正确值：dpr=2.625、800px 窗口 `innerWidth=305`、`innerHeight=267`、`screen.width=412`、`screen.height=915`。
2. 透明：`webView.setBackgroundColor(Color.TRANSPARENT)` + 页面 html/body 透明 → 真透明（Activity 棋盘格/桌面可见）。
3. 自动播放：`setMediaPlaybackRequiresUserGesture(false)` + `<video muted autoplay playsinline>` 正常起播；dsh-pet 切换动画时的程序化 `play()` 也正常。
4. `shouldInterceptRequest`：WebView 对视频发 `Range: bytes=0-`，返回 **206 + Content-Range** 正常；页面主文档必须 mime `text/html` + **encoding "utf-8"**（详见 §8 的 T2 首装 bug）。
5. `favicon.ico` 会被请求一次（404 无害，但建议返回空 200 或映射个图标更干净）。
6. **敏感 App 会隐藏悬浮窗**：Settings 等设置 `mForceHideNonSystemOverlayWindow=true`，进入设置/银行类 App 时宠物被系统隐藏（属系统行为，非 bug；无需也基本无法绕过）。
7. 尺寸/旋转：安全区 [0,63,1080,2337]（状态栏/导航栏各 63px），`WindowInsets` 可直接取。

---

## 8. 对 T2 首版 APK 的首装验证（2026-10-21 13:05，`com.dshpet.mobile` 60.81MB）

| 检查项 | 结果 |
|---|---|
| `adb install -r` / appops 悬浮窗 / 通知权限 / FGS 启动 | ✅ 成功，`Background started FGS: Allowed`，无 FATAL |
| 悬浮窗创建/透明 | ✅ `mAttrs={(334,1525)(683x549)} fl=NOT_FOCUSABLE LAYOUT_NO_LIMITS HARDWARE_ACCELERATED`，frame 与 setBounds 一致 |
| `setFitInsetsTypes(0)`（无 +63 偏移） | ✅ 生效（frame=[334,1525] 与请求一致） |
| `setBounds` 驱动窗口移动 | ✅ 窗口 frame 与 `[svc] info window=[334,1525,683,549]` 一致 |
| **页面渲染** | ❌ **阻塞 bug**：主页被当作纯文本显示（截图 `shots/qa2_t2_pet_started.png` 里是 HTML 源码）；`[web] diag` 显示 `inner=[980,788], hasBridge=false, videos=0, sprites=0` |
| Q4 触摸区域 | ⚠️ 按预期失败并退化整窗：`[touch-probe] targetSdk=35 直读: field=NoSuchFieldException method=ok 豁免=fail` → `[touch] 触摸区域 API 不可用 → 退化整窗可点`（logcat `qa2_t2_pet1.txt`） |

**页面 bug 根因（读 T2 `AssetServer.java` 定位）**：`text()`/`streamAsset()` 把 charset 拼进了 `WebResourceResponse` 的 **mimeType**（如 `"text/html; charset=utf-8"`）且 **encoding 传 null**；Chromium 不把带参数的 mimeType 识别为 HTML，主文档按 text/plain 渲染。修法：mimeType 传纯 `text/html`、encoding 传 `"utf-8"`（文本/JS/CSS/JSON 同理）。已发 android-dev，待修复后复测（详见 `qa\QA-REPORT.md`）。

---

## 9. 复现实验方法（供 T2/Lead 复查）

```powershell
$adb='D:\Android\Sdk\platform-tools\adb.exe'
# 构建探针（PetProbe 自带可用 wrapper，JDK17）
$env:JAVA_HOME='C:\Program Files (x86)\Android\openjdk\jdk-17.0.14'
cd D:\AndroidProjects\PetProbe; .\gradlew.bat --offline assembleDebug
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell appops set com.petprobe SYSTEM_ALERT_WINDOW allow
& $adb shell pm grant com.petprobe android.permission.POST_NOTIFICATIONS
& $adb shell am start -n com.petprobe/.MainActivity
# 驱动 overlay（探针为方便 shell 测试导出 service；正式版不建议导出）
& $adb shell am start-foreground-service -n com.petprobe/.ProbeOverlayService -a com.petprobe.action.START --es mode alpha --ez useHttps true --ei x 100 --ei y 300 --ei w 800 --ei h 700
& $adb shell am start-foreground-service -n com.petprobe/.ProbeOverlayService -a com.petprobe.action.FIT --ei fit 0
& $adb shell am start-foreground-service -n com.petprobe/.ProbeOverlayService -a com.petprobe.action.TOUCH --es tmode rect
& $adb shell input tap 400 1250   # region 内
& $adb shell input tap 700 1700   # region 外 -> 穿透
& $adb logcat -d -v time | Select-String 'PetProbe'
```

> 探针工程中的关键实现文件：`app/src/main/java/com/petprobe/ProbeOverlayService.java`（overlay/位置/反射 region/捕获窗 fallback）、`PetProbeWeb.java`（WebView 配置 + shouldInterceptRequest Range/​mime 正确写法）、`assets/probe/{alpha,pointer,touch}.html`。

---

## 10. 附录：证据文件清单

- 截图（`qa/shots/`）：`q1_inline_alpha.png`、`q2_overlay_alpha_https.png`、`q3_move_neg_nolimits.png`、`q3_move_neg_flagoff.png`、`q4_touch_region_after_taps.png`、`q5_pointer_screenx.png`、`q5c_fallback_capture_window.png`、`q6_crossapp_before.png`、`q6_crossapp_before2.png`、`q7_home_with_overlay.png`、`q7_crossapp_clickthrough.png`、`q7_fallback_crossapp2.png`、`qa2_t2_launch_activity.png`、`qa2_t2_pet_started.png`
- 原始 logcat（`qa/logs/`）：`q1_inline_logcat.txt`、`q2_overlay_logcat.txt`、`q3_full.txt`、`q4_restart.txt`、`q4_taps.txt`、`q5_pointer.txt`、`q5c_fallback_taps.txt`、`q6_check.txt`、`q7_cross.txt`、`q7_fallback_cross2.txt`、`qa2_t2_launch.txt`、`qa2_t2_pet1.txt`

---

## 11. 遗留风险 / 后续 QA

1. **真机验证**：模拟器为 SwiftShader 软件合成，真机 GPU/解码路径需在正式 APK 上复核（重点：alpha 透明、帧率、拖拽/甩抛手感）。
2. **B 方案落地风险**：多捕获窗（宠物身体/菜单/气泡可点区域）需要与 input regions 逐帧同步；菜单弹出时区需要覆盖菜单矩形；拖拽中手指移出捕获窗仍可（窗口级手势持续），但 **WebView 内 pointer capture 与转发事件的 pointerId 一致性**需在正式 APK 上验证。
3. **跨 App 穿透**：B 方案实测通过（桌面图标点击），但目标 App 若设置 hide overlay（Settings 类）会隐藏宠物属正常。
4. **性能**：模拟器帧率低，正式 QA 以"动画在动/无黑底/可拖拽"为准，真机另行确认。
5. **T2 修复后需完整 QA**：安装→授权→开启→渲染→动画两帧对比→点击/拖动/长按菜单→旋转→关闭服务窗口消失→logcat 无 FATAL/JS error（见 `qa/QA-REPORT.md`）。
