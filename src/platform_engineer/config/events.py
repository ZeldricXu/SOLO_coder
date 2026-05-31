import asyncio
from datetime import datetime, timezone
from typing import Any, Callable, Dict, Optional

from ..core.events import DomainEvent, EventHandler, EventBus, get_global_event_bus
from .manager import Configuration


class ConfigChangedEvent:
    def __init__(
        self,
        old_config: Configuration,
        new_config: Configuration,
        namespace: Optional[str] = None,
        changed_keys: Optional[list] = None,
    ):
        self.old_config = old_config
        self.new_config = new_config
        self.namespace = namespace
        self.changed_keys = changed_keys or []
        self.timestamp = datetime.now(timezone.utc)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "old_version": self.old_config.get_version(),
            "new_version": self.new_config.get_version(),
            "old_checksum": self.old_config.get_checksum(),
            "new_checksum": self.new_config.get_checksum(),
            "namespace": self.namespace,
            "changed_keys": self.changed_keys,
            "timestamp": self.timestamp.isoformat(),
        }


class ConfigChangeNotifier:
    def __init__(self, event_bus: Optional[EventBus] = None):
        self._event_bus = event_bus or get_global_event_bus()
        self._callbacks: Dict[str, list] = {}
        self._handler_configured = False

    def register_handler(self, key_pattern: str, callback: Callable[[ConfigChangedEvent], Any]) -> None:
        if key_pattern not in self._callbacks:
            self._callbacks[key_pattern] = []
        self._callbacks[key_pattern].append(callback)
        if not self._handler_configured:
            self._setup_handler()

    def unregister_handler(self, key_pattern: str, callback: Callable[[ConfigChangedEvent], Any]) -> bool:
        if key_pattern in self._callbacks and callback in self._callbacks[key_pattern]:
            self._callbacks[key_pattern].remove(callback)
            if not self._callbacks[key_pattern]:
                del self._callbacks[key_pattern]
            return True
        return False

    def _setup_handler(self) -> None:
        class ConfigEventHandler(EventHandler):
            def __init__(self, notifier: "ConfigChangeNotifier"):
                self.notifier = notifier

            async def handle(self, event: DomainEvent) -> None:
                await self.notifier._handle_event(event)

        handler = ConfigEventHandler(self)
        self._event_bus.subscribe("config.changed", handler)
        self._handler_configured = True

    async def _handle_event(self, event: DomainEvent) -> None:
        config_event = ConfigChangedEvent(
            old_config=Configuration({}, event.payload.get("old_version", 0)),
            new_config=Configuration({}, event.payload.get("new_version", 0)),
            changed_keys=[],
        )
        for pattern, callbacks in self._callbacks.items():
            for callback in callbacks:
                try:
                    result = callback(config_event)
                    if asyncio.iscoroutine(result):
                        await result
                except Exception as e:
                    pass
