@echo off
setlocal
chcp 65001 >nul
title DeepSeek Pet - Wi-Fi proxy (keep this window open)
echo ============================================
echo   DeepSeek Pet - Wi-Fi connection proxy
echo   Same Wi-Fi on phone and PC, then keep this window open.
echo ============================================
echo.
set "NODE_EXE=node"
where node >nul 2>nul
if errorlevel 1 set "NODE_EXE=%USERPROFILE%\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe"
echo Node: %NODE_EXE%
echo Close this window to stop the proxy.
echo.
"%NODE_EXE%" "%~dp0harness-wifi-proxy.js" %*
echo.
echo Proxy exited (exit code %errorlevel%). If there is an error above, take a screenshot.
pause