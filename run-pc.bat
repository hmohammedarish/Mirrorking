@echo off
setlocal
title MirrorKing PC Client

set "ADB=e:\SECUREADY\tools\android-sdk\platform-tools\adb.exe"
if not exist "%ADB%" (
    set "ADB=C:\Users\hshak\Desktop\scrcpy-win64-v3.3.4\scrcpy-win64-v3.3.4\adb.exe"
)

echo [MirrorKing] Checking USB connection...
"%ADB%" reverse tcp:8888 tcp:8888 >nul 2>&1

echo [MirrorKing] Starting Background Tunnel Server...
powershell -NoProfile -Command "if (-not (Get-Process node -ErrorAction SilentlyContinue)) { Start-Process node -ArgumentList 'pc\server.js' -WorkingDirectory '%~dp0' -WindowStyle Hidden; Start-Sleep -Seconds 1 }"

echo [MirrorKing] Launching 60 FPS Zero-Alert Screen Mirror...
start "" "%~dp0launch-scrcpy.bat"

echo.
echo ========================================================
echo   MirrorKing is ACTIVE!
echo   * 60 FPS Mirror: Running (Hardware Direct3D, Zero Alerts)
echo   * Web Rescue UI: http://localhost:3000
echo ========================================================
echo.
timeout /t 5 >nul
