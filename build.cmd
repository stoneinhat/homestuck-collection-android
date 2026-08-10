@echo off
REM Build the web bundle, stage it, build the APK.
REM Needs Node 18, yarn, JDK 17, Android SDK 34, Gradle 8.7 on PATH,
REM or set TOOLS to a folder that holds them.
setlocal
cd /d %~dp0

if not exist base\package.json (
  echo No base\. Run setup.cmd first.
  exit /b 1
)

REM Note: no parentheses here. Inside an (...) block %VAR% expands before
REM set /p runs, which leaves ANDROID_KEY_PASSWORD empty and breaks signing.
if not exist keys\release.keystore goto :nokeys
set ANDROID_KEYSTORE_PATH=%~dp0keys\release.keystore
set /p ANDROID_KEYSTORE_PASSWORD=<%~dp0keys\keystore_password.txt
set ANDROID_KEY_ALIAS=tuhc
set ANDROID_KEY_PASSWORD=%ANDROID_KEYSTORE_PASSWORD%
:nokeys

cd base
if not exist node_modules call yarn install --frozen-lockfile --ignore-engines
if errorlevel 1 exit /b 1
node build\genModTrees.js
set ASSET_PACK_HREF=/assetpack/
set NODE_OPTIONS=--max_old_space_size=8192
node node_modules\@vue\cli-service\bin\vue-cli-service.js build webapp\browser.js
if errorlevel 1 exit /b 1
cd ..

rmdir /s /q android\app\src\main\assets 2>nul
mkdir android\app\src\main\assets
xcopy /e /i /q base\dist android\app\src\main\assets\www >nul
xcopy /e /i /q base\src\imods android\app\src\main\assets\imods >nul

cd android
call gradle assembleRelease
if errorlevel 1 exit /b 1

echo.
echo APK: %~dp0android\app\build\outputs\apk\release\app-release.apk
