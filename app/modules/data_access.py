from datetime import datetime
from typing import Any, Dict, List, Optional, Type
from sqlalchemy import select, delete, update, and_, or_
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import class_mapper
from app.models import SchemaVersion, Entity, RunInstance, DataSnapshot
from app.logger import logger


class SchemaMigrationError(Exception):
    pass


class DataAccessLayer:
    def __init__(self, db: AsyncSession):
        self.db = db
    
    async def get_current_schema_version(self) -> int:
        stmt = select(SchemaVersion).order_by(SchemaVersion.version.desc()).limit(1)
        result = await self.db.execute(stmt)
        record = result.scalar_one_or_none()
        return record.version if record else 0
    
    async def register_schema_version(self, version: int, description: str = "", migration_script: str = "") -> SchemaVersion:
        existing = await self.get_current_schema_version()
        if version <= existing:
            raise SchemaMigrationError(f"Version {version} is not higher than current version {existing}")
        
        record = SchemaVersion(
            version=version,
            description=description,
            migration_script=migration_script
        )
        self.db.add(record)
        await self.db.flush()
        return record
    
    async def get_schema_history(self) -> List[Dict[str, Any]]:
        stmt = select(SchemaVersion).order_by(SchemaVersion.version.asc())
        result = await self.db.execute(stmt)
        records = result.scalars().all()
        return [self._model_to_dict(r) for r in records]
    
    async def create_entity(self, entity_type: str, attributes: Dict[str, Any] = None) -> Entity:
        entity = Entity(
            type=entity_type,
            status="pending",
            attributes=attributes or {}
        )
        self.db.add(entity)
        await self.db.flush()
        return entity
    
    async def get_entity(self, entity_id: str) -> Optional[Entity]:
        stmt = select(Entity).where(Entity.id == entity_id)
        result = await self.db.execute(stmt)
        return result.scalar_one_or_none()
    
    async def update_entity_status(self, entity_id: str, status: str, attributes: Dict[str, Any] = None) -> Optional[Entity]:
        entity = await self.get_entity(entity_id)
        if entity:
            entity.status = status
            if attributes is not None:
                entity.attributes = {**entity.attributes, **attributes}
            await self.db.flush()
        return entity
    
    async def list_entities(self, entity_type: str = None, status: str = None, limit: int = 100, offset: int = 0) -> List[Entity]:
        conditions = []
        if entity_type:
            conditions.append(Entity.type == entity_type)
        if status:
            conditions.append(Entity.status == status)
        
        stmt = select(Entity).where(and_(*conditions) if conditions else True).order_by(Entity.created_at.desc()).offset(offset).limit(limit)
        result = await self.db.execute(stmt)
        return result.scalars().all()
    
    async def create_run_instance(self, entity_id: str = None) -> RunInstance:
        import uuid
        instance = RunInstance(
            run_id=str(uuid.uuid4()),
            entity_id=entity_id,
            phase="initializing",
            progress=0.0
        )
        self.db.add(instance)
        await self.db.flush()
        return instance
    
    async def update_run_instance(self, run_id: str, phase: str = None, progress: float = None, error_detail: str = None) -> Optional[RunInstance]:
        stmt = select(RunInstance).where(RunInstance.run_id == run_id)
        result = await self.db.execute(stmt)
        instance = result.scalar_one_or_none()
        
        if instance:
            if phase is not None:
                instance.phase = phase
            if progress is not None:
                instance.progress = progress
            if error_detail is not None:
                instance.error_detail = error_detail
            if phase in ["completed", "failed"]:
                instance.completed_at = datetime.utcnow()
            await self.db.flush()
        return instance
    
    async def get_run_instance(self, run_id: str) -> Optional[RunInstance]:
        stmt = select(RunInstance).where(RunInstance.run_id == run_id)
        result = await self.db.execute(stmt)
        return result.scalar_one_or_none()
    
    async def create_snapshot(self, metrics: Dict[str, Any], dimensions: Dict[str, Any] = None) -> DataSnapshot:
        import uuid
        snapshot = DataSnapshot(
            snapshot_id=str(uuid.uuid4()),
            metrics=metrics,
            dimensions=dimensions or {}
        )
        self.db.add(snapshot)
        await self.db.flush()
        return snapshot
    
    async def get_recent_snapshots(self, limit: int = 100) -> List[DataSnapshot]:
        stmt = select(DataSnapshot).order_by(DataSnapshot.timestamp.desc()).limit(limit)
        result = await self.db.execute(stmt)
        return result.scalars().all()
    
    def _model_to_dict(self, model: Any) -> Dict[str, Any]:
        columns = [c.key for c in class_mapper(model.__class__).columns]
        return {c: getattr(model, c) for c in columns}


async def execute_data_migration(db: AsyncSession, migration_func, version: int, description: str = ""):
    dal = DataAccessLayer(db)
    current_version = await dal.get_current_schema_version()
    
    if version <= current_version:
        logger.info("Migration already applied", target_version=version, current_version=current_version)
        return False
    
    logger.info("Starting migration", target_version=version, current_version=current_version)
    
    try:
        await migration_func(db)
        await dal.register_schema_version(version, description, migration_func.__name__)
        await db.commit()
        logger.info("Migration completed successfully", target_version=version)
        return True
    except Exception as e:
        await db.rollback()
        logger.error("Migration failed", target_version=version, error=str(e))
        raise SchemaMigrationError(f"Migration to version {version} failed: {e}")
