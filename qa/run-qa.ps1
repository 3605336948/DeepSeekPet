param(
    [string]$Action = "help",
    [string]$Name = "shot"
)
$ErrorActionPreference = "Stop"
$adb = "D:\Android\Sdk\platform-tools\adb.exe"
$qa = "D:\AndroidProjects\DeepSeekPet\qa"
$pkg = "com.dshpet.mobile"
$apk = "D:\AndroidProjects\DeepSeekPet\android\app\build\outputs\apk\release\app-release.apk"

function Shot([string]$n) {
    & $adb shell screencap -p "/sdcard/$n.png" | Out-Null
    & $adb pull "/sdcard/$n.png" "$qa\shots\$n.png" | Out-Null
    Write-Host "SHOT $qa\shots\$n.png"
}
function Logs([string]$n) {
    & $adb logcat -d -v time | Set-Content "$qa\logs\$n.txt" -Encoding utf8
    Write-Host "LOGS $qa\logs\$n.txt"
}

switch ($Action) {
    "install" {
        & $adb install -r $apk
        & $adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow
        & $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null
        Write-Host "installed + granted"
    }
    "grant" {
        & $adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow
        & $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null
    }
    "launch" {
        & $adb logcat -c
        & $adb shell am start -n "$pkg/.MainActivity"
    }
    "startpet" {
        # MainActivity「开启桌宠」按钮中心（1080x2400 布局）
        & $adb shell input tap 309 737
    }
    "stoppet" { & $adb shell am force-stop $pkg }
    "home" { & $adb shell input keyevent KEYCODE_HOME }
    "shot" { Shot $Name }
    "logs" { Logs $Name }
    "logcat-clear" { & $adb logcat -c }
    "dshlogs" {
        & $adb logcat -d -v time | Select-String -Pattern "DSHPet|\[touch-probe\]|\[touch\]|\[web|\[svc\]|AndroidRuntime|FATAL" |
            Select-Object -Last 150 | ForEach-Object { $_.Line }
    }
    "windows" {
        & $adb shell dumpsys window windows 2>$null | Select-String -Pattern "DSHPetOverlay" -Context 0,20 |
            Out-String -Width 220
    }
    default {
        Write-Host "usage: run-qa.ps1 -Action install|grant|launch|startpet|stoppet|home|shot|logs|dshlogs|windows [-Name xxx]"
    }
}
