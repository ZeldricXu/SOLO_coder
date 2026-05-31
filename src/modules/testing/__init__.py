"""
API契约测试模块
"""

from __future__ import annotations

import json
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from src.domain.contracts.tracing import LoggerProtocol


@dataclass
class ValidationError:
    path: str
    message: str
    severity: str = "error"
    line: Optional[int] = None


@dataclass
class ValidationResult:
    valid: bool = True
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


class OpenAPIValidator:
    def __init__(self, logger: Optional[LoggerProtocol] = None) -> None:
        self._logger = logger

    def validate(self, schema: Dict[str, Any]) -> ValidationResult:
        result = ValidationResult()
        if "openapi" not in schema:
            result.add_error("", "Missing 'openapi' field")
        if "info" not in schema:
            result.add_error("", "Missing 'info' field")
        else:
            if "title" not in schema["info"]:
                result.add_error("info", "Missing 'title'")
            if "version" not in schema["info"]:
                result.add_error("info", "Missing 'version'")
        if "paths" not in schema:
            result.add_error("", "Missing 'paths' field")
        return result

    def generate_mock_endpoints(self, schema: Dict[str, Any]) -> List[MockEndpoint]:
        endpoints = []
        for path, path_item in schema.get("paths", {}).items():
            for method in ["get", "post", "put", "delete", "patch"]:
                if method in path_item:
                    responses = path_item[method].get("responses", {})
                    status = int(next((c for c in ["200", "201"] if c in responses), "200"))
                    endpoints.append(MockEndpoint(
                        path=re.sub(r"\{([^}]+)\}", r":\1", path),
                        method=method.upper(),
                        response_status=status,
                        response_body={},
                        response_headers={"Content-Type": "application/json"},
                    ))
        return endpoints


class MockServer:
    def __init__(self, endpoints: Optional[List[MockEndpoint]] = None) -> None:
        self._endpoints: Dict[tuple, MockEndpoint] = {}
        for ep in endpoints or []:
            self._endpoints[(ep.method.upper(), ep.path)] = ep

    def handle_request(self, method: str, path: str) -> tuple:
        key = (method.upper(), path)
        if key in self._endpoints:
            ep = self._endpoints[key]
            return ep.response_status, ep.response_body, ep.response_headers
        return 404, {"error": "Not found"}, {"Content-Type": "application/json"}


class ApiContractTester:
    def __init__(self, logger: Optional[LoggerProtocol] = None) -> None:
        self._logger = logger
        self._validators: Dict[str, OpenAPIValidator] = {"openapi": OpenAPIValidator(logger)}
        self._mock_servers: Dict[str, MockServer] = {}
        self._schemas: Dict[str, Dict[str, Any]] = {}

    def register_schema(self, name: str, schema: Dict[str, Any], schema_type: str = "openapi") -> ValidationResult:
        validator = self._validators.get(schema_type)
        if not validator:
            result = ValidationResult()
            result.add_error("", f"Unsupported schema type: {schema_type}")
            return result
        validation = validator.validate(schema)
        if validation.valid:
            self._schemas[name] = {"schema": schema, "type": schema_type}
            mock_endpoints = validator.generate_mock_endpoints(schema)
            self._mock_servers[name] = MockServer(mock_endpoints)
        return validation

    def mock_request(self, schema_name: str, method: str, path: str) -> tuple:
        server = self._mock_servers.get(schema_name)
        if not server:
            return 404, {"error": "Schema not found"}, {}
        return server.handle_request(method, path)
