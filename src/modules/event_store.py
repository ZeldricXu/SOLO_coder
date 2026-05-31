import json
import uuid
import hashlib
from abc import ABC, abstractmethod
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, AsyncIterator, Generic, TypeVar, Callable
from enum import Enum
from dataclasses import dataclass, field
from collections import defaultdict

from .logging_module import get_logger
from .config_module import get_app_config
from .storage_module import StorageManager, get_storage_manager

logger = get_logger(__name__)

T = TypeVar('T')
S = TypeVar('S')


class EventType(str, Enum):
    CREATED = "entity.created"
    UPDATED = "entity.updated"
    DELETED = "entity.deleted"
    STATUS_CHANGED = "entity.status_changed"
    COMMAND_EXECUTED = "command.executed"
    COMMAND_FAILED = "command.failed"
    TASK_STARTED = "task.started"
    TASK_COMPLETED = "task.completed"
    TASK_FAILED = "task.failed"
    CONFIG_APPLIED = "config.applied"
    BACKUP_CREATED = "backup.created"
    BACKUP_RESTORED = "backup.restored"
    NOTIFICATION_SENT = "notification.sent"
    CUSTOM = "custom"


@dataclass
class Event:
    event_id: str
    aggregate_id: str
    event_type: EventType
    payload: Dict[str, Any]
    version: int
    timestamp: datetime = field(default_factory=datetime.utcnow)
    metadata: Dict[str, Any] = field(default_factory=dict)
    correlation_id: Optional[str] = None
    causation_id: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "event_id": self.event_id,
            "aggregate_id": self.aggregate_id,
            "event_type": self.event_type.value if isinstance(self.event_type, EventType) else self.event_type,
            "payload": self.payload,
            "version": self.version,
            "timestamp": self.timestamp.isoformat(),
            "metadata": self.metadata,
            "correlation_id": self.correlation_id,
            "causation_id": self.causation_id,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'Event':
        return cls(
            event_id=data["event_id"],
            aggregate_id=data["aggregate_id"],
            event_type=EventType(data["event_type"]) if data["event_type"] in EventType.__members__.values() else data["event_type"],
            payload=data["payload"],
            version=data["version"],
            timestamp=datetime.fromisoformat(data["timestamp"].replace('Z', '+00:00')) if isinstance(data["timestamp"], str) else data["timestamp"],
            metadata=data.get("metadata", {}),
            correlation_id=data.get("correlation_id"),
            causation_id=data.get("causation_id"),
        )


@dataclass
class Snapshot:
    snapshot_id: str
    aggregate_id: str
    version: int
    state: Dict[str, Any]
    timestamp: datetime = field(default_factory=datetime.utcnow)
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "snapshot_id": self.snapshot_id,
            "aggregate_id": self.aggregate_id,
            "version": self.version,
            "state": self.state,
            "timestamp": self.timestamp.isoformat(),
            "metadata": self.metadata,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'Snapshot':
        return cls(
            snapshot_id=data["snapshot_id"],
            aggregate_id=data["aggregate_id"],
            version=data["version"],
            state=data["state"],
            timestamp=datetime.fromisoformat(data["timestamp"].replace('Z', '+00:00')) if isinstance(data["timestamp"], str) else data["timestamp"],
            metadata=data.get("metadata", {}),
        )


class Projection(ABC, Generic[S]):
    @abstractmethod
    def apply(self, state: Optional[S], event: Event) -> S:
        pass

    @abstractmethod
    def initial_state(self) -> S:
        pass


class EventStoreBackend(ABC):
    @abstractmethod
    async def save_events(self, events: List[Event], expected_version: Optional[int] = None) -> None:
        pass

    @abstractmethod
    async def load_events(self, aggregate_id: str, from_version: int = 0, to_version: Optional[int] = None) -> List[Event]:
        pass

    @abstractmethod
    async def load_all_events(self, event_type: Optional[EventType] = None,
                              from_time: Optional[datetime] = None,
                              to_time: Optional[datetime] = None,
                              limit: int = 1000) -> AsyncIterator[Event]:
        pass

    @abstractmethod
    async def get_latest_version(self, aggregate_id: str) -> int:
        pass

    @abstractmethod
    async def save_snapshot(self, snapshot: Snapshot) -> None:
        pass

    @abstractmethod
    async def load_snapshot(self, aggregate_id: str, max_version: Optional[int] = None) -> Optional[Snapshot]:
        pass


class InMemoryEventStore(EventStoreBackend):
    def __init__(self):
        self._events: Dict[str, List[Event]] = defaultdict(list)
        self._snapshots: Dict[str, List[Snapshot]] = defaultdict(list)
        self._global_events: List[Event] = []

    async def save_events(self, events: List[Event], expected_version: Optional[int] = None) -> None:
        for event in events:
            current_version = len(self._events[event.aggregate_id])
            if expected_version is not None and current_version != expected_version:
                raise ValueError(f"Concurrency conflict: expected version {expected_version}, got {current_version}")

            event.version = current_version + 1
            self._events[event.aggregate_id].append(event)
            self._global_events.append(event)

    async def load_events(self, aggregate_id: str, from_version: int = 0, to_version: Optional[int] = None) -> List[Event]:
        events = self._events.get(aggregate_id, [])
        if from_version > 0:
            events = [e for e in events if e.version >= from_version]
        if to_version:
            events = [e for e in events if e.version <= to_version]
        return sorted(events, key=lambda e: e.version)

    async def load_all_events(self, event_type: Optional[EventType] = None,
                              from_time: Optional[datetime] = None,
                              to_time: Optional[datetime] = None,
                              limit: int = 1000) -> AsyncIterator[Event]:
        events = self._global_events
        if event_type:
            events = [e for e in events if e.event_type == event_type]
        if from_time:
            events = [e for e in events if e.timestamp >= from_time]
        if to_time:
            events = [e for e in events if e.timestamp <= to_time]

        for event in events[:limit]:
            yield event

    async def get_latest_version(self, aggregate_id: str) -> int:
        return len(self._events.get(aggregate_id, []))

    async def save_snapshot(self, snapshot: Snapshot) -> None:
        self._snapshots[snapshot.aggregate_id].append(snapshot)

    async def load_snapshot(self, aggregate_id: str, max_version: Optional[int] = None) -> Optional[Snapshot]:
        snapshots = self._snapshots.get(aggregate_id, [])
        if max_version:
            snapshots = [s for s in snapshots if s.version <= max_version]
        if not snapshots:
            return None
        return max(snapshots, key=lambda s: s.version)


class StorageEventStore(EventStoreBackend):
    def __init__(self, storage: StorageManager):
        self.storage = storage

    def _get_event_key(self, aggregate_id: str) -> str:
        return f"events/{aggregate_id}/events.jsonl"

    def _get_snapshot_key(self, aggregate_id: str, version: int) -> str:
        return f"events/{aggregate_id}/snapshots/snapshot_v{version}.json"

    def _get_global_index_key(self) -> str:
        return "events/global_index.json"

    async def save_events(self, events: List[Event], expected_version: Optional[int] = None) -> None:
        for event in events:
            aggregate_id = event.aggregate_id
            event_key = self._get_event_key(aggregate_id)

            existing_events = await self.storage.load_data(event_key, deserialize=False) or b""
            current_lines = existing_events.decode('utf-8').strip().split('\n') if existing_events else []
            current_version = len([l for l in current_lines if l.strip()])

            if expected_version is not None and current_version != expected_version:
                raise ValueError(f"Concurrency conflict: expected version {expected_version}, got {current_version}")

            event.version = current_version + 1
            event_line = json.dumps(event.to_dict()) + '\n'

            new_content = existing_events + event_line.encode('utf-8')
            await self.storage.save_data(event_key, new_content, serialize=False)

            global_index = await self.storage.load_data(self._get_global_index_key()) or []
            global_index.append({
                "event_id": event.event_id,
                "aggregate_id": aggregate_id,
                "event_type": event.event_type.value if isinstance(event.event_type, EventType) else event.event_type,
                "timestamp": event.timestamp.isoformat(),
            })
            await self.storage.save_data(self._get_global_index_key(), global_index)

    async def load_events(self, aggregate_id: str, from_version: int = 0, to_version: Optional[int] = None) -> List[Event]:
        event_key = self._get_event_key(aggregate_id)
        data = await self.storage.load_data(event_key, deserialize=False)
        if not data:
            return []

        events = []
        for line in data.decode('utf-8').strip().split('\n'):
            if not line.strip():
                continue
            event_data = json.loads(line)
            event = Event.from_dict(event_data)
            if from_version > 0 and event.version < from_version:
                continue
            if to_version and event.version > to_version:
                continue
            events.append(event)

        return sorted(events, key=lambda e: e.version)

    async def load_all_events(self, event_type: Optional[EventType] = None,
                              from_time: Optional[datetime] = None,
                              to_time: Optional[datetime] = None,
                              limit: int = 1000) -> AsyncIterator[Event]:
        global_index = await self.storage.load_data(self._get_global_index_key()) or []
        count = 0

        for idx_entry in global_index:
            if count >= limit:
                break

            if event_type and idx_entry["event_type"] != event_type.value:
                continue

            ts = datetime.fromisoformat(idx_entry["timestamp"].replace('Z', '+00:00'))
            if from_time and ts < from_time:
                continue
            if to_time and ts > to_time:
                continue

            events = await self.load_events(idx_entry["aggregate_id"])
            for event in events:
                if event.event_id == idx_entry["event_id"]:
                    yield event
                    count += 1
                    break

    async def get_latest_version(self, aggregate_id: str) -> int:
        events = await self.load_events(aggregate_id)
        return events[-1].version if events else 0

    async def save_snapshot(self, snapshot: Snapshot) -> None:
        key = self._get_snapshot_key(snapshot.aggregate_id, snapshot.version)
        await self.storage.save_data(key, snapshot.to_dict())

    async def load_snapshot(self, aggregate_id: str, max_version: Optional[int] = None) -> Optional[Snapshot]:
        prefix = f"events/{aggregate_id}/snapshots/"
        objects = await self.storage.list_data(prefix)

        if not objects:
            return None

        snapshots = []
        for obj in objects:
            data = await self.storage.load_data(obj.key)
            if data:
                snapshot = Snapshot.from_dict(data)
                if max_version is None or snapshot.version <= max_version:
                    snapshots.append(snapshot)

        if not snapshots:
            return None

        return max(snapshots, key=lambda s: s.version)


class EventStore:
    _instance: Optional['EventStore'] = None
    _initialized: bool = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self, backend: Optional[EventStoreBackend] = None):
        if self._initialized:
            return

        config = get_app_config()
        self.snapshot_interval = config.event_store.snapshot_interval
        self.snapshot_retention = config.event_store.snapshot_retention

        if backend:
            self.backend = backend
        else:
            storage = get_storage_manager()
            self.backend = StorageEventStore(storage)

        self._projections: Dict[str, Projection] = {}
        self._initialized = True

    async def append(self, aggregate_id: str, event_type: EventType, payload: Dict[str, Any],
                     metadata: Optional[Dict[str, Any]] = None,
                     expected_version: Optional[int] = None,
                     correlation_id: Optional[str] = None,
                     causation_id: Optional[str] = None) -> Event:
        event = Event(
            event_id=str(uuid.uuid4()),
            aggregate_id=aggregate_id,
            event_type=event_type,
            payload=payload,
            version=0,
            metadata=metadata or {},
            correlation_id=correlation_id,
            causation_id=causation_id,
        )

        await self.backend.save_events([event], expected_version)

        if event.version % self.snapshot_interval == 0:
            await self.create_snapshot(aggregate_id)

        return event

    async def append_events(self, events: List[Event], expected_version: Optional[int] = None) -> None:
        await self.backend.save_events(events, expected_version)

    async def get_events(self, aggregate_id: str, from_version: int = 0,
                         to_version: Optional[int] = None) -> List[Event]:
        return await self.backend.load_events(aggregate_id, from_version, to_version)

    async def get_all_events(self, event_type: Optional[EventType] = None,
                             from_time: Optional[datetime] = None,
                             to_time: Optional[datetime] = None,
                             limit: int = 1000) -> AsyncIterator[Event]:
        return self.backend.load_all_events(event_type, from_time, to_time, limit)

    async def get_latest_version(self, aggregate_id: str) -> int:
        return await self.backend.get_latest_version(aggregate_id)

    async def aggregate_state(self, aggregate_id: str, projection: Projection[T],
                              to_version: Optional[int] = None) -> T:
        snapshot = await self.backend.load_snapshot(aggregate_id, to_version)
        state = snapshot.state if snapshot else projection.initial_state()
        from_version = snapshot.version + 1 if snapshot else 0

        events = await self.get_events(aggregate_id, from_version=from_version, to_version=to_version)
        for event in events:
            state = projection.apply(state, event)

        return state

    async def create_snapshot(self, aggregate_id: str) -> Optional[Snapshot]:
        latest_version = await self.get_latest_version(aggregate_id)
        if latest_version == 0:
            return None

        events = await self.get_events(aggregate_id, to_version=latest_version)
        state = {}
        for event in events:
            state = {**state, **event.payload}

        snapshot = Snapshot(
            snapshot_id=str(uuid.uuid4()),
            aggregate_id=aggregate_id,
            version=latest_version,
            state=state,
        )

        await self.backend.save_snapshot(snapshot)
        logger.info("Snapshot created", aggregate_id=aggregate_id, version=latest_version)
        return snapshot

    async def time_travel(self, aggregate_id: str, target_time: datetime,
                          projection: Projection[T]) -> T:
        all_events = await self.get_events(aggregate_id)
        events_before_target = [e for e in all_events if e.timestamp <= target_time]

        state = projection.initial_state()
        for event in events_before_target:
            state = projection.apply(state, event)

        return state

    async def rebuild_projection(self, projection_name: str, projection: Projection[T],
                                 from_time: Optional[datetime] = None) -> Dict[str, T]:
        states: Dict[str, T] = {}

        async for event in self.get_all_events(from_time=from_time):
            aggregate_id = event.aggregate_id
            if aggregate_id not in states:
                states[aggregate_id] = projection.initial_state()
            states[aggregate_id] = projection.apply(states[aggregate_id], event)

        self._projections[projection_name] = projection
        return states

    async def cleanup_old_snapshots(self, retention_days: Optional[int] = None) -> int:
        retention_days = retention_days or self.snapshot_retention
        cutoff = datetime.utcnow() - timedelta(days=retention_days)
        deleted_count = 0

        storage = get_storage_manager()
        objects = await storage.list_data("events/")

        for obj in objects:
            if "snapshots" in obj.key and obj.last_modified < cutoff:
                if await storage.delete_data(obj.key):
                    deleted_count += 1

        logger.info("Old snapshots cleaned up", deleted=deleted_count)
        return deleted_count

    def register_projection(self, name: str, projection: Projection) -> None:
        self._projections[name] = projection

    def get_projection(self, name: str) -> Optional[Projection]:
        return self._projections.get(name)


def get_event_store() -> EventStore:
    return EventStore()
