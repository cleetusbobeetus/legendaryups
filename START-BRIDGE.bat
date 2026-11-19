@echo off
rem Starts the TikFinity -> Minecraft RCON bridge on 127.0.0.1:4567.
rem The Minecraft server must already be running (RCON on 127.0.0.1:25577).
setlocal
title TikFinity RCON Bridge
cd /d "%~dp0tools\tikfinity-bridge" || (echo Could not open the bridge folder. & pause & exit /b 1)

where node >nul 2>nul
if errorlevel 1 (
  echo Node.js was not found. Install the LTS version from https://nodejs.org and run this again.
  pause
  exit /b 1
)

echo Checking RCON first...
node bridge.js --check
if errorlevel 1 (
  echo.
  echo RCON check failed. Is the Minecraft server running with enable-rcon=true?
  echo The bridge will still start and retry automatically, so press Ctrl+C to quit or wait.
  echo.
)

node bridge.js
echo.
echo Bridge stopped.
pause
