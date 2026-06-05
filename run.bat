@echo off
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

set ENV_FILE=.env
if not exist "%ENV_FILE%" (
    if exist ".env.example" (
        echo ⚠️  .env file not found, copying from .env.example
        copy .env.example .env
    ) else (
        echo ❌ .env file not found and .env.example not available
        exit /b 1
    )
)

set PYTHONPATH=%SCRIPT_DIR%;%PYTHONPATH%

if exist "venv\Scripts\activate.bat" (
    echo ✅ Activating virtual environment
    call venv\Scripts\activate.bat
) else (
    echo ⚠️  Virtual environment not found, using system Python
)

set COMMAND=%1
if "%COMMAND%"=="" set COMMAND=api

if "%COMMAND%"=="api" (
    echo 🚀 Starting FastAPI server...
    python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
) else if "%COMMAND%"=="worker" (
    echo 🚀 Starting Celery worker...
    celery -A app.tasks.celery_app worker --loglevel=info -Q high_priority,default,batch --concurrency=4 --pool=solo
) else if "%COMMAND%"=="worker-high" (
    echo 🚀 Starting Celery worker (high priority)...
    celery -A app.tasks.celery_app worker --loglevel=info -Q high_priority --concurrency=2 --pool=solo
) else if "%COMMAND%"=="worker-batch" (
    echo 🚀 Starting Celery worker (batch)...
    celery -A app.tasks.celery_app worker --loglevel=info -Q batch --concurrency=2 --pool=solo
) else if "%COMMAND%"=="beat" (
    echo 🚀 Starting Celery beat...
    celery -A app.tasks.celery_app beat --loglevel=info
) else if "%COMMAND%"=="flower" (
    echo 🚀 Starting Celery flower...
    celery -A app.tasks.celery_app flower --port=5555
) else if "%COMMAND%"=="init-db" (
    echo 🔧 Initializing database...
    python scripts\init_db.py
) else (
    echo Usage: %~nx0 {api^|worker^|worker-high^|worker-batch^|beat^|flower^|init-db}
    exit /b 1
)

endlocal
