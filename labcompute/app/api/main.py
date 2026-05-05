from fastapi import FastAPI, UploadFile, File, HTTPException, Query, BackgroundTasks
from fastapi.responses import FileResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Dict, Any, Optional, List
import uuid
import os
from pathlib import Path
from datetime import datetime

from app.config.settings import settings
from app.modules.storage import StorageModule, StorageError, TaskNotFoundError
from app.modules.data_parser import DataParser, DataParserError
from app.modules.report_generator import ReportGenerator, ReportGeneratorError
from app.tasks.compute_tasks import submit_task, get_task_status
from app.core.database import init_db

app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    description="LabCompute - Scientific Experiment Data Batch Processing and Simulation Platform"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class ComputeTaskRequest(BaseModel):
    task_type: str
    input_data: Dict[str, Any]
    priority: Optional[int] = None

class FileParseRequest(BaseModel):
    file_format: Optional[str] = None

class GenerateReportRequest(BaseModel):
    task_id: str
    include_charts: Optional[bool] = True

class TaskResponse(BaseModel):
    code: int
    data: Dict[str, Any]

class ErrorResponse(BaseModel):
    code: int
    error: str
    message: str

storage = StorageModule()
data_parser = DataParser()
report_generator = ReportGenerator()

@app.on_event("startup")
def startup_event():
    try:
        init_db()
    except Exception as e:
        print(f"Warning: Failed to initialize database: {e}")

@app.get("/")
def root():
    return {
        "name": settings.PROJECT_NAME,
        "version": settings.VERSION,
        "status": "running",
        "timestamp": datetime.utcnow().isoformat()
    }

@app.get("/health")
def health_check():
    return {
        "status": "healthy",
        "timestamp": datetime.utcnow().isoformat()
    }

@app.post("/api/v1/compute/submit", response_model=TaskResponse)
def submit_compute_task(request: ComputeTaskRequest):
    valid_task_types = [
        'matrix_multiply', 'matrix_inverse', 'matrix_eigenvalues',
        'matrix_transpose', 'matrix_add',
        'ode_solve',
        'stats_descriptive', 'stats_regression', 'stats_ttest',
        'stats_correlation', 'stats_distribution'
    ]
    
    if request.task_type not in valid_task_types:
        raise HTTPException(
            status_code=400,
            detail={
                "code": 400,
                "error": "InvalidTaskType",
                "message": f"Invalid task type: {request.task_type}. Valid types: {valid_task_types}"
            }
        )
    
    try:
        task_id = submit_task(
            task_type=request.task_type,
            input_data=request.input_data,
            priority=request.priority
        )
        
        estimated_time = estimate_task_time(request.task_type, request.input_data)
        
        return TaskResponse(
            code=200,
            data={
                "task_id": task_id,
                "estimated_time": estimated_time
            }
        )
        
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail={
                "code": 500,
                "error": "TaskSubmissionError",
                "message": str(e)
            }
        )

@app.get("/api/v1/compute/status")
def get_task_status_api(task_id: str = Query(..., description="The task ID to check")):
    try:
        task = storage.get_task(task_id)
        
        if task is None:
            raise HTTPException(
                status_code=404,
                detail={
                    "code": 404,
                    "error": "TaskNotFound",
                    "message": f"Task not found: {task_id}"
                }
            )
        
        result = storage.get_result(task_id=task_id)
        
        response_data = {
            "task_id": task_id,
            "status": task["status"],
            "progress": task["progress"],
            "created_at": task["created_at"],
            "started_at": task["started_at"],
            "completed_at": task["completed_at"],
            "error_message": task["error_message"]
        }
        
        if result:
            response_data["output_data"] = result["output_data"]
            response_data["computed_at"] = result["computed_at"]
            response_data["execution_time_seconds"] = result["execution_time_seconds"]
        
        return TaskResponse(
            code=200,
            data=response_data
        )
        
    except TaskNotFoundError:
        raise HTTPException(
            status_code=404,
            detail={
                "code": 404,
                "error": "TaskNotFound",
                "message": f"Task not found: {task_id}"
            }
        )
    except StorageError as e:
        raise HTTPException(
            status_code=500,
            detail={
                "code": 500,
                "error": "StorageError",
                "message": str(e)
            }
        )

@app.get("/api/v1/compute/result")
def get_compute_result(task_id: str = Query(..., description="The task ID to get result for")):
    try:
        task = storage.get_task(task_id)
        
        if task is None:
            raise HTTPException(
                status_code=404,
                detail={
                    "code": 404,
                    "error": "TaskNotFound",
                    "message": f"Task not found: {task_id}"
                }
            )
        
        result = storage.get_result(task_id=task_id)
        
        response_data = {
            "status": task["status"],
            "progress": task["progress"],
            "error_message": task["error_message"]
        }
        
        if result:
            response_data["output_data"] = result["output_data"]
            response_data["computed_at"] = result["computed_at"]
            response_data["execution_time_seconds"] = result["execution_time_seconds"]
        
        return TaskResponse(
            code=200,
            data=response_data
        )
        
    except StorageError as e:
        raise HTTPException(
            status_code=500,
            detail={
                "code": 500,
                "error": "StorageError",
                "message": str(e)
            }
        )

@app.post("/api/v1/data/parse")
async def parse_data_file(
    file: UploadFile = File(...),
    file_format: Optional[str] = Query(None, description="File format (csv/json)")
):
    try:
        file_ext = Path(file.filename).suffix.lower().lstrip('.') if file.filename else None
        detected_format = file_format or file_ext
        
        if detected_format not in ['csv', 'json']:
            raise HTTPException(
                status_code=400,
                detail={
                    "code": 400,
                    "error": "UnsupportedFormat",
                    "message": f"Unsupported format: {detected_format}. Supported: csv, json"
                }
            )
        
        temp_dir = Path(settings.DATA_DIR) / "temp"
        temp_dir.mkdir(parents=True, exist_ok=True)
        
        temp_filename = f"{uuid.uuid4().hex}.{detected_format}"
        temp_path = temp_dir / temp_filename
        
        content = await file.read()
        with open(temp_path, 'wb') as f:
            f.write(content)
        
        parsed_data = data_parser.parse_file(temp_path)
        
        temp_path.unlink()
        
        serializable_data = {}
        for key, value in parsed_data.items():
            if key == 'numeric_arrays':
                serializable_data[key] = {k: v.tolist() for k, v in value.items()}
            else:
                serializable_data[key] = value
        
        return TaskResponse(
            code=200,
            data={
                "filename": file.filename,
                "format": parsed_data.get('format'),
                "metadata": parsed_data.get('metadata', {}),
                "record_count": len(parsed_data.get('records', [])),
                "numeric_fields": list(parsed_data.get('numeric_arrays', {}).keys()),
                "sample_records": parsed_data.get('records', [])[:5] if len(parsed_data.get('records', [])) > 0 else []
            }
        )
        
    except DataParserError as e:
        raise HTTPException(
            status_code=400,
            detail={
                "code": 400,
                "error": "ParseError",
                "message": str(e)
            }
        )
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail={
                "code": 500,
                "error": "FileProcessingError",
                "message": str(e)
            }
        )

@app.post("/api/v1/report/generate")
def generate_report(request: GenerateReportRequest):
    try:
        task = storage.get_task(request.task_id)
        
        if task is None:
            raise HTTPException(
                status_code=404,
                detail={
                    "code": 404,
                    "error": "TaskNotFound",
                    "message": f"Task not found: {request.task_id}"
                }
            )
        
        result = storage.get_result(task_id=request.task_id)
        
        report_path = report_generator.generate_report(
            task_data=task,
            result_data=result["output_data"] if result else None,
            include_charts=request.include_charts
        )
        
        report_filename = Path(report_path).name
        
        return TaskResponse(
            code=200,
            data={
                "task_id": request.task_id,
                "report_filename": report_filename,
                "report_path": report_path,
                "generated_at": datetime.utcnow().isoformat()
            }
        )
        
    except ReportGeneratorError as e:
        raise HTTPException(
            status_code=500,
            detail={
                "code": 500,
                "error": "ReportGenerationError",
                "message": str(e)
            }
        )
    except StorageError as e:
        raise HTTPException(
            status_code=500,
            detail={
                "code": 500,
                "error": "StorageError",
                "message": str(e)
            }
        )

@app.get("/api/v1/report/download/{filename}")
def download_report(filename: str):
    try:
        report_path = Path(settings.REPORTS_DIR) / filename
        
        if not report_path.exists():
            raise HTTPException(
                status_code=404,
                detail={
                    "code": 404,
                    "error": "ReportNotFound",
                    "message": f"Report not found: {filename}"
                }
            )
        
        return FileResponse(
            path=str(report_path),
            media_type="application/pdf",
            filename=filename
        )
        
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail={
                "code": 500,
                "error": "FileDownloadError",
                "message": str(e)
            }
        )

@app.get("/api/v1/tasks")
def list_tasks(
    status: Optional[str] = Query(None, description="Filter by status"),
    task_type: Optional[str] = Query(None, description="Filter by task type"),
    limit: int = Query(100, ge=1, le=1000, description="Number of tasks to return"),
    offset: int = Query(0, ge=0, description="Offset for pagination")
):
    try:
        tasks = storage.get_all_tasks(
            status=status,
            task_type=task_type,
            limit=limit,
            offset=offset
        )
        
        return TaskResponse(
            code=200,
            data={
                "tasks": tasks,
                "count": len(tasks),
                "limit": limit,
                "offset": offset
            }
        )
        
    except StorageError as e:
        raise HTTPException(
            status_code=500,
            detail={
                "code": 500,
                "error": "StorageError",
                "message": str(e)
            }
        )

@app.get("/api/v1/stats")
def get_statistics():
    try:
        stats = storage.get_task_statistics()
        
        return TaskResponse(
            code=200,
            data=stats
        )
        
    except StorageError as e:
        raise HTTPException(
            status_code=500,
            detail={
                "code": 500,
                "error": "StorageError",
                "message": str(e)
            }
        )

@app.delete("/api/v1/tasks/{task_id}")
def delete_task(task_id: str):
    try:
        task = storage.get_task(task_id)
        if task is None:
            raise HTTPException(
                status_code=404,
                detail={
                    "code": 404,
                    "error": "TaskNotFound",
                    "message": f"Task not found: {task_id}"
                }
            )
        
        storage.delete_task(task_id)
        
        return TaskResponse(
            code=200,
            data={
                "task_id": task_id,
                "deleted": True,
                "deleted_at": datetime.utcnow().isoformat()
            }
        )
        
    except TaskNotFoundError:
        raise HTTPException(
            status_code=404,
            detail={
                "code": 404,
                "error": "TaskNotFound",
                "message": f"Task not found: {task_id}"
            }
        )
    except StorageError as e:
        raise HTTPException(
            status_code=500,
            detail={
                "code": 500,
                "error": "StorageError",
                "message": str(e)
            }
        )

def estimate_task_time(task_type: str, input_data: Dict[str, Any]) -> str:
    if task_type.startswith('matrix_'):
        matrix = input_data.get('matrix')
        matrix_a = input_data.get('matrix_a')
        
        size = 0
        if matrix:
            if isinstance(matrix, list) and len(matrix) > 0:
                size = len(matrix)
        if matrix_a:
            if isinstance(matrix_a, list) and len(matrix_a) > 0:
                size = len(matrix_a)
        
        if size <= 100:
            return "1s"
        elif size <= 500:
            return "5s"
        else:
            return "30s"
    
    elif task_type == 'ode_solve':
        solve_range = input_data.get('solve_range', {})
        step_size = input_data.get('step_size', 0.01)
        start = solve_range.get('start', 0)
        end = solve_range.get('end', 10)
        
        steps = (end - start) / step_size if step_size > 0 else 0
        
        if steps <= 1000:
            return "2s"
        elif steps <= 10000:
            return "10s"
        else:
            return "60s"
    
    elif task_type.startswith('stats_'):
        data = input_data.get('data')
        x_data = input_data.get('x_data')
        
        size = 0
        if data and isinstance(data, list):
            size = len(data)
        if x_data and isinstance(x_data, list):
            size = len(x_data)
        
        if size <= 1000:
            return "1s"
        elif size <= 10000:
            return "5s"
        else:
            return "15s"
    
    return "5s"
