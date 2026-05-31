from abc import ABC, abstractmethod
from datetime import datetime, timezone
from typing import Any, Dict, Generic, List, Optional, TypeVar
from uuid import uuid4

from ..core.models import generate_id


T = TypeVar("T")


class Repository(Generic[T], ABC):
    @abstractmethod
    def get_by_id(self, id: str) -> Optional[T]:
        pass

    @abstractmethod
    def list(self, limit: int = 100, offset: int = 0) -> List[T]:
        pass

    @abstractmethod
    def create(self, entity: T) -> T:
        pass

    @abstractmethod
    def update(self, entity: T) -> Optional[T]:
        pass

    @abstractmethod
    def delete(self, id: str) -> bool:
        pass

    @abstractmethod
    def count(self) -> int:
        pass


class AsyncRepository(Generic[T], ABC):
    @abstractmethod
    async def get_by_id(self, id: str) -> Optional[T]:
        pass

    @abstractmethod
    async def list(self, limit: int = 100, offset: int = 0) -> List[T]:
        pass

    @abstractmethod
    async def create(self, entity: T) -> T:
        pass

    @abstractmethod
    async def update(self, entity: T) -> Optional[T]:
        pass

    @abstractmethod
    async def delete(self, id: str) -> bool:
        pass

    @abstractmethod
    async def count(self) -> int:
        pass


class InMemoryRepository(AsyncRepository[T]):
    def __init__(self):
        self._store: Dict[str, T] = {}
        self._created_at: Dict[str, datetime] = {}

    async def get_by_id(self, id: str) -> Optional[T]:
        return self._store.get(id)

    async def list(self, limit: int = 100, offset: int = 0) -> List[T]:
        items = list(self._store.values())
        return items[offset:offset + limit]

    async def create(self, entity: T) -> T:
        if hasattr(entity, "id") and not entity.id:
            entity.id = generate_id("ent")
        entity_id = getattr(entity, "id", str(uuid4().hex[:16]))
        self._store[entity_id] = entity
        self._created_at[entity_id] = datetime.now(timezone.utc)
        return entity

    async def update(self, entity: T) -> Optional[T]:
        entity_id = getattr(entity, "id", None)
        if entity_id and entity_id in self._store:
            self._store[entity_id] = entity
            return entity
        return None

    async def delete(self, id: str) -> bool:
        if id in self._store:
            del self._store[id]
            if id in self._created_at:
                del self._created_at[id]
            return True
        return False

    async def count(self) -> int:
        return len(self._store)

    async def find_by(self, **kwargs) -> List[T]:
        results = []
        for entity in self._store.values():
            match = True
            for key, value in kwargs.items():
                if getattr(entity, key, None) != value:
                    match = False
                    break
            if match:
                results.append(entity)
        return results

    async def exists(self, id: str) -> bool:
        return id in self._store

    def clear(self) -> None:
        self._store.clear()
        self._created_at.clear()

    def get_all(self) -> List[T]:
        return list(self._store.values())
