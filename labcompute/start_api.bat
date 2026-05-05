@echo off
echo Starting LabCompute API Server...
echo.

call venv\Scripts\activate.bat

echo [1/1] Starting FastAPI server...
uvicorn app.api.main:app --reload --host 0.0.0.0 --port 8000
