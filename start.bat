@echo off
echo ========================================
echo MetricMonitor - Starting Server
echo ========================================
echo.

if not exist venv (
    echo Virtual environment not found. Please run setup.bat first.
    exit /b 1
)

call venv\Scripts\activate.bat

echo Starting API server...
echo Server will be available at http://localhost:5000
echo.

python run.py
