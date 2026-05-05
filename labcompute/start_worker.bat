@echo off
echo Starting Celery Worker for LabCompute...
echo.

call venv\Scripts\activate.bat

echo [1/1] Starting Celery worker...
celery -A app.config.celery_config worker --loglevel=info -P solo
