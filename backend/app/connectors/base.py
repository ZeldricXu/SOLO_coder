from abc import ABC, abstractmethod
from typing import Optional, Callable, Dict, Any
from datetime import datetime
import asyncio
import logging

from app.core.models import RawDataEvent, DataSourceType, DataSourceConfig

logger = logging.getLogger(__name__)


class BaseConnector(ABC):
    def __init__(self, config: DataSourceConfig):
        self.config = config
        self.source_id = config.source_id
        self.source_type = config.source_type
        self.connection = None
        self.is_connected = False
        self.is_running = False
        self._on_data_callback: Optional[Callable[[RawDataEvent], None]] = None
        self._reconnect_attempts = 0
        self._max_reconnect_attempts = 5
        self._reconnect_delay = 5

    def set_data_callback(self, callback: Callable[[RawDataEvent], None]):
        self._on_data_callback = callback

    def _emit_data(self, data: Dict[str, Any], event_type: str = "insert"):
        if self._on_data_callback:
            event = RawDataEvent(
                source=self.source_id,
                data=data,
                timestamp=datetime.utcnow(),
                event_type=event_type
            )
            try:
                self._on_data_callback(event)
            except Exception as e:
                logger.error(f"Error emitting data from {self.source_id}: {e}")

    @abstractmethod
    async def connect(self) -> bool:
        pass

    @abstractmethod
    async def disconnect(self):
        pass

    @abstractmethod
    async def start_listening(self):
        pass

    @abstractmethod
    async def stop_listening(self):
        pass

    async def _reconnect(self) -> bool:
        if self._reconnect_attempts >= self._max_reconnect_attempts:
            logger.error(f"Max reconnect attempts reached for {self.source_id}")
            return False

        self._reconnect_attempts += 1
        logger.warning(
            f"Attempting to reconnect {self.source_id} "
            f"(attempt {self._reconnect_attempts}/{self._max_reconnect_attempts})"
        )
        await asyncio.sleep(self._reconnect_delay)
        return await self.connect()

    def get_status(self) -> Dict[str, Any]:
        return {
            "source_id": self.source_id,
            "source_type": self.source_type.value,
            "is_connected": self.is_connected,
            "is_running": self.is_running,
            "reconnect_attempts": self._reconnect_attempts
        }
