from typing import Optional
from fastapi import Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession
from app.core.database import get_db
from app.models.models import APIConfig
from app.services.api_config_service import APIConfigService


async def get_active_api_config(
    db: AsyncSession = Depends(get_db)
) -> Optional[APIConfig]:
    service = APIConfigService(db)
    config = await service.get_active()
    
    if not config:
        raise HTTPException(
            status_code=400,
            detail="请先配置API设置。前往设置页面添加API配置。"
        )
    
    return config
