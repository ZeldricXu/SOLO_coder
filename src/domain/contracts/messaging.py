"""Messaging-related contract interfaces."""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Callable, Dict, Optional

from ..models.common import EventMessage


class IMessagePublisher(ABC):
    @abstractmethod
    async def publish(
        self,
        topic: str,
        message: EventMessage,
        headers: Optional[Dict[str, str]] = None,
    ) -> bool:
        pass

    @abstractmethod
    async def publish_batch(
        self,
        topic: str,
        messages: list[EventMessage],
        headers: Optional[Dict[str, str]] = None,
    ) -> int:
        pass

    @abstractmethod
    async def close(self) -> None:
        pass


class IMessageConsumer(ABC):
    @abstractmethod
    async def subscribe(
        self,
        topic: str,
        handler: Callable[[EventMessage],
        group_id: Optional[str] = None,
    ) -> None:
        pass

    @abstractmethod
    async def start(self) -> None:
        pass

    @abstractmethod
    async def stop(self) -> None:
        pass

    @abstractmethod
    async def poll(self, timeout_ms: int = 1000) -> Optional[EventMessage]:
        pass

    @abstractmethod
    async def commit(self) -> None:
        pass
