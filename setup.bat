@echo off
echo ========================================
echo MetricMonitor - System Monitoring Platform
echo ========================================
echo.

if not exist venv (
    echo Creating virtual environment...
    python -m venv venv
    if errorlevel 1 (
        echo Failed to create virtual environment
        exit /b 1
    )
)

echo Activating virtual environment...
call venv\Scripts\activate.bat

echo Installing dependencies...
pip install -r requirements.txt -q

echo.
echo ========================================
echo Setup complete!
echo.
echo Next steps:
echo 1. Configure InfluxDB in config.json
echo 2. Configure notification channels in config.json
echo 3. Run 'start.bat' to start the server
echo ========================================
