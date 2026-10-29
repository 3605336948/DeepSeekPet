# harness-connect.ps1 —— 一键把手机上的「DeepSeek 桌宠」连到电脑上的 DeepSeek Harness（DSH）
#
# 原理：DSH 桌面端只监听电脑本机 127.0.0.1:<端口>，而且**每次启动端口可能变化**。
#       USB 连接时用 `adb reverse` 把手机的 127.0.0.1:3412 反向映射到电脑上
#       **当前**的 DSH 端口，App 内始终填 http://127.0.0.1:3412 即可。
#
# 用法：
#   .\harness-connect.ps1                 # 自动检测 DSH 端口 + 自动选设备，建立 adb reverse + 自检
#   .\harness-connect.ps1 -Serial 10AE... # 指定设备
#   .\harness-connect.ps1 -DshPort 30984  # 手动指定电脑上的 DSH 端口（一般不用，自动检测）
#   .\harness-connect.ps1 -Stop           # 断开映射
#   .\harness-connect.ps1 -OpenTest       # 建立映射后再用手机浏览器打开 /state 做可视自检
#
# 注意：映射在 adb 服务/重启后会失效；DSH 重启换了端口时，重新运行本脚本即可。

param(
    [string]$Serial = "",
    [int]$Port = 3412,
    [int]$DshPort = 0,
    [string]$Adb = "D:\Android\Sdk\platform-tools\adb.exe",
    [switch]$Stop,
    [switch]$OpenTest
)

$ErrorActionPreference = "Continue"

function Fail($msg) {
    Write-Host "[harness-connect] 失败：$msg" -ForegroundColor Red
    exit 1
}

function Find-DshPort([int]$Hint = 0) {
    $pids = @((Get-Process -Name 'DeepSeek Harness' -ErrorAction SilentlyContinue).Id)
    $ports = @()
    if ($pids.Count -gt 0) {
        $ports = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $pids -contains $_.OwningProcess -and ($_.LocalAddress -eq '127.0.0.1' -or $_.LocalAddress -eq '0.0.0.0') } |
            Select-Object -ExpandProperty LocalPort -Unique)
    }
    $candidates = @()
    if ($Hint -gt 0) { $candidates += $Hint }
    $candidates += $ports
    $candidates += @(3412, 17838, 30984)
    foreach ($p in ($candidates | Select-Object -Unique)) {
        if (-not $p -or $p -le 0) { continue }
        try {
            $r = Invoke-WebRequest -Uri "http://127.0.0.1:$p/dsh-pet-7340/state" -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop
            if ($r.StatusCode -eq 200 -and ([string]$r.Content) -match '"sections"') { return [int]$p }
        } catch { }
    }
    return 0
}

if (-not (Test-Path $Adb)) { Fail "找不到 adb：$Adb（可用 -Adb 指定）" }

# 1) 选择设备
$devices = @()
$raw = & $Adb devices 2>$null
foreach ($line in $raw) {
    if ($line -match '^(\S+)\s+device\s*$') { $devices += $Matches[1] }
}
if ($Serial -ne "") {
    if ($devices -notcontains $Serial) { Fail "指定设备不在线：$Serial（当前：$($devices -join ', ')）" }
    $target = $Serial
} elseif ($devices.Count -eq 1) {
    $target = $devices[0]
} elseif ($devices.Count -eq 0) {
    Fail "没有已授权的 Android 设备（请插 USB 并允许 USB 调试）"
} else {
    $physical = @($devices | Where-Object { $_ -notmatch '^emulator-' })
    if ($physical.Count -eq 1) {
        $target = $physical[0]
        Write-Host "[harness-connect] 检测到多台设备，自动选择实体机：$target（其它：$((@($devices) | Where-Object { $_ -ne $target }) -join ', ')）" -ForegroundColor Yellow
    } else {
        Fail "检测到多个设备，请用 -Serial 指定其中之一：$($devices -join ', ')"
    }
}
Write-Host "[harness-connect] 设备：$target"

# 2) -Stop：移除反向映射
if ($Stop) {
    & $Adb -s $target reverse --remove tcp:$Port 2>$null | Out-Null
    $left = & $Adb -s $target reverse --list 2>$null
    if ($left -match "tcp:$Port") { Fail "移除失败，仍存在：$left" }
    Write-Host "[harness-connect] 已断开手机 -> 电脑的 $Port 反向映射（桌宠将回到未连接模式）" -ForegroundColor Yellow
    exit 0
}

# 3) 找电脑上当前正在运行的 DSH 端口（每次启动可能不同）
if ($DshPort -le 0) {
    Write-Host "[harness-connect] 正在检测电脑上的 DeepSeek Harness 端口…"
    $DshPort = Find-DshPort
}
if ($DshPort -le 0) {
    Fail "没有找到正在运行的 DeepSeek Harness（请先打开 DSH 桌面端，然后重新运行本脚本）"
}
$stateUrl = "http://127.0.0.1:$DshPort/dsh-pet-7340/state"
$ok = $false
$stateText = ""
try {
    $r = Invoke-WebRequest -Uri $stateUrl -TimeoutSec 4 -UseBasicParsing -ErrorAction Stop
    $stateText = [string]$r.Content
    if ($r.StatusCode -eq 200 -and $stateText -match '"sections"') { $ok = $true }
} catch { $ok = $false }
if (-not $ok) {
    Fail "电脑侧 DSH 已找到（端口 $DshPort）但 /dsh-pet-7340/state 不通，请确认「DeepSeek 桌宠」插件已安装/加载"
}
$balance = ""
try {
    $j = $stateText | ConvertFrom-Json
    $b = $j.sections.balance.data
    if ($b -and $b.ok -eq $true -and $b.data) { $balance = "余额 $($b.data.total)" }
} catch {}
Write-Host "[harness-connect] 电脑侧 DSH 正常（端口 $DshPort）$balance" -ForegroundColor Green

# 4) 建立 adb reverse：手机 127.0.0.1:$Port -> 电脑 127.0.0.1:$DshPort
Write-Host "[harness-connect] 建立反向映射：$target 127.0.0.1:$Port -> 电脑 127.0.0.1:$DshPort"
& $Adb -s $target reverse --remove tcp:$Port 2>$null | Out-Null
& $Adb -s $target reverse tcp:$Port tcp:$DshPort | Out-Null
$list = & $Adb -s $target reverse --list 2>$null
if (-not ($list -match "tcp:$Port")) { Fail "adb reverse 未生效：$list" }
Write-Host "[harness-connect] 反向映射已生效：" -ForegroundColor Green
$list | ForEach-Object { Write-Host "  $_" }

# 5) 手机侧自检（尽力而为：手机上如果有 curl 就验证一次）
try {
    $code = & $Adb -s $target shell "curl -s -m 4 -o /dev/null -w '%{http_code}' http://127.0.0.1:$Port/dsh-pet-7340/state" 2>$null
    if ($code -match '200') {
        Write-Host "[harness-connect] 手机侧自检通过：手机浏览器/App 访问 127.0.0.1:$Port 已能看到真实 JSON" -ForegroundColor Green
    } else {
        Write-Host "[harness-connect] 手机侧 curl 自检未通过/不可用（不影响 App，App 内点「测试连接」为准）" -ForegroundColor Yellow
    }
} catch {
    Write-Host "[harness-connect] 手机侧 curl 不可用，跳过自检（不影响 App 连接）" -ForegroundColor Yellow
}

# 6) 手机侧可视自检（可选）
if ($OpenTest) {
    Write-Host "[harness-connect] 用手机浏览器打开 http://127.0.0.1:$Port/dsh-pet-7340/state 做可视自检（能看到 JSON 即通）…"
    & $Adb -s $target shell am start -a android.intent.action.VIEW -d "http://127.0.0.1:$Port/dsh-pet-7340/state" | Out-Null
    Start-Sleep -Seconds 2
}

# 7) 下一步指引
Write-Host ""
Write-Host "下一步（手机 App 内）：" -ForegroundColor Cyan
Write-Host "  1. 打开「DeepSeek 桌宠」→ 找到「连接电脑 DeepSeek Harness」卡片"
Write-Host "  2. 地址填：http://127.0.0.1:$Port（固定值，不用管电脑上的端口号）"
Write-Host "  3. 点「保存并连接 / 测试连接」→ 显示已连接后，桌宠会重启并点亮碎碎念/对话/余额/工作状态。"
Write-Host ""
Write-Host "提示：拔数据线、重启 adb、重启电脑或重启 DSH 后，重新运行本脚本即可。"
Write-Host "Wi-Fi 方案（不插线）：电脑上双击 tools\harness-wifi-proxy.bat，"
Write-Host "  手机 App 地址填 http://电脑局域网IP:3413（代理会自动跟随 DSH 端口变化）。"
