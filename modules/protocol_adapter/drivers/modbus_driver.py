from typing import Dict, Any, Optional, Callable
import threading
import time

from modules.protocol_adapter.drivers.base import ProtocolDriver, DriverStatus
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class ModbusDriver(ProtocolDriver):
    def __init__(self):
        super().__init__("modbus")
        self._client = None
        self._polling_thread: Optional[threading.Thread] = None
        self._polling_stop = threading.Event()
        self._subscriptions: Dict[str, Callable[[str, Dict[str, Any]], None]] = {}
        self._polling_interval = 1.0

    def connect(self, config: Dict[str, Any]) -> bool:
        try:
            self.config = config
            self.set_status(DriverStatus.CONNECTING)

            mode = config.get("mode", "tcp")
            host = config.get("host", "localhost")
            port = config.get("port", 502)
            timeout = config.get("timeout", 5)
            self._polling_interval = config.get("polling_interval", 1.0)

            try:
                from pymodbus.client import ModbusTcpClient, ModbusSerialClient
                if mode == "tcp":
                    self._client = ModbusTcpClient(host=host, port=port, timeout=timeout)
                elif mode == "rtu":
                    self._client = ModbusSerialClient(
                        port=config.get("serial_port", "/dev/ttyUSB0"),
                        baudrate=config.get("baudrate", 9600),
                        parity=config.get("parity", "N"),
                        stopbits=config.get("stopbits", 1),
                        bytesize=config.get("bytesize", 8),
                        timeout=timeout,
                    )
                else:
                    raise ValueError(f"Unsupported Modbus mode: {mode}")

                connection = self._client.connect()
                if connection:
                    self.set_status(DriverStatus.CONNECTED)
                    self._start_polling()
                    return True
                else:
                    self.set_error("Failed to connect to Modbus device")
                    return False

            except ImportError:
                logger.warning("pymodbus not installed, using mock driver for development")
                self._client = MockModbusClient(host, port)
                self.set_status(DriverStatus.CONNECTED)
                self._start_polling()
                return True

        except Exception as e:
            self.set_error(f"Connection failed: {str(e)}")
            return False

    def disconnect(self) -> None:
        self._polling_stop.set()
        if self._polling_thread:
            self._polling_thread.join(timeout=2)

        if self._client:
            if hasattr(self._client, "close"):
                self._client.close()
            self._client = None

        self.set_status(DriverStatus.DISCONNECTED)

    def read_data(self, address: str, **kwargs) -> Optional[Dict[str, Any]]:
        if not self.is_connected() or not self._client:
            logger.warning("Modbus driver not connected, cannot read data")
            return None

        try:
            slave_id = kwargs.get("slave_id", 1)
            count = kwargs.get("count", 1)
            data_type = kwargs.get("data_type", "holding_register")

            if data_type == "holding_register":
                result = self._client.read_holding_registers(int(address), count=count, slave=slave_id)
            elif data_type == "input_register":
                result = self._client.read_input_registers(int(address), count=count, slave=slave_id)
            elif data_type == "coil":
                result = self._client.read_coils(int(address), count=count, slave=slave_id)
            elif data_type == "discrete_input":
                result = self._client.read_discrete_inputs(int(address), count=count, slave=slave_id)
            else:
                raise ValueError(f"Unsupported data type: {data_type}")

            if not hasattr(result, "isError") or result.isError():
                raise Exception(f"Modbus read error: {str(result)}")

            return {
                "address": address,
                "values": result.registers if hasattr(result, "registers") else result.bits,
                "data_type": data_type,
                "slave_id": slave_id,
            }

        except Exception as e:
            logger.error(f"Failed to read Modbus data from address {address}: {str(e)}")
            return None

    def write_data(self, address: str, data: Dict[str, Any], **kwargs) -> bool:
        if not self.is_connected() or not self._client:
            logger.warning("Modbus driver not connected, cannot write data")
            return False

        try:
            slave_id = kwargs.get("slave_id", 1)
            values = data.get("values", [data.get("value")])
            data_type = kwargs.get("data_type", "holding_register")

            if data_type == "holding_register":
                if len(values) == 1:
                    result = self._client.write_register(int(address), values[0], slave=slave_id)
                else:
                    result = self._client.write_registers(int(address), values, slave=slave_id)
            elif data_type == "coil":
                if len(values) == 1:
                    result = self._client.write_coil(int(address), bool(values[0]), slave=slave_id)
                else:
                    result = self._client.write_coils(int(address), [bool(v) for v in values], slave=slave_id)
            else:
                raise ValueError(f"Unsupported data type for write: {data_type}")

            if hasattr(result, "isError") and result.isError():
                raise Exception(f"Modbus write error: {str(result)}")

            return True

        except Exception as e:
            logger.error(f"Failed to write Modbus data to address {address}: {str(e)}")
            return False

    def subscribe(self, address: str, callback: Callable[[str, Dict[str, Any]], None], **kwargs) -> bool:
        self._subscriptions[address] = callback
        logger.info(f"Subscribed to Modbus address: {address}")
        return True

    def unsubscribe(self, address: str) -> None:
        if address in self._subscriptions:
            del self._subscriptions[address]
            logger.info(f"Unsubscribed from Modbus address: {address}")

    def _start_polling(self) -> None:
        def poll():
            while not self._polling_stop.is_set():
                if self.is_connected():
                    for address, callback in list(self._subscriptions.items()):
                        try:
                            data = self.read_data(address)
                            if data:
                                callback(address, data)
                                self.on_data_received(address, data)
                        except Exception as e:
                            logger.error(f"Error polling Modbus address {address}: {str(e)}")
                time.sleep(self._polling_interval)

        self._polling_thread = threading.Thread(target=poll, daemon=True)
        self._polling_thread.start()


class MockModbusClient:
    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port
        self._connected = True
        self._registers = {i: 0 for i in range(100)}

    def connect(self) -> bool:
        self._connected = True
        return True

    def close(self) -> None:
        self._connected = False

    def read_holding_registers(self, address: int, count: int, slave: int = 1):
        class Result:
            isError = lambda self: False
            registers = [self._registers.get(address + i, 0) for i in range(count)]
        return Result()

    def read_input_registers(self, address: int, count: int, slave: int = 1):
        return self.read_holding_registers(address, count, slave)

    def write_register(self, address: int, value: int, slave: int = 1):
        self._registers[address] = value
        class Result:
            isError = lambda self: False
        return Result()

    def write_registers(self, address: int, values: list, slave: int = 1):
        for i, value in enumerate(values):
            self._registers[address + i] = value
        class Result:
            isError = lambda self: False
        return Result()
