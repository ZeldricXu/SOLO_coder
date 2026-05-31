import asyncio
import json
import random
import re
import threading
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from http import HTTPStatus
from typing import Any, AsyncIterator, Callable, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, urlparse

import yaml
from jsonschema import Draft202012Validator, SchemaError, ValidationError


class ValidationSeverity(str, Enum):
    ERROR = "error"
    WARNING = "warning"
    INFO = "info"


@dataclass
class ValidationError:
    message: str
    path: str = ""
    severity: ValidationSeverity = ValidationSeverity.ERROR


@dataclass
class ValidationResult:
    valid: bool
    errors: List[ValidationError] = field(default_factory=list)
    warnings: List[ValidationError] = field(default_factory=list)

    def add_error(self, message: str, path: str = "") -> None:
        self.errors.append(ValidationError(message=message, path=path, severity=ValidationSeverity.ERROR))

    def add_warning(self, message: str, path: str = "") -> None:
        self.warnings.append(ValidationError(message=message, path=path, severity=ValidationSeverity.WARNING))

    @property
    def has_errors(self) -> bool:
        return len(self.errors) > 0


@dataclass
class MockEndpoint:
    method: str
    path: str
    status_code: int = 200
    response_body: Any = None
    response_headers: Dict[str, str] = field(default_factory=dict)
    delay_ms: int = 0
    handler: Optional[Callable] = None
    example_index: int = 0


class SchemaValidator:
    def __init__(self):
        self._validators: Dict[str, Draft202012Validator] = {}
        self._lock = threading.Lock()

    def _load_schema(self, schema_data: Dict[str, Any]) -> Draft202012Validator:
        schema_key = json.dumps(schema_data, sort_keys=True)
        with self._lock:
            if schema_key in self._validators:
                return self._validators[schema_key]
            validator = Draft202012Validator(schema_data)
            self._validators[schema_key] = validator
            return validator

    def validate_against_schema(
        self,
        data: Any,
        schema: Dict[str, Any]
    ) -> ValidationResult:
        result = ValidationResult(valid=True)
        try:
            validator = self._load_schema(schema)
            for error in validator.iter_errors(data):
                path = ".".join(str(p) for p in error.path) if error.path else ""
                result.add_error(error.message, path)
        except SchemaError as e:
            result.add_error(f"Invalid schema: {e}")
        result.valid = not result.has_errors
        return result

    def validate_openapi_schema(
        self,
        data: Any,
        schema_ref: str,
        openapi_doc: Dict[str, Any]
    ) -> ValidationResult:
        result = ValidationResult(valid=True)
        schema = self._resolve_schema_ref(schema_ref, openapi_doc)
        if schema is None:
            result.add_error(f"Schema reference not found: {schema_ref}")
            result.valid = False
            return result
        return self.validate_against_schema(data, schema)

    def _resolve_schema_ref(
        self,
        ref: str,
        openapi_doc: Dict[str, Any]
    ) -> Optional[Dict[str, Any]]:
        if not ref.startswith("#/"):
            return None
        parts = ref[2:].split("/")
        current: Any = openapi_doc
        for part in parts:
            if isinstance(current, dict) and part in current:
                current = current[part]
            else:
                return None
        return current

    def validate_openapi_document(self, openapi_doc: Dict[str, Any]) -> ValidationResult:
        result = ValidationResult(valid=True)
        if "openapi" not in openapi_doc:
            result.add_error("Missing 'openapi' field")
        if "info" not in openapi_doc:
            result.add_error("Missing 'info' field")
        else:
            if "title" not in openapi_doc["info"]:
                result.add_error("Missing 'info.title' field")
            if "version" not in openapi_doc["info"]:
                result.add_error("Missing 'info.version' field")
        if "paths" not in openapi_doc:
            result.add_warning("No 'paths' defined in OpenAPI document")

        paths = openapi_doc.get("paths", {})
        for path, path_item in paths.items():
            if not path.startswith("/"):
                result.add_warning(f"Path '{path}' does not start with '/'", path=path)
            for method in ["get", "post", "put", "delete", "patch", "options", "head", "trace"]:
                operation = path_item.get(method)
                if operation and "responses" not in operation:
                    result.add_warning(
                        f"Operation {method.upper()} {path} has no responses defined",
                        path=f"{path}.{method}"
                    )
        result.valid = not result.has_errors
        return result

    def validate_graphql_schema(self, schema_str: str) -> ValidationResult:
        result = ValidationResult(valid=True)
        if not schema_str or not schema_str.strip():
            result.add_error("Empty GraphQL schema")
            result.valid = False
            return result

        required_types = ["Query"]
        for req_type in required_types:
            if f"type {req_type}" not in schema_str and f"type {req_type} " not in schema_str:
                result.add_warning(f"Missing {req_type} type definition")

        bracket_stack: List[str] = []
        for i, char in enumerate(schema_str):
            if char in "{[(":
                bracket_stack.append(char)
            elif char in "}])":
                if not bracket_stack:
                    result.add_error(f"Unmatched closing bracket '{char}' at position {i}")
                else:
                    opening = bracket_stack.pop()
                    if (opening == "{" and char != "}") or \
                       (opening == "[" and char != "]") or \
                       (opening == "(" and char != ")"):
                        result.add_error(f"Mismatched bracket '{opening}' and '{char}' at position {i}")
        if bracket_stack:
            result.add_error(f"Unclosed brackets: {bracket_stack}")

        type_matches = re.findall(r'type\s+(\w+)', schema_str)
        for type_name in type_matches:
            if type_name[0].isupper() and not type_name.isupper():
                pass
            else:
                result.add_warning(f"Type name '{type_name}' should be PascalCase")

        result.valid = not result.has_errors
        return result


class MockServer:
    def __init__(self):
        self._endpoints: Dict[str, MockEndpoint] = {}
        self._lock = threading.Lock()
        self._request_log: List[Dict[str, Any]] = []
        self._enabled = True

    def _make_key(self, method: str, path: str) -> str:
        return f"{method.upper()}:{path}"

    def register_endpoint(
        self,
        method: str,
        path: str,
        status_code: int = 200,
        response_body: Any = None,
        response_headers: Optional[Dict[str, str]] = None,
        delay_ms: int = 0,
        handler: Optional[Callable] = None
    ) -> MockEndpoint:
        endpoint = MockEndpoint(
            method=method.upper(),
            path=path,
            status_code=status_code,
            response_body=response_body,
            response_headers=response_headers or {},
            delay_ms=delay_ms,
            handler=handler
        )
        with self._lock:
            self._endpoints[self._make_key(method, path)] = endpoint
        return endpoint

    def register_from_openapi(self, openapi_doc: Dict[str, Any], base_path: str = "") -> int:
        count = 0
        paths = openapi_doc.get("paths", {})
        for path, path_item in paths.items():
            full_path = base_path + path
            for method in ["get", "post", "put", "delete", "patch", "options"]:
                operation = path_item.get(method)
                if not operation:
                    continue
                responses = operation.get("responses", {})
                success_status = "200"
                for status in ["200", "201", "204"]:
                    if status in responses:
                        success_status = status
                        break

                response = responses.get(success_status, {})
                content = response.get("content", {})
                json_content = content.get("application/json", {})
                schema = json_content.get("schema") or response.get("schema")

                example = None
                examples = json_content.get("examples")
                if examples:
                    first_example = next(iter(examples.values()), None)
                    if first_example:
                        example = first_example.get("value")

                if example is None and schema:
                    example = self._generate_example_from_schema(schema, openapi_doc)

                self.register_endpoint(
                    method=method,
                    path=full_path,
                    status_code=int(success_status),
                    response_body=example or {"message": "Mock response"}
                )
                count += 1
        return count

    def _generate_example_from_schema(
        self,
        schema: Dict[str, Any],
        openapi_doc: Dict[str, Any]
    ) -> Any:
        if "$ref" in schema:
            ref = schema["$ref"]
            parts = ref[2:].split("/")
            current: Any = openapi_doc
            for part in parts:
                if isinstance(current, dict) and part in current:
                    current = current[part]
                else:
                    return None
            return self._generate_example_from_schema(current, openapi_doc)

        schema_type = schema.get("type", "object")
        if schema_type == "object":
            result = {}
            properties = schema.get("properties", {})
            for prop_name, prop_schema in properties.items():
                result[prop_name] = self._generate_example_from_schema(prop_schema, openapi_doc)
            return result
        elif schema_type == "array":
            items = schema.get("items", {})
            return [self._generate_example_from_schema(items, openapi_doc)]
        elif schema_type == "string":
            enum_values = schema.get("enum")
            if enum_values:
                return enum_values[0]
            format_type = schema.get("format")
            if format_type == "uuid":
                return str(uuid.uuid4())
            if format_type == "date-time":
                return datetime.utcnow().isoformat() + "Z"
            if format_type == "date":
                return datetime.utcnow().date().isoformat()
            return "string"
        elif schema_type == "integer":
            return random.randint(1, 100)
        elif schema_type == "number":
            return round(random.uniform(0, 100), 2)
        elif schema_type == "boolean":
            return True
        elif schema_type == "null":
            return None
        return None

    def get_endpoint(self, method: str, path: str) -> Optional[MockEndpoint]:
        with self._lock:
            key = self._make_key(method, path)
            if key in self._endpoints:
                return self._endpoints[key]
            for ep_key, endpoint in self._endpoints.items():
                if self._path_matches(endpoint.path, path):
                    return endpoint
        return None

    def _path_matches(self, pattern: str, actual: str) -> bool:
        pattern_parts = pattern.split("/")
        actual_parts = actual.split("/")
        if len(pattern_parts) != len(actual_parts):
            return False
        for p_part, a_part in zip(pattern_parts, actual_parts):
            if p_part.startswith("{") and p_part.endswith("}"):
                continue
            if p_part != a_part:
                return False
        return True

    async def handle_request(
        self,
        method: str,
        path: str,
        headers: Optional[Dict[str, str]] = None,
        body: Any = None
    ) -> Tuple[int, Dict[str, str], Any]:
        endpoint = self.get_endpoint(method, path)
        if not endpoint:
            return 404, {}, {"error": "Not Found", "message": f"No mock endpoint for {method} {path}"}

        self._request_log.append({
            "method": method,
            "path": path,
            "headers": headers or {},
            "body": body,
            "timestamp": datetime.utcnow().isoformat()
        })

        if endpoint.delay_ms > 0:
            await asyncio.sleep(endpoint.delay_ms / 1000.0)

        if endpoint.handler:
            try:
                result = endpoint.handler(headers, body)
                if asyncio.iscoroutine(result):
                    result = await result
                if isinstance(result, tuple) and len(result) >= 2:
                    status_code = result[0]
                    resp_body = result[1]
                    resp_headers = result[2] if len(result) > 2 else {}
                else:
                    status_code = endpoint.status_code
                    resp_body = result
                    resp_headers = endpoint.response_headers
                return status_code, resp_headers, resp_body
            except Exception as e:
                return 500, {}, {"error": "Mock Handler Error", "message": str(e)}

        return endpoint.status_code, endpoint.response_headers, endpoint.response_body

    def get_request_log(self, limit: int = 100) -> List[Dict[str, Any]]:
        with self._lock:
            return list(self._request_log[-limit:])

    def clear_request_log(self) -> None:
        with self._lock:
            self._request_log.clear()

    def list_endpoints(self) -> List[Dict[str, Any]]:
        with self._lock:
            return [
                {
                    "method": ep.method,
                    "path": ep.path,
                    "status_code": ep.status_code
                }
                for ep in self._endpoints.values()
            ]

    def clear_endpoints(self) -> None:
        with self._lock:
            self._endpoints.clear()


class ContractTester:
    def __init__(self):
        self.validator = SchemaValidator()
        self.mock_server = MockServer()

    def validate_request(
        self,
        method: str,
        path: str,
        body: Any,
        openapi_doc: Dict[str, Any]
    ) -> ValidationResult:
        result = ValidationResult(valid=True)
        paths = openapi_doc.get("paths", {})
        path_item = paths.get(path)

        if not path_item:
            for pattern, item in paths.items():
                if self._path_matches(pattern, path):
                    path_item = item
                    break

        if not path_item:
            result.add_error(f"Path '{path}' not found in OpenAPI spec")
            result.valid = False
            return result

        operation = path_item.get(method.lower())
        if not operation:
            result.add_error(f"Method '{method}' not defined for path '{path}'")
            result.valid = False
            return result

        request_body = operation.get("requestBody", {})
        if request_body:
            content = request_body.get("content", {})
            json_content = content.get("application/json", {})
            schema = json_content.get("schema")
            if schema and body is not None:
                schema_result = self.validator.validate_against_schema(body, schema)
                result.errors.extend(schema_result.errors)
                result.warnings.extend(schema_result.warnings)

        result.valid = not result.has_errors
        return result

    def validate_response(
        self,
        status_code: int,
        body: Any,
        method: str,
        path: str,
        openapi_doc: Dict[str, Any]
    ) -> ValidationResult:
        result = ValidationResult(valid=True)
        paths = openapi_doc.get("paths", {})
        path_item = paths.get(path)

        if not path_item:
            for pattern, item in paths.items():
                if self._path_matches(pattern, path):
                    path_item = item
                    break

        if not path_item:
            result.add_warning(f"Path '{path}' not found in spec, skipping response validation")
            return result

        operation = path_item.get(method.lower(), {})
        responses = operation.get("responses", {})

        status_str = str(status_code)
        if status_str not in responses and "default" not in responses:
            result.add_warning(f"Status code {status_code} not defined in responses")
            return result

        response = responses.get(status_str) or responses.get("default", {})
        content = response.get("content", {})
        json_content = content.get("application/json", {})
        schema = json_content.get("schema")

        if schema and body is not None:
            schema_result = self.validator.validate_against_schema(body, schema)
            result.errors.extend(schema_result.errors)
            result.warnings.extend(schema_result.warnings)

        result.valid = not result.has_errors
        return result

    @staticmethod
    def _path_matches(pattern: str, actual: str) -> bool:
        pattern_parts = pattern.split("/")
        actual_parts = actual.split("/")
        if len(pattern_parts) != len(actual_parts):
            return False
        for p_part, a_part in zip(pattern_parts, actual_parts):
            if p_part.startswith("{") and p_part.endswith("}"):
                continue
            if p_part != a_part:
                return False
        return True

    def run_contract_test(
        self,
        requests: List[Dict[str, Any]],
        openapi_doc: Dict[str, Any]
    ) -> List[Dict[str, Any]]:
        results = []
        for req in requests:
            method = req.get("method", "GET")
            path = req.get("path", "/")
            body = req.get("body")
            expected_status = req.get("expected_status", 200)

            req_validation = self.validate_request(method, path, body, openapi_doc)

            result = {
                "method": method,
                "path": path,
                "request_valid": req_validation.valid,
                "request_errors": [e.__dict__ for e in req_validation.errors],
                "request_warnings": [e.__dict__ for e in req_validation.warnings],
            }
            results.append(result)
        return results


_contract_tester_instance: Optional[ContractTester] = None
_contract_lock = threading.Lock()


def get_contract_tester() -> ContractTester:
    global _contract_tester_instance
    if _contract_tester_instance is None:
        with _contract_lock:
            if _contract_tester_instance is None:
                _contract_tester_instance = ContractTester()
    return _contract_tester_instance


def validate_openapi(schema: Dict[str, Any]) -> ValidationResult:
    tester = get_contract_tester()
    return tester.validator.validate_openapi_document(schema)


def validate_graphql(schema_str: str) -> ValidationResult:
    tester = get_contract_tester()
    return tester.validator.validate_graphql_schema(schema_str)


def create_mock_server() -> MockServer:
    return MockServer()
