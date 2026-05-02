from fastapi import APIRouter, Depends, HTTPException
from typing import List
from sqlalchemy.ext.asyncio import AsyncSession
from app.core.database import get_db
from app.models.schemas import APIConfigCreate, APIConfigUpdate, APIConfigResponse
from app.services.api_config_service import APIConfigService


router = APIRouter(prefix="/api-config", tags=["API Configuration"])


@router.get("/", response_model=List[APIConfigResponse])
async def list_api_configs(db: AsyncSession = Depends(get_db)):
    service = APIConfigService(db)
    configs = await service.get_all()
    return [APIConfigResponse.model_validate(config.to_safe_dict()) for config in configs]


@router.get("/active", response_model=APIConfigResponse)
async def get_active_config(db: AsyncSession = Depends(get_db)):
    service = APIConfigService(db)
    config = await service.get_active()
    
    if not config:
        raise HTTPException(status_code=404, detail="没有活跃的API配置")
    
    return APIConfigResponse.model_validate(config.to_safe_dict())


@router.post("/", response_model=APIConfigResponse)
async def create_api_config(
    data: APIConfigCreate,
    db: AsyncSession = Depends(get_db)
):
    service = APIConfigService(db)
    
    config = await service.create(
        name=data.name,
        api_key=data.api_key,
        base_url=data.base_url,
        model=data.model,
        embedding_model=data.embedding_model
    )
    
    return APIConfigResponse.model_validate(config.to_safe_dict())


@router.put("/{config_id}", response_model=APIConfigResponse)
async def update_api_config(
    config_id: str,
    data: APIConfigUpdate,
    db: AsyncSession = Depends(get_db)
):
    service = APIConfigService(db)
    
    update_data = data.model_dump(exclude_unset=True)
    
    config = await service.update(
        config_id=config_id,
        name=update_data.get("name"),
        api_key=update_data.get("api_key"),
        base_url=update_data.get("base_url"),
        model=update_data.get("model"),
        embedding_model=update_data.get("embedding_model")
    )
    
    if not config:
        raise HTTPException(status_code=404, detail="API配置不存在")
    
    return APIConfigResponse.model_validate(config.to_safe_dict())


@router.post("/{config_id}/activate")
async def activate_api_config(
    config_id: str,
    db: AsyncSession = Depends(get_db)
):
    service = APIConfigService(db)
    
    success = await service.set_active(config_id)
    
    if not success:
        raise HTTPException(status_code=404, detail="API配置不存在")
    
    return {"message": "已切换到指定API配置", "config_id": config_id}


@router.delete("/{config_id}")
async def delete_api_config(
    config_id: str,
    db: AsyncSession = Depends(get_db)
):
    service = APIConfigService(db)
    
    success = await service.delete(config_id)
    
    if not success:
        raise HTTPException(
            status_code=400, 
            detail="无法删除配置（可能是最后一个配置或不存在）"
        )
    
    return {"message": "配置已删除", "config_id": config_id}
