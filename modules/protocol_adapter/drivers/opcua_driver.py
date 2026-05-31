from typing import Dict, Any, Optional, Callable
import threading
import time

from modules.protocol_adapter.drivers.base import ProtocolDriver, DriverStatus
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class OPCUADriver(ProtocolDriver):
    def __init__(self):
        super().__init__("opcua")
        self._client = None
        self._subscription = None
        self._subscriptions: Dict[str, Callable[[str, Dict[str, Any]], None]] = {}
        self._nodes: Dict[str, Any] = {}

    def connect(self, config: Dict[str, Any]) -> bool:
        try:
            self.config = config
            self.set_status(DriverStatus.CONNECTING)

            url = config.get("url", "opc.tcp://localhost:4840")
            username = config.get("username")
            password = config.get("password")
            timeout = config.get("timeout", 10)

            try:
                from opcua import Client, ua
                self._client = Client(url=url, timeout=timeout)

                if username and password:
                    self._client.set_user(username)
                    self._client.set_password(password)

                security_policy = config.get("security_policy")
                if security_policy:
                    self._client.set_security_string(security_policy)

                self._client.connect()
                self.set_status(DriverStatus.CONNECTED)
                logger.info("OPC UA driver connected successfully")
                return True

            except ImportError:
                logger.warning("opcua not installed, using mock driver for development")
                self._client = MockOPCUAClient(url)
                self.set_status(DriverStatus.CONNECTED)
                return True

        except Exception as e:
            self.set_error(f"Connection failed: {str(e)}")
            return False

    def disconnect(self) -> None:
        try:
            if self._subscription:
                self._subscription.delete()
                self._subscription = None

            if self._client:
                if hasattr(self._client, "disconnect"):
                    self._client.disconnect()
                self._client = None

            self._subscriptions.clear()
            self._nodes.clear()
            self.set_status(DriverStatus.DISCONNECTED)
        except Exception as e:
            logger.error(f"Error disconnecting OPC UA driver: {str(e)}")

    def read_data(self, address: str, **kwargs) -> Optional[Dict[str, Any]]:
        if not self.is_connected() or not self._client:
            logger.warning("OPC UA driver not connected, cannot read data")
            return None

        try:
            node = self._get_node(address)
            if not node:
                raise ValueError(f"Node not found: {address}")

            value = node.get_value()
            data_type = node.get_data_type_as_variant_type()

            return {
                "node_id": address,
                "value": value,
                "data_type": str(data_type),
                "timestamp": time.time(),
            }

        except Exception as e:
            logger.error(f"Failed to read OPC UA data from node {address}: {str(e)}")
            return None

    def write_data(self, address: str, data: Dict[str, Any], **kwargs) -> bool:
        if not self.is_connected() or not self._client:
            logger.warning("OPC UA driver not connected, cannot write data")
            return False

        try:
            node = self._get_node(address)
            if not node:
                raise ValueError(f"Node not found: {address}")

            value = data.get("value", data)
            variant_type = kwargs.get("variant_type")

            if variant_type:
                from opcua import ua
                variant = ua.Variant(value, getattr(ua.VariantType, variant_type))
                node.set_value(variant)
            else:
                node.set_value(value)

            return True

        except Exception as e:
            logger.error(f"Failed to write OPC UA data to node {address}: {str(e)}")
            return False

    def subscribe(self, address: str, callback: Callable[[str, Dict[str, Any]], None], **kwargs) -> bool:
        if not self.is_connected() or not self._client:
            logger.warning("OPC UA driver not connected, cannot subscribe")
            return False

        try:
            node = self._get_node(address)
            if not node:
                raise ValueError(f"Node not found: {address}")

            self._subscriptions[address] = callback

            if not self._subscription:
                self._subscription = self._client.create_subscription(
                    kwargs.get("sampling_interval", 1000),
                    OPCUASubHandler(self._on_data_change)
                )

            self._subscription.subscribe_data_change(node)
            logger.info(f"Subscribed to OPC UA node: {address}")
            return True

        except Exception as e:
            logger.error(f"Failed to subscribe to OPC UA node {address}: {str(e)}")
            return False

    def unsubscribe(self, address: str) -> None:
        if address in self._subscriptions:
            try:
                node = self._get_node(address)
                if node and self._subscription:
                    self._subscription.unsubscribe(node)
            except Exception as e:
                logger.error(f"Error unsubscribing from OPC UA node {address}: {str(e)}")

            del self._subscriptions[address]
            logger.info(f"Unsubscribed from OPC UA node: {address}")

    def _get_node(self, node_id: str):
        if node_id in self._nodes:
            return self._nodes[node_id]

        try:
            node = self._client.get_node(node_id)
            self._nodes[node_id] = node
            return node
        except Exception as e:
            logger.error(f"Error getting OPC UA node {node_id}: {str(e)}")
            return None

    def _on_data_change(self, node, value, data_type):
        try:
            node_id = str(node.nodeid)
            data = {
                "node_id": node_id,
                "value": value,
                "data_type": str(data_type),
                "timestamp": time.time(),
            }

            if node_id in self._subscriptions:
                self._subscriptions[node_id](node_id, data)

            self.on_data_received(node_id, data)

        except Exception as e:
            logger.error(f"Error processing OPC UA data change: {str(e)}")

    def browse_node(self, node_id: str = "Root", max_depth: int = 2) -> Dict[str, Any]:
        if not self.is_connected() or not self._client:
            return {}

        try:
            node = self._get_node(node_id) if node_id != "Root" else self._client.get_root_node()
            return self._browse_node_recursive(node, max_depth, 0)
        except Exception as e:
            logger.error(f"Error browsing OPC UA nodes: {str(e)}")
            return {}

    def _browse_node_recursive(self, node, max_depth: int, current_depth: int) -> Dict[str, Any]:
        if current_depth >= max_depth:
            return {}

        result = {
            "node_id": str(node.nodeid),
            "display_name": str(node.get_display_name().Text) if hasattr(node, "get_display_name") else "",
            "node_class": str(node.get_node_class()) if hasattr(node, "get_node_class") else "",
        }

        if current_depth < max_depth - 1:
            try:
                children = node.get_children()
                result["children"] = [
                    self._browse_node_recursive(child, max_depth, current_depth + 1)
                    for child in children
                ]
            except Exception:
                result["children"] = []

        return result


class OPCUASubHandler:
    def __init__(self, callback):
        self.callback = callback

    def datachange_notification(self, node, value, data):
        try:
            data_type = data.monitored_item.Value.VariantType
            self.callback(node, value, data_type)
        except Exception as e:
            logger.error(f"Error in OPC UA subscription handler: {str(e)}")


class MockOPCUAClient:
    def __init__(self, url: str):
        self.url = url
        self._connected = True
        self._nodes = {}

    def connect(self) -> None:
        self._connected = True

    def disconnect(self) -> None:
        self._connected = False

    def get_node(self, node_id: str):
        if node_id not in self._nodes:
            self._nodes[node_id] = MockOPCUANode(node_id)
        return self._nodes[node_id]

    def get_root_node(self):
        return self.get_node("Root")

    def create_subscription(self, interval, handler):
        return MockOPCUASubscription(handler)


class MockOPCUANode:
    def __init__(self, node_id: str):
        self.nodeid = node_id
        self._value = 0

    def get_value(self):
        return self._value

    def set_value(self, value):
        self._value = value

    def get_data_type_as_variant_type(self):
        return "Int32"

    def get_display_name(self):
        class Name:
            Text = self.nodeid
        return Name()

    def get_node_class(self):
        return "Variable"

    def get_children(self):
        return []


class MockOPCUASubscription:
    def __init__(self, handler):
        self.handler = handler

    def subscribe_data_change(self, node):
        pass

    def unsubscribe(self, node):
        pass

    def delete(self):
        pass
