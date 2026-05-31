"""Data access module for data migration and schema version control."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, AsyncGenerator, Dict, List, Optional
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...domain.models.common import EventMessage, ProcessingResult, ProcessingStatus
from ...infrastructure.logging.structured_logger import LogManager
from ...infrastructure.config.settings import Settings
from .schema_manager import SchemaVersionManager
from .data_migration import DataMigrationManager, Migration


class DataAccessModule:
    def __init__(self, settings: Optional[Settings] = None) -> None:
        self._settings = settings or Settings.model_validate({
            "storage": {
                "hot": {"path": "./data/hot", "max_size_gb": 100},
                "cold": {"path": "./data/cold", "max_size_gb": 500},
                "archive": {"path": "./data/archive", "max_size_gb": 2000},
            },
            "messaging": {
                "kafka": {
                    "bootstrap_servers": "localhost:9092",
                    "topics": {
                        "storage_events": "storage.events",
                        "lifecycle_events": "lifecycle.events",
                    },
                }
            },
        })
        self._schema_manager = SchemaVersionManager()
        self._migration_manager = DataMigrationManager(self._schema_manager)
        self._logger = LogManager().get_logger(__name__)

    @property
    def schema_manager(self) -> SchemaVersionManager:
        return self._schema_manager

    @property
    def migration_manager(self) -> DataMigrationManager:
        return self._migration_manager

    async def process_event(self, event: EventMessage) -> ProcessingResult:
        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        try:
            event_type = event.event_type
            payload = event.payload

            if event_type == "schema.register":
                register_result = await self._handle_schema_register(payload)
                result.results = [register_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Schema registered successfully"

            elif event_type == "schema.validate":
                valid, errors = await self._handle_schema_validate(payload)
                result.results = [{"valid": valid, "errors": errors}]
                result.status = ProcessingStatus.SUCCESS if valid else ProcessingStatus.FAILED
                result.message = "Schema validation completed"

            elif event_type == "data.migrate":
                migration_result = await self._handle_data_migrate(payload)
                result.results = [migration_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Data migration completed successfully"

            elif event_type == "migration.execute":
                migration_result = await self._handle_migration_execute(payload)
                result.results = [migration_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Migration executed successfully"

            elif event_type == "migration.rollback":
                rollback_result = await self._handle_migration_rollback(payload)
                result.results = [rollback_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Migration rolled back successfully"

            else:
                raise ValidationError(
                    message=f"Unknown event type: {event_type}",
                    suggestion="Check the event type and try again.",
                )

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.message = f"Data access event processing failed: {str(e)}"
            result.errors.append({"error": str(e)})

            self._logger.error(
                "Data access event processing failed",
                event_type=event.event_type,
                error=str(e),
            )

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        return result

    async def _handle_schema_register(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        schema_data = payload.get("schema")
        if not schema_data:
            raise ValidationError(
                message="Schema data is required",
                suggestion="Provide schema data in the payload.",
            )

        schema_version = self._schema_manager.import_schema(schema_data)

        return {
            "table_name": schema_version.schema.table_name,
            "version": schema_version.version,
            "fields_count": len(schema_version.schema.fields),
            "backward_compatible": schema_version.backward_compatible,
        }

    async def _handle_schema_validate(self, payload: Dict[str, Any]) -> tuple[bool, List[str]]:
        data = payload.get("data", {})
        table_name = payload.get("table_name")
        version = payload.get("version")

        if not table_name:
            raise ValidationError(
                message="Table name is required for validation",
                suggestion="Provide table_name in the payload.",
            )

        return self._schema_manager.validate_data_against_schema(
            data,
            table_name,
            version,
        )

    async def _handle_data_migrate(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        table_name = payload.get("table_name")
        from_version = payload.get("from_version")
        to_version = payload.get("to_version")
        data = payload.get("data", [])

        if not all([table_name, from_version, to_version]):
            raise ValidationError(
                message="table_name, from_version, and to_version are required",
                suggestion="Provide all required parameters in the payload.",
            )

        migration_result = await self._migration_manager.migrate_data(
            table_name=table_name,
            from_version=from_version,
            to_version=to_version,
            data=data,
        )

        return {
            "table_name": table_name,
            "from_version": from_version,
            "to_version": to_version,
            "rows_processed": len(data),
            "duration_ms": migration_result.duration_ms,
            "data": migration_result.results[0]["data"] if migration_result.results else [],
        }

    async def _handle_migration_execute(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        migration_id = payload.get("migration_id")
        data = payload.get("data")

        if not migration_id:
            raise ValidationError(
                message="Migration ID is required",
                suggestion="Provide migration_id in the payload.",
            )

        migration_uuid = UUID(migration_id)
        result = await self._migration_manager.execute_migration(migration_uuid, data)

        return {
            "migration_id": migration_id,
            "status": result.status.value,
            "duration_ms": result.duration_ms,
            "message": result.message,
        }

    async def _handle_migration_rollback(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        migration_id = payload.get("migration_id")

        if not migration_id:
            raise ValidationError(
                message="Migration ID is required",
                suggestion="Provide migration_id in the payload.",
            )

        migration_uuid = UUID(migration_id)
        result = await self._migration_manager.rollback_migration(migration_uuid)

        return {
            "migration_id": migration_id,
            "status": result.status.value,
            "duration_ms": result.duration_ms,
            "message": result.message,
        }

    def create_migration(
        self,
        table_name: str,
        from_version: str,
        to_version: str,
        **kwargs: Any,
    ) -> Migration:
        return self._migration_manager.create_migration(
            table_name=table_name,
            from_version=from_version,
            to_version=to_version,
            **kwargs,
        )

    def get_schema(self, table_name: str, version: Optional[str] = None) -> Optional[Dict[str, Any]]:
        return self._schema_manager.export_schema(table_name, version)

    def list_schemas(self) -> List[str]:
        return self._schema_manager.list_tables()

    def get_schema_versions(self, table_name: str) -> List[Dict[str, Any]]:
        versions = self._schema_manager.get_all_versions(table_name)
        return [
            {
                "version": v.version,
                "created_at": v.created_at.isoformat(),
                "backward_compatible": v.backward_compatible,
                "notes": v.notes,
            }
            for v in versions
        ]

    def compare_schemas(
        self,
        table_name: str,
        version1: str,
        version2: str,
    ) -> Dict[str, Any]:
        return self._schema_manager.compare_schemas(table_name, version1, version2)

    def validate_migration_path(
        self,
        table_name: str,
        from_version: str,
        to_version: str,
    ) -> Dict[str, Any]:
        valid, errors = self._migration_manager.validate_migration_path(
            table_name,
            from_version,
            to_version,
        )
        return {"valid": valid, "errors": errors}

    def list_migrations(self, table_name: Optional[str] = None) -> List[Dict[str, Any]]:
        migrations = self._migration_manager.list_migrations(table_name)
        return [
            {
                "id": str(m.id),
                "name": m.name,
                "description": m.description,
                "table_name": m.table_name,
                "from_version": m.from_version,
                "to_version": m.to_version,
                "status": m.status,
                "created_at": m.created_at.isoformat(),
            }
            for m in migrations
        ]

    def get_migration_history(self, table_name: Optional[str] = None) -> List[Dict[str, Any]]:
        history = self._migration_manager.get_migration_history(table_name)
        return [
            {
                "id": str(m.id),
                "name": m.name,
                "table_name": m.table_name,
                "from_version": m.from_version,
                "to_version": m.to_version,
                "status": m.status,
                "started_at": m.started_at.isoformat() if m.started_at else None,
                "completed_at": m.completed_at.isoformat() if m.completed_at else None,
                "error_message": m.error_message,
                "duration_ms": (
                    (m.completed_at - m.started_at).total_seconds() * 1000
                    if m.started_at and m.completed_at
                    else None
                ),
            }
            for m in history
        ]

    async def batch_migrate(
        self,
        migrations: List[Dict[str, Any]],
        stop_on_error: bool = True,
    ) -> AsyncGenerator[Dict[str, Any], None]:
        for migration_config in migrations:
            try:
                result = await self.process_event(
                    EventMessage(
                        event_type="data.migrate",
                        payload=migration_config,
                        source="data_access_module",
                    )
                )
                yield {
                    "table_name": migration_config.get("table_name"),
                    "status": result.status.value,
                    "duration_ms": result.duration_ms,
                    "error": result.errors[0] if result.errors else None,
                }

                if result.status == ProcessingStatus.FAILED and stop_on_error:
                    break

            except Exception as e:
                yield {
                    "table_name": migration_config.get("table_name"),
                    "status": ProcessingStatus.FAILED.value,
                    "error": str(e),
                }
                if stop_on_error:
                    break

    def get_current_schema_version(self, table_name: str) -> Optional[str]:
        return self._schema_manager.get_current_version(table_name)

    def set_current_schema_version(self, table_name: str, version: str) -> bool:
        return self._schema_manager.set_current_version(table_name, version)
