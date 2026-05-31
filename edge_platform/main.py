import asyncio
import logging
from typing import Optional

from .common.event_bus import event_bus
from .scheduler import TaskScheduler
from .ota import OTAManager
from .device_shadow import DeviceShadowManager
from .rule_engine import RuleEngine
from .storage import StorageManager
from .notification import NotificationManager
from .protocol import ProtocolManager
from .monitoring import MonitoringManager
from .inference import InferenceManager

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


class EdgePlatform:
    def __init__(self):
        self.event_bus = event_bus
        self.scheduler: Optional[TaskScheduler] = None
        self.ota_manager: Optional[OTAManager] = None
        self.device_shadow: Optional[DeviceShadowManager] = None
        self.rule_engine: Optional[RuleEngine] = None
        self.storage: Optional[StorageManager] = None
        self.notification: Optional[NotificationManager] = None
        self.protocol: Optional[ProtocolManager] = None
        self.monitoring: Optional[MonitoringManager] = None
        self.inference: Optional[InferenceManager] = None
        self._is_running = False

    def initialize(self) -> None:
        logger.info("Initializing Edge Platform...")

        self.scheduler = TaskScheduler(self.event_bus)
        self.ota_manager = OTAManager(self.event_bus)
        self.device_shadow = DeviceShadowManager(self.event_bus)
        self.rule_engine = RuleEngine(self.event_bus)
        self.storage = StorageManager(self.event_bus)
        self.notification = NotificationManager(self.event_bus)
        self.protocol = ProtocolManager(self.event_bus)
        self.monitoring = MonitoringManager(self.event_bus)
        self.inference = InferenceManager(self.event_bus)

        logger.info("All modules initialized")

    async def start(self) -> None:
        if self._is_running:
            logger.warning("Platform is already running")
            return

        logger.info("Starting Edge Platform...")

        await self.scheduler.start()
        await self.notification.start()
        await self.monitoring.start()
        await self.inference.start()

        self._is_running = True
        logger.info("Edge Platform started successfully")

    async def stop(self) -> None:
        if not self._is_running:
            return

        logger.info("Stopping Edge Platform...")

        await self.scheduler.stop()
        await self.notification.stop()
        await self.monitoring.stop()
        await self.inference.stop()
        self.device_shadow.stop_sync_loop()

        self._is_running = False
        logger.info("Edge Platform stopped")

    def get_all_stats(self) -> dict:
        return {
            "scheduler": self.scheduler.get_stats() if self.scheduler else {},
            "ota": self.ota_manager.get_upgrade_stats() if self.ota_manager else {},
            "device_shadow": self.device_shadow.get_stats() if self.device_shadow else {},
            "rule_engine": self.rule_engine.get_stats() if self.rule_engine else {},
            "storage": self.storage.get_stats() if self.storage else {},
            "notification": self.notification.get_stats() if self.notification else {},
            "protocol": self.protocol.get_stats() if self.protocol else {},
            "monitoring": self.monitoring.get_stats() if self.monitoring else {},
            "inference": self.inference.get_stats() if self.inference else {}
        }


async def main():
    platform = EdgePlatform()
    platform.initialize()

    await platform.start()

    try:
        while True:
            await asyncio.sleep(1)
    except KeyboardInterrupt:
        await platform.stop()


if __name__ == "__main__":
    asyncio.run(main())
