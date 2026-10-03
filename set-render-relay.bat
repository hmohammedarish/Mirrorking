@echo off
setlocal enabledelayedexpansion
title MirrorKing - Configure Render.com Cloud Relay

echo ========================================================
echo       MirrorKing - Configure Render Cloud Relay
echo ========================================================
echo.

set "URL=%~1"
if "%URL%"=="" (
    set /p "URL=Enter your Render URL (e.g. https://my-relay.onrender.com): "
)

if "%URL%"=="" (
    echo [ERROR] No URL provided. Aborting.
    pause
    exit /b 1
)

:: Clean the URL (remove https://, wss://, trailing slashes)
set "CLEAN_HOST=%URL%"
set "CLEAN_HOST=%CLEAN_HOST:https://=%"
set "CLEAN_HOST=%CLEAN_HOST:http://=%"
set "CLEAN_HOST=%CLEAN_HOST:wss://=%"
set "CLEAN_HOST=%CLEAN_HOST:ws://=%"
if "%CLEAN_HOST:~-1%"=="/" set "CLEAN_HOST=%CLEAN_HOST:~0,-1%"

echo [1/3] Saving Cloud Relay URL for PC...
echo wss://%CLEAN_HOST%> "%~dp0render_url.txt"
echo       Saved to: render_url.txt (wss://%CLEAN_HOST%)

set "ADB=e:\SECUREADY\tools\android-sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=C:\Users\hshak\Desktop\scrcpy-win64-v3.3.4\scrcpy-win64-v3.3.4\adb.exe"

echo [2/3] Programming phone to connect to Render on 4G/5G...
"%ADB%" shell "am start -n com.secuready.mirrorking/.MainActivity --es host '%CLEAN_HOST%'" >nul 2>&1
if %errorlevel% equ 0 (
    echo       [SUCCESS] Phone configured! Guardian APK will now connect to: %CLEAN_HOST%
) else (
    echo       [NOTE] Phone not currently detected over USB. Make sure USB is connected once to sync,
    echo              or the phone will connect on next reboot.
)

echo.
echo [3/3] Configuration Complete!
echo ========================================================
echo  From now on, you can unplug the USB cable anytime.
echo  Your phone will stream over 4G/5G Mobile Data through:
echo    wss://%CLEAN_HOST%
echo ========================================================
echo.
pause
