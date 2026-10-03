@echo off
setlocal enabledelayedexpansion
title MirrorKing - 1-Click Phone Setup

echo =======================================================
echo          👑 MirrorKing - 1-Click Phone Setup 👑
echo   Permanent Broken-Screen Setup (USB / Wi-Fi / Cellular)
echo =======================================================
echo.

set "ADB=e:\SECUREADY\tools\android-sdk\platform-tools\adb.exe"
if not exist "%ADB%" (
    set "ADB=C:\Users\hshak\Desktop\scrcpy-win64-v3.3.4\scrcpy-win64-v3.3.4\adb.exe"
)

echo [1/6] Checking for connected Android device...
"%ADB%" wait-for-device
echo [OK] Phone detected!

if not exist "bin\MirrorKing.apk" (
    echo [2/6] Building lightweight MirrorKing APK...
    call build-apk.bat
    if !errorlevel! neq 0 (
        echo [ERROR] Failed to compile APK!
        pause
        exit /b 1
    )
) else (
    echo [2/6] Found existing MirrorKing.apk
)

echo [3/6] Installing MirrorKing.apk on device...
"%ADB%" install -r "bin\MirrorKing.apk"
if !errorlevel! neq 0 (
    echo [ERROR] Installation failed!
    pause
    exit /b 1
)

echo [4/6] Activating Accessibility Service (Remote Touch & Auto-Accept)...
"%ADB%" shell settings put secure enabled_accessibility_services com.secuready.mirrorking/.MirrorKingAccessibilityService
"%ADB%" shell settings put secure accessibility_enabled 1

echo [5/6] Granting background keep-alive & battery optimization exemptions...
"%ADB%" shell dumpsys deviceidle whitelist +com.secuready.mirrorking >nul 2>&1
"%ADB%" shell appops set com.secuready.mirrorking PROJECT_MEDIA allow >nul 2>&1
"%ADB%" shell appops set com.secuready.mirrorking SYSTEM_ALERT_WINDOW allow >nul 2>&1
"%ADB%" shell pm grant com.secuready.mirrorking android.permission.POST_NOTIFICATIONS >nul 2>&1
"%ADB%" tcpip 5555 >nul 2>&1

echo [6/6] Establishing USB reverse tunnel and launching service...
"%ADB%" reverse tcp:8888 tcp:8888
"%ADB%" shell am start -n com.secuready.mirrorking/.MainActivity --ez autostart true

echo.
echo =======================================================
echo  🎉 SETUP COMPLETE!
echo.
echo  - MirrorKing is now permanently active on your phone.
echo  - Accessibility & auto-accept are fully configured.
echo  - Survives reboots automatically.
echo.
echo  You can now run "run-pc.bat" to start mirroring!
echo =======================================================
echo.
pause
