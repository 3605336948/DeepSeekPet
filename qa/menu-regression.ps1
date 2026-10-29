# T3 真机菜单回归脚本（v1.1.1 用）
# 用法：pwsh -File qa\menu-regression.ps1 -Phase all|menu|smoke
param([string]$Phase = "all")
$ErrorActionPreference = "Continue"
$adb = "D:\Android\Sdk\platform-tools\adb.exe"
$P = "10AE4F2RHX000K3"
$qa = "D:\AndroidProjects\DeepSeekPet\qa"

function Shot([string]$n) {
    & $adb -s $P shell screencap -p "/sdcard/$n.png" | Out-Null
    & $adb -s $P pull "/sdcard/$n.png" "$qa\shots\$n.png" | Out-Null
    Write-Host "SHOT $n"
}
function Frame {
    $out = & $adb -s $P shell dumpsys window windows 2>$null
    $lines = $out -split "`n"
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match 'DSHPetOverlay') {
            for ($j = $i; $j -lt [Math]::Min($i + 16, $lines.Count); $j++) {
                if ($lines[$j] -match 'mAttrs=\{\((-?\d+),(-?\d+)\)\((\d+)x(\d+)\)') {
                    return @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
                }
            }
        }
    }
    return @(-999, -999, 0, 0)
}
function MenuWindows {
    $log = & $adb -s $P logcat -d -v time 2>$null | Select-Object -Last 40
    $ms = $log | Select-String '捕获窗同步: (\d+) 个'
    if (-not $ms) { return -1 }
    return [int]($ms[-1].Matches.Groups[1].Value)
}
function OpenMenu([int]$retries = 3) {
    for ($k = 0; $k -lt $retries; $k++) {
        $f = Frame
        & $adb -s $P shell input swipe ($f[0] + 455) ($f[1] + 386) ($f[0] + 457) ($f[1] + 386) 900
        Start-Sleep -Milliseconds 1700
        $w = MenuWindows
        Write-Host "OpenMenu try=$k frame=$($f -join ',') menuWindows=$w"
        if ($w -ge 3) { return $f }
    }
    return $null
}

$apk = "D:\AndroidProjects\DeepSeekPet\android\app\build\outputs\apk\release\app-release.apk"
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash
Write-Host "APK sha256=$sha size=$((Get-Item $apk).Length)"

if ($Phase -eq "all" -or $Phase -eq "menu") {
    Write-Host "=== install + launch ==="
    & $adb -s $P install -r $apk | Select-Object -Last 1
    & $adb -s $P shell appops set com.dshpet.mobile SYSTEM_ALERT_WINDOW allow
    & $adb -s $P shell am start -n com.dshpet.mobile/.MainActivity | Out-Null
    Start-Sleep -Seconds 6
    & $adb -s $P shell input tap 369 926   # 开启桌宠
    Start-Sleep -Seconds 14

    Write-Host "=== M1: 动作 -> 待机 -> 选具体动画（补丁核心） ==="
    & $adb -s $P logcat -c
    $f = OpenMenu
    if ($f) {
        & $adb -s $P shell input tap ([int]($f[0] + 599)) ([int]($f[1] + 607))  # 动作
        Start-Sleep -Milliseconds 900
        Shot "rg_m1_actions_level2"
        & $adb -s $P shell input tap 700 2090   # 待机 分类（首击只展开，不应直接播动画）
        Start-Sleep -Milliseconds 700
        $w1 = MenuWindows
        Shot "rg_m2_flyout_stays"
        Write-Host "after category tap menuWindows=$w1 (期望仍>=4，flyout 保持)"
        # 第二击：点 flyout 第一项（flyout 位置按窗口+子面板 rect，取平均位置尝试两次）
        & $adb -s $P shell input tap 700 2130
        Start-Sleep -Milliseconds 900
        $w2 = MenuWindows
        Shot "rg_m3_after_leaf_tap"
        Write-Host "after leaf tap menuWindows=$w2 (期望回到 1~2，菜单关闭=动画已播)"
        & $adb -s $P logcat -d -v time | Set-Content "$qa\logs\rg_menu_fix.txt" -Encoding utf8
    }

    Write-Host "=== M2: 碎碎念/对话 Toast ==="
    & $adb -s $P shell am start -n com.dshpet.mobile/.MainActivity | Out-Null
    Start-Sleep -Seconds 2
    $f = OpenMenu
    if ($f) { & $adb -s $P shell input tap ([int]($f[0] + 599)) ([int]($f[1] + 206)); Start-Sleep -Milliseconds 600; Shot "rg_m4_whisper_toast" }
    & $adb -s $P shell input keyevent KEYCODE_BACK | Out-Null
    Start-Sleep -Milliseconds 500
    & $adb -s $P shell am start -n com.dshpet.mobile/.MainActivity | Out-Null
    Start-Sleep -Seconds 2
    $f = OpenMenu
    if ($f) { & $adb -s $P shell input tap ([int]($f[0] + 599)) ([int]($f[1] + 307)); Start-Sleep -Milliseconds 600; Shot "rg_m5_chat_toast" }

    Write-Host "=== M3: 回到初始位置（拖走后归位） ==="
    & $adb -s $P shell am start -n com.dshpet.mobile/.MainActivity | Out-Null
    Start-Sleep -Seconds 2
    $f = Frame
    & $adb -s $P shell input swipe ($f[0] + 455) ($f[1] + 386) 500 1500 2000
    Start-Sleep -Seconds 3
    $f2 = Frame
    Write-Host "dragged frame=$($f2 -join ',')"
    $f = OpenMenu
    if ($f) {
        & $adb -s $P shell input tap ([int]($f[0] + 599)) ([int]($f[1] + 406))
        Start-Sleep -Seconds 3
        $f3 = Frame
        Write-Host "reset frame=$($f3 -join ',') (期望回到初始右下角)"
        Shot "rg_m6_reset_position"
    }
}

if ($Phase -eq "all" -or $Phase -eq "smoke") {
    Write-Host "=== S1: 透明度 40% -> 80% 热更新 ==="
    & $adb -s $P shell am start -n com.dshpet.mobile/.MainActivity | Out-Null
    Start-Sleep -Seconds 2
    & $adb -s $P logcat -c
    & $adb -s $P shell input swipe 1140 1827 319 1827 800
    Start-Sleep -Seconds 2
    & $adb -s $P logcat -d -v time | Select-String '透明度更新' | Select-Object -Last 2 | ForEach-Object { $_.Line }
    Shot "rg_s1_alpha40"
    & $adb -s $P shell input swipe 319 1827 1140 1827 800
    Start-Sleep -Seconds 2
    & $adb -s $P logcat -d -v time | Select-String '透明度更新' | Select-Object -Last 2 | ForEach-Object { $_.Line }

    Write-Host "=== S2: 跨 App 穿透（HOME 后点透明余量处图标） ==="
    & $adb -s $P shell input keyevent KEYCODE_HOME
    Start-Sleep -Seconds 2
    Shot "rg_s2_home"
    Write-Host "（按当前宠物窗口位置手工选图标点按；参考此前用过的 QQ 图标坐标）"

    Write-Host "=== S3: 旋转/关闭/重开 smoke ==="
    & $adb -s $P shell settings put system accelerometer_rotation 0
    & $adb -s $P shell settings put system user_rotation 1
    Start-Sleep -Seconds 5
    Shot "rg_s3_landscape"
    & $adb -s $P shell settings put system user_rotation 0
    Start-Sleep -Seconds 5
    & $adb -s $P shell am start -n com.dshpet.mobile/.MainActivity | Out-Null
    Start-Sleep -Seconds 2
    & $adb -s $P shell input tap 891 926
    Start-Sleep -Seconds 2
    & $adb -s $P shell input tap 369 926
    Start-Sleep -Seconds 12
    Shot "rg_s4_reopened"
    & $adb -s $P logcat -d -v time | Set-Content "$qa\logs\rg_smoke_final.txt" -Encoding utf8
    Write-Host "=== final logcat critical scan ==="
    Get-Content "$qa\logs\rg_smoke_final.txt" | Where-Object { $_ -match 'FATAL EXCEPTION|ANR in com.dshpet|net::ERR_FAILED|JS_ERROR' } | Select-Object -Last 10 | ForEach-Object { Write-Output $_ }
    Write-Host "SMOKE DONE"
}
