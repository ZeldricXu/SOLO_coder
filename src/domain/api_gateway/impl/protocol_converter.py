from typing import Dict, Any
from ..models import (
    GatewayRequest,
    GatewayResponse,
    ProtocolType,
)
from ..interfaces import ProtocolConverterPort
from src.core import PlatformError
import logging
import json

logger = logging.getLogger(__name__)


class ProtocolConverter(ProtocolConverterPort):
    def __init__(self):
        self._protocol_handlers = {
            ProtocolType.HTTP: self._handle_http,
            ProtocolType.GRPC: self._handle_grpc,
            ProtocolType.WEBSOCKET: self._handle_websocket,
            ProtocolType.MQTT: self._handle_mqtt,
            ProtocolType.AMQP: self._handle_amqp,
        }

    async def convert_request(
        self, request: GatewayRequest, target_protocol: ProtocolType
    ) -> Dict[str, Any]:
        handler = self._protocol_handlers.get(target_protocol)
        if not handler:
            raise PlatformError(f"不支持的目标协议: {target_protocol}")

        return await handler(request)

    async def convert_response(
        self,
        response_body: Any,
        source_protocol: ProtocolType,
        target_protocol: ProtocolType,
        request_id: str,
    ) -> GatewayResponse:
        body = response_body

        if isinstance(body, (dict, list)):
            pass
        elif isinstance(body, str):
            try:
                body = json.loads(body)
            except (json.JSONDecodeError, TypeError):
                body = {"content": body}
        elif hasattr(body, "model_dump"):
            body = body.model_dump()

        return GatewayResponse(
            request_id=request_id,
            status_code=200,
            body=body,
            protocol=target_protocol,
            headers={"content-type": "application/json"},
        )

    async def transform_request_body(
        self, body: Dict[str, Any], transform: Dict[str, Any]
    ) -> Dict[str, Any]:
        result = dict(body)

        if "rename" in transform:
            for old_key, new_key in transform["rename"].items():
                if old_key in result:
                    result[new_key] = result.pop(old_key)

        if "add" in transform:
            result.update(transform["add"])

        if "remove" in transform:
            for key in transform["remove"]:
                result.pop(key, None)

        if "map" in transform:
            for key, mapper in transform["map"].items():
                if key in result:
                    result[key] = eval(mapper, {"x": result[key]}) if isinstance(mapper, str) else result[key]

        return result

    async def transform_response_body(
        self, body: Dict[str, Any], transform: Dict[str, Any]
    ) -> Dict[str, Any]:
        return await self.transform_request_body(body, transform)

    async def _handle_http(self, request: GatewayRequest) -> Dict[str, Any]:
        return {
            "method": request.method.value,
            "headers": dict(request.headers),
            "body": request.body,
            "query_params": dict(request.query_params),
        }

    async def _handle_grpc(self, request: GatewayRequest) -> Dict[str, Any]:
        body = request.body if isinstance(request.body, dict) else {}
        return {
            "service": request.path.strip("/").split("/")[0] if request.path else "",
            "method": request.path.strip("/").split("/")[-1] if request.path else "",
            "message": body,
            "metadata": dict(request.headers),
        }

    async def _handle_websocket(self, request: GatewayRequest) -> Dict[str, Any]:
        return {
            "type": "message",
            "payload": request.body,
            "headers": dict(request.headers),
        }

    async def _handle_mqtt(self, request: GatewayRequest) -> Dict[str, Any]:
        topic = request.path.strip("/")
        return {
            "topic": topic,
            "payload": request.body,
            "qos": 1,
            "retain": False,
        }

    async def _handle_amqp(self, request: GatewayRequest) -> Dict[str, Any]:
        routing_key = request.path.strip("/").replace("/", ".")
        return {
            "exchange": "",
            "routing_key": routing_key,
            "body": request.body,
            "headers": dict(request.headers),
        }

    def get_supported_protocols(self) -> list:
        return list(self._protocol_handlers.keys())
