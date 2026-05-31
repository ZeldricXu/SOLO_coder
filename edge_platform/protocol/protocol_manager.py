import asyncio
import logging
import json
import struct
from typing import Dict, List, Optional, Any, Callable
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
import uuid
import threading
from abc import ABC, abstractmethod

from ..common.event_bus import EventBus, Event, event_bus
from ..common.config import config
from ..common.exceptions import ProtocolException

logger = logging.getLogger(__name__)


class StandardFormat(str, Enum):
    JSON = "json"
    XML = "xml"
    PROTOBUF = "protobuf"
    CSV = "csv"


@dataclass
class ProtocolData:
    data_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    protocol_type: str = ""
    source_device: str = ""
    raw_data: Any = None
    normalized_data: Dict[str, Any] = field(default_factory=dict)
    timestamp: datetime = field(default_factory=datetime.now)
    tags: Dict[str, str] = field(default_factory=dict)


class ProtocolDriver(ABC):
    def __init__(self, name: str):
        self._name = name
        self._is_connected = False
        self._callbacks: List[Callable[[ProtocolData], None]] = []

    @property
    def name(self) -> str:
        return self._name

    @property
    def is_connected(self) -> bool:
        return self._is_connected

    def register_callback(self, callback: Callable[[ProtocolData], None]) -> None:
        self._callbacks.append(callback)

    def unregister_callback(self, callback: Callable[[ProtocolData], None]) -> None:
        if callback in self._callbacks:
            self._callbacks.remove(callback)

    def _notify_callbacks(self, data: ProtocolData) -> None:
        for callback in self._callbacks:
            try:
                callback(data)
            except Exception as e:
                logger.error(f"Error in protocol callback: {e}")

    @abstractmethod
    async def connect(self, config: Dict[str, Any]) -> None:
        pass

    @abstractmethod
    async def disconnect(self) -> None:
        pass

    @abstractmethod
    async def read(self, address: str, **kwargs) -> ProtocolData:
        pass

    @abstractmethod
    async def write(self, address: str, value: Any, **kwargs) -> bool:
        pass

    @abstractmethod
    def normalize(self, raw_data: Any) -> Dict[str, Any]:
        pass


class ModbusDriver(ProtocolDriver):
    def __init__(self):
        super().__init__("modbus")
        self._host: str = ""
        self._port: int = 502
        self._slave_id: int = 1

    async def connect(self, config: Dict[str, Any]) -> None:
        self._host = config.get("host", "localhost")
        self._port = config.get("port", 502)
        self._slave_id = config.get("slave_id", 1)

        logger.info(f"Connecting to Modbus at {self._host}:{self._port}")
        self._is_connected = True

        asyncio.create_task(self._data_polling())

    async def disconnect(self) -> None:
        self._is_connected = False
        logger.info("Disconnected from Modbus")

    async def _data_polling(self) -> None:
        while self._is_connected:
            try:
                data = await self.read("holding_register:0")
                self._notify_callbacks(data)
            except Exception as e:
                logger.error(f"Modbus polling error: {e}")
            await asyncio.sleep(1)

    async def read(self, address: str, **kwargs) -> ProtocolData:
        if not self._is_connected:
            raise ProtocolException("Modbus not connected")

        raw_value = kwargs.get("simulate_value", 1234)

        normalized = self.normalize({
            "address": address,
            "value": raw_value,
            "timestamp": datetime.now().isoformat()
        })

        return ProtocolData(
            protocol_type="modbus",
            source_device=f"{self._host}:{self._port}",
            raw_data=raw_value,
            normalized_data=normalized
        )

    async def write(self, address: str, value: Any, **kwargs) -> bool:
        if not self._is_connected:
            raise ProtocolException("Modbus not connected")

        logger.info(f"Modbus write: {address} = {value}")
        return True

    def normalize(self, raw_data: Any) -> Dict[str, Any]:
        if isinstance(raw_data, dict):
            return {
                "protocol": "modbus",
                "address": raw_data.get("address", ""),
                "value": raw_data.get("value"),
                "unit": raw_data.get("unit", ""),
                "timestamp": raw_data.get("timestamp", datetime.now().isoformat())
            }
        return {
            "protocol": "modbus",
            "value": raw_data,
            "timestamp": datetime.now().isoformat()
        }


class MQTTDriver(ProtocolDriver):
    def __init__(self):
        super().__init__("mqtt")
        self._broker: str = ""
        self._port: int = 1883
        self._topic: str = "#"
        self._client_id: str = ""

    async def connect(self, config: Dict[str, Any]) -> None:
        self._broker = config.get("broker", "localhost")
        self._port = config.get("port", 1883)
        self._topic = config.get("topic", "#")
        self._client_id = config.get("client_id", f"edge_{uuid.uuid4().hex[:8]}")

        logger.info(f"Connecting to MQTT broker at {self._broker}:{self._port}")
        self._is_connected = True

        asyncio.create_task(self._simulate_mqtt_messages())

    async def disconnect(self) -> None:
        self._is_connected = False
        logger.info("Disconnected from MQTT broker")

    async def _simulate_mqtt_messages(self) -> None:
        message_count = 0
        while self._is_connected:
            try:
                raw_data = {
                    "topic": f"sensor/temp/{message_count % 5}",
                    "payload": {
                        "temperature": 25.0 + (message_count % 10),
                        "humidity": 60.0 + (message_count % 15),
                        "timestamp": datetime.now().isoformat()
                    }
                }
                normalized = self.normalize(raw_data)
                self._notify_callbacks(ProtocolData(
                    protocol_type="mqtt",
                    source_device=self._broker,
                    raw_data=raw_data,
                    normalized_data=normalized
                ))
                message_count += 1
            except Exception as e:
                logger.error(f"MQTT message error: {e}")
            await asyncio.sleep(2)

    async def read(self, address: str, **kwargs) -> ProtocolData:
        if not self._is_connected:
            raise ProtocolException("MQTT not connected")

        raw_data = {"topic": address, "payload": kwargs.get("payload", {})}
        normalized = self.normalize(raw_data)

        return ProtocolData(
            protocol_type="mqtt",
            source_device=self._broker,
            raw_data=raw_data,
            normalized_data=normalized
        )

    async def write(self, address: str, value: Any, **kwargs) -> bool:
        if not self._is_connected:
            raise ProtocolException("MQTT not connected")

        logger.info(f"MQTT publish: {address} = {value}")
        return True

    def normalize(self, raw_data: Any) -> Dict[str, Any]:
        if isinstance(raw_data, dict):
            payload = raw_data.get("payload", {})
            if isinstance(payload, str):
                try:
                    payload = json.loads(payload)
                except json.JSONDecodeError:
                    pass

            return {
                "protocol": "mqtt",
                "topic": raw_data.get("topic", ""),
                "payload": payload,
                "timestamp": datetime.now().isoformat()
            }
        return {
            "protocol": "mqtt",
            "payload": raw_data,
            "timestamp": datetime.now().isoformat()
        }


class OPCUADriver(ProtocolDriver):
    def __init__(self):
        super().__init__("opcua")
        self._endpoint: str = ""
        self._namespace: str = ""

    async def connect(self, config: Dict[str, Any]) -> None:
        self._endpoint = config.get("endpoint", "opc.tcp://localhost:4840")
        self._namespace = config.get("namespace", "http://example.org")

        logger.info(f"Connecting to OPC UA at {self._endpoint}")
        self._is_connected = True

        asyncio.create_task(self._data_monitoring())

    async def disconnect(self) -> None:
        self._is_connected = False
        logger.info("Disconnected from OPC UA")

    async def _data_monitoring(self) -> None:
        node_count = 0
        while self._is_connected:
            try:
                raw_data = {
                    "node_id": f"ns=2;s=Variable{node_count % 10}",
                    "value": 100.0 + (node_count % 50),
                    "data_type": "Double",
                    "timestamp": datetime.now().isoformat()
                }
                normalized = self.normalize(raw_data)
                self._notify_callbacks(ProtocolData(
                    protocol_type="opcua",
                    source_device=self._endpoint,
                    raw_data=raw_data,
                    normalized_data=normalized
                ))
                node_count += 1
            except Exception as e:
                logger.error(f"OPC UA monitoring error: {e}")
            await asyncio.sleep(3)

    async def read(self, address: str, **kwargs) -> ProtocolData:
        if not self._is_connected:
            raise ProtocolException("OPC UA not connected")

        raw_data = {
            "node_id": address,
            "value": kwargs.get("simulate_value", 42.0),
            "data_type": kwargs.get("data_type", "Double")
        }
        normalized = self.normalize(raw_data)

        return ProtocolData(
            protocol_type="opcua",
            source_device=self._endpoint,
            raw_data=raw_data,
            normalized_data=normalized
        )

    async def write(self, address: str, value: Any, **kwargs) -> bool:
        if not self._is_connected:
            raise ProtocolException("OPC UA not connected")

        logger.info(f"OPC UA write: {address} = {value}")
        return True

    def normalize(self, raw_data: Any) -> Dict[str, Any]:
        if isinstance(raw_data, dict):
            return {
                "protocol": "opcua",
                "node_id": raw_data.get("node_id", ""),
                "value": raw_data.get("value"),
                "data_type": raw_data.get("data_type", ""),
                "timestamp": raw_data.get("timestamp", datetime.now().isoformat())
            }
        return {
            "protocol": "opcua",
            "value": raw_data,
            "timestamp": datetime.now().isoformat()
        }


class DataFormatConverter:
    @staticmethod
    def to_json(data: Dict[str, Any]) -> str:
        return json.dumps(data, ensure_ascii=False, indent=2)

    @staticmethod
    def from_json(json_str: str) -> Dict[str, Any]:
        return json.loads(json_str)

    @staticmethod
    def to_xml(data: Dict[str, Any]) -> str:
        def dict_to_xml(d: Dict[str, Any], root: str = "data") -> str:
            xml_parts = [f"<{root}>"]
            for key, value in d.items():
                if isinstance(value, dict):
                    xml_parts.append(dict_to_xml(value, key))
                elif isinstance(value, list):
                    for item in value:
                        xml_parts.append(dict_to_xml(item, key))
                else:
                    xml_parts.append(f"<{key}>{value}</{key}>")
            xml_parts.append(f"</{root}>")
            return "".join(xml_parts)

        return dict_to_xml(data)

    @staticmethod
    def to_csv(data: Dict[str, Any]) -> str:
        def flatten(d: Dict[str, Any], parent_key: str = "") -> Dict[str, Any]:
            items = []
            for key, value in d.items():
                new_key = f"{parent_key}.{key}" if parent_key else key
                if isinstance(value, dict):
                    items.extend(flatten(value, new_key).items())
                else:
                    items.append((new_key, value))
            return dict(items)

        flat = flatten(data)
        headers = ",".join(flat.keys())
        values = ",".join(str(v) for v in flat.values())
        return f"{headers}\n{values}"

    @classmethod
    def convert(
        cls,
        data: Dict[str, Any],
        output_format: StandardFormat
    ) -> str:
        if output_format == StandardFormat.JSON:
            return cls.to_json(data)
        elif output_format == StandardFormat.XML:
            return cls.to_xml(data)
        elif output_format == StandardFormat.CSV:
            return cls.to_csv(data)
        else:
            raise ProtocolException(f"Unsupported format: {output_format}")


class ProtocolManager:
    def __init__(self, event_bus_instance: Optional[EventBus] = None):
        self._event_bus = event_bus_instance or event_bus
        self._drivers: Dict[str, ProtocolDriver] = {}
        self._connections: Dict[str, Dict[str, Any]] = {}
        self._data_history: List[ProtocolData] = []
        self._max_history = 1000
        self._lock = threading.RLock()
        self._forward_rules: List[Dict[str, Any]] = []
        self._standard_format = StandardFormat.JSON
        self._initialize_drivers()

    def _initialize_drivers(self) -> None:
        self._drivers["modbus"] = ModbusDriver()
        self._drivers["mqtt"] = MQTTDriver()
        self._drivers["opcua"] = OPCUADriver()

    def register_driver(self, driver_name: str, driver: ProtocolDriver) -> None:
        self._drivers[driver_name] = driver
        logger.info(f"Registered protocol driver: {driver_name}")

    def get_driver(self, protocol_type: str) -> ProtocolDriver:
        driver = self._drivers.get(protocol_type)
        if not driver:
            raise ProtocolException(f"Driver not found for protocol: {protocol_type}")
        return driver

    async def connect_protocol(
        self,
        protocol_type: str,
        connection_config: Dict[str, Any]
    ) -> bool:
        driver = self.get_driver(protocol_type)

        if driver.is_connected:
            await driver.disconnect()

        driver.register_callback(self._on_protocol_data)

        await driver.connect(connection_config)
        self._connections[protocol_type] = connection_config

        self._event_bus.publish(Event(
            event_type="protocol.connected",
            source="protocol",
            payload={"protocol_type": protocol_type}
        ))

        return True

    async def disconnect_protocol(self, protocol_type: str) -> None:
        driver = self.get_driver(protocol_type)
        driver.unregister_callback(self._on_protocol_data)
        await driver.disconnect()

        if protocol_type in self._connections:
            del self._connections[protocol_type]

        self._event_bus.publish(Event(
            event_type="protocol.disconnected",
            source="protocol",
            payload={"protocol_type": protocol_type}
        ))

    def _on_protocol_data(self, data: ProtocolData) -> None:
        with self._lock:
            self._data_history.append(data)
            if len(self._data_history) > self._max_history:
                self._data_history.pop(0)

        self._event_bus.publish(Event(
            event_type="protocol.data.received",
            source="protocol",
            payload={
                "protocol_type": data.protocol_type,
                "source_device": data.source_device,
                "normalized_data": data.normalized_data
            }
        ))

        self._apply_forward_rules(data)

    async def read_data(
        self,
        protocol_type: str,
        address: str,
        **kwargs
    ) -> ProtocolData:
        driver = self.get_driver(protocol_type)
        return await driver.read(address, **kwargs)

    async def write_data(
        self,
        protocol_type: str,
        address: str,
        value: Any,
        **kwargs
    ) -> bool:
        driver = self.get_driver(protocol_type)
        return await driver.write(address, value, **kwargs)

    def add_forward_rule(
        self,
        source_protocol: str,
        target_protocol: str,
        data_filter: Optional[Dict[str, Any]] = None,
        transformation: Optional[Dict[str, Any]] = None
    ) -> str:
        rule_id = str(uuid.uuid4())
        rule = {
            "rule_id": rule_id,
            "source_protocol": source_protocol,
            "target_protocol": target_protocol,
            "filter": data_filter or {},
            "transformation": transformation or {},
            "enabled": True
        }
        self._forward_rules.append(rule)

        logger.info(f"Added forward rule: {source_protocol} -> {target_protocol}")
        return rule_id

    def remove_forward_rule(self, rule_id: str) -> None:
        self._forward_rules = [
            r for r in self._forward_rules if r["rule_id"] != rule_id
        ]

    def _apply_forward_rules(self, data: ProtocolData) -> None:
        for rule in self._forward_rules:
            if not rule.get("enabled", True):
                continue

            if rule["source_protocol"] != data.protocol_type:
                continue

            if not self._match_filter(data.normalized_data, rule["filter"]):
                continue

            asyncio.create_task(self._forward_data(data, rule))

    async def _forward_data(self, data: ProtocolData, rule: Dict[str, Any]) -> None:
        try:
            transformed_data = self._transform_data(data.normalized_data, rule["transformation"])

            target_driver = self.get_driver(rule["target_protocol"])
            if target_driver.is_connected:
                await target_driver.write("forward", transformed_data)

                self._event_bus.publish(Event(
                    event_type="protocol.data.forwarded",
                    source="protocol",
                    payload={
                        "from": data.protocol_type,
                        "to": rule["target_protocol"],
                        "rule_id": rule["rule_id"]
                    }
                ))
        except Exception as e:
            logger.error(f"Error forwarding data: {e}")

    def _match_filter(
        self,
        data: Dict[str, Any],
        filter_config: Dict[str, Any]
    ) -> bool:
        if not filter_config:
            return True

        for key, expected_value in filter_config.items():
            actual_value = data.get(key)
            if actual_value != expected_value:
                return False
        return True

    def _transform_data(
        self,
        data: Dict[str, Any],
        transformation: Dict[str, Any]
    ) -> Dict[str, Any]:
        if not transformation:
            return data

        result = data.copy()
        for key, value in transformation.items():
            if callable(value):
                result[key] = value(data)
            else:
                result[key] = value
        return result

    def convert_format(
        self,
        data: Dict[str, Any],
        output_format: StandardFormat
    ) -> str:
        return DataFormatConverter.convert(data, output_format)

    def get_recent_data(
        self,
        protocol_type: Optional[str] = None,
        limit: int = 100
    ) -> List[ProtocolData]:
        with self._lock:
            data = self._data_history.copy()

        if protocol_type:
            data = [d for d in data if d.protocol_type == protocol_type]

        data.sort(key=lambda d: d.timestamp, reverse=True)
        return data[:limit]

    def set_standard_format(self, format_type: StandardFormat) -> None:
        self._standard_format = format_type

    def list_protocols(self) -> List[Dict[str, Any]]:
        protocols = []
        for name, driver in self._drivers.items():
            protocols.append({
                "name": name,
                "connected": driver.is_connected
            })
        return protocols

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            total_data = len(self._data_history)
            by_protocol: Dict[str, int] = {}
            for d in self._data_history:
                by_protocol[d.protocol_type] = by_protocol.get(d.protocol_type, 0) + 1

        return {
            "total_data_points": total_data,
            "by_protocol": by_protocol,
            "forward_rules": len(self._forward_rules),
            "connected_protocols": sum(
                1 for d in self._drivers.values() if d.is_connected
            )
        }
