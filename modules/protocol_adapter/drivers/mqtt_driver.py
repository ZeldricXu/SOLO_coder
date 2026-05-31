import paho.mqtt.client as mqtt
import json
from typing import Dict, Any, Optional, Callable
from datetime import datetime
import threading
import time

from modules.protocol_adapter.drivers.base import ProtocolDriver, DriverStatus
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class MQTTDriver(ProtocolDriver):
    def __init__(self):
        super().__init__("mqtt")
        self.client: Optional[mqtt.Client] = None
        self.subscriptions: Dict[str, Callable[[str, Dict[str, Any]], None]] = {}
        self._reconnect_thread: Optional[threading.Thread] = None
        self._reconnect_stop = threading.Event()

    def connect(self, config: Dict[str, Any]) -> bool:
        try:
            self.config = config
            self.set_status(DriverStatus.CONNECTING)

            broker = config.get("broker", "localhost")
            port = config.get("port", 1883)
            username = config.get("username")
            password = config.get("password")
            client_id = config.get("client_id", f"edge-gateway-{int(time.time())}")
            keepalive = config.get("keepalive", 60)

            self.client = mqtt.Client(client_id=client_id, protocol=mqtt.MQTTv311)

            if username and password:
                self.client.username_pw_set(username, password)

            self.client.on_connect = self._on_connect
            self.client.on_disconnect = self._on_disconnect
            self.client.on_message = self._on_message
            self.client.on_subscribe = self._on_subscribe

            tls_config = config.get("tls")
            if tls_config:
                self.client.tls_set(
                    ca_certs=tls_config.get("ca_certs"),
                    certfile=tls_config.get("certfile"),
                    keyfile=tls_config.get("keyfile"),
                )

            self.client.connect(broker, port, keepalive)
            self.client.loop_start()

            timeout = config.get("connect_timeout", 10)
            start_time = time.time()
            while self.status != DriverStatus.CONNECTED and (time.time() - start_time) < timeout:
                time.sleep(0.1)

            if self.status == DriverStatus.CONNECTED:
                self._start_reconnect_monitor()
                return True
            else:
                self.set_error("Connection timeout")
                return False

        except Exception as e:
            self.set_error(f"Connection failed: {str(e)}")
            return False

    def disconnect(self) -> None:
        self._reconnect_stop.set()
        if self._reconnect_thread:
            self._reconnect_thread.join(timeout=2)

        if self.client:
            for topic in list(self.subscriptions.keys()):
                self.client.unsubscribe(topic)
            self.subscriptions.clear()
            self.client.loop_stop()
            self.client.disconnect()
            self.client = None

        self.set_status(DriverStatus.DISCONNECTED)

    def read_data(self, address: str, **kwargs) -> Optional[Dict[str, Any]]:
        if not self.is_connected():
            logger.warning("MQTT driver not connected, cannot read data")
            return None

        result_holder = {"data": None, "event": threading.Event()}
        timeout = kwargs.get("timeout", 5)

        def callback(topic: str, data: Dict[str, Any]) -> None:
            if topic == address:
                result_holder["data"] = data
                result_holder["event"].set()

        self.client.subscribe(address, qos=kwargs.get("qos", 1))
        self.client.message_callback_add(address, lambda client, userdata, msg: callback(msg.topic, self._parse_message(msg)))

        result_holder["event"].wait(timeout=timeout)
        self.client.message_callback_remove(address)
        self.client.unsubscribe(address)

        return result_holder["data"]

    def write_data(self, address: str, data: Dict[str, Any], **kwargs) -> bool:
        if not self.is_connected():
            logger.warning("MQTT driver not connected, cannot write data")
            return False

        try:
            payload = json.dumps(data) if isinstance(data, dict) else str(data)
            qos = kwargs.get("qos", 1)
            retain = kwargs.get("retain", False)

            result = self.client.publish(address, payload, qos=qos, retain=retain)
            result.wait_for_publish()
            return result.is_published()

        except Exception as e:
            logger.error(f"Failed to publish MQTT message: {str(e)}")
            return False

    def subscribe(self, address: str, callback: Callable[[str, Dict[str, Any]], None], **kwargs) -> bool:
        if not self.is_connected():
            logger.warning("MQTT driver not connected, cannot subscribe")
            return False

        try:
            qos = kwargs.get("qos", 1)
            self.client.subscribe(address, qos=qos)
            self.subscriptions[address] = callback
            logger.info(f"Subscribed to MQTT topic: {address}")
            return True

        except Exception as e:
            logger.error(f"Failed to subscribe to MQTT topic {address}: {str(e)}")
            return False

    def unsubscribe(self, address: str) -> None:
        if address in self.subscriptions:
            if self.client:
                self.client.unsubscribe(address)
            del self.subscriptions[address]
            logger.info(f"Unsubscribed from MQTT topic: {address}")

    def _on_connect(self, client, userdata, flags, rc) -> None:
        if rc == 0:
            self.set_status(DriverStatus.CONNECTED)
            logger.info("MQTT driver connected successfully")
            for topic, callback in self.subscriptions.items():
                self.client.subscribe(topic, qos=1)
        else:
            self.set_error(f"Connection failed with code: {rc}")

    def _on_disconnect(self, client, userdata, rc) -> None:
        if rc != 0:
            logger.warning(f"MQTT driver disconnected unexpectedly (code: {rc})")
            self.set_status(DriverStatus.RECONNECTING)

    def _on_message(self, client, userdata, msg) -> None:
        try:
            data = self._parse_message(msg)
            if msg.topic in self.subscriptions:
                self.subscriptions[msg.topic](msg.topic, data)
            self.on_data_received(msg.topic, data)
        except Exception as e:
            logger.error(f"Error processing MQTT message: {str(e)}")

    def _on_subscribe(self, client, userdata, mid, granted_qos) -> None:
        logger.debug(f"MQTT subscribe acknowledged (mid: {mid}, qos: {granted_qos})")

    def _parse_message(self, msg) -> Dict[str, Any]:
        try:
            payload = msg.payload.decode("utf-8")
            try:
                return json.loads(payload)
            except json.JSONDecodeError:
                return {"value": payload}
        except Exception as e:
            return {"raw": str(msg.payload), "error": str(e)}

    def _start_reconnect_monitor(self) -> None:
        def monitor():
            while not self._reconnect_stop.is_set():
                if self.status in [DriverStatus.DISCONNECTED, DriverStatus.ERROR, DriverStatus.RECONNECTING]:
                    try:
                        if self.config:
                            logger.info("Attempting to reconnect MQTT driver...")
                            self.set_status(DriverStatus.RECONNECTING)
                            self.client.reconnect()
                    except Exception as e:
                        logger.error(f"Reconnection attempt failed: {str(e)}")
                        time.sleep(5)
                time.sleep(1)

        self._reconnect_thread = threading.Thread(target=monitor, daemon=True)
        self._reconnect_thread.start()
