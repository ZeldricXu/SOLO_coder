"""Data migration manager for data access module."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional
from uuid import UUID, uuid4

from ...domain.errors.common import ConfigurationError, ValidationError
from ...domain.models.common import ProcessingResult, ProcessingStatus
from ...infrastructure.logging.structured_logger import LogManager
from .schema_manager import SchemaVersionManager


@dataclass
class MigrationStatus:
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    ROLLED_BACK = "rolled_back"


@dataclass
class Migration:
    id: UUID = field(default_factory=uuid4)
    name: str
    description: str
    from_version: str
    to_version: str
    table_name: str
    script: str
    status: str = MigrationStatus.PENDING
    created_at: datetime = field(default_factory=datetime.utcnow)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    error_message: Optional[str] = None
    rollback_script: Optional[str] = None


class DataMigrationManager:
    def __init__(self, schema_manager: SchemaVersionManager) -> None:
        self._schema_manager = schema_manager
        self._migrations: Dict[UUID, Migration] = {}
        self._migration_history: List[Migration] = []
        self._logger = LogManager().get_logger(__name__)
        self._custom_transformers: Dict[str, Callable] = {}

    def create_migration(
        self,
        table_name: str,
        from_version: str,
        to_version: str,
        name: Optional[str] = None,
        description: str = "",
        script: Optional[str] = None,
        rollback_script: Optional[str] = None,
    ) -> Migration:
        migration = Migration(
            name=name or f"migrate_{table_name}_{from_version}_to_{to_version}",
            description=description,
            from_version=from_version,
            to_version=to_version,
            table_name=table_name,
            script=script or "",
            rollback_script=rollback_script,
        )

        self._migrations[migration.id] = migration
        self._logger.info(
            f"Created migration: {migration.name}",
            migration_id=str(migration.id),
            table_name=table_name,
            from_version=from_version,
            to_version=to_version,
        )

        return migration

    async def execute_migration(
        self,
        migration_id: UUID,
        data: Optional[List[Dict[str, Any]]] = None,
        transformer: Optional[Callable[[Dict[str, Any]], Dict[str, Any]]] = None,
    ) -> ProcessingResult:
        migration = self._migrations.get(migration_id)
        if not migration:
            raise ValidationError(
                message=f"Migration not found: {migration_id}",
                suggestion="Check that the migration ID is correct.",
            )

        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        migration.status = MigrationStatus.RUNNING
        migration.started_at = datetime.utcnow()

        try:
            self._logger.info(f"Starting migration: {migration.name}")

            migration_path = self._schema_manager.get_migration_path(
                migration.table_name,
                migration.from_version,
                migration.to_version,
            )

            if not migration_path:
                raise ConfigurationError(
                    message=f"No migration path found from {migration.from_version} to {migration.to_version}",
                    suggestion="Check that both schema versions exist and are properly registered.",
                )

            processed_data: List[Dict[str, Any]] = []

            if data:
                for row in data:
                    processed_row = await self._apply_migration_steps(
                        row,
                        migration_path,
                        transformer,
                    )
                    processed_data.append(processed_row)

                result.results = [
                    {
                        "table_name": migration.table_name,
                        "rows_processed": len(processed_data),
                        "from_version": migration.from_version,
                        "to_version": migration.to_version,
                        "data": processed_data,
                    }
                ]

            migration.status = MigrationStatus.COMPLETED
            migration.completed_at = datetime.utcnow()

            self._schema_manager.set_current_version(
                migration.table_name,
                migration.to_version,
            )

            result.status = ProcessingStatus.SUCCESS
            result.message = f"Migration {migration.name} completed successfully"

            self._logger.info(
                f"Migration completed: {migration.name}",
                rows_processed=len(processed_data),
                duration_ms=result.duration_ms,
            )

        except Exception as e:
            migration.status = MigrationStatus.FAILED
            migration.completed_at = datetime.utcnow()
            migration.error_message = str(e)

            result.status = ProcessingStatus.FAILED
            result.message = f"Migration failed: {str(e)}"
            result.errors.append({"error": str(e)})

            self._logger.error(
                f"Migration failed: {migration.name}",
                error=str(e),
            )

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        self._migration_history.append(migration)

        return result

    async def _apply_migration_steps(
        self,
        row: Dict[str, Any],
        migration_path: List,
        custom_transformer: Optional[Callable[[Dict[str, Any]], Dict[str, Any]]],
    ) -> Dict[str, Any]:
        current_row = row.copy()

        for schema_version in migration_path:
            schema = schema_version.schema

            if schema_version.migration_script:
                try:
                    namespace = {"row": current_row, "schema": schema}
                    exec(schema_version.migration_script, namespace)
                    current_row = namespace["row"]
                except Exception as e:
                    self._logger.warning(
                        f"Error executing migration script for version {schema_version.version}: {e}")
                    raise

            field_map = {f.name: f for f in schema.fields}

            transformed_row: Dict[str, Any] = {}
            for field_name, field in field_map.items():
                if field_name in current_row:
                    transformed_row[field_name] = current_row[field_name]
                elif not field.nullable:
                    transformed_row[field_name] = self._get_default_value(field.data_type)

            current_row.update(transformed_row)

            if custom_transformer:
                current_row = custom_transformer(current_row, schema_version.version)

        return current_row

    def _get_default_value(self, data_type: str) -> Any:
        defaults = {
            "string": "",
            "integer": 0,
            "float": 0.0,
            "boolean": False,
            "datetime": None,
            "json": {},
            "array": [],
        }
        return defaults.get(data_type.lower(), None)

    async def rollback_migration(self, migration_id: UUID) -> ProcessingResult:
        migration = self._migrations.get(migration_id)
        if not migration:
            raise ValidationError(
                message=f"Migration not found: {migration_id}",
                suggestion="Check that the migration ID is correct.",
            )

        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        try:
            self._logger.info(f"Rolling back migration: {migration.name}")

            if migration.rollback_script:
                namespace = {}
                exec(migration.rollback_script, namespace)

            self._schema_manager.set_current_version(
                    migration.table_name,
                    migration.from_version,
                )

            migration.status = MigrationStatus.ROLLED_BACK

            result.status = ProcessingStatus.SUCCESS
            result.message = f"Migration {migration.name} rolled back successfully"

            self._logger.info(f"Migration rolled back: {migration.name}")

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.message = f"Rollback failed: {str(e)}"
            result.errors.append({"error": str(e)})

            self._logger.error(f"Rollback failed: {migration.name}", error=str(e))

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        return result

    def get_migration(self, migration_id: UUID) -> Optional[Migration]:
        return self._migrations.get(migration_id)

    def list_migrations(self, table_name: Optional[str] = None) -> List[Migration]:
        migrations = list(self._migrations.values())
        if table_name:
            migrations = [m for m in migrations if m.table_name == table_name]
        return sorted(migrations, key=lambda m: m.created_at)

    def get_migration_history(self, table_name: Optional[str] = None) -> List[Migration]:
        history = self._migration_history
        if table_name:
            history = [m for m in history if m.table_name == table_name]
        return sorted(history, key=lambda m: m.created_at, reverse=True)

    def register_transformer(self, name: str, transformer: Callable) -> None:
        self._custom_transformers[name] = transformer
        self._logger.info(f"Registered custom transformer: {name}")

    async def migrate_data(
        self,
        table_name: str,
        from_version: str,
        to_version: str,
        data: List[Dict[str, Any]],
        transformer: Optional[Callable[[Dict[str, Any]], Dict[str, Any]]] = None,
    ) -> ProcessingResult:
        migration = self.create_migration(
            table_name=table_name,
            from_version=from_version,
            to_version=to_version,
        )
        return await self.execute_migration(migration.id, data, transformer)

    def validate_migration_path(self, table_name: str, from_version: str, to_version: str) -> tuple[bool, List[str]]:
        errors: List[str] = []

        from_schema = self._schema_manager.get_schema(table_name, from_version)
        to_schema = self._schema_manager.get_schema(table_name, to_version)

        if from_schema is None:
            errors.append(f"Source schema version {from_version} not found")

        if to_schema is None:
            errors.append(f"Target schema version {to_version} not found")

        if errors:
            return False, errors

        migration_path = self._schema_manager.get_migration_path(table_name, from_version, to_version)
        if not migration_path:
            errors.append("No migration path found between versions")
            return False, errors

        for version in migration_path:
            if not version.backward_compatible:
                errors.append(f"Migration to version {version.version} is not backward compatible")

        return len(errors) == 0, errors
