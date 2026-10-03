@echo off
setlocal
title MirrorKing - Native Screen Mirror

set "SCRCPY_DIR=C:\Users\hshak\Desktop\scrcpy-win64-v3.3.4\scrcpy-win64-v3.3.4"
set "ADB=e:\SECUREADY\tools\android-sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=%SCRCPY_DIR%\adb.exe"

set "RENDER_URL="
if exist "%~dp0render_url.txt" set /p RENDER_URL=<"%~dp0render_url.txt"

:connect_loop
cls
echo ========================================================
echo        MirrorKing - Native Screen Mirror
echo    Auto-Healing Engine * Zero-Alerts
if defined RENDER_URL echo    Cloud Relay: %RENDER_URL%
echo ========================================================
echo.

echo [1/2] Checking connection...
"%ADB%" disconnect >nul 2>&1

echo [2/2] Launching Screen Mirror...
"%SCRCPY_DIR%\scrcpy.exe" -d --no-audio -m 1600 -b 8M --video-codec=h264 --stay-awake --window-title="MirrorKing - Samsung Galaxy S22"
if %errorlevel% equ 0 (
    echo.
    echo [MirrorKing] Mirror closed normally.
    exit /b 0
)

echo.
echo [ROUTE] USB not active. Connecting over Wireless / Cellular Tunnel...
if defined RENDER_URL (
    powershell -NoProfile -Command "if (-not (Get-Process node -ErrorAction SilentlyContinue)) { Start-Process node -ArgumentList 'pc\server.js --relay %RENDER_URL%' -WorkingDirectory 'e:\SECUREADY\Mirrorking' -WindowStyle Hidden; Start-Sleep -Seconds 1 }"
) else (
    powershell -NoProfile -Command "if (-not (Get-Process node -ErrorAction SilentlyContinue)) { Start-Process node -ArgumentList 'pc\server.js' -WorkingDirectory 'e:\SECUREADY\Mirrorking' -WindowStyle Hidden; Start-Sleep -Seconds 1 }"
)
"%ADB%" connect 127.0.0.1:7777 >nul 2>&1
"%SCRCPY_DIR%\scrcpy.exe" -s 127.0.0.1:7777 --no-audio --force-adb-forward -m 1600 -b 4M --video-codec=h264 --stay-awake --window-title="MirrorKing - Samsung Galaxy S22 (Wireless/Cellular)"
if %errorlevel% equ 0 (
    echo.
    echo [MirrorKing] Mirror closed normally.
    exit /b 0
)

echo.
echo [RECONNECT] Connection ended or cable shifted. Re-establishing in 2 seconds...
timeout /t 2 >nul
goto :connect_loop
