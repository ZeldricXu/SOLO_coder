from __future__ import annotations

from abc import ABC, abstractmethod
from datetime import datetime
from typing import Any, Dict, Generic, List, Optional, Type, TypeVar

from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from top.core.models import (
    AuditLogEntry,
    CommandRecord,
    ConfigModel,
    EntityModel,
    RunInstanceModel,
    SnapshotModel,
)
from top.infrastructure.persistence.orm import (
    AuditLogORM,
    CommandORM,
    ConfigORM,
    EntityORM,
    RunInstanceORM,
    SnapshotORM,
)


T = TypeVar('T')


class Repository(ABC, Generic[T]):
    @abstractmethod
    async def create(self, entity: T) -> T:
        pass

    @abstractmethod
    async def get_by_id(self, entity_id: str) -> Optional[T]:
        pass

    @abstractmethod
    async def delete(self, entity_id: str) -> bool:
        pass


class DatabaseRepository(Repository[T]):
    def __init__(self, session: AsyncSession):
        self.session = session


class EntityRepository(DatabaseRepository[EntityModel]):
    async def create(self, entity: EntityModel) -> EntityModel:
        orm = EntityORM(
            id=entity.id,
            type=entity.type,
            status=entity.status,
            attributes=entity.attributes,
            created_at=entity.created_at,
            updated_at=entity.updated_at,
        )
        self.session.add(orm)
        await self.session.flush()
        return entity

    async def get_by_id(self, entity_id: str) -> Optional[EntityModel]:
        stmt = select(EntityORM).where(EntityORM.id == entity_id)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            return EntityModel.model_validate(orm)
        return None

    async def list_by_type(self, entity_type: str, limit: int = 100) -> List[EntityModel]:
        stmt = select(EntityORM).where(EntityORM.type == entity_type).limit(limit)
        result = await self.session.execute(stmt)
        return [EntityModel.model_validate(orm) for orm in result.scalars().all()]

    async def update_status(self, entity_id: str, status: str) -> Optional[EntityModel]:
        stmt = select(EntityORM).where(EntityORM.id == entity_id)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            orm.status = status
            orm.updated_at = datetime.utcnow()
            return EntityModel.model_validate(orm)
        return None

    async def delete(self, entity_id: str) -> bool:
        stmt = select(EntityORM).where(EntityORM.id == entity_id)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            await self.session.delete(orm)
            return True
        return False

    async def count_by_type(self) -> Dict[str, int]:
        stmt = (
            select(EntityORM.type, func.count(EntityORM.id))
            .group_by(EntityORM.type)
        )
        result = await self.session.execute(stmt)
        return {row.type: row.count for row in result.all()}


class ConfigRepository(DatabaseRepository[ConfigModel]):
    async def create(self, config: ConfigModel) -> ConfigModel:
        orm = ConfigORM(
            config_id=config.config_id,
            namespace=config.namespace,
            version=config.version,
            parameters=config.parameters,
            enabled=config.enabled,
            applied_at=config.applied_at,
        )
        self.session.add(orm)
        await self.session.flush()
        return config

    async def get_by_id(self, config_id: str) -> Optional[ConfigModel]:
        stmt = select(ConfigORM).where(ConfigORM.config_id == config_id).limit(1)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            return ConfigModel.model_validate(orm)
        return None

    async def get_latest(self, namespace: str) -> Optional[ConfigModel]:
        stmt = (
            select(ConfigORM)
            .where(ConfigORM.namespace == namespace)
            .order_by(ConfigORM.version.desc())
            .limit(1)
        )
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            return ConfigModel.model_validate(orm)
        return None

    async def get_version(
        self, namespace: str, version: int
    ) -> Optional[ConfigModel]:
        stmt = select(ConfigORM).where(
            and_(
                ConfigORM.namespace == namespace,
                ConfigORM.version == version,
            )
        )
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            return ConfigModel.model_validate(orm)
        return None

    async def list_versions(self, namespace: str) -> List[ConfigModel]:
        stmt = (
            select(ConfigORM)
            .where(ConfigORM.namespace == namespace)
            .order_by(ConfigORM.version.desc())
        )
        result = await self.session.execute(stmt)
        return [ConfigModel.model_validate(orm) for orm in result.scalars().all()]

    async def delete(self, config_id: str) -> bool:
        stmt = select(ConfigORM).where(ConfigORM.config_id == config_id)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            await self.session.delete(orm)
            return True
        return False


class RunInstanceRepository(DatabaseRepository[RunInstanceModel]):
    async def create(self, run: RunInstanceModel) -> RunInstanceModel:
        orm = RunInstanceORM(
            run_id=run.run_id,
            entity_id=run.entity_id,
            phase=run.phase,
            progress=run.progress,
            started_at=run.started_at,
            completed_at=run.completed_at,
            error_detail=run.error_detail,
        )
        self.session.add(orm)
        await self.session.flush()
        return run

    async def get_by_id(self, run_id: str) -> Optional[RunInstanceModel]:
        stmt = select(RunInstanceORM).where(RunInstanceORM.run_id == run_id)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            return RunInstanceModel.model_validate(orm)
        return None

    async def update_progress(
        self, run_id: str, phase: str, progress: float, error_detail: Optional[str] = None
    ) -> Optional[RunInstanceModel]:
        stmt = select(RunInstanceORM).where(RunInstanceORM.run_id == run_id)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            orm.phase = phase
            orm.progress = progress
            if phase in ["completed", "failed"]:
                orm.completed_at = datetime.utcnow()
            if error_detail:
                orm.error_detail = error_detail
            return RunInstanceModel.model_validate(orm)
        return None

    async def list_by_entity(self, entity_id: str, limit: int = 50) -> List[RunInstanceModel]:
        stmt = (
            select(RunInstanceORM)
            .where(RunInstanceORM.entity_id == entity_id)
            .order_by(RunInstanceORM.started_at.desc())
            .limit(limit)
        )
        result = await self.session.execute(stmt)
        return [RunInstanceModel.model_validate(orm) for orm in result.scalars().all()]

    async def delete(self, run_id: str) -> bool:
        stmt = select(RunInstanceORM).where(RunInstanceORM.run_id == run_id)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            await self.session.delete(orm)
            return True
        return False


class SnapshotRepository(DatabaseRepository[SnapshotModel]):
    async def create(self, snapshot: SnapshotModel) -> SnapshotModel:
        orm = SnapshotORM(
            snapshot_id=snapshot.snapshot_id,
            timestamp=snapshot.timestamp,
            metrics=snapshot.metrics,
            dimensions=snapshot.dimensions,
        )
        self.session.add(orm)
        await self.session.flush()
        return snapshot

    async def get_by_id(self, snapshot_id: str) -> Optional[SnapshotModel]:
        stmt = select(SnapshotORM).where(SnapshotORM.snapshot_id == snapshot_id)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            return SnapshotModel.model_validate(orm)
        return None

    async def list_by_time_range(
        self, start: datetime, end: datetime, dimensions: Optional[Dict[str, str]] = None
    ) -> List[SnapshotModel]:
        stmt = select(SnapshotORM).where(
            and_(
                SnapshotORM.timestamp >= start,
                SnapshotORM.timestamp <= end,
            )
        )
        result = await self.session.execute(stmt)
        snapshots = [SnapshotModel.model_validate(orm) for orm in result.scalars().all()]
        if dimensions:
            return [
                s
                for s in snapshots
                if all(s.dimensions.get(k) == v for k, v in dimensions.items())
            ]
        return snapshots

    async def delete(self, snapshot_id: str) -> bool:
        stmt = select(SnapshotORM).where(SnapshotORM.snapshot_id == snapshot_id)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            await self.session.delete(orm)
            return True
        return False


class CommandRepository(DatabaseRepository[CommandRecord]):
    async def create(self, command: CommandRecord) -> CommandRecord:
        return await self.store(command)

    async def store(self, command: CommandRecord) -> CommandRecord:
        orm = CommandORM(
            command_id=command.command_id,
            command_type=command.command_type,
            payload=command.payload,
            issued_by=command.issued_by,
            issued_at=command.issued_at,
            correlation_id=command.correlation_id,
        )
        self.session.add(orm)
        await self.session.flush()
        return command

    async def get_by_id(self, command_id: str) -> Optional[CommandRecord]:
        stmt = select(CommandORM).where(CommandORM.command_id == command_id)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            return CommandRecord.model_validate(orm)
        return None

    async def list_by_type(self, command_type: str, limit: int = 100) -> List[CommandRecord]:
        stmt = (
            select(CommandORM)
            .where(CommandORM.command_type == command_type)
            .order_by(CommandORM.issued_at.desc())
            .limit(limit)
        )
        result = await self.session.execute(stmt)
        return [CommandRecord.model_validate(orm) for orm in result.scalars().all()]

    async def delete(self, command_id: str) -> bool:
        stmt = select(CommandORM).where(CommandORM.command_id == command_id)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            await self.session.delete(orm)
            return True
        return False


class AuditLogRepository(DatabaseRepository[AuditLogEntry]):
    async def create(self, entry: AuditLogEntry) -> AuditLogEntry:
        return await self.append(entry)

    async def append(self, entry: AuditLogEntry) -> AuditLogEntry:
        orm = AuditLogORM(
            log_id=entry.log_id,
            timestamp=entry.timestamp,
            action=entry.action,
            actor=entry.actor,
            resource=entry.resource,
            details=entry.details,
            command_id=entry.command_id,
            correlation_id=entry.correlation_id,
        )
        self.session.add(orm)
        await self.session.flush()
        return entry

    async def get_by_id(self, log_id: str) -> Optional[AuditLogEntry]:
        stmt = select(AuditLogORM).where(AuditLogORM.log_id == log_id)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            return AuditLogEntry.model_validate(orm)
        return None

    async def query(
        self,
        actor: Optional[str] = None,
        action: Optional[str] = None,
        resource: Optional[str] = None,
        start: Optional[datetime] = None,
        end: Optional[datetime] = None,
        limit: int = 100,
    ) -> List[AuditLogEntry]:
        conditions = []
        if actor:
            conditions.append(AuditLogORM.actor == actor)
        if action:
            conditions.append(AuditLogORM.action == action)
        if resource:
            conditions.append(AuditLogORM.resource == resource)
        if start:
            conditions.append(AuditLogORM.timestamp >= start)
        if end:
            conditions.append(AuditLogORM.timestamp <= end)

        stmt = select(AuditLogORM)
        if conditions:
            stmt = stmt.where(and_(*conditions))
        stmt = stmt.order_by(AuditLogORM.timestamp.desc()).limit(limit)

        result = await self.session.execute(stmt)
        return [AuditLogEntry.model_validate(orm) for orm in result.scalars().all()]

    async def generate_compliance_report(
        self, start: datetime, end: datetime
    ) -> Dict[str, Any]:
        stmt = (
            select(
                AuditLogORM.action,
                func.count(AuditLogORM.log_id).label("count"),
            )
            .where(
                and_(
                    AuditLogORM.timestamp >= start,
                    AuditLogORM.timestamp <= end,
                )
            )
            .group_by(AuditLogORM.action)
        )
        result = await self.session.execute(stmt)

        action_counts: Dict[str, int] = {}
        total = 0
        for row in result.all():
            action_counts[row.action] = row.count
            total += row.count

        unique_actors_stmt = (
            select(func.count(func.distinct(AuditLogORM.actor)))
            .where(
                and_(
                    AuditLogORM.timestamp >= start,
                    AuditLogORM.timestamp <= end,
                )
            )
        )
        actor_result = await self.session.execute(unique_actors_stmt)
        unique_actors = actor_result.scalar() or 0

        return {
            "period_start": start.isoformat(),
            "period_end": end.isoformat(),
            "total_events": total,
            "unique_actors": unique_actors,
            "action_breakdown": action_counts,
            "generated_at": datetime.utcnow().isoformat(),
        }

    async def delete(self, log_id: str) -> bool:
        stmt = select(AuditLogORM).where(AuditLogORM.log_id == log_id)
        result = await self.session.execute(stmt)
        orm = result.scalar_one_or_none()
        if orm:
            await self.session.delete(orm)
            return True
        return False
