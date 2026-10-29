# DeepSeek 桌宠（手机版）—— 模拟器冒烟 + 透明度自测脚本
#
# 前置：模拟器已启动（AVD DSH_API35 headless，1080x2400），adb 可用，release APK 已构建。
# 用法：
#   .\emu-test.ps1                    # 安装最新 release APK 并跑一遍冒烟（含透明度）
#   .\emu-test.ps1 -SkipInstall      # 不重装，只重启桌宠并抓证据
#   .\emu-test.ps1 -SkipAlpha        # 跳过透明度滑杆测试
#   .\emu-test.ps1 -Apk <路径>
#
# 产物（全部在 D 盘）：android\emu-evidence\shots\*.png 与 logs\*.txt
#
# 说明：
#  - 服务 android:exported="false"，shell 不能直接 start-foreground-service；
#    脚本通过打开 MainActivity → input tap「开启桌宠 / 关闭桌宠」按钮来驱动（与真实用户一致）。
#  - 透明度 SeekBar 的坐标通过 `uiautomator dump` 动态查找（程序化 UI 无 resource-id，
#    按 SeekBar 出现顺序：第 1 个=宠物大小、第 2 个=宠物透明度），不依赖硬编码分辨率。
#  - 透明度生效后用 `dumpsys window` 读 DSHPetOverlay 的 alpha= 值校验。
#  - 模拟器由 T3 统一调度，跑之前确认没有别人正在用。

param(
    [string]$Apk = "D:\AndroidProjects\DeepSeekPet\android\app\build\outputs\apk\release\app-release.apk",
    [string]$OutDir = "D:\AndroidProjects\DeepSeekPet\android\emu-evidence",
    [switch]$SkipInstall,
    [switch]$SkipAlpha,
    [string]$Serial = "emulator-5554",
    [int]$StartX = 311,
    [int]$StartY = 733,
    [int]$StopX = 838,
    [int]$StopY = 733
)

$ErrorActionPreference = "Continue"  # PS5.1 下 adb 的 stderr 警告不应中断脚本；关键步骤显式 throw
# 本机可能同时插着真机：用 ANDROID_SERIAL 锁定模拟器，避免 adb 报 "more than one device"
$env:ANDROID_SERIAL = $Serial
$adb = "D:\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.dshpet.mobile"
$activity = "$pkg/.MainActivity"

$shots = Join-Path $OutDir "shots"
$logs = Join-Path $OutDir "logs"
New-Item -ItemType Directory -Path $shots, $logs -Force | Out-Null

function Shot([string]$name) {
    $path = Join-Path $shots ($name + ".png")
    # 用 cmd 重定向写二进制，避免 PowerShell 文本重定向破坏 PNG
    cmd /c "`"$adb`" exec-out screencap -p > `"$path`""
    Write-Host ("  截图: " + $path)
}
function Logcat([string]$name) {
    $path = Join-Path $logs ($name + ".txt")
    & $adb logcat -d -s DSHPet AndroidRuntime ActivityManager InputDispatcher WindowManager 2>$null |
        Out-File -Encoding utf8 $path
    Write-Host ("  log: " + $path)
}
function Check-Errors() {
    $hits = & $adb logcat -d 2>$null |
        Select-String -Pattern 'FATAL EXCEPTION|ANR in com\.dshpet|Dropping untrusted touch|is unresponsive|net::ERR_FAILED'
    if ($hits) {
        Write-Host "!! 发现错误日志：" -ForegroundColor Red
        $hits | Select-Object -First 10
        return $false
    }
    Write-Host "  logcat 未见 FATAL/ANR/untrusted-touch/资源加载失败 ✅" -ForegroundColor Green
    return $true
}
# 读渲染窗当前 alpha（dumpsys window windows 里 DSHPetOverlay 的 mAttrs=... alpha=0.8）
function Get-PetWindowAlpha() {
    $txt = (& $adb shell dumpsys window windows 2>$null) -join "`n"
    $idx = $txt.IndexOf("Window{" )
    $m = [regex]::Matches($txt, 'DSHPetOverlay[\s\S]{0,1200}?alpha=([0-9.]+)')
    if ($m.Count -gt 0) { return [double]$m[$m.Count - 1].Groups[1].Value }
    return -1
}
# 用 uiautomator dump 找第 N 个 SeekBar（0-based）的 bounds
function Get-SeekBarBounds([int]$index) {
    & $adb shell uiautomator dump /sdcard/ui_dump.xml 2>$null | Out-Null
    $raw = (& $adb exec-out cat /sdcard/ui_dump.xml 2>$null) -join ""
    if (-not $raw) { return $null }
    $xml = [xml]$raw
    $nodes = $xml.SelectNodes("//node[@class='android.widget.SeekBar']")
    if ($nodes.Count -le $index) { return $null }
    $b = $nodes[$index].bounds
    if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        return @{ Left = [int]$matches[1]; Top = [int]$matches[2]; Right = [int]$matches[3]; Bottom = [int]$matches[4] }
    }
    return $null
}
# 把透明度滑杆拖到指定百分比（30–80）
function Set-AlphaPercent([int]$pct) {
    $b = Get-SeekBarBounds 1
    if (-not $b) { Write-Host "  !! 未找到透明度 SeekBar（uiautomator dump 失败）" -ForegroundColor Red; return }
    $frac = ($pct - 30) / 50.0
    # 边界留 2px 余量：直接点 SeekBar 最右端像素可能落在控件外（实测会 miss，不触发 progress 变化）
    $x = [int]($b.Left + 1 + ($b.Right - $b.Left - 2) * $frac)
    $y = [int](($b.Top + $b.Bottom) / 2)
    Write-Host ("  >> 透明度滑杆 → {0}%  (tap {1},{2})" -f $pct, $x, $y)
    & $adb shell input tap $x $y 2>$null | Out-Null
    Start-Sleep -Seconds 2
}

Write-Host "== DeepSeek 桌宠 模拟器冒烟 ==" -ForegroundColor Cyan
$devices = (& $adb devices) -join "`n"
if ($devices -notmatch "emulator-\d+\s+device") { throw "没有可用的模拟器设备：`n$devices" }

if (-not $SkipInstall) {
    Write-Host ">> 安装 $Apk"
    & $adb install -r $Apk
    if ($LASTEXITCODE -ne 0) { throw "adb install 失败" }
}

Write-Host ">> 授权悬浮窗 / 通知权限"
& $adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow | Out-Null
& $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Null
& $adb shell am force-stop $pkg | Out-Null
Start-Sleep -Milliseconds 600
& $adb logcat -c

Write-Host ">> 打开控制台"
& $adb shell am start -n $activity 2>$null | Out-Null
Start-Sleep -Seconds 3
Shot "01_main"
Logcat "01_main"

Write-Host ">> 点「开启桌宠」"
& $adb shell input tap $StartX $StartY
Start-Sleep -Seconds 10
Shot "02_pet_overlay"
Start-Sleep -Seconds 3
Shot "03_pet_overlay_frame2"
Logcat "02_pet_started"
Write-Host ("  当前渲染窗 alpha = " + (Get-PetWindowAlpha))

if (-not $SkipAlpha) {
    Write-Host ">> 透明度测试：80% → 40% → 80%"
    & $adb shell am start -n $activity 2>$null | Out-Null
    Start-Sleep -Seconds 2
    Set-AlphaPercent 40
    Shot "04_alpha_40_main"
    Logcat "03_alpha_40"
    $a40 = Get-PetWindowAlpha
    Write-Host ("  40% 时 dumpsys alpha = " + $a40)
    & $adb shell input keyevent KEYCODE_HOME
    Start-Sleep -Seconds 2
    Shot "05_alpha_40_home"
    & $adb shell am start -n $activity 2>$null | Out-Null
    Start-Sleep -Seconds 2
    Set-AlphaPercent 80
    Logcat "04_alpha_80"
    $a80 = Get-PetWindowAlpha
    Write-Host ("  80% 时 dumpsys alpha = " + $a80)
    $ok = ([math]::Abs($a40 - 0.40) -lt 0.03) -and ([math]::Abs($a80 - 0.80) -lt 0.03)
    if ($ok) { Write-Host "  透明度热更新 ✅ (0.40 → 0.80)" -ForegroundColor Green }
    else { Write-Host "  !! 透明度热更新校验失败：expected 0.40/0.80" -ForegroundColor Red }
}

Write-Host ">> 点击 + 拖动（宠物身体在右下角：屏幕坐标约 845,2001）"
& $adb shell input keyevent KEYCODE_HOME
Start-Sleep -Seconds 1
& $adb shell input tap 845 2001
Start-Sleep -Seconds 2
& $adb shell input swipe 845 2001 845 1500 500
Start-Sleep -Seconds 2
Shot "06_after_tap_drag"
Logcat "05_interaction"

Write-Host ">> 关闭桌宠（回控制台 → 点关闭按钮）"
& $adb shell am start -n $activity 2>$null | Out-Null
Start-Sleep -Seconds 2
& $adb shell input tap $StopX $StopY
Start-Sleep -Seconds 3
Shot "07_after_close"
Logcat "06_after_close"
$windows = & $adb shell dumpsys window windows 2>$null | Select-String -Pattern 'DSHPetOverlay|DSHPetTouch'
if ($windows) { Write-Host "  !! 关闭后仍有悬浮窗残留" -ForegroundColor Red } else { Write-Host "  关闭服务后悬浮窗已移除 ✅" -ForegroundColor Green }

Write-Host ""
$errOk = Check-Errors
Write-Host "== 完成 ==" -ForegroundColor Green
Get-ChildItem $shots, $logs -File | ForEach-Object { Write-Host ("  " + $_.FullName + "  " + $_.Length + " bytes") }
if (-not $errOk) { exit 1 }
