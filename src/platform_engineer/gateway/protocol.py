import json
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

from ..core.exceptions import GatewayError


@dataclass
class ProtocolRequest:
    protocol: str
    method: str
    path: str
    headers: Dict[str, str]
    body: Optional[bytes]
    query_params: Dict[str, str]


@dataclass
class ProtocolResponse:
    protocol: str
    status_code: int
    headers: Dict[str, str]
    body: Optional[bytes]
    metadata: Dict[str, Any]


class ProtocolConverter(ABC):
    @abstractmethod
    def to_internal(self, request: ProtocolRequest) -> Dict[str, Any]:
        pass

    @abstractmethod
    def from_internal(self, data: Dict[str, Any], original: ProtocolRequest) -> ProtocolResponse:
        pass

    @abstractmethod
    def get_supported_protocols(self) -> List[str]:
        pass


class RESTConverter(ProtocolConverter):
    def __init__(self, default_content_type: str = "application/json"):
        self._default_content_type = default_content_type

    def to_internal(self, request: ProtocolRequest) -> Dict[str, Any]:
        body_dict = {}
        if request.body:
            try:
                body_dict = json.loads(request.body.decode("utf-8"))
            except Exception:
                body_dict = {"raw": request.body}
        return {
            "method": request.method,
            "path": request.path,
            "headers": dict(request.headers),
            "query_params": dict(request.query_params),
            "body": body_dict,
            "protocol": request.protocol,
        }

    def from_internal(self, data: Dict[str, Any], original: ProtocolRequest) -> ProtocolResponse:
        status_code = data.get("status_code", 200)
        headers = data.get("headers", {})
        if "Content-Type" not in headers:
            headers["Content-Type"] = self._default_content_type
        body = data.get("body")
        body_bytes: Optional[bytes] = None
        if body is not None:
            if isinstance(body, bytes):
                body_bytes = body
            elif isinstance(body, str):
                body_bytes = body.encode("utf-8")
            else:
                body_bytes = json.dumps(body).encode("utf-8")
        return ProtocolResponse(
            protocol="http",
            status_code=status_code,
            headers=headers,
            body=body_bytes,
            metadata=data.get("metadata", {}),
        )

    def get_supported_protocols(self) -> List[str]:
        return ["http", "https", "rest"]


class GRPCConverter(ProtocolConverter):
    def __init__(self):
        self._converters: Dict[str, Any] = {}

    def register_message_converter(self, message_type: str, converter: Any) -> None:
        self._converters[message_type] = converter

    def to_internal(self, request: ProtocolRequest) -> Dict[str, Any]:
        message_type = request.headers.get("Grpc-Message-Type", "unknown")
        body_dict: Dict[str, Any] = {}
        if request.body:
            converter = self._converters.get(message_type)
            if converter and hasattr(converter, "from_bytes"):
                try:
                    body_dict = converter.from_bytes(request.body)
                except Exception as e:
                    raise GatewayError(f"gRPC message parse error: {e}")
            else:
                try:
                    body_dict = json.loads(request.body.decode("utf-8"))
                except Exception:
                    body_dict = {"raw": request.body.hex() if request.body else {}}
        return {
            "method": request.method,
            "path": request.path,
            "headers": dict(request.headers),
            "query_params": dict(request.query_params),
            "body": body_dict,
            "protocol": "grpc",
            "message_type": message_type,
        }

    def from_internal(self, data: Dict[str, Any], original: ProtocolRequest) -> ProtocolResponse:
        message_type = original.headers.get("Grpc-Message-Type", "unknown")
        status_code = data.get("status_code", 200)
        headers = data.get("headers", {"Content-Type": "application/grpc"})
        body = data.get("body", {})
        body_bytes: Optional[bytes] = None
        converter = self._converters.get(message_type)
        if body is not None:
            if isinstance(body, bytes):
                body_bytes = body
            elif converter and hasattr(converter, "to_bytes"):
                try:
                    body_bytes = converter.to_bytes(body)
                except Exception as e:
                    raise GatewayError(f"gRPC message serialize error: {e}")
            elif isinstance(body, (dict, list)):
                body_bytes = json.dumps(body).encode("utf-8")
        return ProtocolResponse(
            protocol="grpc",
            status_code=status_code,
            headers=headers,
            body=body_bytes,
            metadata=data.get("metadata", {}),
        )

    def get_supported_protocols(self) -> List[str]:
        return ["grpc", "grpc-web"]


class ProtocolConverterRegistry:
    def __init__(self):
        self._converters: Dict[str, ProtocolConverter] = {}

    def register(self, converter: ProtocolConverter) -> None:
        for protocol in converter.get_supported_protocols():
            self._converters[protocol] = converter

    def get(self, protocol: str) -> Optional[ProtocolConverter]:
        return self._converters.get(protocol.lower())

    def supports(self, protocol: str) -> bool:
        return protocol.lower() in self._converters

    def list_protocols(self) -> List[str]:
        return list(self._converters.keys())
