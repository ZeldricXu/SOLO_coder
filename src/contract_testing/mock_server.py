from __future__ import annotations

import asyncio
import json
import logging
import random
import time
from typing import Any, Dict, List, Optional
from urllib.parse import parse_qs

from src.contract_testing.models import (
    APICallLog,
    MockEndpoint,
    MockServerConfig,
    SchemaDefinition,
    SchemaType,
)

logger = logging.getLogger(__name__)


class MockResponseGenerator:
    @staticmethod
    def generate_from_schema(schema: Dict[str, Any]) -> Any:
        schema_type = schema.get("type")
        if "example" in schema:
            return schema["example"]
        if "default" in schema:
            return schema["default"]
        if schema_type == "string":
            return MockResponseGenerator._generate_string(schema)
        if schema_type == "integer":
            return random.randint(0, 100)
        if schema_type == "number":
            return round(random.uniform(0, 100), 2)
        if schema_type == "boolean":
            return random.choice([True, False])
        if schema_type == "null":
            return None
        if schema_type == "array":
            items = schema.get("items", {})
            return [MockResponseGenerator.generate_from_schema(items) for _ in range(random.randint(1, 3))]
        if schema_type == "object":
            props = schema.get("properties", {})
            result: Dict[str, Any] = {}
            for prop_name, prop_schema in props.items():
                if prop_name in schema.get("required", []) or random.choice([True, False]):
                    result[prop_name] = MockResponseGenerator.generate_from_schema(prop_schema)
            return result
        return None

    @staticmethod
    def _generate_string(schema: Dict[str, Any]) -> str:
        if "enum" in schema:
            return random.choice(schema["enum"])
        if "format" in schema:
            fmt = schema["format"]
            if fmt == "date-time":
                return "2024-01-01T00:00:00Z"
            if fmt == "uuid":
                return "550e8400-e29b-41d4-a716-446655440000"
            if fmt == "email":
                return "user@example.com"
            if fmt == "uri":
                return "https://example.com"
            if fmt == "hostname":
                return "example.com"
        if "pattern" in schema:
            return f"string_matching_{schema['pattern']}"
        min_length = schema.get("minLength", 5)
        max_length = schema.get("maxLength", 20)
        length = random.randint(min_length, max_length)
        return "a" * length


class MockServer:
    def __init__(self, config: MockServerConfig, schema: SchemaDefinition) -> None:
        self.config = config
        self.schema = schema
        self._call_logs: List[APICallLog] = []
        self._latency_simulator = True

    def get_endpoint(self, path: str, method: str) -> Optional[MockEndpoint]:
        for endpoint in self.config.endpoints:
            if endpoint.path == path and endpoint.method.upper() == method.upper():
                return endpoint
        return self._generate_mock_endpoint(path, method)

    def _generate_mock_endpoint(self, path: str, method: str) -> Optional[MockEndpoint]:
        if self.schema.type != SchemaType.OPENAPI:
            return None
        paths = self.schema.content.get("paths", {})
        path_schema = paths.get(path)
        if not path_schema:
            return None
        method_schema = path_schema.get(method.lower())
        if not method_schema:
            return None
        responses = method_schema.get("responses", {})
        success_codes = [k for k in responses.keys() if k.startswith("2")]
        status_code = int(success_codes[0]) if success_codes else 200
        response_def = responses.get(str(status_code), {})
        content = response_def.get("content", {})
        response_schema = content.get("application/json", {}).get("schema")
        mock_response = MockResponseGenerator.generate_from_schema(response_schema) if response_schema else None
        return MockEndpoint(
            path=path,
            method=method,
            response_schema=response_schema,
            status_code=status_code,
            mock_response=mock_response,
        )

    async def handle_request(
        self,
        method: str,
        path: str,
        headers: Dict[str, str],
        query_string: str,
        body: Optional[Any] = None,
    ) -> Dict[str, Any]:
        start_time = time.time()
        endpoint = self.get_endpoint(path, method)

        query_params = parse_qs(query_string) if query_string else {}

        if endpoint and endpoint.delay_ms > 0:
            await asyncio.sleep(endpoint.delay_ms / 1000)

        if endpoint:
            status_code = endpoint.status_code
            response_body = endpoint.mock_response
            headers_out = endpoint.headers
        else:
            status_code = 404
            response_body = {"error": "Not Found"}
            headers_out = {}

        duration = (time.time() - start_time) * 1000

        self._call_logs.append(APICallLog(
            method=method,
            path=path,
            request_headers=headers,
            request_body=body,
            response_status=status_code,
            response_headers=headers_out,
            response_body=response_body,
        ))

        return {
            "status_code": status_code,
            "headers": headers_out,
            "body": response_body,
            "duration_ms": duration,
        }

    def get_call_logs(self, limit: int = 100) -> List[APICallLog]:
        return self._call_logs[-limit:]

    def clear_logs(self) -> None:
        self._call_logs.clear()


class MockServerManager:
    def __init__(self) -> None:
        self._servers: Dict[str, MockServer] = {}
        self._configs: Dict[str, MockServerConfig] = {}

    def create_server(
        self,
        schema: SchemaDefinition,
        name: Optional[str] = None,
    ) -> MockServerConfig:
        config = MockServerConfig(
            name=name or f"mock-{schema.name}",
            schema_id=schema.schema_id,
            endpoints=self._extract_endpoints(schema),
        )
        self._configs[config.server_id] = config
        self._servers[config.server_id] = MockServer(config, schema)
        logger.info(f"Created mock server: {config.name}")
        return config

    def _extract_endpoints(self, schema: SchemaDefinition) -> List[MockEndpoint]:
        endpoints: List[MockEndpoint] = []
        if schema.type != SchemaType.OPENAPI:
            return endpoints
        paths = schema.content.get("paths", {})
        for path, path_schema in paths.items():
            for method, method_schema in path_schema.items():
                if method.upper() in ["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"]:
                    responses = method_schema.get("responses", {})
                    success_codes = [k for k in responses.keys() if k.startswith("2")]
                    status_code = int(success_codes[0]) if success_codes else 200
                    response_def = responses.get(str(status_code), {})
                    content = response_def.get("content", {})
                    response_schema = content.get("application/json", {}).get("schema")
                    mock_response = MockResponseGenerator.generate_from_schema(response_schema) if response_schema else None
                    endpoints.append(MockEndpoint(
                        path=path,
                        method=method.upper(),
                        response_schema=response_schema,
                        status_code=status_code,
                        mock_response=mock_response,
                    ))
        return endpoints

    def get_server(self, server_id: str) -> Optional[MockServer]:
        return self._servers.get(server_id)

    def get_config(self, server_id: str) -> Optional[MockServerConfig]:
        return self._configs.get(server_id)

    def list_servers(self) -> List[MockServerConfig]:
        return list(self._configs.values())

    def delete_server(self, server_id: str) -> bool:
        if server_id in self._servers:
            del self._servers[server_id]
            del self._configs[server_id]
            return True
        return False

    def update_endpoint(
        self,
        server_id: str,
        endpoint_id: str,
        updates: Dict[str, Any],
    ) -> Optional[MockEndpoint]:
        server = self._servers.get(server_id)
        if not server:
            return None
        config = self._configs[server_id]
        for i, ep in enumerate(config.endpoints):
            if ep.endpoint_id == endpoint_id:
                for key, value in updates.items():
                    if hasattr(ep, key):
                        setattr(ep, key, value)
                config.endpoints[i] = ep
                return ep
        return None
