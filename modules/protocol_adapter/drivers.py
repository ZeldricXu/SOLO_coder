from abc import ABC, abstractmethod
from typing import Any, AsyncGenerator, Dict, List, Optional
from datetime import datetime
import asyncio


class ProtocolDriver(ABC):
    def __init__(self, config: Dict[str, Any]):
        self.config = config
        self.connected = False
        self.driver_name = self.__class__.__name__

    @abstractmethod
    async def connect(self) -> bool:
        pass

    @abstractmethod
    async def disconnect(self) -> None:
        pass

    @abstractmethod
    async def read(self, address: str, **kwargs) -> Any:
        pass

    @abstractmethod
    async def write(self, address: str, value: Any, **kwargs) -> bool:
        pass

    async def read_batch(self, addresses: List[str], **kwargs) -> Dict[str, Any]:
        results = {}
        for address in addresses:
            try:
                results[address] = await self.read(address, **kwargs)
            except Exception as e:
                results[address] = {"error": str(e)}
        return results

    async def write_batch(self, values: Dict[str, Any], **kwargs) -> Dict[str, bool]:
        results = {}
        for address, value in values.items():
            try:
                results[address] = await self.write(address, value, **kwargs)
            except Exception as e:
                results[address] = False
        return results

    async def subscribe(self, address: str, callback) -> None:
        pass

    async def unsubscribe(self, address: str) -> None:
        pass

    def is_connected(self) -> bool:
        return self.connected

    def get_info(self) -> Dict[str, Any]:
        return {
            "driver": self.driver_name,
            "connected": self.connected,
            "config": self.config,
        }


class ModbusTCPDriver(ProtocolDriver):
    def __init__(self, config: Dict[str, Any]):
        super().__init__(config)
        self.host = config.get("host", "localhost")
        self.port = config.get("port", 502)
        self.slave_id = config.get("slave_id", 1)
        self._client = None

    async def connect(self) -> bool:
        try:
            self.connected = True
            return True
        except Exception:
            return False

    async def disconnect(self) -> None:
        self.connected = False

    async def read(self, address: str, **kwargs) -> Any:
        if not self.connected:
            raise ConnectionError("Not connected")

        register_type = kwargs.get("register_type", "holding")
        count = kwargs.get("count", 1)

        return {
            "address": address,
            "value": [0] * count,
            "register_type": register_type,
            "timestamp": datetime.utcnow().isoformat(),
        }

    async def write(self, address: str, value: Any, **kwargs) -> bool:
        if not self.connected:
            raise ConnectionError("Not connected")

        register_type = kwargs.get("register_type", "holding")

        return True


class MQTTDriver(ProtocolDriver):
    def __init__(self, config: Dict[str, Any]):
        super().__init__(config)
        self.broker = config.get("broker", "localhost")
        self.port = config.get("port", 1883)
        self.username = config.get("username")
        self.password = config.get("password")
        self.client_id = config.get("client_id", "iot-gateway")
        self._subscriptions: Dict[str, callable] = {}

    async def connect(self) -> bool:
        try:
            self.connected = True
            return True
        except Exception:
            return False

    async def disconnect(self) -> None:
        self.connected = False
        self._subscriptions.clear()

    async def read(self, address: str, **kwargs) -> Any:
        timeout = kwargs.get("timeout", 5.0)
        return {
            "topic": address,
            "payload": None,
            "timestamp": datetime.utcnow().isoformat(),
        }

    async def write(self, address: str, value: Any, **kwargs) -> bool:
        if not self.connected:
            raise ConnectionError("Not connected")

        qos = kwargs.get("qos", 0)
        retain = kwargs.get("retain", False)

        return True

    async def subscribe(self, address: str, callback) -> None:
        self._subscriptions[address] = callback

    async def unsubscribe(self, address: str) -> None:
        if address in self._subscriptions:
            del self._subscriptions[address]


class OPCUADriver(ProtocolDriver):
    def __init__(self, config: Dict[str, Any]):
        super().__init__(config)
        self.endpoint = config.get("endpoint", "opc.tcp://localhost:4840")
        self.namespace = config.get("namespace", 0)
        self._client = None

    async def connect(self) -> bool:
        try:
            self.connected = True
            return True
        except Exception:
            return False

    async def disconnect(self) -> None:
        self.connected = False

    async def read(self, address: str, **kwargs) -> Any:
        if not self.connected:
            raise ConnectionError("Not connected")

        return {
            "node_id": address,
            "value": None,
            "source_timestamp": datetime.utcnow().isoformat(),
            "server_timestamp": datetime.utcnow().isoformat(),
        }

    async def write(self, address: str, value: Any, **kwargs) -> bool:
        if not self.connected:
            raise ConnectionError("Not connected")
        return True


class HTTPRestDriver(ProtocolDriver):
    def __init__(self, config: Dict[str, Any]):
        super().__init__(config)
        self.base_url = config.get("base_url", "")
        self.timeout = config.get("timeout", 30)
        self.headers = config.get("headers", {})
        self.auth = config.get("auth")

    async def connect(self) -> bool:
        self.connected = True
        return True

    async def disconnect(self) -> None:
        self.connected = False

    async def read(self, address: str, **kwargs) -> Any:
        method = kwargs.get("method", "GET")
        params = kwargs.get("params")

        return {
            "url": f"{self.base_url}/{address.lstrip('/')}",
            "method": method,
            "status_code": 200,
            "data": {},
            "timestamp": datetime.utcnow().isoformat(),
        }

    async def write(self, address: str, value: Any, **kwargs) -> bool:
        method = kwargs.get("method", "POST")

        return True


class DriverFactory:
    _drivers: Dict[str, type] = {
        "modbus_tcp": ModbusTCPDriver,
        "mqtt": MQTTDriver,
        "opcua": OPCUADriver,
        "http_rest": HTTPRestDriver,
    }

    @classmethod
    def register(cls, name: str, driver_class: type) -> None:
        cls._drivers[name] = driver_class

    @classmethod
    def create(cls, driver_type: str, config: Dict[str, Any]) -> ProtocolDriver:
        if driver_type not in cls._drivers:
            raise ValueError(f"Unknown driver type: {driver_type}")
        return cls._drivers[driver_type](config)

    @classmethod
    def available_drivers(cls) -> List[str]:
        return list(cls._drivers.keys())
