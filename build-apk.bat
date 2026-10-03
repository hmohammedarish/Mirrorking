@echo off
setlocal
echo ==============================================
echo     Building MirrorKing Ultra-Light APK
echo ==============================================

set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
set "SDK=e:\SECUREADY\tools\android-sdk"
set "PATH=%JAVA_HOME%\bin;%SDK%\build-tools\34.0.0;%PATH%"

if not exist bin mkdir bin
if not exist bin\classes mkdir bin\classes
if not exist android\gen mkdir android\gen

echo [1/5] Compiling resources with aapt2...
aapt2 compile --dir android\res -o bin\res.zip
if %errorlevel% neq 0 ( echo Error in aapt2 compile & exit /b 1 )

echo [2/5] Linking resources and generating R.java...
aapt2 link -I "%SDK%\platforms\android-34\android.jar" bin\res.zip --manifest android\AndroidManifest.xml -o bin\app.unsigned.apk --java android\gen --auto-add-overlay
if %errorlevel% neq 0 ( echo Error in aapt2 link & exit /b 1 )

echo [3/5] Compiling Java classes with javac...
del /q /s bin\classes\* >nul 2>&1
javac -source 8 -target 8 -cp "%SDK%\platforms\android-34\android.jar;android\gen" -d bin\classes android\src\com\secuready\mirrorking\*.java android\gen\com\secuready\mirrorking\*.java
if %errorlevel% neq 0 ( echo Error in javac & exit /b 1 )

echo [4/5] Dexing with d8...
powershell -NoProfile -Command "$classes = (Get-ChildItem -Recurse -Filter '*.class' bin\classes).FullName; & '%SDK%\build-tools\34.0.0\d8.bat' --lib '%SDK%\platforms\android-34\android.jar' --output bin $classes"
if %errorlevel% neq 0 ( echo Error in d8 & exit /b 1 )

echo [5/5] Packaging, aligning and signing APK...
jar -uf bin\app.unsigned.apk -C bin classes.dex
zipalign -p -f 4 bin\app.unsigned.apk bin\app.aligned.apk
call apksigner.bat sign --ks "e:\SECUREADY\Mirrorking\debug.keystore" --ks-pass pass:android --key-pass pass:android --out bin\MirrorKing.apk bin\app.aligned.apk
if %errorlevel% neq 0 ( echo Error in apksigner & exit /b 1 )

echo.
echo ==============================================
echo [SUCCESS] APK built: bin\MirrorKing.apk
echo ==============================================
