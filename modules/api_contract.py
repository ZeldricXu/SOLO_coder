import json
import re
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from enum import Enum
from .logging_module import get_logger

logger = get_logger(__name__)


class SchemaType(str, Enum):
    OPENAPI = "openapi"
    GRAPHQL = "graphql"


class ValidationErrorType(str, Enum):
    MISSING_FIELD = "missing_field"
    TYPE_MISMATCH = "type_mismatch"
    INVALID_ENUM = "invalid_enum"
    INVALID_FORMAT = "invalid_format"
    UNKNOWN_FIELD = "unknown_field"


@dataclass
class ValidationIssue:
    path: str
    message: str
    error_type: ValidationErrorType


@dataclass
class ValidationResult:
    valid: bool
    issues: List[ValidationIssue] = field(default_factory=list)


class SchemaValidator:
    def __init__(self):
        self._type_mapping = {
            "string": str, "integer": int, "number": (int, float),
            "boolean": bool, "array": list, "object": dict,
        }

    def validate(self, data: Any, schema: Dict[str, Any]) -> ValidationResult:
        issues: List[ValidationIssue] = []
        self._validate(data, schema, "", issues)
        return ValidationResult(valid=len(issues) == 0, issues=issues)

    def _validate(self, data: Any, schema: Dict[str, Any], path: str, issues: List[ValidationIssue]) -> None:
        if not isinstance(schema, dict):
            return

        expected_type = schema.get("type")
        if expected_type and not self._check_type(data, expected_type):
            issues.append(ValidationIssue(path or "root",
                f"Expected type {expected_type}, got {type(data).__name__}",
                ValidationErrorType.TYPE_MISMATCH))
            return

        if expected_type == "object" and isinstance(data, dict):
            self._validate_object(data, schema, path, issues)
        elif expected_type == "array" and isinstance(data, list):
            self._validate_array(data, schema, path, issues)
        elif schema.get("enum"):
            self._validate_enum(data, schema, path, issues)

    def _check_type(self, value: Any, expected: str) -> bool:
        if value is None:
            return True
        py_type = self._type_mapping.get(expected)
        return py_type is None or isinstance(value, py_type)

    def _validate_object(self, data: Dict, schema: Dict, path: str, issues: List) -> None:
        properties = schema.get("properties", {})
        for prop in schema.get("required", []):
            if prop not in data:
                issues.append(ValidationIssue(f"{path}.{prop}" if path else prop,
                    f"Missing required field: {prop}", ValidationErrorType.MISSING_FIELD))
        for key, value in data.items():
            if key in properties:
                self._validate(value, properties[key], f"{path}.{key}" if path else key, issues)

    def _validate_array(self, data: List, schema: Dict, path: str, issues: List) -> None:
        items = schema.get("items", {})
        for i, item in enumerate(data):
            self._validate(item, items, f"{path}[{i}]", issues)

    def _validate_enum(self, data: Any, schema: Dict, path: str, issues: List) -> None:
        if data not in schema.get("enum", []):
            issues.append(ValidationIssue(path,
                f"Value must be one of {schema['enum']}", ValidationErrorType.INVALID_ENUM))


class MockGenerator:
    def generate(self, schema: Dict[str, Any]) -> Any:
        if schema.get("example") is not None:
            return schema["example"]
        if schema.get("enum"):
            return schema["enum"][0]

        node_type = schema.get("type")
        if node_type == "object":
            return {k: self.generate(v) for k, v in schema.get("properties", {}).items()}
        elif node_type == "array":
            return [self.generate(schema.get("items", {}))]
        elif node_type == "string":
            return schema.get("default", "mock_string")
        elif node_type == "integer":
            return 42
        elif node_type == "number":
            return 3.14
        elif node_type == "boolean":
            return True
        return None


class OpenAPIMockServer:
    def __init__(self, spec: Dict[str, Any]):
        self.spec = spec
        self.validator = SchemaValidator()
        self.mock_gen = MockGenerator()
        self._logs: List[Dict] = []

    def handle(self, method: str, path: str, body: Optional[Dict] = None) -> Dict[str, Any]:
        self._logs.append({"method": method, "path": path, "body": body})
        path_item = self._find_path(path)
        if not path_item:
            return {"status": 404, "body": {"error": "Not found"}}
        method_spec = path_item.get(method.lower())
        if not method_spec:
            return {"status": 405, "body": {"error": "Method not allowed"}}

        responses = method_spec.get("responses", {})
        success = responses.get("200", {})
        content = success.get("content", {}).get("application/json", {})
        schema = content.get("schema", {})
        return {"status": 200, "body": self.mock_gen.generate(schema)}

    def _find_path(self, path: str) -> Optional[Dict]:
        paths = self.spec.get("paths", {})
        if path in paths:
            return paths[path]
        for template, item in paths.items():
            if self._match_template(template, path):
                return item
        return None

    def _match_template(self, template: str, actual: str) -> bool:
        t_parts = template.split("/")
        a_parts = actual.split("/")
        if len(t_parts) != len(a_parts):
            return False
        for t, a in zip(t_parts, a_parts):
            if not (t.startswith("{") and t.endswith("}")) and t != a:
                return False
        return True


class ContractTestingService:
    def __init__(self):
        self._schemas: Dict[str, Dict] = {}
        self._mock_servers: Dict[str, Any] = {}
        self.validator = SchemaValidator()
        self.mock_gen = MockGenerator()

    def register_schema(self, name: str, schema_type: SchemaType, schema: Dict) -> None:
        self._schemas[name] = {"type": schema_type, "schema": schema}
        if schema_type == SchemaType.OPENAPI:
            self._mock_servers[name] = OpenAPIMockServer(schema)
        logger.info(f"Registered {schema_type} schema: {name}")

    def validate(self, data: Dict, schema: Dict) -> ValidationResult:
        return self.validator.validate(data, schema)

    def mock(self, schema_name: str, method: str, path: str, body: Optional[Dict] = None) -> Dict:
        server = self._mock_servers.get(schema_name)
        if not server:
            return {"status": 404, "body": {"error": "Schema not found"}}
        return server.handle(method, path, body)

    def generate_mock(self, schema: Dict) -> Any:
        return self.mock_gen.generate(schema)


def get_contract_testing_service() -> ContractTestingService:
    return ContractTestingService()
