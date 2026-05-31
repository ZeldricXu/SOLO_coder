from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import get_async_db
from app.modules.edge_inference import EdgeInferenceManager, InferenceError, model_registry
from app.modules.api_gateway import get_current_user, Permission, require_permission
from app.schemas import EdgeModelCreate, InferenceJobCreate, APIResponse
from app.logger import logger

router = APIRouter(prefix="/api/v1/inference", tags=["Edge Inference"])


@router.post("/models", response_model=APIResponse)
async def register_model(
    data: EdgeModelCreate,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = EdgeInferenceManager(db)
    
    model = await manager.register_model(
        model_id=data.model_id,
        name=data.name,
        version=data.version,
        model_type=data.model_type,
        model_path=data.model_path,
        input_spec=data.input_spec,
        output_spec=data.output_spec,
        requirements=data.requirements
    )
    await db.commit()
    
    return APIResponse(
        code=201,
        data={
            "id": model.id,
            "model_id": model.model_id,
            "name": model.name,
            "version": model.version,
            "model_type": model.model_type
        }
    )


@router.get("/models", response_model=APIResponse)
async def list_models(
    active_only: bool = True,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = EdgeInferenceManager(db)
    models = await manager.list_models(active_only)
    
    return APIResponse(
        code=200,
        data=[
            {
                "id": m.id,
                "model_id": m.model_id,
                "name": m.name,
                "version": m.version,
                "model_type": m.model_type,
                "is_active": m.is_active
            }
            for m in models
        ]
    )


@router.get("/models/{model_id}", response_model=APIResponse)
async def get_model(
    model_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = EdgeInferenceManager(db)
    model = await manager.get_model(model_id)
    
    if not model:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Model not found"
        )
    
    return APIResponse(
        code=200,
        data={
            "id": model.id,
            "model_id": model.model_id,
            "name": model.name,
            "version": model.version,
            "model_type": model.model_type,
            "model_path": model.model_path,
            "input_spec": model.input_spec,
            "output_spec": model.output_spec,
            "requirements": model.requirements,
            "is_active": model.is_active
        }
    )


@router.post("/devices/{device_id}/register", response_model=APIResponse)
async def register_edge_device(
    device_id: str,
    capabilities: dict,
    user: dict = Depends(require_permission(Permission.WRITE))
):
    model_registry.register_device(device_id, capabilities)
    
    return APIResponse(
        code=200,
        data={
            "device_id": device_id,
            "capabilities": capabilities,
            "registered": True
        }
    )


@router.post("/jobs", response_model=APIResponse)
async def create_inference_job(
    data: InferenceJobCreate,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.EXECUTE))
):
    manager = EdgeInferenceManager(db)
    
    try:
        job = await manager.create_inference_job(
            model_id=data.model_id,
            device_id=data.device_id,
            input_data=data.input_data
        )
        await db.commit()
        
        return APIResponse(
            code=201,
            data={
                "job_id": job.id,
                "model_id": job.model_id,
                "device_id": job.device_id,
                "status": job.status
            }
        )
    except InferenceError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e)
        )


@router.post("/jobs/{job_id}/execute", response_model=APIResponse)
async def execute_inference_job(
    job_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.EXECUTE))
):
    manager = EdgeInferenceManager(db)
    result = await manager.execute_inference(job_id)
    await db.commit()
    
    return APIResponse(code=200, data=result)


@router.get("/jobs/{job_id}", response_model=APIResponse)
async def get_inference_job_status(
    job_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = EdgeInferenceManager(db)
    job = await manager.get_job_status(job_id)
    
    if not job:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Inference job not found"
        )
    
    return APIResponse(code=200, data=job)


@router.get("/jobs", response_model=APIResponse)
async def list_inference_jobs(
    job_status: str = None,
    limit: int = 100,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = EdgeInferenceManager(db)
    jobs = await manager.list_jobs(job_status, limit)
    
    return APIResponse(code=200, data=jobs)


@router.post("/jobs/quick", response_model=APIResponse)
async def quick_inference(
    data: InferenceJobCreate,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.EXECUTE))
):
    manager = EdgeInferenceManager(db)
    
    try:
        job = await manager.create_inference_job(
            model_id=data.model_id,
            device_id=data.device_id,
            input_data=data.input_data
        )
        await db.commit()
        
        result = await manager.execute_inference(job.id)
        await db.commit()
        
        return APIResponse(code=200, data=result)
    except InferenceError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e)
        )
