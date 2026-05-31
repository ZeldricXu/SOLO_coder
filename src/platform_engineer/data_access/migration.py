import asyncio
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

from ..core.exceptions import MigrationError


class MigrationStatus(Enum):
    PENDING = "pending"
    RUNNING = "running"
    SUCCESS = "success"
    FAILED = "failed"
    ROLLED_BACK = "rolled_back"


@dataclass
class SchemaVersion:
    version: int
    name: str
    applied_at: datetime
    description: Optional[str] = None
    checksum: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "version": self.version,
            "name": self.name,
            "applied_at": self.applied_at.isoformat(),
            "description": self.description,
            "checksum": self.checksum,
        }


@dataclass
class Migration:
    version: int
    name: str
    up: Callable
    down: Optional[Callable] = None
    description: str = ""
    checksum: Optional[str] = None

    async def apply(self, *args, **kwargs) -> bool:
        try:
            result = self.up(*args, **kwargs)
            if asyncio.iscoroutine(result):
                await result
            return True
        except Exception as e:
            raise MigrationError(f"Migration {self.version} failed: {e}")

    async def rollback(self, *args, **kwargs) -> bool:
        if not self.down:
            return False
        try:
            result = self.down(*args, **kwargs)
            if asyncio.iscoroutine(result):
                await result
            return True
        except Exception as e:
            raise MigrationError(f"Migration rollback {self.version} failed: {e}")


class MigrationRegistry:
    def __init__(self):
        self._migrations: Dict[int, Migration] = {}

    def register(self, migration: Migration) -> None:
        if migration.version in self._migrations:
            raise MigrationError(f"Migration version {migration.version} already exists")
        self._migrations[migration.version] = migration

    def get(self, version: int) -> Optional[Migration]:
        return self._migrations.get(version)

    def list(self) -> List[Migration]:
        return sorted(self._migrations.values(), key=lambda m: m.version)

    def get_pending(self, applied_versions: List[int]) -> List[Migration]:
        return [m for m in self.list() if m.version not in applied_versions]

    def get_latest_version(self) -> Optional[int]:
        migrations = self.list()
        return migrations[-1].version if migrations else None


class MigrationEngine:
    def __init__(
        self,
        registry: Optional[MigrationRegistry] = None,
        logger=None,
    ):
        self._registry = registry or MigrationRegistry()
        self._applied_versions: List[int] = []
        self._schema_history: List[SchemaVersion] = []
        self._logger = logger
        self._lock = asyncio.Lock()
        self._migration_status: Dict[int, MigrationStatus] = {}

    def register_migration(self, migration: Migration) -> None:
        self._registry.register(migration)

    def register(self, version: int, name: str, up: Callable, down: Optional[Callable] = None, description: str = "") -> None:
        migration = Migration(
            version=version,
            name=name,
            up=up,
            down=down,
            description=description,
        )
        self._registry.register(migration)

    async def migrate(self, target_version: Optional[int] = None, *args, **kwargs) -> List[SchemaVersion]:
        async with self._lock:
            applied_versions = [sv.version for sv in self._schema_history]
            pending = self._registry.get_pending(applied_versions)
            if target_version is not None:
                pending = [m for m in pending if m.version <= target_version]
            results = []
            for migration in pending:
                result = await self._apply_migration(migration, *args, **kwargs)
                results.append(result)
            return results

    async def _apply_migration(self, migration: Migration, *args, **kwargs) -> SchemaVersion:
        self._migration_status[migration.version] = MigrationStatus.RUNNING
        if self._logger:
            self._logger.info(f"Applying migration {migration.version}: {migration.name}")
        try:
            await migration.apply(*args, **kwargs)
            schema_version = SchemaVersion(
                version=migration.version,
                name=migration.name,
                applied_at=datetime.now(timezone.utc),
                description=migration.description,
                checksum=migration.checksum,
            )
            self._schema_history.append(schema_version)
            self._applied_versions.append(migration.version)
            self._migration_status[migration.version] = MigrationStatus.SUCCESS
            if self._logger:
                self._logger.info(f"Migration {migration.version} applied successfully")
            return schema_version
        except Exception as e:
            self._migration_status[migration.version] = MigrationStatus.FAILED
            if self._logger:
                self._logger.error(f"Migration {migration.version} failed: {e}")
            raise

    async def rollback(self, to_version: int, *args, **kwargs) -> List[SchemaVersion]:
        async with self._lock:
            to_rollback = [
                sv for sv in reversed(self._schema_history)
                if sv.version > to_version
            ]
            rolled_back = []
            for schema_version in to_rollback:
                migration = self._registry.get(schema_version.version)
                if migration and migration.down:
                    await self._rollback_migration(migration, *args, **kwargs)
                    rolled_back.append(schema_version)
            return rolled_back

    async def _rollback_migration(self, migration: Migration, *args, **kwargs) -> None:
        self._migration_status[migration.version] = MigrationStatus.RUNNING
        if self._logger:
            self._logger.info(f"Rolling back migration {migration.version}: {migration.name}")
        try:
            await migration.rollback(*args, **kwargs)
            self._migration_status[migration.version] = MigrationStatus.ROLLED_BACK
            self._schema_history = [sv for sv in self._schema_history if sv.version != migration.version]
            self._applied_versions = [v for v in self._applied_versions if v != migration.version]
            if self._logger:
                self._logger.info(f"Migration {migration.version} rolled back successfully")
        except Exception as e:
            self._migration_status[migration.version] = MigrationStatus.FAILED
            if self._logger:
                self._logger.error(f"Migration rollback {migration.version} failed: {e}")
            raise

    def get_current_version(self) -> Optional[int]:
        if not self._schema_history:
            return None
        return max(sv.version for sv in self._schema_history)

    def get_latest_available_version(self) -> Optional[int]:
        return self._registry.get_latest_version()

    def get_schema_history(self) -> List[SchemaVersion]:
        return list(self._schema_history)

    def get_migration_status(self, version: int) -> Optional[MigrationStatus]:
        return self._migration_status.get(version)

    def get_status(self) -> Dict[str, Any]:
        return {
            "current_version": self.get_current_version(),
            "latest_available_version": self.get_latest_available_version(),
            "migrations_applied": len(self._schema_history),
            "migrations_pending": len(self._registry.get_pending([sv.version for sv in self._schema_history])),
            "schema_history": [sv.to_dict() for sv in self._schema_history],
        }


_global_migration_engine: Optional[MigrationEngine] = None


def get_migration_engine() -> MigrationEngine:
    global _global_migration_engine
    if _global_migration_engine is None:
        _global_migration_engine = MigrationEngine()
    return _global_migration_engine


def set_migration_engine(engine: MigrationEngine) -> None:
    global _global_migration_engine
    _global_migration_engine = engine
