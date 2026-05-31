from typing import Optional, Dict, Any, List
from fastapi import APIRouter, HTTPException, Query, UploadFile, File
from pydantic import BaseModel
from datetime import datetime
import os
import uuid

from application.services.ota_service import OTAService

router = APIRouter(prefix="/ota", tags=["ota"])


class PackageCreateRequest(BaseModel):
    package_name: str
    version: str
    firmware_version: str
    file_path: str
    release_notes: Optional[str] = None
    is_delta: bool = False
    base_version: Optional[str] = None
    min_firmware_version: Optional[str] = None
    force_upgrade: bool = False


class UpgradeTaskCreateRequest(BaseModel):
    package_id: str
    device_id: str
    strategy: str = "sequential"
    scheduled_at: Optional[datetime] = None
    rollback_on_failure: bool = True


class BatchUpgradeRequest(BaseModel):
    package_id: str
    device_ids: List[str]
    strategy: str = "batch"
    batch_size: int = 10
    delay_between_batches: int = 300


class CanaryUpgradeRequest(BaseModel):
    package_id: str
    canary_device_ids: List[str]
    remaining_device_ids: List[str]
    success_threshold: float = 1.0
    monitoring_period_seconds: int = 3600


class TaskStatusUpdateRequest(BaseModel):
    status: str
    error_message: Optional[str] = None
    error_code: Optional[int] = None
    download_progress: Optional[int] = None
    install_progress: Optional[int] = None


_ota_service: Optional[OTAService] = None


def set_ota_service(service: OTAService) -> None:
    global _ota_service
    _ota_service = service


def get_ota_service() -> OTAService:
    if _ota_service is None:
        raise RuntimeError("OTAService not initialized")
    return _ota_service


UPLOAD_DIR = "./ota_uploads"
os.makedirs(UPLOAD_DIR, exist_ok=True)


@router.post("/packages/upload")
async def upload_package(file: UploadFile = File(...)):
    file_location = os.path.join(UPLOAD_DIR, f"{uuid.uuid4()}_{file.filename}")
    with open(file_location, "wb") as f:
        content = await file.read()
        f.write(content)
    return {"file_path": file_location, "filename": file.filename, "size": len(content)}


@router.post("/packages")
def create_package(request: PackageCreateRequest):
    service = get_ota_service()
    try:
        package = service.create_package(
            package_name=request.package_name,
            version=request.version,
            firmware_version=request.firmware_version,
            file_path=request.file_path,
            release_notes=request.release_notes,
            is_delta=request.is_delta,
            base_version=request.base_version,
            min_firmware_version=request.min_firmware_version,
            force_upgrade=request.force_upgrade,
        )
        return {
            "package_id": package.package_id,
            "package_name": package.package_name,
            "version": package.version,
            "message": "Package created successfully",
        }
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/packages")
def list_packages():
    service = get_ota_service()
    packages = service.list_packages()
    return {
        "packages": [
            {
                "package_id": p.package_id,
                "package_name": p.package_name,
                "version": p.version,
                "firmware_version": p.firmware_version,
                "file_size": p.file_size,
                "is_delta": p.is_delta,
                "force_upgrade": p.force_upgrade,
            }
            for p in packages
        ],
        "count": len(packages),
    }


@router.get("/packages/{package_id}")
def get_package(package_id: str):
    service = get_ota_service()
    package = service.get_package(package_id)
    if not package:
        raise HTTPException(status_code=404, detail="Package not found")
    return {
        "package_id": package.package_id,
        "package_name": package.package_name,
        "version": package.version,
        "firmware_version": package.firmware_version,
        "file_path": package.file_path,
        "file_size": package.file_size,
        "checksum": package.checksum,
        "release_notes": package.release_notes,
        "is_delta": package.is_delta,
        "base_version": package.base_version,
        "min_firmware_version": package.min_firmware_version,
        "force_upgrade": package.force_upgrade,
        "auto_apply": package.auto_apply,
    }


@router.delete("/packages/{package_id}")
def delete_package(package_id: str):
    service = get_ota_service()
    success = service.delete_package(package_id)
    if not success:
        raise HTTPException(status_code=404, detail="Package not found")
    return {"message": "Package deleted successfully"}


@router.post("/packages/{package_id}/verify")
def verify_package(package_id: str):
    service = get_ota_service()
    is_valid = service.verify_package(package_id)
    return {"package_id": package_id, "is_valid": is_valid}


@router.post("/upgrade-tasks")
def create_upgrade_task(request: UpgradeTaskCreateRequest):
    service = get_ota_service()
    try:
        task = service.create_upgrade_task(
            package_id=request.package_id,
            device_id=request.device_id,
            strategy=request.strategy,
            scheduled_at=request.scheduled_at,
            rollback_on_failure=request.rollback_on_failure,
        )
        return {
            "task_id": task.task_id,
            "package_id": task.package_id,
            "device_id": task.device_id,
            "status": task.status.value,
            "message": "Upgrade task created successfully",
        }
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/upgrade-tasks/batch")
def create_batch_upgrade(request: BatchUpgradeRequest):
    service = get_ota_service()
    try:
        tasks = service.create_batch_upgrade(
            package_id=request.package_id,
            device_ids=request.device_ids,
            strategy=request.strategy,
            batch_size=request.batch_size,
            delay_between_batches=request.delay_between_batches,
        )
        return {
            "tasks": [
                {"task_id": t.task_id, "device_id": t.device_id, "batch_number": t.batch_number}
                for t in tasks
            ],
            "total_tasks": len(tasks),
        }
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/upgrade-tasks/canary")
def create_canary_upgrade(request: CanaryUpgradeRequest):
    service = get_ota_service()
    try:
        result = service.create_canary_upgrade(
            package_id=request.package_id,
            canary_device_ids=request.canary_device_ids,
            remaining_device_ids=request.remaining_device_ids,
            success_threshold=request.success_threshold,
            monitoring_period_seconds=request.monitoring_period_seconds,
        )
        return result
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/upgrade-tasks/{task_id}")
def get_task(task_id: str):
    service = get_ota_service()
    task = service.get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    return {
        "task_id": task.task_id,
        "package_id": task.package_id,
        "device_id": task.device_id,
        "status": task.status.value,
        "current_version": task.current_version,
        "target_version": task.target_version,
        "strategy": task.strategy.value if hasattr(task.strategy, "value") else task.strategy,
        "download_progress": task.download_progress,
        "install_progress": task.install_progress,
        "scheduled_at": task.scheduled_at.isoformat() if task.scheduled_at else None,
        "started_at": task.started_at.isoformat() if task.started_at else None,
        "completed_at": task.completed_at.isoformat() if task.completed_at else None,
        "error_message": task.error_message,
    }


@router.patch("/upgrade-tasks/{task_id}/status")
def update_task_status(task_id: str, request: TaskStatusUpdateRequest):
    service = get_ota_service()
    updated = service.update_task_status(
        task_id=task_id,
        status=request.status,
        error_message=request.error_message,
        error_code=request.error_code,
        download_progress=request.download_progress,
        install_progress=request.install_progress,
    )
    if not updated:
        raise HTTPException(status_code=404, detail="Task not found")
    return {"task_id": task_id, "status": request.status, "message": "Status updated successfully"}


@router.post("/upgrade-tasks/{task_id}/rollback")
def initiate_rollback(task_id: str):
    service = get_ota_service()
    task = service.initiate_rollback(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found or rollback not enabled")
    return {"task_id": task_id, "message": "Rollback initiated"}


@router.post("/upgrade-tasks/{task_id}/rollback/complete")
def complete_rollback(task_id: str):
    service = get_ota_service()
    task = service.complete_rollback(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    return {"task_id": task_id, "message": "Rollback completed"}


@router.delete("/upgrade-tasks/{task_id}")
def cancel_task(task_id: str):
    service = get_ota_service()
    success = service.cancel_task(task_id)
    if not success:
        raise HTTPException(status_code=404, detail="Task not found or already completed")
    return {"message": "Task cancelled successfully"}


@router.get("/devices/{device_id}/history")
def get_device_upgrade_history(device_id: str):
    service = get_ota_service()
    tasks = service.get_device_upgrade_history(device_id)
    return {
        "device_id": device_id,
        "history": [
            {
                "task_id": t.task_id,
                "package_id": t.package_id,
                "status": t.status.value,
                "target_version": t.target_version,
                "completed_at": t.completed_at.isoformat() if t.completed_at else None,
            }
            for t in tasks
        ],
    }


@router.get("/stats")
def get_upgrade_stats():
    service = get_ota_service()
    stats = service.get_upgrade_stats()
    return stats


@router.post("/start")
def start_ota_service():
    service = get_ota_service()
    service.start()
    return {"message": "OTA service started"}


@router.post("/stop")
def stop_ota_service():
    service = get_ota_service()
    service.stop()
    return {"message": "OTA service stopped"}
