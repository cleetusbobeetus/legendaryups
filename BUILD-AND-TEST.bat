@echo off
REM Quick automation: Build plugin → Sync to test server → Run tests
setlocal enabledelayedexpansion

echo.
echo ╔══════════════════════════════════════════════════════════════╗
echo ║   LEGENDARY UPS - BUILD, SYNC & TEST (4 New Level Actions)   ║
echo ╚══════════════════════════════════════════════════════════════╝
echo.

set PLUGIN_DIR=..\..\..\LegendaryUps-v0.6.1\LegendaryUps
set PLUGIN_SOURCE=%PLUGIN_DIR%\plugin\dist\LegendaryUpsBridge-*.jar
set PLUGINS_FOLDER=%cd%\plugins

echo 📦 Step 1: Building plugin from source...
echo    Location: %PLUGIN_DIR%
echo.
cd /d "%PLUGIN_DIR%"
call BUILD-PLUGIN.bat
if errorlevel 1 (
  echo.
  echo ❌ Plugin build failed!
  pause
  exit /b 1
)

echo.
echo ✅ Plugin built successfully
echo.

echo 🔄 Step 2: Syncing JAR to test server plugins folder...
set LATEST_JAR=
for /f "tokens=*" %%A in ('powershell -Command "Get-ChildItem '%PLUGIN_DIR%\plugin\dist\LegendaryUpsBridge-*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName"') do set LATEST_JAR=%%A

if "%LATEST_JAR%"=="" (
  echo ❌ No JAR file found in plugin/dist/
  pause
  exit /b 1
)

copy /y "%LATEST_JAR%" "%PLUGINS_FOLDER%\" >nul
if errorlevel 1 (
  echo ❌ Failed to copy JAR to plugins folder
  pause
  exit /b 1
)

echo ✅ Synced: %LATEST_JAR%
echo    To: %PLUGINS_FOLDER%
echo.

echo 📋 Step 3: Next Steps
echo    ════════════════════════════════════════════════════════════
echo.
echo    ⚠️  IMPORTANT: The Paper server needs to reload the plugin
echo.
echo    Option A - RELOAD (no restart needed):
echo       In-game, run: /reload confirm
echo.
echo    Option B - RESTART (full server restart):
echo       Stop the server and start it again
echo.
echo    After reload/restart, test with:
echo.
echo       /lub test levelup      (Test Level Up action)
echo       /lub test leveldown    (Test Level Down action)
echo       /lub test level        (Test raw level effect)
echo.
echo ════════════════════════════════════════════════════════════
echo.

pause
exit /b 0
