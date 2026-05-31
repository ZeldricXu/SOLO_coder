from typing import Optional, Dict, Any, List
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel
from datetime import datetime

from application.services.inference_service import InferenceService

router = APIRouter(prefix="/inference", tags=["inference"])


class ModelRegisterRequest(BaseModel):
    model_id: str
    name: str
    model_path: str
    model_type: str = "custom"
    description: Optional[str] = None
    version: str = "1.0.0"
    input_schema: Optional[Dict[str, Any]] = None
    output_schema: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None


class InferenceTaskRequest(BaseModel):
    model_id: str
    input_data: Dict[str, Any]
    device_id: Optional[str] = None
    priority: int = 0
    callback_url: Optional[str] = None


_inference_service: Optional[InferenceService] = None


def set_inference_service(service: InferenceService) -> None:
    global _inference_service
    _inference_service = service


def get_inference_service() -> InferenceService:
    if _inference_service is None:
        raise RuntimeError("InferenceService not initialized")
    return _inference_service


@router.post("/models")
def register_model(request: ModelRegisterRequest):
    service = get_inference_service()
    try:
        model = service.register_model(
            model_id=request.model_id,
            name=request.name,
            model_path=request.model_path,
            model_type=request.model_type,
            description=request.description,
            version=request.version,
            input_schema=request.input_schema,
            output_schema=request.output_schema,
            tags=request.tags,
        )
        return {
            "model_id": model.model_id,
            "name": model.name,
            "version": model.version,
            "message": "Model registered successfully",
        }
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/models")
def list_models():
    service = get_inference_service()
    models = service.list_models()
    return {
        "models": [
            {
                "model_id": m.model_id,
                "name": m.name,
                "model_type": m.model_type.value if hasattr(m.model_type, "value") else m.model_type,
                "version": m.version,
                "is_loaded": m.is_loaded,
            }
            for m in models
        ],
        "count": len(models),
    }


@router.get("/models/{model_id}")
def get_model(model_id: str):
    service = get_inference_service()
    model = service.get_model(model_id)
    if not model:
        raise HTTPException(status_code=404, detail="Model not found")
    return {
        "model_id": model.model_id,
        "name": model.name,
        "model_type": model.model_type.value if hasattr(model.model_type, "value") else model.model_type,
        "version": model.version,
        "description": model.description,
        "model_path": model.model_path,
        "input_schema": model.input_schema,
        "output_schema": model.output_schema,
        "tags": model.tags,
        "is_loaded": model.is_loaded,
        "loaded_at": model.loaded_at.isoformat() if model.loaded_at else None,
    }


@router.delete("/models/{model_id}")
def delete_model(model_id: str):
    service = get_inference_service()
    success = service.delete_model(model_id)
    if not success:
        raise HTTPException(status_code=404, detail="Model not found")
    return {"message": "Model deleted successfully"}


@router.post("/tasks")
def submit_inference_task(request: InferenceTaskRequest):
    service = get_inference_service()
    try:
        task = service.submit_inference_task(
            model_id=request.model_id,
            input_data=request.input_data,
            device_id=request.device_id,
            priority=request.priority,
            callback_url=request.callback_url,
        )
        return {
            "task_id": task.task_id,
            "model_id": task.model_id,
            "status": task.status.value,
            "message": "Task submitted successfully",
        }
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/tasks/sync")
def run_inference_sync(request: InferenceTaskRequest, timeout: float = Query(30.0, ge=1.0, le=300.0)):
    service = get_inference_service()
    result = service.run_inference_sync(
        model_id=request.model_id,
        input_data=request.input_data,
        timeout=timeout,
    )
    if not result:
        raise HTTPException(status_code=408, detail="Inference task timed out")
    return {
        "task_id": result.task_id,
        "output": result.output,
        "inference_time_ms": result.inference_time_ms,
        "completed_at": result.completed_at.isoformat() if result.completed_at else None,
    }


@router.get("/tasks")
def list_tasks(
    model_id: Optional[str] = None,
    device_id: Optional[str] = None,
    status: Optional[str] = None,
    limit: int = Query(100, ge=1, le=1000),
):
    service = get_inference_service()
    tasks = service.list_tasks(
        model_id=model_id,
        device_id=device_id,
        status=status,
        limit=limit,
    )
    return {
        "tasks": [
            {
                "task_id": t.task_id,
                "model_id": t.model_id,
                "device_id": t.device_id,
                "status": t.status.value,
                "priority": t.priority,
                "submitted_at": t.submitted_at.isoformat() if t.submitted_at else None,
            }
            for t in tasks
        ],
        "count": len(tasks),
    }


@router.get("/tasks/{task_id}")
def get_task(task_id: str):
    service = get_inference_service()
    task = service.get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    return {
        "task_id": task.task_id,
        "model_id": task.model_id,
        "device_id": task.device_id,
        "status": task.status.value,
        "input_data": task.input_data,
        "priority": task.priority,
        "callback_url": task.callback_url,
        "submitted_at": task.submitted_at.isoformat() if task.submitted_at else None,
        "started_at": task.started_at.isoformat() if task.started_at else None,
        "completed_at": task.completed_at.isoformat() if task.completed_at else None,
    }


@router.get("/tasks/{task_id}/result")
def get_task_result(task_id: str):
    service = get_inference_service()
    result = service.get_task_result(task_id)
    if not result:
        raise HTTPException(status_code=404, detail="Result not found or task not completed")
    return {
        "task_id": result.task_id,
        "model_id": result.model_id,
        "output": result.output,
        "inference_time_ms": result.inference_time_ms,
        "error": result.error,
        "completed_at": result.completed_at.isoformat() if result.completed_at else None,
    }


@router.delete("/tasks/{task_id}")
def cancel_task(task_id: str):
    service = get_inference_service()
    success = service.cancel_task(task_id)
    if not success:
        raise HTTPException(status_code=404, detail="Task not found or already completed")
    return {"message": "Task cancelled successfully"}


@router.get("/stats")
def get_inference_stats():
    service = get_inference_service()
    stats = service.get_inference_stats()
    return stats


@router.post("/engine/start")
def start_inference_engine():
    service = get_inference_service()
    service.start_inference_engine()
    return {"message": "Inference engine started"}


@router.post("/engine/stop")
def stop_inference_engine():
    service = get_inference_service()
    service.stop_inference_engine()
    return {"message": "Inference engine stopped"}
