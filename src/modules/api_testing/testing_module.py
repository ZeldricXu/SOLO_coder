"""
API契约测试实现
核心功能：
1. OpenAPI/GraphQL Schema校验
2. Mock Server自动生成
"""

from __future__ import annotations

import json
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional
from urllib.parse import urlparse

from src.core import LoggerProtocol


@dataclass
class ValidationError:
    path: str
    message: str
    severity: str = "error"
    line: Optional[int] = None


@dataclass
class ValidationResult:
    valid: bool
    errors: List[ValidationError] = field(default_factory=list)
    warnings: List[ValidationError] = field(default_factory=list)

    def add_error(self, path: str, message: str, severity: str = "error") -> None:
        self.errors.append(ValidationError(path=path, message=message, severity=severity))
        self.valid = False

    def add_warning(self, path: str, message: str) -> None:
        self.warnings.append(ValidationError(path=path, message=message, severity="warning"))


@dataclass
class MockEndpoint:
    path: str
    method: str
    response_status: int = 200
    response_body: Any = None
    response_headers: Dict[str, str] = field(default_factory=dict)
    delay_ms: int = 0


class SchemaValidator(ABC):
    """Schema校验器抽象基类"""

    @abstractmethod
    def validate(self, schema: Dict[str, Any]) -> ValidationResult: ...

    @abstractmethod
    def validate_request(
        self,
        schema: Dict[str, Any],
        path: str,
        method: str,
        request_data: Optional[Dict[str, Any]] = None,
    ) -> ValidationResult: ...

    @abstractmethod
    def validate_response(
        self,
        schema: Dict[str, Any],
        path: str,
        method: str,
        response_data: Any,
        status_code: int = 200,
    ) -> ValidationResult: ...

    @abstractmethod
    def generate_mock_endpoints(
        self, schema: Dict[str, Any]
    ) -> List[MockEndpoint]: ...


class OpenAPIValidator(SchemaValidator):
    """OpenAPI 3.0 Schema校验器"""

    def __init__(self, logger: Optional[LoggerProtocol] = None) -> None:
        self._logger = logger

    def _get_path_item(
        self, schema: Dict[str, Any], path: str
    ) -> Optional[Dict[str, Any]]:
        paths = schema.get("paths", {})
        for pattern, item in paths.items():
            if self._match_path(pattern, path):
                return item
        return None

    def _match_path(self, pattern: str, actual_path: str) -> bool:
        pattern_parts = pattern.rstrip("/").split("/")
        actual_parts = actual_path.rstrip("/").split("/")

        if len(pattern_parts) != len(actual_parts):
            return False

        for p, a in zip(pattern_parts, actual_parts):
            if p.startswith("{") and p.endswith("}"):
                continue
            if p != a:
                return False

        return True

    def validate(self, schema: Dict[str, Any]) -> ValidationResult:
        result = ValidationResult(valid=True)

        if "openapi" not in schema:
            result.add_error("", "Missing 'openapi' field")

        if "info" not in schema:
            result.add_error("", "Missing 'info' field")
        else:
            info = schema["info"]
            if "title" not in info:
                result.add_error("info", "Missing 'title' field")
            if "version" not in info:
                result.add_error("info", "Missing 'version' field")

        if "paths" not in schema:
            result.add_error("", "Missing 'paths' field")
        else:
            for path, path_item in schema["paths"].items():
                for method in ["get", "post", "put", "delete", "patch"]:
                    if method in path_item:
                        operation = path_item[method]
                        if "responses" not in operation:
                            result.add_error(
                                f"paths.{path}.{method}",
                                f"Missing 'responses' for {method.upper()} {path}",
                            )

                        if "parameters" in operation:
                            for i, param in enumerate(operation["parameters"]):
                                if "name" not in param:
                                    result.add_error(
                                        f"paths.{path}.{method}.parameters[{i}]",
                                        "Missing 'name' field",
                                    )
                                if "in" not in param:
                                    result.add_error(
                                        f"paths.{path}.{method}.parameters[{i}]",
                                        "Missing 'in' field",
                                    )

        if self._logger and not result.valid:
            self._logger.warning(
                "OpenAPI schema validation failed",
                errors_count=len(result.errors),
            )

        return result

    def validate_request(
        self,
        schema: Dict[str, Any],
        path: str,
        method: str,
        request_data: Optional[Dict[str, Any]] = None,
    ) -> ValidationResult:
        result = ValidationResult(valid=True)

        path_item = self._get_path_item(schema, path)
        if not path_item:
            result.add_error(path, f"Path not found in schema: {path}")
            return result

        method_lower = method.lower()
        if method_lower not in path_item:
            result.add_error(
                path,
                f"Method {method.upper()} not defined for path {path}",
            )
            return result

        operation = path_item[method_lower]

        if "parameters" in operation and request_data:
            for param in operation["parameters"]:
                param_name = param.get("name", "")
                param_in = param.get("in", "")
                required = param.get("required", False)

                if required and param_in == "query":
                    if param_name not in request_data.get("query", {}):
                        result.add_error(
                            f"{path}.query.{param_name}",
                            f"Required query parameter missing: {param_name}",
                        )

        if "requestBody" in operation and request_data:
            content = operation["requestBody"].get("content", {})
            required = operation["requestBody"].get("required", False)

            if required and "body" not in request_data:
                result.add_error(
                    f"{path}.body",
                    "Required request body missing",
                )

        return result

    def validate_response(
        self,
        schema: Dict[str, Any],
        path: str,
        method: str,
        response_data: Any,
        status_code: int = 200,
    ) -> ValidationResult:
        result = ValidationResult(valid=True)

        path_item = self._get_path_item(schema, path)
        if not path_item:
            return result

        operation = path_item.get(method.lower(), {})
        responses = operation.get("responses", {})

        status_str = str(status_code)
        if status_str not in responses and "default" not in responses:
            result.add_warning(
                f"{path}.{method}.{status_str}",
                f"Response {status_code} not defined in schema",
            )

        return result

    def generate_mock_endpoints(
        self, schema: Dict[str, Any]
    ) -> List[MockEndpoint]:
        endpoints = []
        paths = schema.get("paths", {})

        for path, path_item in paths.items():
            for method in ["get", "post", "put", "delete", "patch"]:
                if method in path_item:
                    operation = path_item[method]
                    responses = operation.get("responses", {})

                    success_response = None
                    for code in ["200", "201", "default"]:
                        if code in responses:
                            success_response = responses[code]
                            break

                    mock_body = self._generate_mock_body(success_response) if success_response else {}

                    endpoint = MockEndpoint(
                        path=self._convert_path_pattern(path),
                        method=method.upper(),
                        response_status=int(
                            next((c for c in ["200", "201"] if c in responses), "200")
                        ),
                        response_body=mock_body,
                        response_headers={"Content-Type": "application/json"},
                    )
                    endpoints.append(endpoint)

        return endpoints

    def _convert_path_pattern(self, path: str) -> str:
        return re.sub(r"\{([^}]+)\}", r":\1", path)

    def _generate_mock_body(self, response_schema: Dict[str, Any]) -> Any:
        content = response_schema.get("content", {})
        json_content = content.get("application/json", {})
        schema_body = json_content.get("schema", {})

        return self._generate_example_from_schema(schema_body)

    def _generate_example_from_schema(self, schema: Dict[str, Any]) -> Any:
        schema_type = schema.get("type", "object")

        if schema.get("example") is not None:
            return schema["example"]

        if schema_type == "object":
            result = {}
            properties = schema.get("properties", {})
            for prop_name, prop_schema in properties.items():
                result[prop_name] = self._generate_example_from_schema(prop_schema)
            return result

        if schema_type == "array":
            items = schema.get("items", {})
            return [self._generate_example_from_schema(items)]

        if schema_type == "string":
            enum = schema.get("enum")
            if enum:
                return enum[0]
            format_type = schema.get("format")
            if format_type == "uuid":
                return "123e4567-e89b-12d3-a456-426614174000"
            if format_type == "date-time":
                return "2024-01-01T00:00:00Z"
            return "string"

        if schema_type == "integer":
            return 0

        if schema_type == "number":
            return 0.0

        if schema_type == "boolean":
            return True

        return None


class GraphQLValidator(SchemaValidator):
    """GraphQL Schema校验器"""

    def __init__(self, logger: Optional[LoggerProtocol] = None) -> None:
        self._logger = logger

    def validate(self, schema: Dict[str, Any]) -> ValidationResult:
        result = ValidationResult(valid=True)

        schema_str = schema.get("schema", "")
        if not schema_str:
            result.add_error("", "Missing GraphQL schema string")

        if "query" not in schema_str and "type Query" not in schema_str:
            result.add_warning("", "No Query type defined")

        return result

    def validate_request(
        self,
        schema: Dict[str, Any],
        path: str,
        method: str,
        request_data: Optional[Dict[str, Any]] = None,
    ) -> ValidationResult:
        result = ValidationResult(valid=True)

        if request_data and "query" not in request_data:
            result.add_error("body", "Missing 'query' field in request")

        return result

    def validate_response(
        self,
        schema: Dict[str, Any],
        path: str,
        method: str,
        response_data: Any,
        status_code: int = 200,
    ) -> ValidationResult:
        result = ValidationResult(valid=True)

        if status_code != 200:
            result.add_warning(
                f"{path}.{status_code}",
                f"Unexpected status code: {status_code}",
            )

        if isinstance(response_data, dict) and "errors" in response_data:
            for error in response_data["errors"]:
                result.add_error(
                    path,
                    f"GraphQL error: {error.get('message', 'Unknown error')}",
                )

        return result

    def generate_mock_endpoints(
        self, schema: Dict[str, Any]
    ) -> List[MockEndpoint]:
        endpoints = []

        schema_str = schema.get("schema", "")
        query_types = re.findall(r"type\s+Query\s*\{([^}]+)\}", schema_str)

        if query_types:
            fields = re.findall(r"(\w+)\s*\(.*?\)\s*:\s*(\w+)", query_types[0])
            for field_name, return_type in fields:
                mock_body = {
                    "data": {
                        field_name: self._generate_mock_value(return_type)
                    }
                }
                endpoint = MockEndpoint(
                    path="/graphql",
                    method="POST",
                    response_status=200,
                    response_body=mock_body,
                    response_headers={"Content-Type": "application/json"},
                )
                endpoints.append(endpoint)

        return endpoints

    def _generate_mock_value(self, type_name: str) -> Any:
        type_map = {
            "String": "string",
            "Int": 0,
            "Float": 0.0,
            "Boolean": True,
            "ID": "123",
        }
        return type_map.get(type_name, {})


class MockServer:
    """Mock Server - 根据Schema自动生成Mock响应"""

    def __init__(self, endpoints: Optional[List[MockEndpoint]] = None) -> None:
        self._endpoints: Dict[tuple[str, str], MockEndpoint] = {}
        for endpoint in endpoints or []:
            self.add_endpoint(endpoint)

    def add_endpoint(self, endpoint: MockEndpoint) -> None:
        key = (endpoint.method.upper(), endpoint.path)
        self._endpoints[key] = endpoint

    def get_endpoint(self, method: str, path: str) -> Optional[MockEndpoint]:
        key = (method.upper(), path)
        if key in self._endpoints:
            return self._endpoints[key]

        for (ep_method, ep_path), endpoint in self._endpoints.items():
            if ep_method == method.upper() and self._match_path(ep_path, path):
                return endpoint

        return None

    def _match_path(self, pattern: str, actual_path: str) -> bool:
        pattern_parts = pattern.rstrip("/").split("/")
        actual_parts = actual_path.rstrip("/").split("/")

        if len(pattern_parts) != len(actual_parts):
            return False

        for p, a in zip(pattern_parts, actual_parts):
            if p.startswith(":"):
                continue
            if p != a:
                return False

        return True

    def handle_request(self, method: str, path: str) -> tuple[int, Any, Dict[str, str]]:
        endpoint = self.get_endpoint(method, path)
        if endpoint:
            import time
            if endpoint.delay_ms > 0:
                time.sleep(endpoint.delay_ms / 1000)
            return (
                endpoint.response_status,
                endpoint.response_body,
                endpoint.response_headers,
            )
        return 404, {"error": "Not found"}, {"Content-Type": "application/json"}


class ApiContractTester:
    """
    API契约测试 - 核心类
    整合Schema校验和Mock Server
    """

    def __init__(
        self,
        logger: Optional[LoggerProtocol] = None,
    ) -> None:
        self._logger = logger
        self._validators: Dict[str, SchemaValidator] = {
            "openapi": OpenAPIValidator(logger),
            "graphql": GraphQLValidator(logger),
        }
        self._mock_servers: Dict[str, MockServer] = {}
        self._schemas: Dict[str, Dict[str, Any]] = {}

    def register_schema(
        self,
        name: str,
        schema: Dict[str, Any],
        schema_type: str = "openapi",
    ) -> ValidationResult:
        validator = self._validators.get(schema_type)
        if not validator:
            result = ValidationResult(valid=False)
            result.add_error("", f"Unsupported schema type: {schema_type}")
            return result

        validation_result = validator.validate(schema)
        if validation_result.valid:
            self._schemas[name] = {
                "schema": schema,
                "type": schema_type,
            }

            mock_endpoints = validator.generate_mock_endpoints(schema)
            mock_server = MockServer(mock_endpoints)
            self._mock_servers[name] = mock_server

            if self._logger:
                self._logger.info(
                    "Schema registered successfully",
                    name=name,
                    type=schema_type,
                    endpoints_count=len(mock_endpoints),
                )

        return validation_result

    def validate_request(
        self,
        schema_name: str,
        path: str,
        method: str,
        request_data: Optional[Dict[str, Any]] = None,
    ) -> ValidationResult:
        schema_info = self._schemas.get(schema_name)
        if not schema_info:
            result = ValidationResult(valid=False)
            result.add_error("", f"Schema not found: {schema_name}")
            return result

        validator = self._validators[schema_info["type"]]
        return validator.validate_request(
            schema_info["schema"], path, method, request_data
        )

    def validate_response(
        self,
        schema_name: str,
        path: str,
        method: str,
        response_data: Any,
        status_code: int = 200,
    ) -> ValidationResult:
        schema_info = self._schemas.get(schema_name)
        if not schema_info:
            result = ValidationResult(valid=False)
            result.add_error("", f"Schema not found: {schema_name}")
            return result

        validator = self._validators[schema_info["type"]]
        return validator.validate_response(
            schema_info["schema"], path, method, response_data, status_code
        )

    def get_mock_server(self, schema_name: str) -> Optional[MockServer]:
        return self._mock_servers.get(schema_name)

    def mock_request(
        self,
        schema_name: str,
        method: str,
        path: str,
    ) -> tuple[int, Any, Dict[str, str]]:
        mock_server = self._mock_servers.get(schema_name)
        if not mock_server:
            return 404, {"error": "Schema not found"}, {}
        return mock_server.handle_request(method, path)
