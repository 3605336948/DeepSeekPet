# DeepSeek 桌宠（手机版）构建脚本 —— 必须用 JDK 17（系统默认是 JDK 8，不能直接用）
#
# 用法：
#   .\build.ps1              # 默认 assembleRelease（优先 --offline，失败再联网）
#   .\build.ps1 -Task assembleDebug -Online
#   产物：android\app\build\outputs\apk\release\app-release.apk
#
# 说明：
#  - assets 源目录含 ..\..\web（T1 的 dsh-pet 移动版资源包）与 src\main\assets（仅调试占位页）；
#  - release 用工程根目录的自签名 release.keystore（密码见 gradle.properties）；
#  - 所有产物/缓存都在 D 盘（用户 C 盘空间紧张）。

param(
    [string]$Task = "assembleRelease",
    [switch]$Online
)

$ErrorActionPreference = "Stop"

$JdkHome = "C:\Program Files (x86)\Android\openjdk\jdk-17.0.14"
if (-not (Test-Path "$JdkHome\bin\java.exe")) {
    throw "找不到 JDK 17：$JdkHome"
}
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$env:PATH"

# Gradle 用户目录固定到 D 盘（C 盘空间紧张；如需沿用 C 盘已有缓存可注释掉下面两行）
# $env:GRADLE_USER_HOME = "D:\AndroidProjects\DeepSeekPet\.gradle-home"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir

Write-Host "== DeepSeek Pet Android build ==" -ForegroundColor Cyan
Write-Host "JAVA_HOME = $env:JAVA_HOME"
Write-Host "Task      = $Task"
Write-Host "Project   = $ProjectDir"
Write-Host ""

if (-not $Online) {
    Write-Host ">> gradlew --offline $Task" -ForegroundColor Yellow
    & .\gradlew.bat --offline $Task --console=plain
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "BUILD OK (offline)" -ForegroundColor Green
        Get-ChildItem "app\build\outputs\apk\release\*.apk", "app\build\outputs\apk\debug\*.apk" -ErrorAction SilentlyContinue |
            ForEach-Object { Write-Host ("  " + $_.FullName + "  (" + [math]::Round($_.Length / 1MB, 2) + " MB)") }
        exit 0
    }
    Write-Host "离线构建失败（exit $LASTEXITCODE），尝试联网构建…" -ForegroundColor Yellow
}

Write-Host ">> gradlew $Task" -ForegroundColor Yellow
& .\gradlew.bat $Task --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "BUILD FAILED (exit $LASTEXITCODE)" -ForegroundColor Red
    exit $LASTEXITCODE
}
Write-Host ""
Write-Host "BUILD OK" -ForegroundColor Green
Get-ChildItem "app\build\outputs\apk\release\*.apk", "app\build\outputs\apk\debug\*.apk" -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host ("  " + $_.FullName + "  (" + [math]::Round($_.Length / 1MB, 2) + " MB)") }
