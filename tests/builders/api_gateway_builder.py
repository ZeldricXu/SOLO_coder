from typing import Any, Dict, Optional, Tuple
import uuid
from datetime import datetime, timezone


class RequestBuilder:
    def __init__(self):
        self._method: str = "GET"
        self._path: str = "/api/v1/test"
        self._headers: Dict[str, str] = {}
        self._body: Optional[str] = None
        self._client_ip: str = "127.0.0.1"
        self._user_agent: str = "TestClient/1.0"
        self._trace_id: Optional[str] = None
        self._span_id: Optional[str] = None

    def with_method(self, method: str) -> "RequestBuilder":
        self._method = method
        return self

    def with_path(self, path: str) -> "RequestBuilder":
        self._path = path
        return self

    def with_header(self, key: str, value: str) -> "RequestBuilder":
        self._headers[key] = value
        return self

    def with_headers(self, headers: Dict[str, str]) -> "RequestBuilder":
        self._headers = headers
        return self

    def with_body(self, body: str) -> "RequestBuilder":
        self._body = body
        return self

    def with_json_body(self, data: Dict[str, Any]) -> "RequestBuilder":
        import json
        self._body = json.dumps(data)
        self._headers["Content-Type"] = "application/json"
        return self

    def with_client_ip(self, client_ip: str) -> "RequestBuilder":
        self._client_ip = client_ip
        return self

    def with_user_agent(self, user_agent: str) -> "RequestBuilder":
        self._user_agent = user_agent
        return self

    def with_trace_id(self, trace_id: str) -> "RequestBuilder":
        self._trace_id = trace_id
        self._headers["X-Trace-ID"] = trace_id
        return self

    def with_span_id(self, span_id: str) -> "RequestBuilder":
        self._span_id = span_id
        self._headers["X-Span-ID"] = span_id
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "method": self._method,
            "path": self._path,
            "headers": self._headers,
            "body": self._body,
            "client_ip": self._client_ip,
            "user_agent": self._user_agent,
            "trace_id": self._trace_id,
            "span_id": self._span_id,
        }


class SpanBuilder:
    def __init__(self):
        self._name: str = "test-span"
        self._service_name: str = "api-gateway"
        self._trace_id: Optional[str] = None
        self._span_id: Optional[str] = None
        self._parent_span_id: Optional[str] = None
        self._kind: Optional[str] = "server"
        self._attributes: Dict[str, Any] = {}
        self._status: Optional[str] = None
        self._status_message: Optional[str] = None

    def with_name(self, name: str) -> "SpanBuilder":
        self._name = name
        return self

    def with_service_name(self, service_name: str) -> "SpanBuilder":
        self._service_name = service_name
        return self

    def with_trace_id(self, trace_id: str) -> "SpanBuilder":
        self._trace_id = trace_id
        return self

    def with_span_id(self, span_id: str) -> "SpanBuilder":
        self._span_id = span_id
        return self

    def with_parent_span_id(self, parent_span_id: str) -> "SpanBuilder":
        self._parent_span_id = parent_span_id
        return self

    def with_kind(self, kind: str) -> "SpanBuilder":
        self._kind = kind
        return self

    def with_attribute(self, key: str, value: Any) -> "SpanBuilder":
        self._attributes[key] = value
        return self

    def with_attributes(self, attributes: Dict[str, Any]) -> "SpanBuilder":
        self._attributes = attributes
        return self

    def with_status(self, status: str, message: Optional[str] = None) -> "SpanBuilder":
        self._status = status
        self._status_message = message
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "name": self._name,
            "service_name": self._service_name,
            "trace_id": self._trace_id,
            "span_id": self._span_id,
            "parent_span_id": self._parent_span_id,
            "kind": self._kind,
            "attributes": self._attributes,
            "status": self._status,
            "status_message": self._status_message,
        }


class TraceContextBuilder:
    def __init__(self):
        self._trace_id: str = str(uuid.uuid4())
        self._span_id: str = str(uuid.uuid4())
        self._parent_span_id: Optional[str] = None
        self._service_name: str = "api-gateway"

    def with_trace_id(self, trace_id: str) -> "TraceContextBuilder":
        self._trace_id = trace_id
        return self

    def with_span_id(self, span_id: str) -> "SpanBuilder":
        self._span_id = span_id
        return self

    def with_parent_span_id(self, parent_span_id: str) -> "TraceContextBuilder":
        self._parent_span_id = parent_span_id
        return self

    def with_service_name(self, service_name: str) -> "TraceContextBuilder":
        self._service_name = service_name
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "trace_id": self._trace_id,
            "span_id": self._span_id,
            "parent_span_id": self._parent_span_id,
            "service_name": self._service_name,
        }

    def build_headers(self) -> Dict[str, str]:
        headers = {
            "X-Trace-ID": self._trace_id,
            "X-Span-ID": self._span_id,
        }
        if self._parent_span_id:
            headers["X-Parent-Span-ID"] = self._parent_span_id
        return headers
