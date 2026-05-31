"""
通知契约
"""

from __future__ import annotations

from abc import abstractmethod
from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class NotificationProtocol(Protocol):
    @abstractmethod
    async def send(
        self,
        recipient: str,
        title: str,
        content: str,
        priority: str = "normal",
        **kwargs: Any,
    ) -> bool: ...

    @abstractmethod
    def supports(self, channel: str) -> bool: ...
