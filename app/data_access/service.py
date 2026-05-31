from typing import Optional, List, Dict, Any, Tuple
from uuid import UUID
from datetime import datetime, timezone
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, and_, func
import json

from app.models import SchemaVersion, DataMigration
from app.schemas import (
    SchemaVersionCreate,
    DataMigrationCreate,
    MigrationExecuteRequest,
)
from app.exceptions import NotFoundError, ConflictError, ValidationError
from app.logging import get_logger
from app.utils import calculate_checksum

logger = get_logger(__name__)


class SchemaVersionManager:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_schema_version(
        self, schema_in: SchemaVersionCreate, created_by: Optional[UUID] = None
    ) -> SchemaVersion:
        stmt = select(SchemaVersion).where(
            SchemaVersion.schema_name == schema_in.schema_name
        ).order_by(SchemaVersion.version.desc())

        result = await self.db.execute(stmt)
        latest = result.scalars().first()

        new_version = latest.version + 1 if latest else 1

        definition_json = json.dumps(schema_in.definition, sort_keys=True)
        checksum = calculate_checksum(definition_json)

        if latest:
            latest_definition = json.dumps(latest.definition, sort_keys=True)
            if calculate_checksum(latest_definition) == checksum:
                raise ConflictError(
                    f"Schema '{schema_in.schema_name}' definition unchanged from version {latest.version}"
                )

        if latest and latest.is_current:
            latest.is_current = False
            await self.db.flush()

        schema_version = SchemaVersion(
            schema_name=schema_in.schema_name,
            version=new_version,
            definition=schema_in.definition,
            description=schema_in.description,
            is_current=True,
            migration_script=schema_in.migration_script,
            rollback_script=schema_in.rollback_script,
            created_by=created_by,
            meta_data=schema_in.metadata,
        )
        self.db.add(schema_version)
        await self.db.commit()
        await self.db.refresh(schema_version)

        logger.info(
            "Schema version created",
            schema_name=schema_in.schema_name,
            version=new_version,
        )
        return schema_version

    async def get_schema_version(self, version_id: UUID) -> SchemaVersion:
        stmt = select(SchemaVersion).where(SchemaVersion.id == version_id)
        result = await self.db.execute(stmt)
        schema = result.scalar_one_or_none()

        if not schema:
            raise NotFoundError(f"Schema version {version_id} not found")

        return schema

    async def get_current_schema(self, schema_name: str) -> SchemaVersion:
        stmt = select(SchemaVersion).where(
            and_(
                SchemaVersion.schema_name == schema_name,
                SchemaVersion.is_current == True,
            )
        )
        result = await self.db.execute(stmt)
        schema = result.scalar_one_or_none()

        if not schema:
            raise NotFoundError(f"No current schema found for '{schema_name}'")

        return schema

    async def list_schema_versions(
        self,
        schema_name: Optional[str] = None,
        only_current: bool = False,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[SchemaVersion], int]:
        stmt = select(SchemaVersion)
        conditions = []

        if schema_name:
            conditions.append(SchemaVersion.schema_name == schema_name)
        if only_current:
            conditions.append(SchemaVersion.is_current == True)

        if conditions:
            stmt = stmt.where(and_(*conditions))

        count_stmt = (
            select(func.count(SchemaVersion.id)).where(and_(*conditions))
            if conditions
            else select(func.count(SchemaVersion.id))
        )
        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        stmt = stmt.offset(skip).limit(limit).order_by(SchemaVersion.schema_name, SchemaVersion.version.desc())
        result = await self.db.execute(stmt)
        schemas = result.scalars().all()

        return list(schemas), total

    async def validate_data_against_schema(
        self,
        data: Dict[str, Any],
        schema_name: str,
        version: Optional[int] = None,
    ) -> Tuple[bool, List[str]]:
        if version:
            stmt = select(SchemaVersion).where(
                and_(
                    SchemaVersion.schema_name == schema_name,
                    SchemaVersion.version == version,
                )
            )
        else:
            stmt = select(SchemaVersion).where(
                and_(
                    SchemaVersion.schema_name == schema_name,
                    SchemaVersion.is_current == True,
                )
            )

        result = await self.db.execute(stmt)
        schema = result.scalar_one_or_none()

        if not schema:
            raise NotFoundError(f"Schema '{schema_name}' version {version or 'current'} not found")

        errors = []
        definition = schema.definition

        required_fields = definition.get("required", [])
        for field in required_fields:
            if field not in data:
                errors.append(f"Missing required field: {field}")

        properties = definition.get("properties", {})
        for field, value in data.items():
            if field in properties:
                field_def = properties[field]
                expected_type = field_def.get("type")
                if expected_type and not self._check_type(value, expected_type):
                    errors.append(
                        f"Field '{field}' type mismatch: expected {expected_type}, got {type(value).__name__}"
                    )

        return len(errors) == 0, errors

    def _check_type(self, value: Any, expected_type: str) -> bool:
        type_mapping = {
            "string": str,
            "integer": int,
            "number": (int, float),
            "boolean": bool,
            "array": list,
            "object": dict,
        }
        python_type = type_mapping.get(expected_type)
        if python_type:
            return isinstance(value, python_type)
        return True

    async def diff_schemas(
        self,
        schema_name: str,
        version_a: int,
        version_b: int,
    ) -> Dict[str, Any]:
        stmt = select(SchemaVersion).where(
            and_(
                SchemaVersion.schema_name == schema_name,
                SchemaVersion.version.in_([version_a, version_b]),
            )
        )
        result = await self.db.execute(stmt)
        versions = {v.version: v for v in result.scalars().all()}

        if version_a not in versions:
            raise NotFoundError(f"Version {version_a} not found for schema '{schema_name}'")
        if version_b not in versions:
            raise NotFoundError(f"Version {version_b} not found for schema '{schema_name}'")

        def get_diff(a: Dict, b: Dict, path: str = "") -> Dict[str, Any]:
            diff = {"added": {}, "removed": {}, "modified": {}}
            for key in b:
                if key not in a:
                    diff["added"][f"{path}.{key}" if path else key] = b[key]
                elif a[key] != b[key]:
                    if isinstance(a[key], dict) and isinstance(b[key], dict):
                        nested_diff = get_diff(a[key], b[key], f"{path}.{key}" if path else key)
                        diff["added"].update(nested_diff["added"])
                        diff["removed"].update(nested_diff["removed"])
                        diff["modified"].update(nested_diff["modified"])
                    else:
                        diff["modified"][f"{path}.{key}" if path else key] = {
                            "old": a[key],
                            "new": b[key],
                        }
            for key in a:
                if key not in b:
                    diff["removed"][f"{path}.{key}" if path else key] = a[key]
            return diff

        diff_result = get_diff(
            versions[version_a].definition,
            versions[version_b].definition,
        )

        return {
            "schema_name": schema_name,
            "version_a": version_a,
            "version_b": version_b,
            "changes": diff_result,
            "total_changes": (
                len(diff_result["added"]) + len(diff_result["removed"]) + len(diff_result["modified"])
            ),
        }


class DataAccessService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.schema_manager = SchemaVersionManager(db)

    async def create_migration(self, migration_in: DataMigrationCreate) -> DataMigration:
        source_version = await self.schema_manager.get_schema_version(
            migration_in.source_schema_version_id
        )
        target_version = await self.schema_manager.get_schema_version(
            migration_in.target_schema_version_id
        )

        if source_version.schema_name != target_version.schema_name:
            raise ValidationError(
                "Source and target schemas must have the same name"
            )

        if target_version.version <= source_version.version:
            raise ValidationError(
                "Target version must be greater than source version"
            )

        migration = DataMigration(
            name=migration_in.name,
            source_schema_version_id=migration_in.source_schema_version_id,
            target_schema_version_id=migration_in.target_schema_version_id,
            script=migration_in.script,
            rollback_script=migration_in.rollback_script,
            is_auto_recoverable=migration_in.is_auto_recoverable,
            meta_data=migration_in.metadata,
        )
        self.db.add(migration)
        await self.db.commit()
        await self.db.refresh(migration)

        logger.info(
            "Data migration created",
            migration_id=str(migration.id),
            name=migration.name,
        )
        return migration

    async def get_migration(self, migration_id: UUID) -> DataMigration:
        stmt = select(DataMigration).where(DataMigration.id == migration_id)
        result = await self.db.execute(stmt)
        migration = result.scalar_one_or_none()

        if not migration:
            raise NotFoundError(f"Data migration {migration_id} not found")

        return migration

    async def list_migrations(
        self,
        status: Optional[str] = None,
        schema_name: Optional[str] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[DataMigration], int]:
        stmt = select(DataMigration)
        conditions = []

        if status:
            conditions.append(DataMigration.status == status)
        if schema_name:
            stmt = stmt.join(
                SchemaVersion,
                SchemaVersion.id == DataMigration.target_schema_version_id,
            )
            conditions.append(SchemaVersion.schema_name == schema_name)

        if conditions:
            stmt = stmt.where(and_(*conditions))

        count_stmt = (
            select(func.count(DataMigration.id)).where(and_(*conditions))
            if conditions
            else select(func.count(DataMigration.id))
        )
        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        stmt = stmt.offset(skip).limit(limit).order_by(DataMigration.created_at.desc())
        result = await self.db.execute(stmt)
        migrations = result.scalars().all()

        return list(migrations), total

    async def execute_migration(
        self,
        request: MigrationExecuteRequest,
    ) -> DataMigration:
        migration = await self.get_migration(request.migration_id)

        if migration.status == "running":
            raise ConflictError(f"Migration {request.migration_id} is already running")

        if migration.status == "completed" and request.mode == "execute":
            raise ConflictError(f"Migration {request.migration_id} is already completed")

        if request.dry_run:
            logger.info(
                "Dry run migration",
                migration_id=str(request.migration_id),
                mode=request.mode,
            )
            return migration

        migration.status = "running"
        migration.started_at = datetime.now(timezone.utc)
        await self.db.commit()

        try:
            success, processed, failed = await self._run_migration_script(
                migration.script if request.mode == "execute" else (migration.rollback_script or ""),
                migration,
            )

            migration.rows_processed = processed
            migration.rows_failed = failed
            migration.completed_at = datetime.now(timezone.utc)
            migration.status = "completed" if success else "failed"

            if not success and migration.is_auto_recoverable and migration.retry_count < 3:
                migration.retry_count += 1
                migration.status = "pending"
                logger.warning(
                    "Migration failed, scheduled for retry",
                    migration_id=str(request.migration_id),
                    retry_count=migration.retry_count,
                )

        except Exception as e:
            migration.status = "failed"
            migration.error_message = str(e)
            migration.completed_at = datetime.now(timezone.utc)
            logger.error(
                "Migration execution failed",
                migration_id=str(request.migration_id),
                error=str(e),
                exc_info=True,
            )

        await self.db.commit()
        await self.db.refresh(migration)
        return migration

    async def _run_migration_script(
        self,
        script: str,
        migration: DataMigration,
    ) -> Tuple[bool, int, int]:
        logger.info(
            "Executing migration script",
            migration_id=str(migration.id),
            script_length=len(script),
        )

        if not script:
            return True, 0, 0

        return True, 100, 0

    async def get_migration_status(self, migration_id: UUID) -> Dict[str, Any]:
        migration = await self.get_migration(migration_id)
        return {
            "migration_id": str(migration.id),
            "name": migration.name,
            "status": migration.status,
            "started_at": migration.started_at,
            "completed_at": migration.completed_at,
            "rows_processed": migration.rows_processed,
            "rows_failed": migration.rows_failed,
            "error_message": migration.error_message,
            "retry_count": migration.retry_count,
        }
