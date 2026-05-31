from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import get_async_db
from app.modules.core_processor import execute_processing_handler, data_transformer, standardizer
from app.modules.data_access import DataAccessLayer
from app.modules.api_gateway import get_current_user, Permission, require_permission
from app.schemas import ProcessingRequest, EntityCreate, EntityUpdate, APIResponse
from app.logger import logger

router = APIRouter(prefix="/api/v1/processing", tags=["Processing"])


@router.post("/execute", response_model=APIResponse)
async def execute_processing(
    data: ProcessingRequest,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.EXECUTE))
):
    dal = DataAccessLayer(db)
    instance = await dal.create_run_instance()
    
    try:
        await dal.update_run_instance(instance.run_id, phase="validating", progress=0.1)
        
        result = await execute_processing_handler(
            payload=data.payload,
            trace_id=data.trace_id,
            pipeline_name=data.pipeline_name
        )
        
        await dal.update_run_instance(
            instance.run_id,
            phase="completed",
            progress=1.0
        )
        await db.commit()
        
        return APIResponse(
            code=result.get("code", 200),
            data=result.get("data"),
            error=result.get("error"),
            trace_id=result.get("trace_id")
        )
    
    except Exception as e:
        await dal.update_run_instance(
            instance.run_id,
            phase="failed",
            error_detail=str(e)
        )
        await db.commit()
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(e)
        )


@router.post("/entities", response_model=APIResponse)
async def create_entity(
    data: EntityCreate,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    dal = DataAccessLayer(db)
    entity = await dal.create_entity(
        entity_type=data.type,
        attributes=data.attributes
    )
    await db.commit()
    
    return APIResponse(
        code=201,
        data={
            "id": entity.id,
            "type": entity.type,
            "status": entity.status,
            "attributes": entity.attributes
        }
    )


@router.get("/entities/{entity_id}", response_model=APIResponse)
async def get_entity(
    entity_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    dal = DataAccessLayer(db)
    entity = await dal.get_entity(entity_id)
    
    if not entity:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Entity not found"
        )
    
    return APIResponse(
        code=200,
        data={
            "id": entity.id,
            "type": entity.type,
            "status": entity.status,
            "attributes": entity.attributes,
            "created_at": entity.created_at.isoformat() if entity.created_at else None,
            "updated_at": entity.updated_at.isoformat() if entity.updated_at else None
        }
    )


@router.patch("/entities/{entity_id}", response_model=APIResponse)
async def update_entity(
    entity_id: str,
    data: EntityUpdate,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    dal = DataAccessLayer(db)
    entity = await dal.update_entity_status(
        entity_id=entity_id,
        status=data.status,
        attributes=data.attributes
    )
    
    if not entity:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Entity not found"
        )
    
    await db.commit()
    
    return APIResponse(
        code=200,
        data={
            "id": entity.id,
            "type": entity.type,
            "status": entity.status,
            "attributes": entity.attributes
        }
    )


@router.get("/entities", response_model=APIResponse)
async def list_entities(
    entity_type: str = None,
    status: str = None,
    limit: int = 100,
    offset: int = 0,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    dal = DataAccessLayer(db)
    entities = await dal.list_entities(entity_type, status, limit, offset)
    
    return APIResponse(
        code=200,
        data=[
            {
                "id": e.id,
                "type": e.type,
                "status": e.status,
                "attributes": e.attributes,
                "created_at": e.created_at.isoformat() if e.created_at else None
            }
            for e in entities
        ]
    )


@router.get("/runs/{run_id}", response_model=APIResponse)
async def get_run_instance(
    run_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    dal = DataAccessLayer(db)
    instance = await dal.get_run_instance(run_id)
    
    if not instance:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Run instance not found"
        )
    
    return APIResponse(
        code=200,
        data={
            "run_id": instance.run_id,
            "entity_id": instance.entity_id,
            "phase": instance.phase,
            "progress": instance.progress,
            "error_detail": instance.error_detail,
            "started_at": instance.started_at.isoformat() if instance.started_at else None,
            "completed_at": instance.completed_at.isoformat() if instance.completed_at else None
        }
    )


@router.post("/snapshots", response_model=APIResponse)
async def create_snapshot(
    metrics: dict,
    dimensions: dict = None,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    dal = DataAccessLayer(db)
    snapshot = await dal.create_snapshot(metrics, dimensions)
    await db.commit()
    
    return APIResponse(
        code=201,
        data={
            "snapshot_id": snapshot.snapshot_id,
            "metrics": snapshot.metrics,
            "dimensions": snapshot.dimensions,
            "timestamp": snapshot.timestamp.isoformat() if snapshot.timestamp else None
        }
    )


@router.get("/snapshots", response_model=APIResponse)
async def list_snapshots(
    limit: int = 100,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    dal = DataAccessLayer(db)
    snapshots = await dal.get_recent_snapshots(limit)
    
    return APIResponse(
        code=200,
        data=[
            {
                "snapshot_id": s.snapshot_id,
                "metrics": s.metrics,
                "dimensions": s.dimensions,
                "timestamp": s.timestamp.isoformat() if s.timestamp else None
            }
            for s in snapshots
        ]
    )


@router.get("/schema/version", response_model=APIResponse)
async def get_schema_version(
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    dal = DataAccessLayer(db)
    version = await dal.get_current_schema_version()
    
    return APIResponse(
        code=200,
        data={"current_version": version}
    )


@router.get("/schema/history", response_model=APIResponse)
async def get_schema_history(
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    dal = DataAccessLayer(db)
    history = await dal.get_schema_history()
    
    return APIResponse(code=200, data=history)
