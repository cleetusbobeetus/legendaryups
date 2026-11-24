@echo off
setlocal
cd /d "%~dp0"

if not defined JDK_BIN set "JDK_BIN=C:\Users\Zane\Downloads\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8\bin"
set "LIB=..\libraries"
set "PAPER=%LIB%\io\papermc\paper\paper-api\1.21.10-R0.1-SNAPSHOT\paper-api-1.21.10-R0.1-SNAPSHOT.jar"
set "CP=%PAPER%"
for /r "%LIB%" %%J in (*adventure*.jar *kyori*.jar *bungeecord-chat*.jar *examination*.jar) do call set "CP=%%CP%%;%%J"

if exist build rmdir /s /q build
mkdir build\test-classes build\classes

echo == 1. Ledger + acceptance tests
"%JDK_BIN%\javac.exe" --release 21 -d build\test-classes src\gg\legendaryups\punchqueue\PunchLedger.java src\gg\legendaryups\punchqueue\Pacer.java test\gg\legendaryups\punchqueue\PunchLedgerTest.java || goto :fail
"%JDK_BIN%\java.exe" -cp build\test-classes gg.legendaryups.punchqueue.PunchLedgerTest "%TEMP%" || goto :fail

echo == 2. Plugin compile against Paper API
"%JDK_BIN%\javac.exe" --release 21 -nowarn -cp "%CP%" -d build\classes src\gg\legendaryups\punchqueue\PunchLedger.java src\gg\legendaryups\punchqueue\Pacer.java src\gg\legendaryups\punchqueue\PunchQueuePlugin.java || goto :fail
copy /y plugin.yml build\classes\ >nul
copy /y config.yml build\classes\ >nul

echo == 3. Package jar
"%JDK_BIN%\jar.exe" cf build\PunchQueue-2.0.0.jar -C build\classes . || goto :fail

echo.
echo BUILD OK: %~dp0build\PunchQueue-2.0.0.jar
endlocal
exit /b 0

:fail
echo.
echo BUILD FAILED
endlocal
exit /b 1
