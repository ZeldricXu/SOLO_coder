from datetime import datetime
from typing import Any, Dict, List, Optional, Callable, AsyncIterator, Union, Tuple
from dataclasses import dataclass, field
from enum import Enum
import hashlib
import json
import asyncio
from pathlib import Path
import uuid
from collections import deque

from app.core.logger import logger
from app.core.events import event_bus, EventType, Event, build_event
from app.core.models import RunInstance, PhaseStatus, ResourceStatus, BaseEntity


class MigrationStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    VALIDATING = "validating"
    COMPLETED = "completed"
    FAILED = "failed"
    ROLLBACK = "rollback"
    PAUSED = "paused"


class StreamMode(str, Enum):
    BATCH = "batch"
    STREAM = "stream"
    HYBRID = "hybrid"


@dataclass
class StreamMetrics:
    bytes_processed: int = 0
    records_processed: int = 0
    batches_processed: int = 0
    throughput_records_per_sec: float = 0.0
    throughput_bytes_per_sec: float = 0.0
    avg_latency_ms: float = 0.0
    p95_latency_ms: float = 0.0
    p99_latency_ms: float = 0.0
    errors: int = 0
    retries: int = 0
    backpressure_count: int = 0


@dataclass
class StreamBuffer:
    max_size: int = 1000
    flush_timeout: float = 5.0
    _buffer: deque = field(default_factory=deque)
    _last_flush: datetime = field(default_factory=datetime.utcnow)

    def add(self, item: Any) -> bool:
        self._buffer.append(item)
        return len(self._buffer) >= self.max_size

    def should_flush(self) -> bool:
        if len(self._buffer) >= self.max_size:
            return True
        elapsed = (datetime.utcnow() - self._last_flush).total_seconds()
        return len(self._buffer) > 0 and elapsed >= self.flush_timeout

    def flush(self) -> List[Any]:
        items = list(self._buffer)
        self._buffer.clear()
        self._last_flush = datetime.utcnow()
        return items

    def size(self) -> int:
        return len(self._buffer)

    def clear(self):
        self._buffer.clear()
        self._last_flush = datetime.utcnow()


@dataclass
class SchemaVersion:
    version: int
    hash: str
    definition: Dict[str, Any]
    created_at: datetime
    description: str = ""
    migrations: List[str] = field(default_factory=list)


@dataclass
class MigrationTask:
    task_id: str
    source: str
    target: str
    table_name: str
    batch_size: int = 1000
    stream_mode: StreamMode = StreamMode.BATCH
    status: MigrationStatus = MigrationStatus.PENDING
    records_processed: int = 0
    total_records: int = 0
    error_message: Optional[str] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    stream_buffer: Optional[StreamBuffer] = None
    metrics: StreamMetrics = field(default_factory=StreamMetrics)
    checkpoint: Optional[Dict[str, Any]] = None


@dataclass
class StreamChunk:
    chunk_id: str
    batch_number: int
    records: List[Any]
    timestamp: datetime = field(default_factory=datetime.utcnow)
    size_bytes: int = 0
    checksum: str = ""


@dataclass
class Checkpoint:
    checkpoint_id: str
    task_id: str
    last_processed_offset: int
    last_processed_id: Optional[str]
    timestamp: datetime = field(default_factory=datetime.utcnow)
    metadata: Dict[str, Any] = field(default_factory=dict)


class StreamProcessor:
    def __init__(self, buffer_size: int = 1000, flush_interval: float = 5.0,
                 max_concurrent_batches: int = 3):
        self._buffer = StreamBuffer(max_size=buffer_size, flush_timeout=flush_interval)
        self._max_concurrent = max_concurrent_batches
        self._semaphore = asyncio.Semaphore(max_concurrent_batches)
        self._latency_samples: List[float] = []
        self._processed_count = 0
        self._processed_bytes = 0
        self._start_time: Optional[datetime] = None

    async def stream_process(self, source_iterator: AsyncIterator[Any],
                              writer: Callable[[List[Any]], Any],
                              progress_callback: Optional[Callable[[int, int], None]] = None
                              ) -> Tuple[int, int, StreamMetrics]:
        self._start_time = datetime.utcnow()
        self._buffer.clear()
        self._latency_samples.clear()
        self._processed_count = 0
        self._processed_bytes = 0

        batch_number = 0

        async for item in source_iterator:
            chunk_start = datetime.utcnow()
            should_flush = self._buffer.add(item)

            self._processed_count += 1
            self._processed_bytes += len(json.dumps(item, default=str).encode("utf-8"))

            if progress_callback:
                progress_callback(self._processed_count, self._buffer.size())

            if should_flush:
                batch_number += 1
                await self._flush_buffer(writer, batch_number)

            chunk_end = datetime.utcnow()
            self._latency_samples.append(
                (chunk_end - chunk_start).total_seconds() * 1000
            )

        if self._buffer.size() > 0:
            batch_number += 1
            await self._flush_buffer(writer, batch_number)

        metrics = self._compute_metrics(batch_number)
        return self._processed_count, self._processed_bytes, metrics

    async def _flush_buffer(self, writer: Callable[[List[Any]], Any], batch_number: int):
        batch = self._buffer.flush()
        if not batch:
            return

        async with self._semaphore:
            try:
                checksum = self._compute_batch_checksum(batch)
                chunk = StreamChunk(
                    chunk_id=f"chunk_{uuid.uuid4().hex[:8]}",
                    batch_number=batch_number,
                    records=batch,
                    size_bytes=len(json.dumps(batch, default=str).encode("utf-8")),
                    checksum=checksum
                )
                await writer(batch)
                logger.debug(f"Flushed batch {batch_number}: {len(batch)} records, checksum={checksum[:12]}...")
            except Exception as e:
                logger.error(f"Failed to flush batch {batch_number}: {e}")
                raise

    def _compute_batch_checksum(self, batch: List[Any]) -> str:
        content = json.dumps(batch, default=str, sort_keys=True)
        return hashlib.sha256(content.encode("utf-8")).hexdigest()

    def _compute_metrics(self, total_batches: int) -> StreamMetrics:
        metrics = StreamMetrics(
            records_processed=self._processed_count,
            bytes_processed=self._processed_bytes,
            batches_processed=total_batches
        )

        if self._start_time and self._processed_count > 0:
            elapsed = (datetime.utcnow() - self._start_time).total_seconds()
            if elapsed > 0:
                metrics.throughput_records_per_sec = self._processed_count / elapsed
                metrics.throughput_bytes_per_sec = self._processed_bytes / elapsed

        if self._latency_samples:
            sorted_latencies = sorted(self._latency_samples)
            n = len(sorted_latencies)
            metrics.avg_latency_ms = sum(sorted_latencies) / n
            metrics.p95_latency_ms = sorted_latencies[int(n * 0.95)] if n > 0 else 0
            metrics.p99_latency_ms = sorted_latencies[int(n * 0.99)] if n > 0 else 0

        return metrics

    def get_current_stats(self) -> Dict[str, Any]:
        return {
            "buffer_size": self._buffer.size(),
            "processed_count": self._processed_count,
            "processed_bytes": self._processed_bytes,
            "latency_samples_count": len(self._latency_samples)
        }


class CheckpointManager:
    def __init__(self, storage_dir: Optional[str] = None):
        from app.core.config import settings
        self._storage_dir = Path(storage_dir) if storage_dir else Path(settings.storage_path) / "checkpoints"
        self._storage_dir.mkdir(parents=True, exist_ok=True)
        self._checkpoints: Dict[str, Checkpoint] = {}

    def save_checkpoint(self, task_id: str, offset: int, last_id: Optional[str] = None,
                        metadata: Optional[Dict[str, Any]] = None) -> Checkpoint:
        checkpoint = Checkpoint(
            checkpoint_id=f"cp_{uuid.uuid4().hex[:8]}",
            task_id=task_id,
            last_processed_offset=offset,
            last_processed_id=last_id,
            metadata=metadata or {}
        )
        self._checkpoints[task_id] = checkpoint
        self._persist_checkpoint(checkpoint)
        logger.info(f"Saved checkpoint for task {task_id}: offset={offset}")
        return checkpoint

    def get_checkpoint(self, task_id: str) -> Optional[Checkpoint]:
        return self._checkpoints.get(task_id)

    def load_checkpoint(self, task_id: str) -> Optional[Checkpoint]:
        checkpoint = self._checkpoints.get(task_id)
        if checkpoint:
            return checkpoint

        checkpoint_file = self._storage_dir / f"checkpoint_{task_id}.json"
        if checkpoint_file.exists():
            try:
                with open(checkpoint_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                checkpoint = Checkpoint(
                    checkpoint_id=data["checkpoint_id"],
                    task_id=data["task_id"],
                    last_processed_offset=data["last_processed_offset"],
                    last_processed_id=data.get("last_processed_id"),
                    timestamp=datetime.fromisoformat(data["timestamp"]),
                    metadata=data.get("metadata", {})
                )
                self._checkpoints[task_id] = checkpoint
                return checkpoint
            except Exception as e:
                logger.error(f"Failed to load checkpoint for {task_id}: {e}")
        return None

    def _persist_checkpoint(self, checkpoint: Checkpoint):
        try:
            checkpoint_file = self._storage_dir / f"checkpoint_{checkpoint.task_id}.json"
            with open(checkpoint_file, "w", encoding="utf-8") as f:
                json.dump({
                    "checkpoint_id": checkpoint.checkpoint_id,
                    "task_id": checkpoint.task_id,
                    "last_processed_offset": checkpoint.last_processed_offset,
                    "last_processed_id": checkpoint.last_processed_id,
                    "timestamp": checkpoint.timestamp.isoformat(),
                    "metadata": checkpoint.metadata
                }, f, indent=2)
        except Exception as e:
            logger.error(f"Failed to persist checkpoint: {e}")

    def clear_checkpoint(self, task_id: str):
        if task_id in self._checkpoints:
            del self._checkpoints[task_id]
        checkpoint_file = self._storage_dir / f"checkpoint_{task_id}.json"
        if checkpoint_file.exists():
            checkpoint_file.unlink()


class BackpressureController:
    def __init__(self, high_watermark: int = 10000, low_watermark: int = 2000,
                 poll_interval: float = 0.1):
        self._high_watermark = high_watermark
        self._low_watermark = low_watermark
        self._poll_interval = poll_interval
        self._is_paused = False
        self._current_load = 0
        self._backpressure_events = 0

    async def wait_if_needed(self, current_buffer_size: int):
        self._current_load = current_buffer_size

        if current_buffer_size >= self._high_watermark and not self._is_paused:
            self._is_paused = True
            self._backpressure_events += 1
            logger.warning(f"Backpressure activated: buffer={current_buffer_size} >= {self._high_watermark}")

        if self._is_paused:
            while current_buffer_size >= self._low_watermark:
                await asyncio.sleep(self._poll_interval)
            self._is_paused = False
            logger.info(f"Backpressure released: buffer={current_buffer_size} < {self._low_watermark}")

    def get_stats(self) -> Dict[str, Any]:
        return {
            "is_paused": self._is_paused,
            "current_load": self._current_load,
            "high_watermark": self._high_watermark,
            "low_watermark": self._low_watermark,
            "backpressure_events": self._backpressure_events
        }


class SchemaVersionController:
    def __init__(self, storage_dir: Optional[str] = None):
        from app.core.config import settings
        self._storage_dir = Path(storage_dir) if storage_dir else Path(settings.storage_path) / "schema"
        self._storage_dir.mkdir(parents=True, exist_ok=True)
        self._versions_file = self._storage_dir / "schema_versions.json"
        self._versions: List[SchemaVersion] = []
        self._current_version: int = 0
        self._load_versions()

    def _load_versions(self):
        if self._versions_file.exists():
            try:
                with open(self._versions_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                self._versions = [
                    SchemaVersion(
                        version=v["version"],
                        hash=v["hash"],
                        definition=v["definition"],
                        created_at=datetime.fromisoformat(v["created_at"]),
                        description=v.get("description", ""),
                        migrations=v.get("migrations", [])
                    )
                    for v in data
                ]
                if self._versions:
                    self._current_version = max(v.version for v in self._versions)
            except Exception as e:
                logger.error(f"Failed to load schema versions: {e}")
                self._versions = []

    def _save_versions(self):
        try:
            with open(self._versions_file, "w", encoding="utf-8") as f:
                json.dump([
                    {
                        "version": v.version,
                        "hash": v.hash,
                        "definition": v.definition,
                        "created_at": v.created_at.isoformat(),
                        "description": v.description,
                        "migrations": v.migrations
                    }
                    for v in self._versions
                ], f, indent=2, default=str)
        except Exception as e:
            logger.error(f"Failed to save schema versions: {e}")
            raise

    def _compute_hash(self, definition: Dict[str, Any], version: int) -> str:
        content = json.dumps({"version": version, "definition": definition}, sort_keys=True)
        return hashlib.sha256(content.encode("utf-8")).hexdigest()

    def create_version(self, definition: Dict[str, Any], description: str = "",
                        migration_scripts: Optional[List[str]] = None) -> SchemaVersion:
        new_version = self._current_version + 1
        hash_val = self._compute_hash(definition, new_version)
        version = SchemaVersion(
            version=new_version,
            hash=hash_val,
            definition=definition,
            created_at=datetime.utcnow(),
            description=description,
            migrations=migration_scripts or []
        )
        self._versions.append(version)
        self._current_version = new_version
        self._save_versions()
        logger.info(f"Created schema version {new_version}")
        return version

    def get_current_version(self) -> SchemaVersion:
        if not self._versions:
            return SchemaVersion(
                version=0,
                hash=self._compute_hash({}, 0),
                definition={},
                created_at=datetime.utcnow()
            )
        return max(self._versions, key=lambda v: v.version)

    def get_version(self, version: int) -> Optional[SchemaVersion]:
        for v in self._versions:
            if v.version == version:
                return v
        return None

    def list_versions(self) -> List[SchemaVersion]:
        return sorted(self._versions, key=lambda v: v.version, reverse=True)

    def validate_version(self, version: int, definition: Dict[str, Any]) -> bool:
        schema = self.get_version(version)
        if not schema:
            return False
        expected_hash = self._compute_hash(definition, version)
        return schema.hash == expected_hash

    def compare_versions(self, v1: int, v2: int) -> Dict[str, Any]:
        schema1 = self.get_version(v1)
        schema2 = self.get_version(v2)
        if not schema1 or not schema2:
            return {"error": "Version not found"}

        added = set(schema2.definition.keys()) - set(schema1.definition.keys())
        removed = set(schema1.definition.keys()) - set(schema2.definition.keys())
        modified = []
        for key in set(schema1.definition.keys()) & set(schema2.definition.keys()):
            if schema1.definition[key] != schema2.definition[key]:
                modified.append(key)

        return {
            "from_version": v1,
            "to_version": v2,
            "added_fields": list(added),
            "removed_fields": list(removed),
            "modified_fields": modified,
            "is_compatible": len(removed) == 0
        }


class DataMigrationService:
    def __init__(self, schema_controller: SchemaVersionController):
        self._schema_controller = schema_controller
        self._migrations: Dict[str, MigrationTask] = {}
        self._validation_handlers: Dict[str, Callable] = {}
        self._compensation_handlers: Dict[str, Callable] = {}
        self._checkpoint_manager = CheckpointManager()
        self._stream_processors: Dict[str, StreamProcessor] = {}
        self._backpressure_controllers: Dict[str, BackpressureController] = {}

    def register_validation(self, table_name: str, handler: Callable):
        self._validation_handlers[table_name] = handler

    def register_compensation(self, table_name: str, handler: Callable):
        self._compensation_handlers[table_name] = handler

    def create_migration_task(self, source: str, target: str, table_name: str,
                               total_records: int = 0, batch_size: int = 1000,
                               stream_mode: StreamMode = StreamMode.BATCH,
                               enable_checkpoint: bool = True) -> MigrationTask:
        task = MigrationTask(
            task_id=f"mig_{uuid.uuid4().hex[:8]}",
            source=source,
            target=target,
            table_name=table_name,
            batch_size=batch_size,
            stream_mode=stream_mode,
            total_records=total_records,
            stream_buffer=StreamBuffer(max_size=batch_size) if stream_mode != StreamMode.BATCH else None
        )
        self._migrations[task.task_id] = task

        if stream_mode != StreamMode.BATCH:
            self._stream_processors[task.task_id] = StreamProcessor(
                buffer_size=batch_size,
                max_concurrent_batches=3
            )
            self._backpressure_controllers[task.task_id] = BackpressureController(
                high_watermark=batch_size * 10,
                low_watermark=batch_size * 2
            )

        if enable_checkpoint:
            checkpoint = self._checkpoint_manager.load_checkpoint(task.task_id)
            if checkpoint:
                task.checkpoint = {
                    "checkpoint_id": checkpoint.checkpoint_id,
                    "last_offset": checkpoint.last_processed_offset,
                    "last_id": checkpoint.last_processed_id
                }
                task.records_processed = checkpoint.last_processed_offset
                logger.info(f"Resuming migration {task.task_id} from checkpoint: offset={checkpoint.last_processed_offset}")

        logger.info(f"Created migration task: {task.task_id} for {table_name}, mode={stream_mode}")
        return task

    async def execute_migration(self, task_id: str, data_generator: Callable,
                                 writer: Callable, validate: bool = True,
                                 checkpoint_interval: int = 10000) -> MigrationTask:
        task = self._migrations.get(task_id)
        if not task:
            raise ValueError(f"Migration task {task_id} not found")

        task.started_at = datetime.utcnow()
        task.status = MigrationStatus.RUNNING

        try:
            if validate and task.table_name in self._validation_handlers:
                self._validation_handlers[task.table_name](task)
                task.status = MigrationStatus.VALIDATING

            if task.stream_mode == StreamMode.STREAM:
                await self._execute_stream_migration(task, data_generator, writer, checkpoint_interval)
            else:
                await self._execute_batch_migration(task, data_generator, writer, checkpoint_interval)

            if validate:
                await self._validate_integrity(task, task.records_processed)

            task.status = MigrationStatus.COMPLETED
            task.completed_at = datetime.utcnow()
            self._checkpoint_manager.clear_checkpoint(task_id)
            logger.info(f"Migration {task_id} completed successfully: {task.records_processed} records")

        except Exception as e:
            logger.error(f"Migration {task_id} failed: {e}")
            task.status = MigrationStatus.FAILED
            task.error_message = str(e)
            await self._execute_compensation(task)

        return task

    async def _execute_batch_migration(self, task: MigrationTask,
                                        data_generator: Callable,
                                        writer: Callable,
                                        checkpoint_interval: int):
        records_processed = task.records_processed
        batches_processed = 0
        last_checkpoint = records_processed

        async for batch in self._batch_generator(data_generator, task.batch_size):
            records = await writer(batch)
            records_processed += len(batch)
            batches_processed += 1
            task.records_processed = records_processed
            task.metrics.batches_processed = batches_processed
            task.metrics.records_processed = records_processed

            if records_processed - last_checkpoint >= checkpoint_interval:
                self._checkpoint_manager.save_checkpoint(
                    task.task_id, records_processed,
                    metadata={"batch_number": batches_processed}
                )
                last_checkpoint = records_processed

            event_bus.emit(build_event(EventType.DATA_MIGRATED, {
                "task_id": task.task_id,
                "table_name": task.table_name,
                "records_processed": records_processed,
                "total_records": task.total_records,
                "mode": "batch"
            }))

    async def _execute_stream_migration(self, task: MigrationTask,
                                         data_generator: Callable,
                                         writer: Callable,
                                         checkpoint_interval: int):
        processor = self._stream_processors.get(task.task_id)
        backpressure = self._backpressure_controllers.get(task.task_id)
        if not processor:
            raise ValueError(f"Stream processor not initialized for task {task.task_id}")

        last_checkpoint = task.records_processed

        async def progress_callback(count: int, buffer_size: int):
            task.records_processed = count
            task.metrics.records_processed = count

            if backpressure:
                await backpressure.wait_if_needed(buffer_size)
                task.metrics.backpressure_count = backpressure._backpressure_events

            if count - last_checkpoint >= checkpoint_interval:
                self._checkpoint_manager.save_checkpoint(task.task_id, count)

            if task.total_records > 0:
                event_bus.emit(build_event(EventType.DATA_MIGRATED, {
                    "task_id": task.task_id,
                    "table_name": task.table_name,
                    "records_processed": count,
                    "total_records": task.total_records,
                    "mode": "stream",
                    "progress": count / task.total_records
                }))

        count, bytes_processed, metrics = await processor.stream_process(
            data_generator(),
            writer,
            progress_callback
        )

        task.metrics = metrics
        task.metrics.bytes_processed = bytes_processed

    async def _batch_generator(self, generator: Callable, batch_size: int):
        items = []
        async for item in generator():
            items.append(item)
            if len(items) >= batch_size:
                yield items
                items = []
        if items:
            yield items

    async def _validate_integrity(self, task: MigrationTask, migrated_count: int):
        if task.total_records > 0 and migrated_count != task.total_records:
            raise ValueError(
                f"Integrity check failed: expected {task.total_records}, got {migrated_count}"
            )

    async def _execute_compensation(self, task: MigrationTask):
        if task.table_name in self._compensation_handlers:
            try:
                task.status = MigrationStatus.ROLLBACK
                await self._compensation_handlers[task.table_name](task)
                logger.info(f"Compensation executed for {task.task_id}")
            except Exception as e:
                logger.error(f"Compensation failed for {task.task_id}: {e}")

    def get_task_status(self, task_id: str) -> Optional[MigrationTask]:
        return self._migrations.get(task_id)

    def get_task_metrics(self, task_id: str) -> Optional[Dict[str, Any]]:
        task = self._migrations.get(task_id)
        if not task:
            return None

        metrics = task.metrics
        return {
            "task_id": task_id,
            "status": task.status,
            "stream_mode": task.stream_mode,
            "records_processed": metrics.records_processed,
            "total_records": task.total_records,
            "progress": metrics.records_processed / max(task.total_records, 1),
            "throughput_records_per_sec": metrics.throughput_records_per_sec,
            "throughput_bytes_per_sec": metrics.throughput_bytes_per_sec,
            "avg_latency_ms": metrics.avg_latency_ms,
            "p95_latency_ms": metrics.p95_latency_ms,
            "p99_latency_ms": metrics.p99_latency_ms,
            "batches_processed": metrics.batches_processed,
            "bytes_processed": metrics.bytes_processed,
            "errors": metrics.errors,
            "retries": metrics.retries,
            "backpressure_events": metrics.backpressure_count,
            "has_checkpoint": task.checkpoint is not None
        }

    def list_tasks(self, status: Optional[MigrationStatus] = None) -> List[MigrationTask]:
        tasks = list(self._migrations.values())
        if status:
            tasks = [t for t in tasks if t.status == status]
        return tasks

    def pause_migration(self, task_id: str) -> bool:
        task = self._migrations.get(task_id)
        if task and task.status == MigrationStatus.RUNNING:
            task.status = MigrationStatus.PAUSED
            logger.info(f"Paused migration: {task_id}")
            return True
        return False

    def resume_migration(self, task_id: str) -> bool:
        task = self._migrations.get(task_id)
        if task and task.status == MigrationStatus.PAUSED:
            task.status = MigrationStatus.RUNNING
            logger.info(f"Resumed migration: {task_id}")
            return True
        return False

    def cancel_migration(self, task_id: str) -> bool:
        task = self._migrations.get(task_id)
        if task and task.status in [MigrationStatus.RUNNING, MigrationStatus.PAUSED]:
            task.status = MigrationStatus.FAILED
            task.error_message = "Cancelled by user"
            logger.info(f"Cancelled migration: {task_id}")
            return True
        return False


class DataAccessModule:
    def __init__(self):
        self._schema_controller = SchemaVersionController()
        self._migration_service = DataMigrationService(self._schema_controller)
        self._resources: Dict[str, BaseEntity] = {}
        logger.info("DataAccessModule initialized")

    @property
    def schema_controller(self) -> SchemaVersionController:
        return self._schema_controller

    @property
    def migration_service(self) -> DataMigrationService:
        return self._migration_service

    def create_resource(self, entity_type: str, attributes: Dict[str, Any]) -> BaseEntity:
        entity = BaseEntity(
            type=entity_type,
            attributes=attributes,
            status=ResourceStatus.ACTIVE
        )
        self._resources[entity.id] = entity
        logger.info(f"Created resource: {entity.id}")
        return entity

    def get_resource(self, resource_id: str) -> Optional[BaseEntity]:
        return self._resources.get(resource_id)

    def update_resource(self, resource_id: str, attributes: Dict[str, Any]) -> Optional[BaseEntity]:
        resource = self._resources.get(resource_id)
        if resource:
            resource.attributes.update(attributes)
            resource.updated_at = datetime.utcnow()
            logger.info(f"Updated resource: {resource_id}")
            return resource
        return None

    def delete_resource(self, resource_id: str) -> bool:
        if resource_id in self._resources:
            del self._resources[resource_id]
            logger.info(f"Deleted resource: {resource_id}")
            return True
        return False

    def list_resources(self, entity_type: Optional[str] = None) -> List[BaseEntity]:
        resources = list(self._resources.values())
        if entity_type:
            resources = [r for r in resources if r.type == entity_type]
        return resources

    def get_migration_metrics(self, task_id: Optional[str] = None) -> Dict[str, Any]:
        if task_id:
            metrics = self._migration_service.get_task_metrics(task_id)
            return {"task": metrics} if metrics else {"error": "Task not found"}

        all_tasks = self._migration_service.list_tasks()
        all_metrics = []
        for task in all_tasks:
            m = self._migration_service.get_task_metrics(task.task_id)
            if m:
                all_metrics.append(m)

        return {
            "total_tasks": len(all_tasks),
            "tasks": all_metrics,
            "summary": {
                "total_records_processed": sum(m["records_processed"] for m in all_metrics),
                "total_bytes_processed": sum(m["bytes_processed"] for m in all_metrics),
                "avg_throughput_records": sum(m["throughput_records_per_sec"] for m in all_metrics) / max(len(all_metrics), 1)
            }
        }

    async def execute_migration_with_scheduled_check(self, task_id: str,
                                                      data_generator: Callable,
                                                      writer: Callable,
                                                      schedule_interval: int = 3600):
        while True:
            await self._migration_service.execute_migration(task_id, data_generator, writer)
            await asyncio.sleep(schedule_interval)

    def compare_schema_versions(self, v1: int, v2: int) -> Dict[str, Any]:
        return self._schema_controller.compare_versions(v1, v2)


data_access_module = DataAccessModule()
