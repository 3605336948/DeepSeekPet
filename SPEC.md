# DeepSeek 桌宠（手机版）打包工程 — 技术规格

> 目标：把用户刚安装在电脑上的 **dsh-pet v0.3.7 桌宠**（"大肥鱼"）原样打包成 Android APK，
> 在最新版安卓手机上以**透明悬浮窗**形式常驻桌面（可拖拽、抛掷、点击互动、长按菜单）。
> **不允许重新做一只自己的宠物/游戏**：素材、动画、物理、交互逻辑全部复用 dsh-pet 的原包。
>
> 工作根目录：`D:\AndroidProjects\DeepSeekPet`（新工程，不污染用户已有的 CollocationMemory 仓库）
> 源插件路径：`C:\Users\王佳豪\.dsh\profiles\desktop\node_modules\dsh-pet`（v0.3.7，MIT）
> 本规格由 Lead 维护；有疑问发消息给 lead，不要自行改本文件。

---

## 0. 结论性的技术路线

1. dsh-pet 的桌面形态 = **Electron 透明小窗 + 网页渲染**：
   - 页面 `runtime/electron-helper/index.html` 加载 `shared-core.js → constants.js → sprite.js → events.js → renderer.js`；
   - 宠物 sprite 固定钉在小窗内 `(margin.l, margin.t)`，宠物的"移动"由页面逐帧调用
     `window.petBridge.setBounds(x, y, w, h, boxX, boxY, size, bottomPad, vx, vy)` 让**宿主移动整窗**；
   - 配置来自 `GET {BASE}/config`（BASE = `configUrl` 的 origin + `/dsh-pet-7340`），
     动画 `GET {BASE}/thumb/<素材根>/<名称>.webm`，字体 `/font/<名>.ttf`，表情包 `/pic/...`。
2. 手机端 = **Android 原生外壳完全复刻 Electron 宿主那一层**：
   - 前台服务 + `WindowManager` 添加一个 `TYPE_APPLICATION_OVERLAY` 的透明小窗（大小 = 宠物包围盒 + 四边各 0.5×size 余量，与 Electron 完全一致）；
   - 小窗里放 `WebView`，加载打包进 APK 的同一份页面（用 `WebViewClient.shouldInterceptRequest`
     拦截 `https://pet.local/...`，从 assets 返回，页面/配置/视频同源，无 CORS）；
   - 注入 `window.petBridge`（`mobile-bridge.js` + `addJavascriptInterface`），
     `setBounds` → `WindowManager.updateViewLayout` 移动小窗；`setInputRegions` → 设置窗口触摸区域
     （只有宠物身体/菜单区域可点，其它透明像素点击穿透到下层的其它 App）；
   - 页面代码**零逻辑改动**（只加 viewport meta、touch CSS、mobile-bridge.js 一行 script）。
3. 手机上没有 DSH 宿主，因此**明确降级**（与 dsh-pet 官方"独立模式"同一口径，不假装成功）：
   余额 / 碎碎念 / 对话 / 工作状态联动 / 系统通知不可用（配置里关掉），
   动画链、拖拽抛掷物理、点击回应、长按菜单、漫游全部可用。App 里要有说明文案。

---

## 1. 目录与所有权（写作用域）

```
D:\AndroidProjects\DeepSeekPet\
├─ SPEC.md                  # Lead
├─ recon\                   # Lead（merged-config.json / state.json / 截图）
├─ web\                     # T1「web-packager」唯一写作用域
├─ tools\web\               # T1 的构建脚本（可复跑）
├─ android\                 # T2「android-dev」唯一写作用域
├─ qa\                      # T3「probe-qa」报告/截图
└─ dist\                    # Lead 最终产物
D:\AndroidProjects\PetProbe\  # T3 的最小验证工程（技术探针，独立包名）
```

---

## 2. T1：Web 资源包（`web\`）

从源插件复制/改造出可被 Android 直接打包的静态资源目录（**web 目录本身就是 APK 的 assets 根**）：

### 2.1 需要复制的文件
```
runtime/electron-helper/index.html
runtime/electron-helper/shared-core.js
runtime/electron-helper/constants.js
runtime/electron-helper/sprite.js
runtime/electron-helper/events.js
runtime/electron-helper/renderer.js
assets/webm/*.webm            (约 90 个动画，VP9-alpha，共 ~45MB；全量保留)
assets/fonts/上首软糖体.ttf
assets/pic/*.png              (光标 + 通知图标)
assets/memes/*.png            (27 张表情包)
assets/logo.png
```
再新增 `web/mobile-bridge.js`、`web/config.json`、`web/state.json`、`web/README.md`。

### 2.2 index.html 改动（仅这些）
- `<head>` 加 viewport：`<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">`
- 样式补：`html,body{background:transparent;touch-action:none;-webkit-touch-callout:none;overscroll-behavior:none;}`（页面原本已有透明背景/user-select）
- 在 `shared-core.js` 之后、`constants.js` 之前插入 `<script src="./mobile-bridge.js"></script>`（顺序必须如此：bridge 用 `window.PetShared` 计算宠物身体命中区）。
- **不要改** sprite.js / events.js / renderer.js / shared-core.js 的逻辑（如需 patch 先报 Lead）。

### 2.3 mobile-bridge.js 契约（T1 写 JS，T2 写 Java 端同名方法）
必须暴露 `window.petBridge` 的完整对象（sprite 里用 `if (window.petBridge)` 判断，缺失时宠物不移动）：
```js
window.petBridge = {
  setBounds(x, y, w, h, boxX, boxY, size, bottomPad, vx, vy) {}, // 物理像素（= CSS px × scale）；调用 AndroidPet.setBounds(...)
  setInteractive(flag) {},            // → AndroidPet.setInteractive(flag)
  setInputBusy(flag) {},              // → AndroidPet.setInputBusy(flag)
  setInputRegions(input) {},          // → AndroidPet.setInputRegions(JSON.stringify(合并后的区域))
  openDshSite(url) {},                // → AndroidPet.openUrl(url)（可忽略）
  reportFlight() {}, onFlightStates() {}, reportCollide() {}, onPetHit() {}, // 单宠物：空实现但必须存在
  onDisplays(cb) {}                   // 记下回调；Android 调 window.__petApplyDisplays(jsonString) 时 cb(geo)
};
```
- `setInputRegions` 收到的是 sprite 上报的 `{dragging:boolean, rects:[{x,y,width,height}]}`（**物理像素、窗口坐标**，来自 `toScreen(getBoundingClientRect())`）。
  bridge 还需**始终**把宠物身体命中区补进 rects：用 `MutationObserver` 找到 `.pet-hit`，`getBoundingClientRect()` 得到 CSS 窗口坐标，乘 `scale`（见下）后作为基础区域；
  `AndroidPet.setInputRegions` 收到的是最终 `{dragging, rects}` JSON。若 `dragState` 拖拽中，`rects` 里再加整窗矩形（保手）。
- 若 `window.AndroidPet` 不存在（桌面浏览器调试），所有方法安全降级为空操作，并在 `window.__PETPORT_DEBUG` 上记录调用。
- 提供 `window.__petApplyDisplays(geoJson)`（给 Android 屏幕旋转/尺寸变化用）和
  `window.__petportInfo()`（返回 `{innerWidth, innerHeight, dpr, hitRect, spriteCount, configOk, errors}` 的 JSON 字符串，供 QA 用）。
- **物理像素约定**：页面 URL 会带 `scale=<devicePixelRatio>`；bridge 自己拿 `scale = Number(new URLSearchParams(location.search).get('scale')||1)`。
  - `setBounds` 等数字原样透传给 Android（已是物理像素）；`getBoundingClientRect` 结果 × scale 后再报给 Android。

### 2.4 config.json（手机版配置）
以 `recon/merged-config.json` 为基准（这是用官方独立模式宿主合并出来的成品：结构 = `{ "main": { ...全局/条目级字段..., pets:[], animations:{}, animationWeights:{}, physics:{}, events:{}, eventsRefreshSec:{}, memes:{}, ... } }`），只做这些修改（保持其余字段原样，保证动画池/权重/物理不变）：
- `main.pets = [ { "id":"main", "name":"大肥鱼", "size":__PET_SIZE__, "balanceEnabled":false, "whisperEnabled":false, "workStatusEnabled":false, "fixedEnabled":false, "display":"desktop", "position":{"corner":"bottom-right","marginX":__MARGIN_X__,"marginY":__MARGIN_Y__} } ]`
- `main.notificationsEnabled = false`
- `main.physics.petCollision = false`（单宠物）
- 其余（animations / animationWeights / events / physics 数值 / memes / workStatusTexts …）原样保留。
- `__PET_SIZE__` / `__MARGIN_X__` / `__MARGIN_Y__` 是给 Android 运行时做**字符串替换**的占位符（JSON 自校验时可用 130/24/100 临时替换）。
- 文件必须是 UTF-8、无注释的合法 JSON。

### 2.5 state.json（静态 /state 响应，喂给页面 1s 轮询）
```json
{"sections":{"balance":{"counter":1,"data":{"ok":false,"provider":"android","reason":"unsupported"}},
 "workStatus":{"counter":0,"data":null},"notify":{"counter":0,"data":null}},"pets":{}}
```

### 2.6 README.md（web 目录内）
写清：文件清单、页面 URL 参数、所有 HTTP 路由 → assets 文件映射、mime、降级说明。
路由映射（T2 按此实现 `shouldInterceptRequest`）：
| 请求路径 | 返回 |
|---|---|
| `/dsh-pet-7340/` 或 `/dsh-pet-7340/index.html` | `index.html` (text/html; charset=utf-8) |
| `/dsh-pet-7340/config` | `config.json`（Android 运行时替换占位符后返回，application/json; charset=utf-8） |
| `/dsh-pet-7340/state` | `state.json` |
| `/dsh-pet-7340/thumb/main/<名称>.webm`（URL 已百分号解码） | `webm/<名称>.webm`，video/webm |
| `/dsh-pet-7340/font/<名称>.ttf` | `fonts/<名称>.ttf`，font/ttf |
| `/dsh-pet-7340/pic/memes/main/<名称>.png` | `memes/<名称>.png`，image/png |
| `/dsh-pet-7340/pic/<文件>` | `pic/<文件>`，image/png |
| `/dsh-pet-7340/balance`、`/whisper`、`/chat`、`/anim` | `{"ok":false,"reason":"unsupported","message":"手机版未连接 DSH"}` |
| `/dsh-pet-7340/config/meta` | `{"mobile":true}` |
| 其它 | 404 |

### 2.7 交付物 & 验收
- `web\` 完整可打包；`tools\web\build-web.mjs`（或 .ps1）可复跑重建。
- 节点自检：`node --check web/mobile-bridge.js` 通过；config.json 用 node 解析通过（占位符替换后）。
- 汇报给 lead：文件数/总大小、config 修改点、菜单里是否还出现"余额/对话/碎碎念"入口（有就列出，不阻塞）。

---

## 3. T2：Android 工程（`android\`）

### 3.1 环境事实（已验证）
- SDK：`D:\Android\Sdk`（platforms: android-34, android-35；build-tools 34.0.0 / 37.0.0）
- 现成参考工程（同机可构建）：`D:\AndroidProjects\CollocationMemory`（AGP 8.3.0、Gradle 8.4、Kotlin 1.9.22；本工程可纯 Java、无 AndroidX 依赖）
- JDK：**必须用 JDK 17**：`C:\Program Files (x86)\Android\openjdk\jdk-17.0.14`
  （系统默认 JAVA_HOME 是 JDK 8，不能直接用；构建命令里显式 `$env:JAVA_HOME=...`）
- Gradle wrapper 8.4 已在 `~\.gradle\wrapper\dists` 缓存；可直接从 CollocationMemory 复制 `gradle\wrapper\*` 与 `gradlew.bat`。
- AGP 8.3.0 依赖已缓存，优先 `.\gradlew.bat --offline assembleRelease`；网络可用（aliyun/google 镜像），离线不行再联网。
- 模拟器：`D:\Android\Sdk\emulator\emulator.exe -avd DSH_API35`（1080×2400 @420dpi → dpr 2.625，Android 15），
  当前以 headless 运行中；adb：`D:\Android\Sdk\platform-tools\adb.exe`。**模拟器由 T3/Lead 统一调度**，T2 需要安装时先和 Lead 说。
- 用户手机是最新安卓（≥ Android 15），minSdk 30 / targetSdk 35 即可；compileSdk 用 35（本机没有 36 platform，不折腾）。

### 3.2 应用形态
- 包名 `com.dshpet.mobile`；应用名"DeepSeek 桌宠"；versionName 1.0.0 / versionCode 1。
- **MainActivity（控制台，程序化 UI 即可）**：
  - 说明：素材/动画/交互来自 dsh-pet v0.3.7（MIT，附 attribution）。
  - 悬浮窗权限状态 + 「去授权」按钮（`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`，`SYSTEM_ALERT_WINDOW`）。
  - 通知权限（Android 13+ POST_NOTIFICATIONS，前台服务需要）。
  - 「开启桌宠 / 关闭桌宠」按钮 → 启停前台服务。
  - 宠物大小 SeekBar（80–200dp，默认 130dp，存 SharedPreferences，运行中改动重启服务生效）。
  - 操作提示：单指拖动/甩、点击有回应、长按（或右键等价）出菜单；"余额/聊天/碎碎念需连接电脑上的 DeepSeek Harness，手机版暂不可用"。
- **PetOverlayService（前台服务）**：
  - `startForeground` + 通知（渠道 id 固定；小图标用 logo）。
  - 计算 dpr、屏幕物理尺寸/安全区（`WindowManager.getCurrentWindowMetrics()` + `WindowInsets`）。
  - 创建透明 overlay 窗口：`TYPE_APPLICATION_OVERLAY`，`PixelFormat.TRANSLUCENT`，
    flags=`FLAG_NOT_FOCUSABLE|FLAG_LAYOUT_NO_LIMITS|FLAG_HARDWARE_ACCELERATED`，gravity=TOP|LEFT，
    初始位置/大小由页面第一次 `setBounds` 决定（先给个临时大小）——注意窗口必须能**部分移出屏幕**（页面按 Electron 语义会把透明余量放到屏外）。
  - WebView：JS/DOM storage 开、`setBackgroundColor(Color.TRANSPARENT)`、`setMediaPlaybackRequiresUserGesture(false)`、
    `setTextZoom(100)`、关闭滚动条/overScroll、`addJavascriptInterface(new PetBridge(), "AndroidPet")`、
    `WebViewClient.shouldInterceptRequest` 按 §2.6 从 assets 返回（线程池/同步读均可；二进制流 + 正确 mime；支持 Range 可选）。
  - 页面 URL（注意全部 URLEncode）：
    `https://pet.local/dsh-pet-7340/?configUrl=https%3A%2F%2Fpet.local%2Fdsh-pet-7340%2Fconfig&scale=<dpr>&petIndex=0&workAreaX=0&workAreaY=0&workAreaW=<cssW>&workAreaH=<cssH>&areas=<json>&panels=<json>&workAreas=<json>&primaryIndex=0&openTarget=site`
    - `cssW = 物理屏宽/dpr`、`cssH = 物理屏高/dpr`；`areas/panels/workAreas` 是**物理像素**矩形数组 JSON：
      hull 用整屏 `{x:0,y:0,width:pxW,height:pxH}`；areas/workAreas 用去掉状态栏/导航栏的安全区（物理像素，含 x/y 偏移）；
      panels 用整屏。顶部/底部 inset 从 `WindowInsets` 拿，拿不到就整屏。
  - `setBounds(x,y,w,h,boxX,boxY,...)`：用 Handler 合并到下一帧，`updateViewLayout` 把窗口放到 (round(x), round(y))、尺寸 round(w)×round(h)（物理像素）。
  - `setInputRegions(json)`：把 rects（物理像素、窗口内坐标）换算成窗口 View 坐标，`ViewTreeObserver.OnComputeInternalInsetsListener` 里
    `info.setTouchableInsets(TOUCHABLE_INSETS_REGION, region)`（API 29+；失败则退化整窗可点并记日志）。始终保留宠物身体区域。
  - 屏幕旋转/尺寸变化（`onConfigurationChanged` 或 DisplayListener）：重算几何并 `evaluateJavascript("window.__petApplyDisplays(<json>)")`。
  - 服务 onDestroy 移除窗口；用户点"关闭桌宠"或通知里的关闭动作 → stopSelf。
  - 权限/异常兜底：无悬浮窗权限时服务不启动并通知 Activity 提示；WebView 崩溃/白屏要有 logcat 可查。
- **构建**：`android\build.ps1`（设 JAVA_HOME=JDK17，先 offline 再 fallback 在线），
  输出 release APK；release 用工程内自签名 keystore（首次用 keytool 生成，密码写 build.gradle.kts 或 local 配置；JDK17 的 keytool 在 `C:\Program Files (x86)\Android\openjdk\jdk-17.0.14\bin\keytool.exe`）。
- assets 来源：`sourceSets.main.assets.srcDirs("../../web")`（即 DeepSeekPet\web 直接打进 APK）。

### 3.3 交付物 & 验收
- `android\app\build\outputs\apk\release\app-release.apk` 构建成功（文件名可保持默认）。
- `adb install -r` 到模拟器成功；授予悬浮窗（`adb shell appops set com.dshpet.mobile SYSTEM_ALERT_WINDOW allow`）后开启桌宠，
  截图里能看到"大肥鱼"透明悬浮在屏幕上（不是黑底方块），能拖动；logcat 无 FATAL。
- 汇报：APK 绝对路径/大小、构建命令、模拟器验证证据、已知问题。

---

## 4. T3：技术探针 + QA（`D:\AndroidProjects\PetProbe`、`qa\`）

### 4.1 先做最小验证工程 PetProbe（独立包名 com.petprobe），在模拟器上回答 5 个高风险问题：
1. **VP9-alpha webm 在 Android WebView 里能否透明播放**（关键！页面背景透明 + 底层棋盘格/壁纸，截图看视频区域是否黑底）。
   素材直接用 `C:\Users\王佳豪\.dsh\profiles\desktop\node_modules\dsh-pet\assets\webm\待机呼吸休闲.webm`。
2. **前台服务里用 application context 创建 WebView + WindowManager overlay 是否可行**（不崩、可见、透明）。
3. **overlay 窗口位置语义**：`updateViewLayout` 把 x 设为负值/超出屏幕，窗口是否被系统 clamp 回来（截图 + `getLocationOnScreen` 日志）。
4. **触摸区域**：`OnComputeInternalInsetsListener` 的 TOUCHABLE_INSETS_REGION 是否生效（点区域外应穿透到下面的 Activity 按钮）。
5. **WebView 里 PointerEvent 的 `clientX/screenX` 语义**：窗口放在 (100,200) 时，页面日志 `e.clientX/e.screenX`，确认 screenX 是屏幕坐标（dsh-pet 拖拽/甩抛依赖它）。
输出 `qa\PROBE-REPORT.md`（结论 + 截图路径 + logcat 证据）+ 给 T2 的实现建议。

### 4.2 再做最终 APK 的 QA（等 Lead 通知"APK 可测"）
- 安装正式 APK、授权、开启桌宠；shade 截图（`adb exec-out screencap -p`）存 `qa\shots\`。
- 验证：动画在动（两帧截图不同）、无黑底、点击/拖动/长按菜单、旋转屏幕后宠物不丢、关闭服务后悬浮窗消失、logcat 无 FATAL/JS console error。
- 输出 `qa\QA-REPORT.md`：通过项/失败项/证据/建议；失败项直接发消息给 T2/Lead。

---

## 5. 已知风险与对策（Lead 汇总）
- **VP9 alpha 透明**：若 WebView 不支持（黑底），备选 = 用 ffmpeg（本机暂无，需要时可下载）+ 把关键动画转成带 alpha 的 WebP/APNG 或 sprite sheet，改播放层；这是最后手段，先由探针实证。
- **overlay 窗口坐标被 clamp**：若被系统修正，把 `areas` 缩进 0.5×size 让窗口始终在屏内（改加载 URL 的 areas 即可，不改页面）。
- **WebView 在 service context**：若崩溃/白屏，改用 `createWindowContext(TYPE_APPLICATION_OVERLAY)` 创建 WebView 的 context。
- **性能**：模拟器软件渲染下视频可能卡；真机会好很多，QA 以"能播/不黑底"为准，不追求模拟器帧率。

## 6. 团队协作
- 只在自己的写作用域内写文件；跨域需求发消息给 lead。
- 需要读源插件/调研随时读；不要动 `C:\Users\王佳豪\.dsh` 下任何文件。
- 完成/阻塞及时 `send_message` 给 lead；Lead 会做最终集成、构建、真机交付说明。
- 最终 APK 由 Lead 从 `android\app\build\outputs\apk\release\` 复制到 `dist\` 并交付给用户。
