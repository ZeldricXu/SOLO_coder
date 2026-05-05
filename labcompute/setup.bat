@echo off
echo ========================================
echo LabCompute - Scientific Computation Platform
echo ========================================
echo.

echo [1/4] Checking Python environment...
python --version
if errorlevel 1 (
    echo ERROR: Python not found. Please install Python 3.9+.
    exit /b 1
)

echo.
echo [2/4] Creating virtual environment...
python -m venv venv
if errorlevel 1 (
    echo ERROR: Failed to create virtual environment.
    exit /b 1
)

echo.
echo [3/4] Activating virtual environment...
call venv\Scripts\activate.bat

echo.
echo [4/4] Installing dependencies...
pip install -r requirements.txt
if errorlevel 1 (
    echo ERROR: Failed to install dependencies.
    exit /b 1
)

echo.
echo ========================================
echo Setup complete!
echo ========================================
echo.
echo Next steps:
echo 1. Start Redis server
echo 2. Start PostgreSQL server and create database 'labcompute'
echo 3. Copy .env.example to .env and configure settings
echo 4. Start Celery worker: celery -A app.config.celery_config worker --loglevel=info
echo 5. Start API server: uvicorn app.api.main:app --reload --host 0.0.0.0 --port 8000
echo.
