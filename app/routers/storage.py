from fastapi import APIRouter, Depends, HTTPException, status, UploadFile, File
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import get_async_db
from app.modules.storage_manager import StorageManager, StorageError
from app.modules.api_gateway import get_current_user, Permission, require_permission
from app.schemas import BackupRequest, RestoreRequest, APIResponse
from app.logger import logger

router = APIRouter(prefix="/api/v1/storage", tags=["Storage"])


@router.post("/backup", response_model=APIResponse)
async def create_backup(
    data: BackupRequest,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    manager = StorageManager(db)
    
    try:
        backup = await manager.create_backup(
            backup_type=data.backup_type,
            tables=data.tables
        )
        await db.commit()
        
        return APIResponse(
            code=201,
            data={
                "backup_id": backup.backup_id,
                "backup_type": backup.backup_type,
                "file_path": backup.file_path,
                "file_size": backup.file_size,
                "checksum": backup.checksum
            }
        )
    except StorageError as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(e)
        )


@router.post("/restore", response_model=APIResponse)
async def restore_backup(
    data: RestoreRequest,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    manager = StorageManager(db)
    
    try:
        result = await manager.restore_backup(
            backup_id=data.backup_id,
            tables=data.tables
        )
        await db.commit()
        
        return APIResponse(code=200, data=result)
    except StorageError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e)
        )


@router.get("/backups", response_model=APIResponse)
async def list_backups(
    limit: int = 100,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = StorageManager(db)
    backups = await manager.list_backups(limit)
    
    return APIResponse(code=200, data=backups)


@router.delete("/backups/{backup_id}", response_model=APIResponse)
async def delete_backup(
    backup_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    manager = StorageManager(db)
    deleted = await manager.delete_backup(backup_id)
    
    if not deleted:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Backup not found"
        )
    
    await db.commit()
    return APIResponse(code=200, data={"backup_id": backup_id, "deleted": True})


@router.get("/backups/{backup_id}/verify", response_model=APIResponse)
async def verify_backup(
    backup_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = StorageManager(db)
    result = await manager.verify_backup(backup_id)
    
    return APIResponse(code=200, data=result)


@router.post("/upload/{category}", response_model=APIResponse)
async def upload_file(
    category: str,
    file: UploadFile = File(...),
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    if category not in ["firmware", "model"]:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid category. Must be 'firmware' or 'model'"
        )
    
    manager = StorageManager(db)
    content = await file.read()
    
    try:
        result = await manager.upload_file(
            category=category,
            filename=file.filename,
            content=content
        )
        return APIResponse(code=201, data=result)
    except StorageError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e)
        )


@router.get("/stats", response_model=APIResponse)
async def get_storage_stats(
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = StorageManager()
    stats = manager.get_storage_stats()
    
    return APIResponse(code=200, data=stats)
