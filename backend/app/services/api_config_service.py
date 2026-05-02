from typing import Optional, List
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, update
from app.models.models import APIConfig


class APIConfigService:
    def __init__(self, db: AsyncSession):
        self.db = db
    
    async def get_all(self) -> List[APIConfig]:
        result = await self.db.execute(
            select(APIConfig).order_by(APIConfig.created_at.desc())
        )
        return result.scalars().all()
    
    async def get_by_id(self, config_id: str) -> Optional[APIConfig]:
        result = await self.db.execute(
            select(APIConfig).where(APIConfig.id == config_id)
        )
        return result.scalar_one_or_none()
    
    async def get_active(self) -> Optional[APIConfig]:
        result = await self.db.execute(
            select(APIConfig).where(APIConfig.is_active == True)
        )
        return result.scalar_one_or_none()
    
    async def create(
        self,
        name: str,
        api_key: str,
        base_url: str = "https://api.openai.com/v1",
        model: str = "gpt-3.5-turbo",
        embedding_model: str = "text-embedding-3-small"
    ) -> APIConfig:
        existing_active = await self.get_active()
        
        config = APIConfig(
            name=name,
            api_key=api_key,
            base_url=base_url,
            model=model,
            embedding_model=embedding_model,
            is_active=existing_active is None
        )
        
        self.db.add(config)
        await self.db.commit()
        await self.db.refresh(config)
        
        return config
    
    async def update(
        self,
        config_id: str,
        name: Optional[str] = None,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        model: Optional[str] = None,
        embedding_model: Optional[str] = None
    ) -> Optional[APIConfig]:
        config = await self.get_by_id(config_id)
        if not config:
            return None
        
        update_data = {}
        if name is not None:
            update_data["name"] = name
        if api_key is not None:
            update_data["api_key"] = api_key
        if base_url is not None:
            update_data["base_url"] = base_url
        if model is not None:
            update_data["model"] = model
        if embedding_model is not None:
            update_data["embedding_model"] = embedding_model
        
        if update_data:
            await self.db.execute(
                update(APIConfig).where(APIConfig.id == config_id).values(**update_data)
            )
            await self.db.commit()
            await self.db.refresh(config)
        
        return config
    
    async def set_active(self, config_id: str) -> bool:
        config = await self.get_by_id(config_id)
        if not config:
            return False
        
        await self.db.execute(
            update(APIConfig).values(is_active=False)
        )
        
        config.is_active = True
        await self.db.commit()
        await self.db.refresh(config)
        
        return True
    
    async def delete(self, config_id: str) -> bool:
        config = await self.get_by_id(config_id)
        if not config:
            return False
        
        all_configs = await self.get_all()
        if len(all_configs) <= 1:
            return False
        
        was_active = config.is_active
        
        await self.db.delete(config)
        await self.db.commit()
        
        if was_active:
            remaining = await self.get_all()
            if remaining:
                await self.set_active(remaining[0].id)
        
        return True
