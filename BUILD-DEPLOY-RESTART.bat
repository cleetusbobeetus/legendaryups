@echo off
REM Automatic: Build, Deploy, Restart
setlocal enabledelayedexpansion

echo.
echo === BUILD + DEPLOY + RESTART ===
echo.

set PLUGIN_SOURCE=..\..\..\LegendaryUps-v0.6.1\LegendaryUps
set PLUGINS_FOLDER=%cd%\plugins

echo [1/4] Building plugin...
cd /d "%PLUGIN_SOURCE%"
call BUILD-PLUGIN.bat >nul 2>&1
if errorlevel 1 (
  echo ERROR: Build failed!
  pause
  exit /b 1
)
echo OK: Build successful

echo [2/4] Copying JAR...
for /f "tokens=*" %%A in ('powershell -Command "Get-ChildItem '%PLUGIN_SOURCE%\plugin\dist\LegendaryUpsBridge-*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName"') do set LATEST_JAR=%%A
if "!LATEST_JAR!"=="" (
  echo ERROR: No JAR found!
  pause
  exit /b 1
)
copy /y "!LATEST_JAR!" "%PLUGINS_FOLDER%\" >nul
echo OK: Copied to plugins

echo [3/4] Stopping server...
cd /d "%PLUGINS_FOLDER%\.."
taskkill /IM java.exe /F >nul 2>&1
timeout /t 3 /nobreak >nul
echo OK: Server stopped

echo [4/4] Starting server...
start "" "%cd%\paper.jar"
timeout /t 10 /nobreak >nul
echo OK: Server restarted

echo.
echo READY! Join and test.
echo.
pause
