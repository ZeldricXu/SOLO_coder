from typing import Dict, Any, Optional, Callable, List
from datetime import datetime
import uuid
import json

from domain.models.device import Device, DeviceProtocol, DeviceStatus
from domain.models.telemetry import TelemetryData
from domain.models.event import EventType
from domain.models.device_shadow import DeviceShadow

from modules.protocol_adapter.drivers.base import ProtocolDriver, DriverStatus
from modules.protocol_adapter.drivers.mqtt_driver import MQTTDriver
from modules.protocol_adapter.drivers.modbus_driver import ModbusDriver
from modules.protocol_adapter.drivers.opcua_driver import OPCUADriver

from infrastructure.persistence.repositories.device_repository import DeviceRepository
from infrastructure.persistence.repositories.shadow_repository import DeviceShadowRepository
from infrastructure.persistence.repositories.telemetry_repository import TelemetryRepository
from infrastructure.messaging.event_bus import EventBus, get_event_bus
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class ProtocolAdapterService:
    def __init__(
        self,
        device_repo: DeviceRepository,
        shadow_repo: DeviceShadowRepository,
        telemetry_repo: TelemetryRepository,
        event_bus: Optional[EventBus] = None,
    ):
        self.device_repo = device_repo
        self.shadow_repo = shadow_repo
        self.telemetry_repo = telemetry_repo
        self.event_bus = event_bus or get_event_bus()

        self._drivers: Dict[str, ProtocolDriver] = {}
        self._device_drivers: Dict[str, str] = {}
        self._data_handlers: List[Callable[[str, Dict[str, Any]], None]] = []

        self._init_drivers()

    def _init_drivers(self) -> None:
        self._drivers = {
            "mqtt": MQTTDriver(),
            "modbus": ModbusDriver(),
            "opcua": OPCUADriver(),
        }
        logger.info(f"Initialized {len(self._drivers)} protocol drivers")

    def register_driver(self, protocol_name: str, driver: ProtocolDriver) -> None:
        self._drivers[protocol_name] = driver
        logger.info(f"Registered custom protocol driver: {protocol_name}")

    def get_available_protocols(self) -> List[str]:
        return list(self._drivers.keys())

    def connect_device(self, device_id: str) -> bool:
        device = self.device_repo.get_by_device_id(device_id)
        if not device:
            logger.error(f"Device not found: {device_id}")
            return False

        protocol = device.protocol.value
        if protocol not in self._drivers:
            logger.error(f"Unsupported protocol: {protocol} for device {device_id}")
            return False

        driver = self._drivers[protocol]

        if not driver.is_connected():
            config = device.protocol_config or {}
            if not driver.connect(config):
                logger.error(f"Failed to connect driver for device {device_id}")
                return False

        self._device_drivers[device_id] = protocol

        self._subscribe_to_device_data(device, driver)

        event = self.event_bus.create_event(
            event_type=EventType.DEVICE_ONLINE,
            device_id=device_id,
            data={"protocol": protocol},
        )
        self.event_bus.publish(event)

        logger.info(f"Device {device_id} connected successfully via {protocol}")
        return True

    def disconnect_device(self, device_id: str) -> None:
        if device_id in self._device_drivers:
            protocol = self._device_drivers[device_id]
            driver = self._drivers.get(protocol)

            if driver:
                device = self.device_repo.get_by_device_id(device_id)
                if device:
                    for point_config in device.protocol_config.get("data_points", []):
                        address = point_config.get("address")
                        if address:
                            driver.unsubscribe(address)

            del self._device_drivers[device_id]
            logger.info(f"Device {device_id} disconnected")

            event = self.event_bus.create_event(
                event_type=EventType.DEVICE_OFFLINE,
                device_id=device_id,
            )
            self.event_bus.publish(event)

    def _subscribe_to_device_data(self, device: Device, driver: ProtocolDriver) -> None:
        data_points = device.protocol_config.get("data_points", [])

        for point_config in data_points:
            address = point_config.get("address")
            if not address:
                continue

            def create_callback(dev_id: str, point_cfg: dict):
                def callback(addr: str, data: Dict[str, Any]):
                    self._on_device_data(dev_id, addr, point_cfg, data)
                return callback

            driver.subscribe(
                address,
                create_callback(device.device_id, point_config),
                **point_config.get("subscribe_options", {})
            )
            logger.debug(f"Subscribed to data point {address} for device {device.device_id}")

    def _on_device_data(self, device_id: str, address: str, point_config: dict, data: Dict[str, Any]) -> None:
        try:
            telemetry_data = self._normalize_data(device_id, address, point_config, data)

            self.telemetry_repo.save_telemetry(telemetry_data)

            self._update_device_shadow(device_id, telemetry_data.data)

            for handler in self._data_handlers:
                try:
                    handler(device_id, telemetry_data.data)
                except Exception as e:
                    logger.error(f"Error in data handler: {str(e)}")

            event = self.event_bus.create_event(
                event_type=EventType.TELEMETRY_RECEIVED,
                device_id=device_id,
                data=telemetry_data.model_dump(),
            )
            self.event_bus.publish(event)

        except Exception as e:
            logger.error(f"Error processing device data from {device_id}: {str(e)}")

    def _normalize_data(self, device_id: str, address: str, point_config: dict, raw_data: Dict[str, Any]) -> TelemetryData:
        metric_name = point_config.get("name", address)
        value_field = point_config.get("value_field", "value")
        value = raw_data.get(value_field, raw_data)

        transformation = point_config.get("transformation")
        if transformation:
            value = self._apply_transformation(value, transformation)

        data_type = point_config.get("data_type")
        if data_type:
            value = self._convert_data_type(value, data_type)

        scaling = point_config.get("scaling")
        if scaling:
            value = self._apply_scaling(value, scaling)

        return TelemetryData(
            device_id=device_id,
            timestamp=datetime.utcnow(),
            data={metric_name: value},
            quality=raw_data.get("quality", 100),
            metadata={
                "address": address,
                "raw_data": raw_data,
                "point_config": point_config,
            }
        )

    def _apply_transformation(self, value: Any, transformation: str) -> Any:
        try:
            namespace = {"value": value, "math": __import__("math")}
            return eval(transformation, namespace)
        except Exception as e:
            logger.warning(f"Transformation failed: {transformation}, error: {str(e)}")
            return value

    def _convert_data_type(self, value: Any, data_type: str) -> Any:
        try:
            if data_type == "int":
                return int(float(value))
            elif data_type == "float":
                return float(value)
            elif data_type == "string":
                return str(value)
            elif data_type == "bool":
                return bool(value)
            return value
        except Exception as e:
            logger.warning(f"Data type conversion failed: {str(e)}")
            return value

    def _apply_scaling(self, value: Any, scaling: dict) -> Any:
        try:
            if isinstance(value, (int, float)):
                multiplier = scaling.get("multiplier", 1)
                offset = scaling.get("offset", 0)
                return value * multiplier + offset
            return value
        except Exception as e:
            logger.warning(f"Scaling failed: {str(e)}")
            return value

    def _update_device_shadow(self, device_id: str, data: Dict[str, Any]) -> None:
        try:
            shadow = self.shadow_repo.get_by_device_id(device_id)
            if not shadow:
                shadow = DeviceShadow(device_id=device_id)

            shadow.update_reported(data)
            self.shadow_repo.upsert(shadow)

            event = self.event_bus.create_event(
                event_type=EventType.SHADOW_REPORTED_UPDATED,
                device_id=device_id,
                data={"reported": shadow.reported},
            )
            self.event_bus.publish(event)

        except Exception as e:
            logger.error(f"Error updating device shadow for {device_id}: {str(e)}")

    def read_device_data(self, device_id: str, address: str, **kwargs) -> Optional[Dict[str, Any]]:
        if device_id not in self._device_drivers:
            logger.warning(f"Device {device_id} not connected")
            return None

        protocol = self._device_drivers[device_id]
        driver = self._drivers.get(protocol)
        if not driver:
            return None

        return driver.read_data(address, **kwargs)

    def write_device_data(self, device_id: str, address: str, data: Dict[str, Any], **kwargs) -> bool:
        if device_id not in self._device_drivers:
            logger.warning(f"Device {device_id} not connected")
            return False

        protocol = self._device_drivers[device_id]
        driver = self._drivers.get(protocol)
        if not driver:
            return False

        success = driver.write_data(address, data, **kwargs)
        if success:
            logger.info(f"Successfully wrote data to device {device_id} at address {address}")
        return success

    def send_command(self, device_id: str, command: str, parameters: Optional[Dict[str, Any]] = None) -> bool:
        device = self.device_repo.get_by_device_id(device_id)
        if not device:
            logger.error(f"Device not found: {device_id}")
            return False

        command_config = device.protocol_config.get("commands", {}).get(command)
        if not command_config:
            logger.error(f"Command {command} not configured for device {device_id}")
            return False

        address = command_config.get("address")
        if not address:
            logger.error(f"Command {command} has no address configured")
            return False

        data = command_config.get("payload", {})
        if parameters:
            data.update(parameters)

        return self.write_device_data(device_id, address, data)

    def get_driver_status(self, protocol: str) -> Optional[Dict[str, Any]]:
        driver = self._drivers.get(protocol)
        if driver:
            return driver.get_info()
        return None

    def get_all_driver_statuses(self) -> Dict[str, Dict[str, Any]]:
        return {protocol: driver.get_info() for protocol, driver in self._drivers.items()}

    def get_connected_devices(self) -> List[str]:
        return list(self._device_drivers.keys())

    def register_data_handler(self, handler: Callable[[str, Dict[str, Any]], None]) -> None:
        self._data_handlers.append(handler)
        logger.info("Registered data handler")

    def disconnect_all(self) -> None:
        for device_id in list(self._device_drivers.keys()):
            self.disconnect_device(device_id)

        for protocol, driver in self._drivers.items():
            if driver.is_connected():
                driver.disconnect()

        logger.info("All devices and drivers disconnected")

    def start_all(self) -> None:
        devices = self.device_repo.get_all()
        connected_count = 0
        for device in devices:
            if device.status == DeviceStatus.ACTIVE:
                if self.connect_device(device.device_id):
                    connected_count += 1
        logger.info(f"Started {connected_count}/{len(devices)} devices")
