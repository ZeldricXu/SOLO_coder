from abc import ABC, abstractmethod
from enum import Enum
from typing import Dict, Any, Optional, Callable, List
from datetime import datetime

from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class DriverStatus(str, Enum):
    DISCONNECTED = "disconnected"
    CONNECTING = "connecting"
    CONNECTED = "connected"
    RECONNECTING = "reconnecting"
    ERROR = "error"


class ProtocolDriver(ABC):
    def __init__(self, protocol_name: str):
        self.protocol_name = protocol_name
        self.status = DriverStatus.DISCONNECTED
        self.config: Dict[str, Any] = {}
        self.data_callbacks: List[Callable[[str, Dict[str, Any]], None]] = []
        self.status_callbacks: List[Callable[[DriverStatus], None]] = []
        self.last_connected: Optional[datetime] = None
        self.last_error: Optional[str] = None

    @abstractmethod
    def connect(self, config: Dict[str, Any]) -> bool:
        pass

    @abstractmethod
    def disconnect(self) -> None:
        pass

    @abstractmethod
    def read_data(self, address: str, **kwargs) -> Optional[Dict[str, Any]]:
        pass

    @abstractmethod
    def write_data(self, address: str, data: Dict[str, Any], **kwargs) -> bool:
        pass

    @abstractmethod
    def subscribe(self, address: str, callback: Callable[[str, Dict[str, Any]], None], **kwargs) -> bool:
        pass

    @abstractmethod
    def unsubscribe(self, address: str) -> None:
        pass

    def is_connected(self) -> bool:
        return self.status == DriverStatus.CONNECTED

    def get_status(self) -> DriverStatus:
        return self.status

    def set_status(self, status: DriverStatus) -> None:
        old_status = self.status
        self.status = status
        if status == DriverStatus.CONNECTED:
            self.last_connected = datetime.utcnow()
        logger.info(f"Protocol driver {self.protocol_name} status changed: {old_status} -> {status}")
        for callback in self.status_callbacks:
            try:
                callback(status)
            except Exception as e:
                logger.error(f"Error in status callback: {str(e)}")

    def register_data_callback(self, callback: Callable[[str, Dict[str, Any]], None]) -> None:
        self.data_callbacks.append(callback)

    def register_status_callback(self, callback: Callable[[DriverStatus], None]) -> None:
        self.status_callbacks.append(callback)

    def on_data_received(self, address: str, data: Dict[str, Any]) -> None:
        for callback in self.data_callbacks:
            try:
                callback(address, data)
            except Exception as e:
                logger.error(f"Error in data callback: {str(e)}")

    def set_error(self, error_message: str) -> None:
        self.last_error = error_message
        self.set_status(DriverStatus.ERROR)
        logger.error(f"Protocol driver {self.protocol_name} error: {error_message}")

    def get_info(self) -> Dict[str, Any]:
        return {
            "protocol": self.protocol_name,
            "status": self.status,
            "last_connected": self.last_connected,
            "last_error": self.last_error,
            "config": self.config,
        }
