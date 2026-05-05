@echo off
echo ========================================
echo MetricMonitor - Starting Collector Daemon
echo ========================================
echo.

if not exist venv (
    echo Virtual environment not found. Please run setup.bat first.
    exit /b 1
)

call venv\Scripts\activate.bat

echo Starting metric collector daemon...
echo.

python collector_daemon.py
