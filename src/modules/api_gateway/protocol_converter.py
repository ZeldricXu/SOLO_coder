from typing import Dict, Any, Union
from .types import ProtocolType, GatewayRequest, GatewayResponse
from src.core import ValidationError, PlatformError
import logging
import json

logger = logging.getLogger(__name__)


class ProtocolConverter:
    def __init__(self):
        self._converters = {
            (ProtocolType.HTTP, ProtocolType.HTTP): self._http_to_http,
            (ProtocolType.HTTP, ProtocolType.GRPC): self._http_to_grpc,
            (ProtocolType.HTTP, ProtocolType.WEBSOCKET): self._http_to_websocket,
            (ProtocolType.GRPC, ProtocolType.HTTP): self._grpc_to_http,
            (ProtocolType.MQTT, ProtocolType.HTTP): self._mqtt_to_http,
            (ProtocolType.AMQP, ProtocolType.HTTP): self._amqp_to_http,
        }

    async def convert_request(
        self,
        request: GatewayRequest,
        target_protocol: ProtocolType,
    ) -> Dict[str, Any]:
        key = (request.source_protocol, target_protocol)
        converter = self._converters.get(key)
        if not converter:
            raise ValidationError(
                f"Unsupported protocol conversion: {request.source_protocol} -> {target_protocol}"
            )
        return await converter(request)

    async def convert_response(
        self,
        response_body: Any,
        source_protocol: ProtocolType,
        target_protocol: ProtocolType,
        original_request_id: str,
    ) -> GatewayResponse:
        try:
            if target_protocol == ProtocolType.HTTP:
                return GatewayResponse(
                    request_id=original_request_id,
                    status_code=200,
                    headers={"Content-Type": "application/json"},
                    body=response_body if isinstance(response_body, dict) else {"data": response_body},
                    protocol=target_protocol,
                )
            elif target_protocol == ProtocolType.GRPC:
                return GatewayResponse(
                    request_id=original_request_id,
                    status_code=0,
                    body=response_body,
                    protocol=target_protocol,
                )
            else:
                return GatewayResponse(
                    request_id=original_request_id,
                    status_code=200,
                    body=response_body,
                    protocol=target_protocol,
                )
        except Exception as e:
            logger.error(f"Response conversion failed: {e}")
            raise PlatformError(f"响应协议转换失败: {str(e)}")

    async def _http_to_http(self, request: GatewayRequest) -> Dict[str, Any]:
        return {
            "method": request.method.value,
            "headers": dict(request.headers),
            "body": request.body,
            "query_params": dict(request.query_params),
        }

    async def _http_to_grpc(self, request: GatewayRequest) -> Dict[str, Any]:
        body = request.body if isinstance(request.body, dict) else {}
        return {
            "service": request.path.split("/")[-2] if len(request.path.split("/")) > 1 else "default",
            "method": request.path.split("/")[-1],
            "payload": body,
            "metadata": dict(request.headers),
        }

    async def _http_to_websocket(self, request: GatewayRequest) -> Dict[str, Any]:
        body = request.body if isinstance(request.body, dict) else {}
        return {
            "event": body.get("event", "message"),
            "data": body.get("data", body),
            "headers": dict(request.headers),
        }

    async def _grpc_to_http(self, request: GatewayRequest) -> Dict[str, Any]:
        body = request.body if isinstance(request.body, dict) else {}
        return {
            "method": "POST",
            "headers": {"Content-Type": "application/json"},
            "body": body,
            "query_params": {},
        }

    async def _mqtt_to_http(self, request: GatewayRequest) -> Dict[str, Any]:
        body = request.body if isinstance(request.body, dict) else {"payload": request.body}
        return {
            "method": "POST",
            "headers": {"Content-Type": "application/json"},
            "body": body,
            "query_params": {"topic": request.headers.get("mqtt_topic", "")},
        }

    async def _amqp_to_http(self, request: GatewayRequest) -> Dict[str, Any]:
        body = request.body if isinstance(request.body, dict) else {"payload": request.body}
        return {
            "method": "POST",
            "headers": {"Content-Type": "application/json"},
            "body": body,
            "query_params": {
                "exchange": request.headers.get("amqp_exchange", ""),
                "routing_key": request.headers.get("amqp_routing_key", ""),
            },
        }

    async def transform_request_body(
        self,
        body: Any,
        transform_rules: Dict[str, Any],
    ) -> Any:
        if not transform_rules:
            return body

        if not isinstance(body, dict):
            return body

        result = dict(body)
        for key, rule in transform_rules.items():
            if isinstance(rule, str) and rule.startswith("$"):
                source_key = rule[1:]
                if source_key in body:
                    result[key] = body[source_key]
            elif callable(rule):
                result[key] = rule(body)
            else:
                result[key] = rule

        return result

    async def transform_response_body(
        self,
        body: Any,
        transform_rules: Dict[str, Any],
    ) -> Any:
        return await self.transform_request_body(body, transform_rules)
