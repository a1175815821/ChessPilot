@echo off
REM ChessPilot-PC server launcher
title ChessPilot-PC engine server
cd /d %~dp0
where py >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Python launcher 'py' not found.
  goto :stay
)
echo Starting ChessPilot-PC engine server...
echo Keep this window open while playing.
echo.
py -3 pc_engine.py --threads 32 --hash 4096 --movetime 3000
echo.
echo [INFO] Server exited with code %errorlevel%.
:stay
echo Press any key to close this window.
pause >nul