# DeepSeek 桌宠（手机版）· Android 外壳工程

把用户电脑上的 **dsh-pet v0.3.7 桌宠（"大肥鱼"）** 原样打包成 Android APK：透明悬浮窗常驻桌面，
可拖拽 / 甩抛 / 点击 / 长按菜单，素材、动画、物理与交互逻辑全部复用 dsh-pet 原包。

- 包名：`com.dshpet.mobile` ｜ 应用名：DeepSeek 桌宠 ｜ versionName 1.1.4 (versionCode 6)
- `minSdk 30`（Android 11）/ `targetSdk 35`（Android 15）
- **纯 Java + 无 AndroidX + 零第三方依赖**（全部 framework API，WebView/Service/WindowManager）
- 源码位置：`D:\AndroidProjects\DeepSeekPet\android`；页面/素材来源：`D:\AndroidProjects\DeepSeekPet\web`（T1 产出，直接作为 APK assets 根）

### 版本变更

- **v1.1.4（2026-10-21，最终交付版）**：修复部分厂商真机（vivo/Android 16）上**聊天结束后键盘不自动收起**的问题——
  `setChatMode(false)` 改为：**先让页面主动失焦**（`document.activeElement.blur()`，让 Blink 向 IME 发 hide 请求）→
  `InputMethodManager.hideSoftInputFromWindow` → 恢复 `FLAG_NOT_FOCUSABLE` + `softInputMode=HIDDEN` →
  100/400/1000/1800ms 各再补一次「blur + hide + clearFocus」→ WebView `setFocusable(false)`；
  下次打开聊天会重新 `setFocusable(true)+requestFocus()+showSoftInput`。versionCode=6。
- **v1.1.3（2026-10-21）**：DSH 连接体验收尾——
  - 控制台「连接电脑 DeepSeek Harness」卡片里的 **「USB 连接教程」「Wi-Fi 连接教程」改为可点击链接**，
    点击弹出原生教程对话框（含连接步骤、排查项、通用说明与 Wi-Fi 安全提示）；
  - **修复聊天结束后键盘不自动收起**（`setChatMode(false)`：hideSoftInput + clearFocus + 恢复不可聚焦窗口 flags +
    300/900ms 延迟补隐藏；打开→关闭→再打开均正常）；
  - `mobile-bridge.js` 升到 **1.1.1**：连接态菜单变 7 项（多「查看余额」）后注入紧凑样式，
    保证「动作」等全部菜单项在菜单内可见可点（真机上不再被裁掉）；
  - versionCode=5。
- **v1.1.2（2026-10-21）**：新增「连接电脑 DeepSeek Harness」——手机版可通过 `adb reverse`（USB）
  或 Wi-Fi 代理连上电脑上正在运行的 DSH 宿主，**余额 / 碎碎念 / 对话 / 工作状态联动真正可用**：
  - `web/mobile-bridge.js` 1.1.0（fetch 代理：动态端点走 Android 原生 HTTP；
    未连接时直接走原静态降级，行为与 v1.1.1 完全一致）；
  - Android 侧：Harness 地址/连接态持久化、控制台「连接电脑 DeepSeek Harness」卡片（保存并连接 / 断开降级 / 状态 / 3s 测试）、
    `AndroidPet.harnessAvailable/harnessFetch/setChatMode` 桥、连接态联动 config（`__ENABLE_DSH__` 占位符 true/false）、
    对话时临时取焦点弹输入法并自动上移/左移窗口防止键盘遮挡与屏幕裁切；
  - 新增 `tools\harness-connect.ps1`（USB 一键连接）与 `tools\harness-wifi-proxy.bat/.js`（Wi-Fi 代理，端口 3413）；
  - versionCode=4；未连接用户零变化（默认降级）。
- **v1.1.1（2026-10-21）**：修复「长按菜单 → 动作 → 具体动画」三级菜单在触屏上被同一次 tap
  误命中第一项的问题（`mobile-bridge.js` 1.0.2 触摸菜单导航保护窗）；新增碎碎念/对话不可用时的原生 Toast 提示。
  versionCode=3。
- **v1.1.0（2026-10-21）**：新增「宠物透明度」功能（滑杆 30%–80%，默认 80%，运行中热更新、重启服务后持久化；
  80% 是 Android 允许「点击穿透到下层 App」的系统上限，100% 不可行——见"已知限制"）。页脚/诊断版本同步更新。
- **v1.0.0（2026-10-21）**：首个交付版——透明悬浮窗 + dsh-pet v0.3.7 全部宠物行为（拖拽甩抛/点击/长按菜单/漫游）
  + B 方案跨 App 点击穿透 + 可调宠物大小。

## 目录结构

```
android/
├─ build.gradle.kts / settings.gradle.kts / gradle.properties / local.properties
├─ gradlew.bat / gradlew / gradle/wrapper/     # Gradle 8.4 wrapper（腾讯镜像；--offline 可构建）
├─ build.ps1                                   # 一键构建（内设 JDK17，先 offline 再联网兜底）
├─ release.keystore                            # 本地自签名 release 密钥（密码见 gradle.properties）
├─ emu-test.ps1                                # 模拟器冒烟 + 透明度自测脚本（安装/授权/开启/截图/logcat）
├─ emu-evidence/                               # T2 自测证据（截图 + logcat，见 REPORT.md；v112/ v113/ = DSH 连接/教程自测）
├─ ..\tools\harness-connect.ps1                # USB 一键连接助手：adb reverse + 电脑侧 DSH 自检
├─ ..\tools\harness-wifi-proxy.bat/.js         # Wi-Fi 代理：电脑 0.0.0.0:3413 → 127.0.0.1:3412
└─ app/
   ├─ build.gradle.kts                         # compileSdk 35、minSdk 30、versionCode 6/1.1.4、assets 源目录 ../../web
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ assets/debug/placeholder.html         # 仅当 web/ 未就绪时的调试占位页
      ├─ res/                                  # 中文文案、主题、图标（自绘小鱼，非正版素材）
      └─ java/com/dshpet/mobile/
         ├─ MainActivity.java                  # 控制台（权限/启停/大小/透明度/DSH 连接/说明/许可/诊断）
         ├─ PetOverlayService.java             # 前台服务 + 透明悬浮窗 + WebView + 透明度/DSH 联动
         ├─ TouchCaptureManager.java           # 触摸穿透（B 方案：渲染窗不可触 + 捕获小窗转发）
         ├─ TouchRegionHelper.java             # 触摸穿透（A 方案：反射隐藏 API；已停用，仅留档）
         ├─ PetBridge.java                     # window.AndroidPet 的 Java 侧实现（含 harnessFetch 代理）
         ├─ AssetServer.java                   # shouldInterceptRequest 路由（assets + Range + config 替换）
         ├─ Geometry.java                      # 屏幕几何 / dpr / 安全区 / 页面 URL / 初始窗口尺寸
         └─ Prefs.java                         # 宠物大小/透明度/DSH 地址与连接态（SharedPreferences）
```

`app/build.gradle.kts` 的 assets 来源：

```kotlin
sourceSets { getByName("main") { assets.srcDirs("src/main/assets", "../../web") } }
```

即 `web/` 目录本身就是 APK 的 assets 根，**不复制、不修改**（web/ 是 T1 的唯一事实来源）。
`src/main/assets/debug/placeholder.html` 只在 `web/index.html` 缺失时被兜底使用，不会与 web/ 冲突。

## 构建

必须用 **JDK 17**（系统默认 JDK8 不能构建），SDK 在 `D:\Android\Sdk`：

```powershell
cd D:\AndroidProjects\DeepSeekPet\android
$env:JAVA_HOME='C:\Program Files (x86)\Android\openjdk\jdk-17.0.14'
.\gradlew.bat --offline assembleRelease
# 或一键脚本（含 fallback 联网）：
.\build.ps1
```

产物：`app\build\outputs\apk\release\app-release.apk`（63,802,478 B ≈ 60.85 MB，含 106 个 webm 全量素材）。
debug / release 都用工程内 `release.keystore` 签名（可互相覆盖安装）。
本次交付版（v1.1.4）：versionCode=6 / versionName=1.1.4，构建时间 2026-10-21 23:18:53，
sha256 `D79F42F12B06AF3287D0AC851CA6113BCFEF1ED9A6D1F0A911ECFCF1F666EEC0`
（与 `dist\大肥鱼桌宠-手机版-v1.1.4.apk` 逐字节一致）。
APK 内 `assets/mobile-bridge.js`：44,714 B、sha256 `243E2E822DB6F29198318131752B5CCD9883130EB48CBDB76DC00B90A373BCC6`、VERSION `1.1.1`；
`assets/config.json`：9,878 B、sha256 `D9B07A6B6BAAB39A7116FB2735D80B36623BE9F2ADF17FE64AEEB187F32BCD4A`（含 `__ENABLE_DSH__` 占位符）。

## 如何让手机连上电脑 DeepSeek Harness（v1.1.2+）

连接后：**余额 / 碎碎念 / 对话 / 工作状态联动**真正可用；未连接时自动降级为独立模式（与 v1.1.1 完全一致，
不会出现假余额/假气泡）。**App 内「USB 连接教程 / Wi-Fi 连接教程」可点击直接看图文步骤**（v1.1.3）。

1. **电脑端**：保持 DeepSeek Harness 运行（宿主监听 `127.0.0.1:3412`）。
2. **USB 连接（推荐）**：
   ```powershell
   # 一键：建立 adb reverse + 电脑侧自检（项目根目录）
   .\tools\harness-connect.ps1
   # 等价手动命令：
   adb reverse tcp:3412 tcp:3412
   ```
   然后手机 App 内地址保持默认 `http://127.0.0.1:3412`。
3. **Wi-Fi 连接**（手机与电脑同一 Wi-Fi）：
   **双击 `tools\harness-wifi-proxy.bat`**（窗口保持打开；会显示可用地址，如 `http://192.168.2.9:3413`）→
   手机 App 地址填窗口里显示的 `http://<电脑局域网IP>:3413` → 点「保存并连接」→ 状态「已连接」即可使用。
   - **关窗口 = 断开代理**（App 自动降级）；电脑重启后需重新双击打开一次。
   - 若提示端口占用：先关闭旧的代理窗口，或在任务管理器里结束残留的 `node.exe` 后重试。
   - 代理只监听电脑局域网网卡并转发到本机 `127.0.0.1:3412`，不上传外网；建议只在可信 Wi-Fi 使用，用完关掉代理窗口。
4. **App 内**：打开「连接电脑 DeepSeek Harness」卡片 → 地址填好后点「保存并连接」→ 3 秒内显示「已连接」后自动重启桌宠，
   页面按连接态重载配置。点「断开（降级）」可恢复独立模式。
   - 「保存并连接」会先 GET `{base}/dsh-pet-7340/state` 测试（3s 超时）；失败自动回退降级并 Toast。
   - 连接态持久化；拔线/电脑 DSH 关闭后画面会优雅降级（错误日志节流，不刷屏），重新连接即可恢复。

### 连接工具（`tools\`，随工程交付，不进 APK）

| 文件 | 用途 | 大小 / sha256 |
|---|---|---|
| `harness-connect.ps1` | USB 一键连接：自动 `adb reverse tcp:3412 tcp:3412` + 电脑侧 DSH 自检（自动选实体机/跳过模拟器，支持 `-Stop`/`-OpenTest`） | 5,320 B / `8C8C0D3A5A234BB70CDCF4117CCEDEF57E3C88F16BE6B390999E28BEBBAC35BD` |
| `harness-wifi-proxy.bat` | Wi-Fi 代理启动器：双击运行、窗口保持打开、自动找 node（PATH 优先，其次 `%USERPROFILE%\.dsh\...\node.exe`）、支持 `-port 3413`；**CRLF + 纯 ASCII**（避免 cmd 解析/闪退） | 697 B / `36362E0A23CF601F34737F8E4A91A0563A5C5BBDAB241F2CE21E7C8EC0EF8909` |
| `harness-wifi-proxy.js` | Wi-Fi 代理本体：`0.0.0.0:3413` → `127.0.0.1:3412`，只记录 method+path（不记正文） | 4,487 B / `E7E8A901F5BEE3A9F5D3BD197AEA01355B5614C33EAF56A492B4A1B069941001` |

> 说明：动态端点（`/state` `/whisper` `/chat` `/balance` `/anim` `broadcast` `/reload`）由 `mobile-bridge.js` 的 fetch 代理
> → `AndroidPet.harnessFetch` → 原生 `HttpURLConnection`（connect 8s / read 90s，POST JSON）。静态资源（html/webm/字体/图片）
> 仍全部走 APK assets，不经过网络，因此断网也能正常播动画。

## 运行时架构（复刻 dsh-pet Electron 宿主那一层）

1. **MainActivity（控制台，程序化 UI）**
   - 悬浮窗权限状态 + 「去授权悬浮窗」（`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`）
   - 通知权限（Android 13+ POST_NOTIFICATIONS）
   - 开启 / 关闭桌宠（前台服务）、宠物大小 SeekBar 80–200dp（默认 130，SharedPreferences，运行中改动重启生效）
   - 宠物透明度 SeekBar 30%–80%（默认 80%，运行中通过 `ACTION_SET_ALPHA` 热更新、重启服务后持久化；
     文案说明 80% 是 Android 允许点击穿透的系统上限）
   - 碎碎念/对话不可用提示：页面请求 `/whisper`、`/chat` 时弹原生 Toast（2s 节流，见 AssetServer；**仅未连接时**）
   - **连接电脑 DeepSeek Harness 卡片**（v1.1.2，v1.1.3 加教程）：地址 EditText + 「保存并连接」+「断开（降级）」+ 状态文字；
     **「USB 连接教程」「Wi-Fi 连接教程」为可点击链接**（v1.1.3）→ 原生 AlertDialog 显示图文步骤/排查项/安全说明；
     3s 超时 GET `{base}/dsh-pet-7340/state` 测试，成功才启用并重启桌宠让页面重载 config；失败回退降级 + Toast
   - 操作说明、手机版功能说明、dsh-pet v0.3.7 MIT attribution、诊断信息（屏幕/dpr/服务/大小与透明度/DSH 连接态/最近错误）
2. **PetOverlayService（前台服务）**
   - `startForeground`（specialUse 类型）+ 常驻通知（关闭桌宠动作）；渠道 `dshpet_overlay`
   - 渲染窗：`WindowManager.addView(TYPE_APPLICATION_OVERLAY, PixelFormat.TRANSLUCENT)`
     flags = `NOT_FOCUSABLE | LAYOUT_NO_LIMITS | HARDWARE_ACCELERATED | NOT_TOUCHABLE`，
     `gravity TOP|LEFT`、`setFitInsetsTypes(0)`、`layoutInDisplayCutoutMode=ALWAYS`、
     `alpha=Prefs.getPetAlpha()`（30%–80%，默认 0.80；上限原因见"已知限制"）
   - WebView：透明背景、`setMediaPlaybackRequiresUserGesture(false)`、`textZoom=100`、
     `useWideViewPort(true)`、DOM storage、`addJavascriptInterface(new PetBridge(), "AndroidPet")`、
     `WebViewClient.shouldInterceptRequest` → AssetServer；console/错误/`onRenderProcessGone` 全部转发 logcat
   - 页面 URL（全部 URLEncode）：`https://pet.local/dsh-pet-7340/?configUrl=...&scale=<dpr>&petIndex=0&workAreaX/Y/W/H&areas&panels&workAreas&primaryIndex&openTarget=site`
     （hull=整屏、areas/workAreas=去状态栏/导航栏的安全区、panels=整屏，物理像素）
   - `setBounds` → Handler 逐帧合并 → `updateViewLayout` 移动/缩放窗口（物理像素，与 Electron `setContentBounds` 同语义）
   - 显示器变化（DisplayListener + onConfigurationChanged，去抖 350ms）→ 重算几何并
     `evaluateJavascript("window.__petApplyDisplays(<jsonString>)")`
   - `onDestroy` 移除窗口/捕获窗、注销监听、停前台
   - **DSH 联动（v1.1.2，v1.1.3 收尾）**：`harnessFetch` 线程池 `HttpURLConnection`（connect 8s / read 90s，GET/POST，
     POST `application/json; charset=utf-8`）→ 主线程 `evaluateJavascript("window.__petportHarnessDone(cbId, payloadJson)")`
     （2 参数、payload 二次 JSON 转义；**不打印对话正文**，只记 method/path/status/耗时；`/state` 成功 debug 级、
     失败日志 5s 节流）。`setChatMode(open)`：聊天时临时去掉渲染窗 `FLAG_NOT_FOCUSABLE` + 加 `FLAG_NOT_TOUCH_MODAL`、
     `requestFocus` + `showSoftInput`；并把窗口临时上移（`max(IME insets, 屏幕高×38%)`，兜底 overlay 窗口 insets 偏小的问题）
     /左移（贴右边缘时）保证聊天面板不被键盘遮挡、不被屏幕裁切；关闭时恢复且 **v1.1.3 起确保键盘自动收起**
     （hideSoftInput + clearFocus + 恢复 `FLAG_NOT_FOCUSABLE` + `softInputMode=HIDDEN` + 300/900ms 延迟补隐藏）。
     **渲染窗始终保留 `FLAG_NOT_TOUCHABLE`**、捕获窗逻辑不变。
   - `mobile-bridge.js` **1.1.1**（v1.1.3）：连接态菜单变 7 项后注入紧凑样式，保证「动作」等全部菜单项可见可点
   - **键盘自动收起（v1.1.4，OEM 兼容）**：`setChatMode(false)` → 页面主动失焦（Blink 发 IME hide 请求）→
     `IMM.hideSoftInputFromWindow` → 恢复 `FLAG_NOT_FOCUSABLE`/`softInputMode=HIDDEN` →
     100/400/1000/1800ms 各补一次 blur+hide+clearFocus → WebView `setFocusable(false)`
3. **TouchCaptureManager（B 方案触摸穿透，T3 探针实测通过）**
   - 渲染窗 `FLAG_NOT_TOUCHABLE`：任何触摸都不拦截、也不参与下层 App 的遮挡判定 → **透明余量点击穿透到下层应用**
   - 页面上报的每个可交互矩形（宠物身体 / 菜单面板 / 拖拽时整窗）各建一个透明 `TYPE_APPLICATION_OVERLAY` 捕获小窗
     （`NOT_FOCUSABLE|LAYOUT_NO_LIMITS|HARDWARE_ACCELERATED`、`TRANSLUCENT`、`setFitInsetsTypes(0)`）
   - 小窗收到 `MotionEvent` 后 `obtain + offsetLocation(rect.left, rect.top)` → `webView.dispatchTouchEvent(copy)` 注入页面
     （异步派发 + MOVE 合并，避免输入线程 ANR；DOWN/UP/CANCEL 保序）
   - 手势一旦 DOWN 落在某小窗，后续 MOVE/UP 都归它（Android 手势归属），拖拽甩抛不丢事件；
     手势中只跟手移动"持有手势"的捕获窗，其余等手势结束补位
   - 菜单/聊天打开（inputBusy）时自动补整窗捕获，保证菜单外点击能进页面关菜单
   - 窗口上限 6 个，超过合并为外接矩形；空闲 1 个 / 点击 1–2 / 拖拽 1–3 / 级联菜单最多 5
4. **AssetServer（shouldInterceptRequest，全部从 assets 返回，无网络）**
   | 请求 | 返回 |
   |---|---|
   | `/dsh-pet-7340/`、`/index.html` | `index.html`（web/ 缺失时用 debug 占位页） |
   | `/dsh-pet-7340/config` | `config.json`：运行时字符串替换 `__PET_SIZE__`→宠物大小dp、`__MARGIN_X__`→24、`__MARGIN_Y__`→100、**`__ENABLE_DSH__`→true/false**（按「已连接电脑 DSH」；未连接=false，与 v1.1.1 完全一致），并防御性校正 pets[0] 三开关 + `notificationsEnabled=false` |
   | `/dsh-pet-7340/state` | `state.json` |
   | `/dsh-pet-7340/config/meta` | `{"mobile":true}` |
   | `/dsh-pet-7340/thumb/<root>/<名>.webm` | `webm/<名>.webm`（video/webm，支持 Range/206） |
   | `/dsh-pet-7340/font/<名>.ttf` | `fonts/<名>.ttf` |
   | `/dsh-pet-7340/pic/memes/<root>/<名>.png` | `memes/<名>.png` |
   | `/dsh-pet-7340/pic/<文件>` | `pic/<文件>` |
   | `/balance` `/whisper` `/chat` `/anim` `/reload` | `{"ok":false,"reason":"unsupported",...}`（手机版未连 DSH）；其中 `/whisper`、`/chat` 额外弹一次原生 Toast 说明（同类型 2s 节流） |
   | `/dsh-pet-7340/<其它>` | 直接按 assets 根同名文件（`shared-core.js`/`constants.js`/`sprite.js`/`events.js`/`renderer.js`/`mobile-bridge.js` 等） |
   - mime 用**纯 mimeType** + `encoding="utf-8"`（charset 不能塞进 mimeType，否则 WebView 会把 HTML 当纯文本）
   - webm/ttf/png 在 `androidResources.noCompress` 中保持不压缩 → `AssetFileDescriptor` 可拿长度做 206；
     AFD 的关闭挂在返回流的 `close()` 上（提前关会让 WebView 读流失败 `net::ERR_FAILED`）
   - `/whisper`、`/chat` 请求时：主线程 Toast「手机版未连接电脑上的 DeepSeek Harness，碎碎念/对话暂不可用」，
     同类型 2 秒节流；随后照旧返回结构化失败 JSON（不假装成功、不改焦点/IME）。QA 日志：`[asset] whisper/chat 请求…`
   - 已连接 DSH 时，动态端点被 `web/mobile-bridge.js` 的 fetch 代理接管（走 `AndroidPet.harnessFetch`，不经过本静态路由）；
     本路由只在未连接时兜底。
5. **PetBridge（`window.AndroidPet`，与 web/mobile-bridge.js 契约一一对应）**
   - `setBounds(x,y,width,height,boxX,boxY,size,bottomPad,vx,vy)`（物理像素）
   - `setInteractive(flag)`、`setInputBusy(busy)`、`setInputRegions(jsonString)`
   - `openUrl(url)`、`getWindowOrigin()`（JSON：x/y/width/height/scale/pxW/pxH/safe*，供 screenX 物理化校准）
   - `getInfo()`（服务诊断 JSON）、`log(msg)`
   - **DSH 联动（v1.1.2）**：`harnessAvailable()`（"1"/"0"，仅连接态为 1）、
     `harnessFetch(method, path, body, cbId)`（fetch 代理，完成回调 `window.__petportHarnessDone(cbId, payloadJson)`）、
     `setChatMode(open)`（对话面板开/关 → 输入法焦点 + 窗口临时位移）
   - 页面 → `window.__petApplyDisplays(jsonString)`：屏幕旋转/尺寸变化时下发 electron `deskGeometry` 同形 JSON
6. **Geometry / Prefs**：屏幕几何、dpr、安全区、初始窗口尺寸（`petWindowSize()` 同公式）、宠物大小（80–200dp）
   / 宠物透明度（30%–80%，默认 80%）/ **DSH 地址与连接态**（`harness_base` + `harness_connected`，默认未连接）持久化
7. **透明度热更新**：`PetOverlayService.ACTION_SET_ALPHA`（`EXTRA_ALPHA` float）→ 主线程
   `lp.alpha = clamp(0.30–0.80); wm.updateViewLayout(webView, lp)`，不重建 WebView/窗口、不改 flags
   （`FLAG_NOT_TOUCHABLE` 必须保持，否则跨 App 穿透失效）；`buildInfoJson()` 输出 `alphaPct` 供诊断页展示
8. **电脑 DSH 连接（v1.1.2）**：`Preferences(harness_base, harness_connected)` + MainActivity 测试连接（3s）→
   成功才置 connected=true 并重启 pet 服务 → 页面重载 config（`__ENABLE_DSH__`=true）→ `mobile-bridge.js` 1.1.0
   的 fetch 代理把动态端点转到 `PetBridge.harnessFetch` → 原生 HTTP 到电脑 `127.0.0.1:3412`（经 `adb reverse`
   或局域网）。断开 = connected=false + 重启服务 → 回到独立模式。Manifest 已加 `INTERNET` + `usesCleartextTraffic`。

> A 方案（`TouchRegionHelper`，`ViewTreeObserver.OnComputeInternalInsetsListener` + `TOUCHABLE_INSETS_REGION`）
> 保留代码但**不启用**：这些方法是 hidden API，targetSdk 35 下 `touchableRegion` 字段被 non-SDK 策略拒绝；
> 且 Android 12+ 的 untrusted-touch 策略会丢弃穿过悬浮窗落到下层 App 的点击。B 方案是公共 API 且实测可穿透。

## 模拟器自测

```powershell
cd D:\AndroidProjects\DeepSeekPet\android
.\emu-test.ps1                 # 安装最新 release APK → 授权 → 开桌宠 → 透明度热更新 40↔80 → 点击/拖动 → 截图 + logcat
.\emu-test.ps1 -SkipInstall    # 不重装，只重跑冒烟
.\emu-test.ps1 -SkipAlpha      # 跳过透明度滑杆测试
```

证据输出到 `android\emu-evidence\shots\` 与 `\logs\`：
- `emu-evidence\v111\`：v1.1.1（桥 1.0.2 校验 + 碎碎念 Toast 实拍）
- `emu-evidence\v112\`：v1.1.2（DSH 连接 E2E：连接/余额/碎碎念/对话输入&发送/工作状态/断开降级/重连，截图 + logcat）
- `emu-evidence\v113\`：v1.1.3（USB/Wi-Fi 教程弹窗、聊天键盘自动收起 `mInputShown true→false→true`、
  连接态 7 项菜单全部可见含「动作」、最终包校验）
- `emu-evidence\v114\`：v1.1.4（OEM 兼容键盘收起加强后的 `mInputShown true→false→true→false` 验证）
已执行结论见 `emu-evidence\REPORT.md`。
真机全功能测试（用户 vivo V2359A / Android 16）见 `D:\AndroidProjects\DeepSeekPet\qa\FUNCTION-TEST-REPORT.md`：
§7 v1.1.1 回归、§8 v1.1.4 最终验收（键盘自动收起 / USB-Wi-Fi 教程弹窗 / 连接态 7 项菜单可点动作 /
碎碎念·对话·余额·工作状态 / 断开降级与重连 / 干净状态零变化 全 PASS）。
手动关键命令：

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell appops set com.dshpet.mobile SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.dshpet.mobile android.permission.POST_NOTIFICATIONS
adb shell am start -n com.dshpet.mobile/.MainActivity      # 点「开启桌宠」
adb logcat -s DSHPet
```

logcat 关键字（tag 均为 `DSHPet`）：`[overlay]`、`[touchcap]`、`[bridge]`、`[asset]`、`[harness]`、`[web:LOG]`/`[web:diag]`、`[display]`、`[svc]`。

## 已知限制（交付说明）

1. **宠物不透明度可调，上限 80%**：B 方案渲染窗必须 `FLAG_NOT_TOUCHABLE` 才能让透明余量的点击穿透到下层 App；
   Android 12+ 会强制把这类悬浮窗的 alpha 压到 ≤0.80（安全策略，防点击劫持），因此 100% 不透明不可行。
   v1.1.0 起用户可在「宠物透明度」滑杆自选 **30%–80%**（默认 80%，越低调越透明），跨 App 穿透在整个范围内均有效。
   换 A 方案（targetSdk 30 + 隐藏 API）也逃不掉 untrusted-touch 限制，B 方案是当前最优解。
2. **余额 / 碎碎念 / 对话 / 工作状态联动需要连接电脑上的 DeepSeek Harness**（v1.1.2 起支持，见上文「如何让手机连上电脑」）：
   - 未连接时自动降级为独立模式（与 v1.1.1 完全一致，**不会出现假余额/假气泡**；点碎碎念会弹 Toast 说明「需连接电脑 DSH」）。
   - 连接后：余额/碎碎念/对话/工作状态联动可用；对话输入依赖「临时让悬浮窗可取焦点 + 弹出输入法」，
     并把窗口临时上移/左移避免键盘遮挡与屏幕裁切（v1.1.3/v1.1.4 已处理键盘自动收起，含 OEM 兼容兜底；个别厂商 IME 可能行为不同，可反馈）。
   - 手机版不弹系统通知（`notificationsEnabled` 始终 false）。
   - 动画链、拖拽甩抛物理、点击回应、长按菜单、桌面漫游在任何状态下都可用。
   - USB / Wi-Fi 连接步骤可在 App 内点击「USB 连接教程 / Wi-Fi 连接教程」直接查看。
3. `targetSdk 35`：进入设置了 `hide-non-system-overlay` 的敏感页面（系统设置、银行类 App）时，
   悬浮窗会被系统临时隐藏——系统行为，非 bug。
4. 触摸捕获窗数量会随交互区域变化（空闲 1 / 拖拽 2–3 / 级联菜单最多 5）；每次变化是一次 add/removeView，
   日志里 `Input channel disposed without first being removed` 为无害警告。逐帧 setBounds 只做 updateViewLayout。
5. 模拟器（SwiftShader 软渲染 + 2 路 VP9 解码）上动画/拖拽帧率低于真机；在"污染环境极限测试"（叠加另一个
   双视频 WebView + 越界注入坐标）下曾出现一次输入 ANR，干净轮次未复现。真机预期明显更好。
6. 页面右上/左下偶尔会显示宠物名 tooltip（`大肥鱼`）：touch 后 Chrome 合成的 mouse 事件触发原页面的
   hover 提示，属原页面交互在触屏上的表现，不影响功能。
7. **（v1.1.1 已修复，T3 真机回归通过）长按菜单 →「动作」→ 具体动画 的第三级菜单在触屏上会闪现后关闭、无法选择**：
   根因是 dsh-pet 级联子菜单依赖桌面 hover（mouseenter）展开，触摸没有 hover。v1.1.1 在 `mobile-bridge.js`（1.0.2）
   增加「触摸菜单导航」保护窗（350ms）：展开分支后的同一次 tap 不会误命中 flyout 第一项，第二次点击可正常选中并播放动画。
   前两级菜单（打开网站/碎碎念/对话/回到初始位置/重载配置/动作）与长按菜单本身均正常。
8. **（低优先）「重载配置」点击后无可见反馈**：移动端是静态打包配置，重载不会改变任何内容，
   建议后续加 toast/日志提示，避免用户误以为没生效。
9. **DSH 连接稳定性（v1.1.2）**：USB 方案依赖 `adb reverse tcp:3412 tcp:3412`，**拔数据线 / 重启 adb / 重启电脑后失效**，
   重新运行 `tools\harness-connect.ps1` 即可恢复（App 会先优雅降级，不影响桌宠本身）；Wi-Fi 方案需电脑上运行
   `tools\harness-wifi-proxy.bat`（保持窗口打开，App 地址填窗口显示的 `http://<电脑IP>:3413`）。
   电脑 DSH 关闭时页面显示降级状态、日志节流不刷屏；详细排查见 App 内连接教程。

## 许可

- 动画素材、字体、表情包与交互/物理逻辑来自 **dsh-pet v0.3.7（MIT License）**，版权归原项目作者。
- 本工程只是其 Android 透明悬浮窗外壳（Electron 宿主层的复刻），未修改宠物行为逻辑；
  宠物形象「大肥鱼」及全部文案来自 dsh-pet 原配置。
- `release.keystore` 仅用于本地自签名分发（密码 `dshpet2026`，见 gradle.properties），不是商店签名。
