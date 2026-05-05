from abc import ABC, abstractmethod
from typing import Dict, Any, Optional, List
from datetime import datetime
import logging

from app.core.models import AlertNotification, NotificationChannelType

logger = logging.getLogger(__name__)


class NotificationChannel(ABC):
    channel_type: NotificationChannelType

    def __init__(self, name: str, enabled: bool = True, config: Dict[str, Any] = None):
        self.name = name
        self.enabled = enabled
        self.config = config or {}
        self._initialized = False
        self._failed_count = 0
        self._last_sent: Optional[datetime] = None
        self._max_retries = 3

    @property
    def is_available(self) -> bool:
        return self.enabled and self._initialized

    @abstractmethod
    async def initialize(self) -> bool:
        pass

    @abstractmethod
    async def send(self, notification: AlertNotification) -> bool:
        pass

    @abstractmethod
    async def close(self):
        pass

    async def send_with_retry(self, notification: AlertNotification) -> bool:
        if not self.enabled:
            logger.debug(f"Channel {self.name} is disabled")
            return False

        if not self._initialized:
            success = await self.initialize()
            if not success:
                logger.error(f"Failed to initialize channel {self.name}")
                return False

        last_exception = None
        for attempt in range(self._max_retries):
            try:
                success = await self.send(notification)
                if success:
                    self._last_sent = datetime.utcnow()
                    self._failed_count = 0
                    return True
            except Exception as e:
                last_exception = e
                logger.warning(
                    f"Attempt {attempt + 1}/{self._max_retries} "
                    f"failed for channel {self.name}: {e}"
                )

        self._failed_count += 1
        logger.error(
            f"All {self._max_retries} attempts failed for channel {self.name}. "
            f"Total failures: {self._failed_count}"
        )
        return False

    def get_status(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "channel_type": self.channel_type.value if hasattr(self, 'channel_type') else None,
            "enabled": self.enabled,
            "initialized": self._initialized,
            "available": self.is_available,
            "failed_count": self._failed_count,
            "last_sent": self._last_sent.isoformat() + "Z" if self._last_sent else None
        }
