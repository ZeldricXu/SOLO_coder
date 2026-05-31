from __future__ import annotations

import json
import logging
import time
from typing import Any, Dict, List, Optional

from src.common.exceptions import ValidationError as InfrastructureValidationError
from src.contract_testing.models import (
    SchemaDefinition,
    SchemaType,
    ValidationError,
    ValidationResult,
    ValidationSeverity,
)

logger = logging.getLogger(__name__)


class OpenAPIValidator:
    def __init__(self) -> None:
        pass

    def validate_request(
        self,
        schema: SchemaDefinition,
        path: str,
        method: str,
        body: Optional[Any] = None,
        headers: Optional[Dict[str, str]] = None,
        query_params: Optional[Dict[str, Any]] = None,
    ) -> ValidationResult:
        start_time = time.time()
        errors: List[ValidationError] = []
        warnings: List[ValidationError] = []

        try:
            paths = schema.content.get("paths", {})
            path_schema = paths.get(path)
            if not path_schema:
                errors.append(ValidationError(
                    severity=ValidationSeverity.ERROR,
                    path=path,
                    message=f"Path '{path}' not found in schema",
                ))
                return ValidationResult(
                    valid=False,
                    errors=errors,
                    warnings=warnings,
                    duration_ms=(time.time() - start_time) * 1000,
                )

            method_schema = path_schema.get(method.lower())
            if not method_schema:
                errors.append(ValidationError(
                    severity=ValidationSeverity.ERROR,
                    path=path,
                    message=f"Method '{method}' not found for path '{path}'",
                ))
                return ValidationResult(
                    valid=False,
                    errors=errors,
                    warnings=warnings,
                    duration_ms=(time.time() - start_time) * 1000,
                )

            request_body = method_schema.get("requestBody")
            if request_body and body is not None:
                content = request_body.get("content", {})
                json_schema = content.get("application/json", {}).get("schema")
                if json_schema:
                    body_errors = self._validate_json_schema(body, json_schema, "requestBody")
                    errors.extend(body_errors)

            parameters = method_schema.get("parameters", [])
            if query_params:
                for param in parameters:
                    if param.get("in") == "query":
                        param_name = param.get("name")
                        if param.get("required") and param_name not in query_params:
                            errors.append(ValidationError(
                                severity=ValidationSeverity.ERROR,
                                path=f"query.{param_name}",
                                message=f"Required query parameter '{param_name}' is missing",
                            ))

        except Exception as e:
            errors.append(ValidationError(
                severity=ValidationSeverity.ERROR,
                path=path,
                message=f"Validation error: {e}",
            ))

        return ValidationResult(
            valid=len(errors) == 0,
            errors=errors,
            warnings=warnings,
            duration_ms=(time.time() - start_time) * 1000,
        )

    def validate_response(
        self,
        schema: SchemaDefinition,
        path: str,
        method: str,
        status_code: int,
        body: Optional[Any] = None,
    ) -> ValidationResult:
        start_time = time.time()
        errors: List[ValidationError] = []
        warnings: List[ValidationError] = []

        try:
            paths = schema.content.get("paths", {})
            path_schema = paths.get(path, {})
            method_schema = path_schema.get(method.lower(), {})
            responses = method_schema.get("responses", {})

            response_key = str(status_code)
            if response_key not in responses and "default" not in responses:
                warnings.append(ValidationError(
                    severity=ValidationSeverity.WARNING,
                    path=f"responses.{response_key}",
                    message=f"Status code {status_code} not defined in schema",
                ))

            response_def = responses.get(response_key) or responses.get("default", {})
            content = response_def.get("content", {})
            json_schema = content.get("application/json", {}).get("schema")

            if json_schema and body is not None:
                body_errors = self._validate_json_schema(body, json_schema, "responseBody")
                errors.extend(body_errors)

        except Exception as e:
            errors.append(ValidationError(
                severity=ValidationSeverity.ERROR,
                path=path,
                message=f"Validation error: {e}",
            ))

        return ValidationResult(
            valid=len(errors) == 0,
            errors=errors,
            warnings=warnings,
            duration_ms=(time.time() - start_time) * 1000,
        )

    def _validate_json_schema(
        self,
        value: Any,
        schema: Dict[str, Any],
        base_path: str,
    ) -> List[ValidationError]:
        errors: List[ValidationError] = []

        schema_type = schema.get("type")
        if schema_type:
            type_matches = self._check_type(value, schema_type)
            if not type_matches:
                errors.append(ValidationError(
                    severity=ValidationSeverity.ERROR,
                    path=base_path,
                    message=f"Expected type '{schema_type}', got '{type(value).__name__}'",
                    value=value,
                ))
                return errors

        if schema_type == "object" and isinstance(value, dict):
            properties = schema.get("properties", {})
            required = schema.get("required", [])
            for prop_name in required:
                if prop_name not in value:
                    errors.append(ValidationError(
                        severity=ValidationSeverity.ERROR,
                        path=f"{base_path}.{prop_name}",
                        message=f"Required property '{prop_name}' is missing",
                    ))
            for prop_name, prop_value in value.items():
                if prop_name in properties:
                    sub_errors = self._validate_json_schema(
                        prop_value,
                        properties[prop_name],
                        f"{base_path}.{prop_name}",
                    )
                    errors.extend(sub_errors)

        if schema_type == "array" and isinstance(value, list):
            items_schema = schema.get("items")
            if items_schema:
                for i, item in enumerate(value):
                    sub_errors = self._validate_json_schema(
                        item,
                        items_schema,
                        f"{base_path}[{i}]",
                    )
                    errors.extend(sub_errors)

        if "enum" in schema and value not in schema["enum"]:
            errors.append(ValidationError(
                severity=ValidationSeverity.ERROR,
                path=base_path,
                message=f"Value '{value}' not in enum {schema['enum']}",
                value=value,
            ))

        return errors

    def _check_type(self, value: Any, expected_type: str) -> bool:
        type_map = {
            "string": str,
            "integer": int,
            "number": (int, float),
            "boolean": bool,
            "object": dict,
            "array": list,
            "null": type(None),
        }
        expected = type_map.get(expected_type)
        if expected is None:
            return True
        if expected_type == "integer" and isinstance(value, bool):
            return False
        return isinstance(value, expected)


class GraphQLValidator:
    def __init__(self) -> None:
        try:
            from graphql import parse, validate, build_schema
            self._parse = parse
            self._validate = validate
            self._build_schema = build_schema
            self._available = True
        except ImportError:
            self._available = False
            logger.warning("graphql-core not available, GraphQL validation disabled")

    def validate_query(self, schema: SchemaDefinition, query: str) -> ValidationResult:
        start_time = time.time()
        errors: List[ValidationError] = []

        if not self._available:
            return ValidationResult(
                valid=True,
                errors=errors,
                warnings=[ValidationError(
                    severity=ValidationSeverity.WARNING,
                    path="graphql",
                    message="GraphQL validation unavailable",
                )],
                duration_ms=(time.time() - start_time) * 1000,
            )

        try:
            schema_str = schema.content.get("schema", "") if isinstance(schema.content, dict) else schema.content
            graphql_schema = self._build_schema(schema_str)
            document = self._parse(query)
            validation_errors = self._validate(graphql_schema, document)
            for err in validation_errors:
                errors.append(ValidationError(
                    severity=ValidationSeverity.ERROR,
                    path="query",
                    message=str(err),
                ))
        except Exception as e:
            errors.append(ValidationError(
                severity=ValidationSeverity.ERROR,
                path="query",
                message=f"Invalid GraphQL query: {e}",
            ))

        return ValidationResult(
            valid=len(errors) == 0,
            errors=errors,
            duration_ms=(time.time() - start_time) * 1000,
        )


class SchemaValidator:
    def __init__(self) -> None:
        self.openapi_validator = OpenAPIValidator()
        self.graphql_validator = GraphQLValidator()
        self._schemas: Dict[str, SchemaDefinition] = {}

    def register_schema(self, schema: SchemaDefinition) -> str:
        self._schemas[schema.schema_id] = schema
        logger.info(f"Registered schema: {schema.name} ({schema.type})")
        return schema.schema_id

    def get_schema(self, schema_id: str) -> Optional[SchemaDefinition]:
        return self._schemas.get(schema_id)

    def list_schemas(self) -> List[SchemaDefinition]:
        return list(self._schemas.values())

    def delete_schema(self, schema_id: str) -> bool:
        if schema_id in self._schemas:
            del self._schemas[schema_id]
            return True
        return False

    def validate_request(
        self,
        schema_id: str,
        path: str,
        method: str,
        **kwargs: Any,
    ) -> ValidationResult:
        schema = self._schemas.get(schema_id)
        if not schema:
            raise InfrastructureValidationError(f"Schema '{schema_id}' not found")

        if schema.type == SchemaType.OPENAPI:
            return self.openapi_validator.validate_request(schema, path, method, **kwargs)
        elif schema.type == SchemaType.GRAPHQL:
            query = kwargs.get("body", {}).get("query", "") if kwargs.get("body") else ""
            return self.graphql_validator.validate_query(schema, query)

        raise InfrastructureValidationError(f"Unsupported schema type: {schema.type}")

    def validate_response(
        self,
        schema_id: str,
        path: str,
        method: str,
        status_code: int,
        **kwargs: Any,
    ) -> ValidationResult:
        schema = self._schemas.get(schema_id)
        if not schema:
            raise InfrastructureValidationError(f"Schema '{schema_id}' not found")

        if schema.type == SchemaType.OPENAPI:
            return self.openapi_validator.validate_response(schema, path, method, status_code, **kwargs)

        raise InfrastructureValidationError(f"Response validation not supported for type: {schema.type}")
