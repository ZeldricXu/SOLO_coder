from datetime import datetime
from typing import Any, Dict, List, Optional, Callable, Generic, TypeVar, Tuple
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
import shutil
import json
import tarfile
import hashlib
import asyncio
import aiofiles
import uuid
import time
from collections import OrderedDict
from threading import RLock
from contextlib import asynccontextmanager

from app.core.logger import logger
from app.core.events import event_bus, EventType, build_event
from app.core.config import settings


T = TypeVar('T')


class ResourceState(str, Enum):
    IDLE = "idle"
    ACQUIRED = "acquired"
    IN_USE = "in_use"
    EXPIRED = "expired"
    FAILED = "failed"


class BackupStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    DELETED = "deleted"


class RecoveryStatus(str, Enum):
    PENDING = "pending"
    VALIDATING = "validating"
    RESTORING = "restoring"
    COMPLETED = "completed"
    FAILED = "failed"
    ROLLBACK = "rollback"


class StorageTier(str, Enum):
    HOT = "hot"
    WARM = "warm"
    COLD = "cold"
    ARCHIVE = "archive"


@dataclass
class ResourceLease:
    resource_id: str
    resource_type: str
    acquired_at: datetime
    lease_duration_seconds: int = 60
    state: ResourceState = ResourceState.ACQUIRED
    last_heartbeat_at: Optional[datetime] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def is_expired(self) -> bool:
        if self.last_heartbeat_at:
            elapsed = (datetime.utcnow() - self.last_heartbeat_at).total_seconds()
        else:
            elapsed = (datetime.utcnow() - self.acquired_at).total_seconds()
        return elapsed >= self.lease_duration_seconds

    def heartbeat(self):
        self.last_heartbeat_at = datetime.utcnow()
        self.state = ResourceState.IN_USE


@dataclass
class PooledResource(Generic[T]):
    resource: T
    resource_id: str
    resource_type: str
    state: ResourceState = ResourceState.IDLE
    lease: Optional[ResourceLease] = None
    created_at: datetime = field(default_factory=datetime.utcnow)
    last_used_at: Optional[datetime] = None
    use_count: int = 0
    error_count: int = 0
    max_use_count: int = 1000
    max_error_count: int = 5

    def can_reuse(self) -> bool:
        if self.state != ResourceState.IDLE:
            return False
        if self.use_count >= self.max_use_count:
            return False
        if self.error_count >= self.max_error_count:
            return False
        return True

    def mark_used(self, lease: ResourceLease):
        self.state = ResourceState.IN_USE
        self.lease = lease
        self.last_used_at = datetime.utcnow()
        self.use_count += 1

    def mark_idle(self):
        self.state = ResourceState.IDLE
        self.lease = None

    def mark_failed(self):
        self.state = ResourceState.FAILED
        self.error_count += 1
        self.lease = None

    def reset(self):
        self.state = ResourceState.IDLE
        self.lease = None


@dataclass
class PoolStats:
    total_resources: int = 0
    idle_resources: int = 0
    acquired_resources: int = 0
    in_use_resources: int = 0
    failed_resources: int = 0
    total_acquisitions: int = 0
    total_releases: int = 0
    total_evictions: int = 0
    wait_time_ms: float = 0.0
    avg_wait_time_ms: float = 0.0
    hit_ratio: float = 0.0


@dataclass
class BackupRecord:
    backup_id: str
    name: str
    source_path: str
    backup_path: str
    size_bytes: int = 0
    file_count: int = 0
    checksum: str = ""
    status: BackupStatus = BackupStatus.PENDING
    created_at: datetime = field(default_factory=datetime.utcnow)
    completed_at: Optional[datetime] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    error_message: Optional[str] = None
    storage_tier: StorageTier = StorageTier.HOT


@dataclass
class RecoveryTask:
    recovery_id: str
    backup_id: str
    target_path: str
    status: RecoveryStatus = RecoveryStatus.PENDING
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    restored_files: int = 0
    total_files: int = 0
    error_message: Optional[str] = None


class AsyncResourcePool(Generic[T]):
    def __init__(self,
                 name: str,
                 max_size: int = 10,
                 min_idle: int = 2,
                 idle_timeout: int = 300,
                 max_wait_time: float = 30.0,
                 lease_duration: int = 60,
                 resource_factory: Optional[Callable[[], T]] = None,
                 resource_cleanup: Optional[Callable[[T], None]] = None,
                 resource_validator: Optional[Callable[[T], bool]] = None):
        self._name = name
        self._max_size = max_size
        self._min_idle = min_idle
        self._idle_timeout = idle_timeout
        self._max_wait_time = max_wait_time
        self._lease_duration = lease_duration
        self._resource_factory = resource_factory
        self._resource_cleanup = resource_cleanup
        self._resource_validator = resource_validator

        self._resources: Dict[str, PooledResource[T]] = OrderedDict()
        self._idle_resources: List[str] = []
        self._acquired_resources: Dict[str, str] = {}

        self._stats = PoolStats()
        self._lock = asyncio.Lock()
        self._condition = asyncio.Condition()
        self._running = True

        self._maintenance_task: Optional[asyncio.Task] = None
        self._wait_times: List[float] = []

        logger.info(f"AsyncResourcePool '{name}' initialized: max={max_size}, min_idle={min_idle}")

    async def start(self):
        self._running = True
        await self._ensure_min_idle()
        self._maintenance_task = asyncio.create_task(self._maintenance_loop())
        logger.info(f"Resource pool '{self._name}' started")

    async def stop(self):
        self._running = False
        if self._maintenance_task:
            self._maintenance_task.cancel()
            try:
                await self._maintenance_task
            except asyncio.CancelledError:
                pass

        for resource_id, pooled in list(self._resources.items()):
            await self._destroy_resource(resource_id)

        logger.info(f"Resource pool '{self._name}' stopped")

    async def _ensure_min_idle(self):
        async with self._lock:
            while len(self._idle_resources) < self._min_idle:
                if len(self._resources) >= self._max_size:
                    break
                await self._create_resource()

    async def _create_resource(self) -> Optional[str]:
        if not self._resource_factory:
            logger.warning(f"No resource factory for pool '{self._name}'")
            return None

        try:
            resource = self._resource_factory()
            resource_id = f"res_{uuid.uuid4().hex[:8]}"

            pooled = PooledResource(
                resource=resource,
                resource_id=resource_id,
                resource_type=self._name
            )

            self._resources[resource_id] = pooled
            self._idle_resources.append(resource_id)
            self._stats.total_resources += 1

            logger.debug(f"Created resource {resource_id} in pool '{self._name}'")
            return resource_id

        except Exception as e:
            logger.error(f"Failed to create resource in pool '{self._name}': {e}")
            return None

    async def _destroy_resource(self, resource_id: str):
        pooled = self._resources.get(resource_id)
        if not pooled:
            return

        try:
            if self._resource_cleanup:
                self._resource_cleanup(pooled.resource)
        except Exception as e:
            logger.error(f"Error cleaning up resource {resource_id}: {e}")

        if resource_id in self._idle_resources:
            self._idle_resources.remove(resource_id)
        if resource_id in self._acquired_resources:
            del self._acquired_resources[resource_id]
        if resource_id in self._resources:
            del self._resources[resource_id]

        self._stats.total_evictions += 1
        logger.debug(f"Destroyed resource {resource_id} from pool '{self._name}'")

    def _validate_resource(self, pooled: PooledResource[T]) -> bool:
        if not pooled.can_reuse():
            return False

        if self._resource_validator:
            try:
                return self._resource_validator(pooled.resource)
            except Exception as e:
                logger.error(f"Resource validation failed: {e}")
                return False

        return True

    @asynccontextmanager
    async def acquire(self, timeout: Optional[float] = None) -> Tuple[str, T]:
        start_time = time.time()
        wait_timeout = timeout or self._max_wait_time

        async with self._condition:
            while self._running:
                resource_id = await self._try_acquire()
                if resource_id:
                    pooled = self._resources[resource_id]
                    wait_ms = (time.time() - start_time) * 1000
                    self._wait_times.append(wait_ms)
                    self._stats.wait_time_ms += wait_ms
                    self._stats.avg_wait_time_ms = self._stats.wait_time_ms / max(len(self._wait_times), 1)

                    try:
                        yield (resource_id, pooled.resource)
                    finally:
                        await self._release(resource_id)
                    return

                remaining = wait_timeout - (time.time() - start_time)
                if remaining <= 0:
                    raise TimeoutError(f"Timeout waiting for resource in pool '{self._name}'")

                try:
                    await asyncio.wait_for(self._condition.wait(), timeout=remaining)
                except asyncio.TimeoutError:
                    raise TimeoutError(f"Timeout waiting for resource in pool '{self._name}'")

            raise RuntimeError(f"Pool '{self._name}' is stopped")

    async def _try_acquire(self) -> Optional[str]:
        async with self._lock:
            while self._idle_resources:
                resource_id = self._idle_resources.pop(0)
                pooled = self._resources.get(resource_id)

                if not pooled:
                    continue

                if not self._validate_resource(pooled):
                    await self._destroy_resource(resource_id)
                    continue

                lease = ResourceLease(
                    resource_id=resource_id,
                    resource_type=self._name,
                    acquired_at=datetime.utcnow(),
                    lease_duration_seconds=self._lease_duration
                )

                pooled.mark_used(lease)
                self._acquired_resources[resource_id] = resource_id
                self._stats.total_acquisitions += 1

                total_available = self._stats.total_resources
                total_acquired = self._stats.total_acquisitions
                if total_available + total_acquired > 0:
                    self._stats.hit_ratio = total_available / (total_available + total_acquired)

                self._stats.idle_resources = len(self._idle_resources)
                self._stats.acquired_resources = len(self._acquired_resources)
                self._stats.in_use_resources = sum(
                    1 for r in self._resources.values() if r.state == ResourceState.IN_USE
                )

                logger.debug(f"Acquired resource {resource_id} from pool '{self._name}'")
                return resource_id

            if len(self._resources) < self._max_size:
                resource_id = await self._create_resource()
                if resource_id:
                    self._idle_resources.remove(resource_id)
                    pooled = self._resources[resource_id]

                    lease = ResourceLease(
                        resource_id=resource_id,
                        resource_type=self._name,
                        acquired_at=datetime.utcnow(),
                        lease_duration_seconds=self._lease_duration
                    )

                    pooled.mark_used(lease)
                    self._acquired_resources[resource_id] = resource_id
                    self._stats.total_acquisitions += 1
                    self._stats.idle_resources = len(self._idle_resources)
                    self._stats.acquired_resources = len(self._acquired_resources)

                    return resource_id

            return None

    async def _release(self, resource_id: str):
        async with self._lock:
            pooled = self._resources.get(resource_id)
            if not pooled:
                return

            if resource_id in self._acquired_resources:
                del self._acquired_resources[resource_id]

            pooled.mark_idle()
            self._idle_resources.append(resource_id)
            self._stats.total_releases += 1
            self._stats.idle_resources = len(self._idle_resources)
            self._stats.acquired_resources = len(self._acquired_resources)
            self._stats.in_use_resources = sum(
                1 for r in self._resources.values() if r.state == ResourceState.IN_USE
            )

            self._condition.notify()
            logger.debug(f"Released resource {resource_id} to pool '{self._name}'")

    async def heartbeat(self, resource_id: str):
        async with self._lock:
            pooled = self._resources.get(resource_id)
            if pooled and pooled.lease:
                pooled.lease.heartbeat()

    def get_stats(self) -> Dict[str, Any]:
        return {
            "name": self._name,
            "config": {
                "max_size": self._max_size,
                "min_idle": self._min_idle,
                "idle_timeout": self._idle_timeout,
                "max_wait_time": self._max_wait_time,
                "lease_duration": self._lease_duration
            },
            "stats": {
                "total_resources": self._stats.total_resources,
                "idle_resources": len(self._idle_resources),
                "acquired_resources": len(self._acquired_resources),
                "in_use_resources": sum(
                    1 for r in self._resources.values() if r.state == ResourceState.IN_USE
                ),
                "failed_resources": sum(
                    1 for r in self._resources.values() if r.state == ResourceState.FAILED
                ),
                "total_acquisitions": self._stats.total_acquisitions,
                "total_releases": self._stats.total_releases,
                "total_evictions": self._stats.total_evictions,
                "avg_wait_time_ms": self._stats.avg_wait_time_ms,
                "hit_ratio": self._stats.hit_ratio
            }
        }

    async def _maintenance_loop(self):
        while self._running:
            try:
                await asyncio.sleep(30)
                await self._perform_maintenance()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in pool maintenance for '{self._name}': {e}")

    async def _perform_maintenance(self):
        async with self._lock:
            now = datetime.utcnow()
            to_evict = []

            for resource_id, pooled in self._resources.items():
                if pooled.state == ResourceState.IN_USE and pooled.lease:
                    if pooled.lease.is_expired():
                        pooled.state = ResourceState.EXPIRED
                        to_evict.append(resource_id)
                        logger.warning(f"Resource {resource_id} lease expired, evicting")

                elif pooled.state == ResourceState.IDLE and pooled.last_used_at:
                    idle_time = (now - pooled.last_used_at).total_seconds()
                    if idle_time > self._idle_timeout and len(self._idle_resources) > self._min_idle:
                        to_evict.append(resource_id)
                        logger.debug(f"Resource {resource_id} idle for {idle_time}s, evicting")

                elif pooled.state == ResourceState.FAILED:
                    to_evict.append(resource_id)
                    logger.warning(f"Resource {resource_id} failed, evicting")

            for resource_id in to_evict:
                await self._destroy_resource(resource_id)

            await self._ensure_min_idle()


class MultiTierStorageManager:
    def __init__(self):
        self._tiers: Dict[StorageTier, Path] = {}
        self._tier_configs: Dict[StorageTier, Dict[str, Any]] = {
            StorageTier.HOT: {"retention_days": 7, "max_size_gb": 100},
            StorageTier.WARM: {"retention_days": 30, "max_size_gb": 500},
            StorageTier.COLD: {"retention_days": 365, "max_size_gb": 5000},
            StorageTier.ARCHIVE: {"retention_days": None, "max_size_gb": None}
        }
        self._init_tiers()
        self._backup_locations: Dict[str, StorageTier] = {}

    def _init_tiers(self):
        from app.core.config import settings
        base_path = Path(settings.backup_path)

        for tier in StorageTier:
            tier_path = base_path / tier.value
            tier_path.mkdir(parents=True, exist_ok=True)
            self._tiers[tier] = tier_path
            logger.info(f"Initialized storage tier: {tier.value} at {tier_path}")

    def get_tier_path(self, tier: StorageTier) -> Path:
        return self._tiers[tier]

    def assign_to_tier(self, backup_id: str, tier: StorageTier) -> Path:
        self._backup_locations[backup_id] = tier
        return self._tiers[tier]

    def get_backup_tier(self, backup_id: str) -> StorageTier:
        return self._backup_locations.get(backup_id, StorageTier.HOT)

    def can_store(self, tier: StorageTier, size_bytes: int) -> bool:
        config = self._tier_configs[tier]
        if config["max_size_gb"] is None:
            return True

        tier_path = self._tiers[tier]
        used = sum(f.stat().st_size for f in tier_path.rglob("*") if f.is_file())
        available = config["max_size_gb"] * 1024**3 - used
        return size_bytes <= available

    def get_tier_stats(self) -> Dict[str, Any]:
        stats = {}
        for tier in StorageTier:
            tier_path = self._tiers[tier]
            files = list(tier_path.rglob("*"))
            file_count = sum(1 for f in files if f.is_file())
            total_size = sum(f.stat().st_size for f in files if f.is_file())

            stats[tier.value] = {
                "path": str(tier_path),
                "file_count": file_count,
                "size_bytes": total_size,
                "config": self._tier_configs[tier],
                "backup_count": sum(
                    1 for t in self._backup_locations.values() if t == tier
                )
            }
        return stats


class ResourcePoolManager:
    def __init__(self):
        self._pools: Dict[str, AsyncResourcePool] = {}
        self._initialized = False

    async def initialize(self):
        file_buffer_pool = AsyncResourcePool(
            name="file_buffer",
            max_size=20,
            min_idle=5,
            idle_timeout=300,
            max_wait_time=10.0,
            lease_duration=60,
            resource_factory=lambda: {"buffer": bytearray(8192), "position": 0},
            resource_cleanup=lambda r: r["buffer"].clear()
        )

        network_connection_pool = AsyncResourcePool(
            name="network_connection",
            max_size=50,
            min_idle=10,
            idle_timeout=180,
            max_wait_time=30.0,
            lease_duration=120
        )

        compression_pool = AsyncResourcePool(
            name="compression",
            max_size=10,
            min_idle=2,
            idle_timeout=600,
            max_wait_time=5.0,
            lease_duration=180
        )

        hash_compute_pool = AsyncResourcePool(
            name="hash_compute",
            max_size=30,
            min_idle=5,
            idle_timeout=120,
            max_wait_time=2.0,
            lease_duration=30
        )

        self._pools["file_buffer"] = file_buffer_pool
        self._pools["network_connection"] = network_connection_pool
        self._pools["compression"] = compression_pool
        self._pools["hash_compute"] = hash_compute_pool

        for pool in self._pools.values():
            await pool.start()

        self._initialized = True
        logger.info("ResourcePoolManager initialized")

    async def shutdown(self):
        for name, pool in self._pools.items():
            try:
                await pool.stop()
            except Exception as e:
                logger.error(f"Error stopping pool '{name}': {e}")
        self._pools.clear()
        self._initialized = False
        logger.info("ResourcePoolManager shutdown")

    def get_pool(self, name: str) -> Optional[AsyncResourcePool]:
        return self._pools.get(name)

    def get_all_stats(self) -> Dict[str, Any]:
        return {
            "pools": {
                name: pool.get_stats()
                for name, pool in self._pools.items()
            },
            "summary": {
                "total_pools": len(self._pools),
                "total_resources": sum(
                    pool._stats.total_resources for pool in self._pools.values()
                ),
                "total_acquisitions": sum(
                    pool._stats.total_acquisitions for pool in self._pools.values()
                )
            }
        }

    @asynccontextmanager
    async def use_resource(self, pool_name: str):
        pool = self._pools.get(pool_name)
        if not pool:
            raise ValueError(f"Unknown resource pool: {pool_name}")

        async with pool.acquire() as (resource_id, resource):
            yield resource_id, resource


class BackupManager:
    def __init__(self, backup_dir: Optional[str] = None,
                 pool_manager: Optional[ResourcePoolManager] = None):
        from app.core.config import settings
        self._backup_dir = Path(backup_dir) if backup_dir else Path(settings.backup_path)
        self._backup_dir.mkdir(parents=True, exist_ok=True)
        self._records_file = self._backup_dir / "backup_records.json"
        self._records: Dict[str, BackupRecord] = {}
        self._tier_manager = MultiTierStorageManager()
        self._pool_manager = pool_manager
        self._load_records()

    def _load_records(self):
        if self._records_file.exists():
            try:
                with open(self._records_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                for record in data:
                    self._records[record["backup_id"]] = BackupRecord(
                        backup_id=record["backup_id"],
                        name=record["name"],
                        source_path=record["source_path"],
                        backup_path=record["backup_path"],
                        size_bytes=record.get("size_bytes", 0),
                        file_count=record.get("file_count", 0),
                        checksum=record.get("checksum", ""),
                        status=record.get("status", BackupStatus.PENDING),
                        created_at=datetime.fromisoformat(record["created_at"]),
                        completed_at=datetime.fromisoformat(record["completed_at"]) if record.get("completed_at") else None,
                        metadata=record.get("metadata", {}),
                        error_message=record.get("error_message"),
                        storage_tier=record.get("storage_tier", StorageTier.HOT)
                    )
            except Exception as e:
                logger.error(f"Failed to load backup records: {e}")

    def _save_records(self):
        try:
            with open(self._records_file, "w", encoding="utf-8") as f:
                json.dump([
                    {
                        "backup_id": r.backup_id,
                        "name": r.name,
                        "source_path": r.source_path,
                        "backup_path": r.backup_path,
                        "size_bytes": r.size_bytes,
                        "file_count": r.file_count,
                        "checksum": r.checksum,
                        "status": r.status,
                        "created_at": r.created_at.isoformat(),
                        "completed_at": r.completed_at.isoformat() if r.completed_at else None,
                        "metadata": r.metadata,
                        "error_message": r.error_message,
                        "storage_tier": r.storage_tier
                    }
                    for r in self._records.values()
                ], f, indent=2, default=str)
        except Exception as e:
            logger.error(f"Failed to save backup records: {e}")
            raise

    def create_backup(self, source_path: str, name: Optional[str] = None,
                      metadata: Optional[Dict[str, Any]] = None,
                      storage_tier: StorageTier = StorageTier.HOT) -> BackupRecord:
        source = Path(source_path)
        if not source.exists():
            raise ValueError(f"Source path does not exist: {source_path}")

        backup_id = f"bak_{uuid.uuid4().hex[:8]}"
        backup_name = name or f"backup_{backup_id}"

        tier_path = self._tier_manager.assign_to_tier(backup_id, storage_tier)
        backup_path = tier_path / f"{backup_name}.tar.gz"

        record = BackupRecord(
            backup_id=backup_id,
            name=backup_name,
            source_path=str(source),
            backup_path=str(backup_path),
            metadata=metadata or {},
            status=BackupStatus.PENDING,
            storage_tier=storage_tier
        )
        self._records[backup_id] = record
        self._save_records()
        return record

    async def execute_backup(self, backup_id: str, compress: bool = True) -> BackupRecord:
        record = self._records.get(backup_id)
        if not record:
            raise ValueError(f"Backup {backup_id} not found")

        record.status = BackupStatus.RUNNING
        self._save_records()
        logger.info(f"Starting backup: {backup_id}")

        try:
            source = Path(record.source_path)
            backup_path = Path(record.backup_path)

            file_count = 0
            total_size = 0
            hasher = hashlib.sha256()

            if compress:
                with tarfile.open(backup_path, "w:gz") as tar:
                    if source.is_dir():
                        for item in source.rglob("*"):
                            if item.is_file():
                                arcname = item.relative_to(source.parent)
                                tar.add(item, arcname=arcname)
                                with open(item, "rb") as f:
                                    chunk = f.read(8192)
                                    while chunk:
                                        hasher.update(chunk)
                                        chunk = f.read(8192)
                                file_count += 1
                                total_size += item.stat().st_size
                    else:
                        tar.add(source, arcname=source.name)
                        with open(source, "rb") as f:
                            chunk = f.read(8192)
                            while chunk:
                                hasher.update(chunk)
                                chunk = f.read(8192)
                        file_count = 1
                        total_size = source.stat().st_size
            else:
                dest_dir = Path(record.backup_path.replace(".tar.gz", ""))
                dest_dir.mkdir(parents=True, exist_ok=True)
                if source.is_dir():
                    for item in source.rglob("*"):
                        if item.is_file():
                            dest_file = dest_dir / item.relative_to(source)
                            dest_file.parent.mkdir(parents=True, exist_ok=True)
                            shutil.copy2(item, dest_file)
                            with open(item, "rb") as f:
                                chunk = f.read(8192)
                                while chunk:
                                    hasher.update(chunk)
                                    chunk = f.read(8192)
                            file_count += 1
                            total_size += item.stat().st_size
                else:
                    shutil.copy2(source, dest_dir / source.name)
                    with open(source, "rb") as f:
                        chunk = f.read(8192)
                        while chunk:
                            hasher.update(chunk)
                            chunk = f.read(8192)
                    file_count = 1
                    total_size = source.stat().st_size

            record.file_count = file_count
            record.size_bytes = total_size
            record.checksum = hasher.hexdigest()
            record.status = BackupStatus.COMPLETED
            record.completed_at = datetime.utcnow()
            self._save_records()

            event_bus.emit(build_event(EventType.BACKUP_CREATED, {
                "backup_id": backup_id,
                "name": record.name,
                "file_count": file_count,
                "size_bytes": total_size,
                "tier": record.storage_tier
            }))
            logger.info(f"Backup {backup_id} completed: {file_count} files, {total_size} bytes")

        except Exception as e:
            logger.error(f"Backup {backup_id} failed: {e}")
            record.status = BackupStatus.FAILED
            record.error_message = str(e)
            self._save_records()
            raise

        return record

    def list_backups(self, status: Optional[BackupStatus] = None,
                      tier: Optional[StorageTier] = None) -> List[BackupRecord]:
        backups = list(self._records.values())
        if status:
            backups = [b for b in backups if b.status == status]
        if tier:
            backups = [b for b in backups if b.storage_tier == tier]
        return sorted(backups, key=lambda b: b.created_at, reverse=True)

    def get_backup(self, backup_id: str) -> Optional[BackupRecord]:
        return self._records.get(backup_id)

    def delete_backup(self, backup_id: str) -> bool:
        record = self._records.get(backup_id)
        if not record:
            return False

        try:
            backup_path = Path(record.backup_path)
            if backup_path.exists():
                if backup_path.is_file():
                    backup_path.unlink()
                else:
                    shutil.rmtree(backup_path)
            record.status = BackupStatus.DELETED
            self._save_records()
            logger.info(f"Deleted backup: {backup_id}")
            return True
        except Exception as e:
            logger.error(f"Failed to delete backup {backup_id}: {e}")
            return False

    async def verify_backup_integrity(self, backup_id: str) -> bool:
        record = self._records.get(backup_id)
        if not record:
            return False

        if not Path(record.backup_path).exists():
            return False

        hasher = hashlib.sha256()
        backup_path = Path(record.backup_path)

        if backup_path.suffix == ".gz":
            with tarfile.open(backup_path, "r:gz") as tar:
                for member in tar.getmembers():
                    if member.isfile():
                        f = tar.extractfile(member)
                        if f:
                            chunk = f.read(8192)
                            while chunk:
                                hasher.update(chunk)
                                chunk = f.read(8192)
        else:
            for item in backup_path.rglob("*"):
                if item.is_file():
                    with open(item, "rb") as f:
                        chunk = f.read(8192)
                        while chunk:
                            hasher.update(chunk)
                            chunk = f.read(8192)

        return hasher.hexdigest() == record.checksum

    def get_storage_stats(self) -> Dict[str, Any]:
        return self._tier_manager.get_tier_stats()

    def get_pool_stats(self) -> Dict[str, Any]:
        if self._pool_manager:
            return self._pool_manager.get_all_stats()
        return {"error": "Pool manager not initialized"}


class RecoveryManager:
    def __init__(self, backup_manager: BackupManager,
                 pool_manager: Optional[ResourcePoolManager] = None):
        self._backup_manager = backup_manager
        self._pool_manager = pool_manager
        self._tasks: Dict[str, RecoveryTask] = {}

    def create_recovery_task(self, backup_id: str, target_path: str) -> RecoveryTask:
        backup = self._backup_manager.get_backup(backup_id)
        if not backup:
            raise ValueError(f"Backup {backup_id} not found")

        task = RecoveryTask(
            recovery_id=f"rec_{uuid.uuid4().hex[:8]}",
            backup_id=backup_id,
            target_path=target_path
        )
        self._tasks[task.recovery_id] = task
        logger.info(f"Created recovery task: {task.recovery_id}")
        return task

    async def execute_recovery(self, recovery_id: str, overwrite: bool = False) -> RecoveryTask:
        task = self._tasks.get(recovery_id)
        if not task:
            raise ValueError(f"Recovery task {recovery_id} not found")

        backup = self._backup_manager.get_backup(task.backup_id)
        if not backup:
            task.status = RecoveryStatus.FAILED
            task.error_message = f"Backup {task.backup_id} not found"
            return task

        task.started_at = datetime.utcnow()
        task.status = RecoveryStatus.VALIDATING

        try:
            if not await self._backup_manager.verify_backup_integrity(task.backup_id):
                raise ValueError("Backup integrity verification failed")

            target = Path(task.target_path)
            if target.exists() and not overwrite:
                raise ValueError(f"Target path exists and overwrite not allowed: {task.target_path}")

            task.status = RecoveryStatus.RESTORING
            backup_path = Path(backup.backup_path)

            restored_files = 0
            total_files = 0

            if backup_path.suffix == ".gz":
                target.mkdir(parents=True, exist_ok=True)
                with tarfile.open(backup_path, "r:gz") as tar:
                    members = tar.getmembers()
                    total_files = len([m for m in members if m.isfile()])
                    task.total_files = total_files
                    for member in members:
                        if member.isfile():
                            tar.extract(member, target)
                            restored_files += 1
                            task.restored_files = restored_files
            else:
                if backup_path.is_dir():
                    total_files = len(list(backup_path.rglob("*")))
                    task.total_files = total_files
                    for item in backup_path.rglob("*"):
                        if item.is_file():
                            dest_file = target / item.relative_to(backup_path)
                            dest_file.parent.mkdir(parents=True, exist_ok=True)
                            shutil.copy2(item, dest_file)
                            restored_files += 1
                            task.restored_files = restored_files
                else:
                    total_files = 1
                    task.total_files = total_files
                    target.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(backup_path, target / backup_path.name)
                    restored_files = 1
                    task.restored_files = 1

            task.status = RecoveryStatus.COMPLETED
            task.completed_at = datetime.utcnow()

            event_bus.emit(build_event(EventType.BACKUP_RESTORED, {
                "recovery_id": recovery_id,
                "backup_id": task.backup_id,
                "restored_files": restored_files
            }))
            logger.info(f"Recovery {recovery_id} completed: {restored_files} files")

        except Exception as e:
            logger.error(f"Recovery {recovery_id} failed: {e}")
            task.status = RecoveryStatus.FAILED
            task.error_message = str(e)
            await self._execute_rollback(task)

        return task

    async def _execute_rollback(self, task: RecoveryTask):
        task.status = RecoveryStatus.ROLLBACK
        target = Path(task.target_path)
        try:
            if target.exists():
                if target.is_file():
                    target.unlink()
                else:
                    shutil.rmtree(target)
            logger.info(f"Rollback completed for recovery {task.recovery_id}")
        except Exception as e:
            logger.error(f"Rollback failed for recovery {task.recovery_id}: {e}")

    def get_task_status(self, recovery_id: str) -> Optional[RecoveryTask]:
        return self._tasks.get(recovery_id)


class StorageManagementModule:
    def __init__(self):
        self._pool_manager = ResourcePoolManager()
        self._backup_manager = BackupManager(pool_manager=self._pool_manager)
        self._recovery_manager = RecoveryManager(self._backup_manager, self._pool_manager)
        self._initialized = False
        logger.info("StorageManagementModule initialized")

    async def initialize(self):
        if not self._initialized:
            await self._pool_manager.initialize()
            self._initialized = True
            logger.info("StorageManagementModule fully initialized with resource pools")

    async def shutdown(self):
        if self._initialized:
            await self._pool_manager.shutdown()
            self._initialized = False
            logger.info("StorageManagementModule shutdown")

    @property
    def backup_manager(self) -> BackupManager:
        return self._backup_manager

    @property
    def recovery_manager(self) -> RecoveryManager:
        return self._recovery_manager

    @property
    def pool_manager(self) -> ResourcePoolManager:
        return self._pool_manager

    async def create_and_execute_backup(self, source_path: str, name: Optional[str] = None,
                                         metadata: Optional[Dict[str, Any]] = None,
                                         compress: bool = True,
                                         storage_tier: StorageTier = StorageTier.HOT) -> BackupRecord:
        record = self._backup_manager.create_backup(source_path, name, metadata, storage_tier)
        return await self._backup_manager.execute_backup(record.backup_id, compress)

    async def create_and_execute_recovery(self, backup_id: str, target_path: str,
                                           overwrite: bool = False) -> RecoveryTask:
        task = self._recovery_manager.create_recovery_task(backup_id, target_path)
        return await self._recovery_manager.execute_recovery(task.recovery_id, overwrite)

    def get_observability_metrics(self) -> Dict[str, Any]:
        return {
            "storage_tiers": self._backup_manager.get_storage_stats(),
            "resource_pools": self._backup_manager.get_pool_stats(),
            "backups": {
                "total": len(self._backup_manager._records),
                "by_status": {
                    status.value: sum(
                        1 for r in self._backup_manager._records.values() if r.status == status
                    )
                    for status in BackupStatus
                }
            }
        }


_storage_instance: Optional[StorageManagementModule] = None


async def get_storage_module() -> StorageManagementModule:
    global _storage_instance
    if _storage_instance is None:
        _storage_instance = StorageManagementModule()
        await _storage_instance.initialize()
    return _storage_instance


storage_module = StorageManagementModule()
