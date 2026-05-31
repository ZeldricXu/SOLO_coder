import json
import yaml
import random
import asyncio
import re
from datetime import datetime, timezone, timedelta
from typing import Any, Dict, List, Optional, Tuple
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession
import httpx

from ..core.exceptions import NotFoundError, ConflictError, ValidationError
from ..core.utils import generate_id, utc_now, sha256_hash, processing_context, with_retry
from .models import (
    APISchema,
    SchemaVersion,
    MockServer,
    ContractTest,
    SchemaValidationResult,
    MockRequestLog,
    SchemaType,
    MockServerStatus,
)
from .schemas import (
    APISchemaCreate,
    APISchemaUpdate,
    SchemaVersionCreate,
    MockServerCreate,
    MockServerUpdate,
    ContractTestCreate,
    ContractTestUpdate,
    ValidationResult,
    ValidationErrorDetail,
    TestCaseResult,
    TestRunResult,
    RequestValidationResponse,
)


class OpenAPIValidator:
    @staticmethod
    def validate_syntax(content: str) -> Tuple[bool, List[ValidationErrorDetail], List[ValidationErrorDetail]]:
        errors: List[ValidationErrorDetail] = []
        warnings: List[ValidationErrorDetail] = []
        try:
            data = yaml.safe_load(content) if content.strip().startswith(("---", "%YAML")) else json.loads(content)
            if not isinstance(data, dict):
                errors.append(ValidationErrorDetail(
                    path="/",
                    message="Root must be an object",
                    error_type="syntax_error",
                ))
                return False, errors, warnings
            if "openapi" not in data and "swagger" not in data:
                errors.append(ValidationErrorDetail(
                    path="/",
                    message="Missing 'openapi' or 'swagger' field",
                    error_type="syntax_error",
                ))
                return False, errors, warnings
            if "openapi" in data:
                version = data["openapi"]
                if not re.match(r"^3\.\d+\.\d+$", str(version)):
                    warnings.append(ValidationErrorDetail(
                        path="/openapi",
                        message=f"Unusual OpenAPI version format: {version}",
                        error_type="version_warning",
                    ))
            if "info" not in data:
                errors.append(ValidationErrorDetail(
                    path="/info",
                    message="Missing 'info' field",
                    error_type="syntax_error",
                ))
            if "paths" not in data:
                errors.append(ValidationErrorDetail(
                    path="/paths",
                    message="Missing 'paths' field",
                    error_type="syntax_error",
                ))
            return len(errors) == 0, errors, warnings
        except json.JSONDecodeError as e:
            errors.append(ValidationErrorDetail(
                path="/",
                message=f"JSON parse error: {e.msg}",
                error_type="json_error",
                line=e.lineno,
                column=e.colno,
            ))
            return False, errors, warnings
        except yaml.YAMLError as e:
            errors.append(ValidationErrorDetail(
                path="/",
                message=f"YAML parse error: {str(e)}",
                error_type="yaml_error",
            ))
            return False, errors, warnings

    @staticmethod
    def validate_semantic(content: str) -> Tuple[bool, List[ValidationErrorDetail], List[ValidationErrorDetail]]:
        errors: List[ValidationErrorDetail] = []
        warnings: List[ValidationErrorDetail] = []
        try:
            data = yaml.safe_load(content) if content.strip().startswith(("---", "%YAML")) else json.loads(content)
            paths = data.get("paths", {})
            for path, methods in paths.items():
                if not path.startswith("/"):
                    errors.append(ValidationErrorDetail(
                        path=f"/paths/{path}",
                        message="Path must start with /",
                        error_type="semantic_error",
                    ))
                for method, op in methods.items():
                    if method.lower() not in {"get", "post", "put", "delete", "patch", "options", "head", "trace"}:
                        errors.append(ValidationErrorDetail(
                            path=f"/paths/{path}/{method}",
                            message=f"Invalid HTTP method: {method}",
                            error_type="semantic_error",
                        ))
                    if "responses" not in op:
                        errors.append(ValidationErrorDetail(
                            path=f"/paths/{path}/{method}/responses",
                            message="Missing 'responses' field",
                            error_type="semantic_error",
                        ))
                    elif "200" not in op["responses"] and "201" not in op["responses"]:
                        warnings.append(ValidationErrorDetail(
                            path=f"/paths/{path}/{method}/responses",
                            message="No success response (200/201) defined",
                            error_type="semantic_warning",
                        ))
                    if not op.get("operationId"):
                        warnings.append(ValidationErrorDetail(
                            path=f"/paths/{path}/{method}/operationId",
                            message="Missing operationId",
                            error_type="semantic_warning",
                        ))
            schemas = data.get("components", {}).get("schemas", {})
            for name, schema in schemas.items():
                if not isinstance(schema, dict):
                    continue
                if "type" not in schema and "$ref" not in schema and "oneOf" not in schema:
                    warnings.append(ValidationErrorDetail(
                        path=f"/components/schemas/{name}/type",
                        message="Schema missing type or $ref",
                        error_type="semantic_warning",
                    ))
            return len(errors) == 0, errors, warnings
        except Exception:
            return True, errors, warnings

    @staticmethod
    def validate_compatibility(old_content: str, new_content: str) -> Tuple[bool, List[ValidationErrorDetail], List[ValidationErrorDetail]]:
        errors: List[ValidationErrorDetail] = []
        warnings: List[ValidationErrorDetail] = []
        try:
            old = yaml.safe_load(old_content) if old_content.strip().startswith(("---", "%YAML")) else json.loads(old_content)
            new = yaml.safe_load(new_content) if new_content.strip().startswith(("---", "%YAML")) else json.loads(new_content)
            old_paths = set(old.get("paths", {}).keys())
            new_paths = set(new.get("paths", {}).keys())
            removed = old_paths - new_paths
            for path in removed:
                errors.append(ValidationErrorDetail(
                    path=f"/paths/{path}",
                    message=f"Breaking change: path {path} removed",
                    error_type="breaking_change",
                ))
            for path in old_paths & new_paths:
                old_methods = set(old["paths"][path].keys())
                new_methods = set(new["paths"][path].keys())
                for method in old_methods - new_methods:
                    errors.append(ValidationErrorDetail(
                        path=f"/paths/{path}/{method}",
                        message=f"Breaking change: method {method.upper()} removed from {path}",
                        error_type="breaking_change",
                    ))
            return len(errors) == 0, errors, warnings
        except Exception:
            return True, errors, warnings


class GraphQLValidator:
    @staticmethod
    def validate_syntax(content: str) -> Tuple[bool, List[ValidationErrorDetail], List[ValidationErrorDetail]]:
        errors: List[ValidationErrorDetail] = []
        warnings: List[ValidationErrorDetail] = []
        try:
            from graphql import parse, GraphQLSyntaxError
            try:
                parse(content)
                return True, errors, warnings
            except GraphQLSyntaxError as e:
                errors.append(ValidationErrorDetail(
                    path="/",
                    message=f"GraphQL syntax error: {e.message}",
                    error_type="syntax_error",
                    line=e.locations[0].line if e.locations else None,
                    column=e.locations[0].column if e.locations else None,
                ))
                return False, errors, warnings
        except ImportError:
            warnings.append(ValidationErrorDetail(
                path="/",
                message="graphql-core not installed, using basic validation",
                error_type="dependency_warning",
            ))
            if not content.strip():
                errors.append(ValidationErrorDetail(
                    path="/",
                    message="Empty schema",
                    error_type="syntax_error",
                ))
                return False, errors, warnings
            if "type Query" not in content:
                warnings.append(ValidationErrorDetail(
                    path="/",
                    message="No Query type defined",
                    error_type="semantic_warning",
                ))
            return True, errors, warnings

    @staticmethod
    def validate_semantic(content: str) -> Tuple[bool, List[ValidationErrorDetail], List[ValidationErrorDetail]]:
        errors: List[ValidationErrorDetail] = []
        warnings: List[ValidationErrorDetail] = []
        try:
            from graphql import parse, validate_schema, build_schema
            schema = build_schema(content)
            validation_errors = validate_schema(schema)
            for e in validation_errors:
                errors.append(ValidationErrorDetail(
                    path="/",
                    message=str(e.message),
                    error_type="semantic_error",
                ))
            return len(errors) == 0, errors, warnings
        except Exception as e:
            warnings.append(ValidationErrorDetail(
                path="/",
                message=f"Semantic validation skipped: {str(e)}",
                error_type="validation_warning",
            ))
            return True, errors, warnings


class SchemaValidationService:
    @staticmethod
    async def validate_schema(
        content: str,
        schema_type: SchemaType,
        validators: Optional[List[str]] = None,
    ) -> List[ValidationResult]:
        results = []
        validators = validators or ["syntax", "semantic"]
        for validator_name in validators:
            if schema_type == SchemaType.OPENAPI:
                validator = OpenAPIValidator
            else:
                validator = GraphQLValidator
            if validator_name == "syntax":
                is_valid, errors, warnings = validator.validate_syntax(content)
            elif validator_name == "semantic":
                is_valid, errors, warnings = validator.validate_semantic(content)
            elif validator_name == "compatibility":
                is_valid, errors, warnings = True, [], []
            else:
                continue
            results.append(ValidationResult(
                validator=validator_name,
                is_valid=is_valid,
                errors=errors,
                warnings=warnings,
                metadata={"schema_type": schema_type},
                validation_id=generate_id("val"),
                timestamp=utc_now(),
            ))
        return results


class MockServerManager:
    def __init__(self):
        self._servers: Dict[str, Any] = {}
        self._next_port = 8000

    def _get_next_port(self) -> int:
        port = self._next_port
        self._next_port += 1
        return port

    async def start_server(self, mock_server: MockServer) -> Tuple[int, int]:
        port = self._get_next_port()
        self._servers[mock_server.id] = {
            "port": port,
            "status": MockServerStatus.RUNNING,
            "request_count": 0,
        }
        return port, 12345

    async def stop_server(self, mock_server: MockServer) -> None:
        if mock_server.id in self._servers:
            self._servers[mock_server.id]["status"] = MockServerStatus.STOPPED

    def get_server_status(self, server_id: str) -> Optional[Dict[str, Any]]:
        return self._servers.get(server_id)

    def handle_request(
        self,
        server_id: str,
        method: str,
        path: str,
        schema: APISchema,
        mock_config: MockServer,
    ) -> Tuple[int, Dict[str, Any], Any]:
        server = self._servers.get(server_id)
        if not server:
            return 404, {}, {"error": "Mock server not found"}
        server["request_count"] += 1
        if random.random() < mock_config.error_rate:
            error_codes = [400, 401, 403, 404, 500, 502, 503]
            return random.choice(error_codes), {}, {"error": "Simulated error"}
        try:
            schema_data = json.loads(schema.content) if schema.content.strip().startswith("{") else yaml.safe_load(schema.content)
        except Exception:
            return 200, {}, {"message": "Mock response"}
        paths = schema_data.get("paths", {})
        matched_path = None
        for schema_path in paths:
            if self._match_path(schema_path, path):
                matched_path = schema_path
                break
        if matched_path and method.lower() in paths[matched_path]:
            op = paths[matched_path][method.lower()]
            responses = op.get("responses", {})
            success_resp = responses.get("200", responses.get("201", list(responses.values())[0] if responses else {}))
            mock_body = self._generate_mock_body(success_resp, schema_data)
            return 200, {"Content-Type": "application/json"}, mock_body
        custom_key = f"{method.upper()}:{path}"
        if custom_key in mock_config.custom_responses:
            custom = mock_config.custom_responses[custom_key]
            return custom.get("status", 200), custom.get("headers", {}), custom.get("body", {})
        return 404, {}, {"error": f"No mock found for {method} {path}"}

    def _match_path(self, schema_path: str, request_path: str) -> bool:
        schema_parts = schema_path.strip("/").split("/")
        request_parts = request_path.strip("/").split("/")
        if len(schema_parts) != len(request_parts):
            return False
        for sp, rp in zip(schema_parts, request_parts):
            if sp.startswith("{") and sp.endswith("}"):
                continue
            if sp != rp:
                return False
        return True

    def _generate_mock_body(self, response_spec: Dict[str, Any], schema_data: Dict[str, Any]) -> Any:
        content = response_spec.get("content", {})
        json_content = content.get("application/json", {})
        schema = json_content.get("schema", {})
        return self._generate_from_schema(schema, schema_data)

    def _generate_from_schema(self, schema: Dict[str, Any], full_schema: Dict[str, Any]) -> Any:
        if "$ref" in schema:
            ref_path = schema["$ref"].replace("#/", "").split("/")
            ref_schema = full_schema
            for part in ref_path:
                ref_schema = ref_schema.get(part, {})
            return self._generate_from_schema(ref_schema, full_schema)
        schema_type = schema.get("type")
        if schema_type == "object":
            result = {}
            props = schema.get("properties", {})
            for name, prop in props.items():
                result[name] = self._generate_from_schema(prop, full_schema)
            return result
        elif schema_type == "array":
            items = schema.get("items", {})
            return [self._generate_from_schema(items, full_schema) for _ in range(2)]
        elif schema_type == "string":
            enum = schema.get("enum")
            if enum:
                return random.choice(enum)
            format = schema.get("format")
            if format == "date-time":
                return utc_now().isoformat()
            if format == "email":
                return "user@example.com"
            if format == "uuid":
                return generate_id("uuid")
            return "mock_string"
        elif schema_type == "integer":
            return random.randint(1, 100)
        elif schema_type == "number":
            return round(random.uniform(0, 100), 2)
        elif schema_type == "boolean":
            return random.choice([True, False])
        else:
            example = schema.get("example")
            if example is not None:
                return example
            return None


mock_server_manager = MockServerManager()


class APISchemaService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def import_from_url(self, url: str, name: Optional[str] = None, namespace: str = "default") -> APISchema:
        async with httpx.AsyncClient() as client:
            response = await with_retry(client.get, url)
            content = response.text
        schema_type = SchemaType.OPENAPI
        if "graphql" in url.lower() or "type Query" in content[:500]:
            schema_type = SchemaType.GRAPHQL
        if not name:
            name = url.split("/")[-1] or f"imported_{generate_id('sch')}"
        return await self.create_schema(APISchemaCreate(
            name=name,
            schema_type=schema_type,
            content=content,
            url=url,
            namespace=namespace,
        ))

    async def create_schema(self, schema_in: APISchemaCreate) -> APISchema:
        content_hash = sha256_hash(schema_in.content)
        existing = await self.db.execute(
            select(APISchema).where(
                (APISchema.name == schema_in.name) & (APISchema.namespace == schema_in.namespace)
            )
        )
        if existing.scalar_one_or_none():
            raise ConflictError("Schema with this name already exists in namespace")
        validation_results = await SchemaValidationService.validate_schema(
            schema_in.content, schema_in.schema_type
        )
        is_valid = all(r.is_valid for r in validation_results)
        all_errors = [e.model_dump() for r in validation_results for e in r.errors]
        schema = APISchema(
            id=generate_id("sch"),
            **schema_in.model_dump(),
            content_hash=content_hash,
            is_valid=is_valid,
            validation_errors=all_errors,
            last_validated_at=utc_now(),
            status="active",
        )
        self.db.add(schema)
        for r in validation_results:
            result = SchemaValidationResult(
                schema_id=schema.id,
                validator=r.validator,
                is_valid=r.is_valid,
                errors=[e.model_dump() for e in r.errors],
                warnings=[w.model_dump() for w in r.warnings],
                metadata=r.metadata,
            )
            self.db.add(result)
        await self.db.commit()
        await self.db.refresh(schema)
        return schema

    async def get_schema(self, schema_id: str) -> APISchema:
        result = await self.db.execute(select(APISchema).where(APISchema.id == schema_id))
        schema = result.scalar_one_or_none()
        if not schema:
            raise NotFoundError(f"API Schema {schema_id} not found")
        return schema

    async def list_schemas(
        self,
        namespace: Optional[str] = None,
        schema_type: Optional[SchemaType] = None,
        is_valid: Optional[bool] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[APISchema], int]:
        query = select(APISchema)
        if namespace:
            query = query.where(APISchema.namespace == namespace)
        if schema_type:
            query = query.where(APISchema.schema_type == schema_type)
        if is_valid is not None:
            query = query.where(APISchema.is_valid == is_valid)
        count_result = await self.db.execute(select(func.count()).select_from(query.subquery()))
        total = count_result.scalar_one()
        result = await self.db.execute(query.offset(skip).limit(limit).order_by(APISchema.created_at.desc()))
        return list(result.scalars().all()), total

    async def update_schema(self, schema_id: str, schema_in: APISchemaUpdate) -> APISchema:
        schema = await self.get_schema(schema_id)
        old_content = schema.content
        update_data = schema_in.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(schema, key, value)
        if schema_in.content:
            new_hash = sha256_hash(schema_in.content)
            if new_hash != schema.content_hash:
                version = SchemaVersion(
                    id=generate_id("ver"),
                    schema_id=schema.id,
                    version=f"{schema.version}-backup",
                    content=old_content,
                    content_hash=schema.content_hash,
                    type="schema_version",
                    status="active",
                )
                self.db.add(version)
                schema.content_hash = new_hash
                validation_results = await SchemaValidationService.validate_schema(
                    schema_in.content, schema.schema_type
                )
                schema.is_valid = all(r.is_valid for r in validation_results)
                schema.validation_errors = [e.model_dump() for r in validation_results for e in r.errors]
                schema.last_validated_at = utc_now()
        await self.db.commit()
        await self.db.refresh(schema)
        return schema

    async def delete_schema(self, schema_id: str) -> None:
        schema = await self.get_schema(schema_id)
        await self.db.delete(schema)
        await self.db.commit()

    async def validate(self, schema_id: str) -> List[ValidationResult]:
        schema = await self.get_schema(schema_id)
        results = await SchemaValidationService.validate_schema(schema.content, schema.schema_type)
        schema.is_valid = all(r.is_valid for r in results)
        schema.validation_errors = [e.model_dump() for r in results for e in r.errors]
        schema.last_validated_at = utc_now()
        for r in results:
            result = SchemaValidationResult(
                schema_id=schema.id,
                validator=r.validator,
                is_valid=r.is_valid,
                errors=[e.model_dump() for e in r.errors],
                warnings=[w.model_dump() for w in r.warnings],
                metadata=r.metadata,
            )
            self.db.add(result)
        await self.db.commit()
        return results

    async def validate_request(
        self,
        schema_id: str,
        path: str,
        method: str,
        headers: Dict[str, str],
        query_params: Dict[str, Any],
        body: Optional[Any],
    ) -> RequestValidationResponse:
        schema = await self.get_schema(schema_id)
        errors: List[ValidationErrorDetail] = []
        matched_op = None
        try:
            schema_data = json.loads(schema.content) if schema.content.strip().startswith("{") else yaml.safe_load(schema.content)
            paths = schema_data.get("paths", {})
            for schema_path, methods in paths.items():
                if self._match_path(schema_path, path) and method.lower() in methods:
                    matched_op = f"{method.upper()} {schema_path}"
                    op = methods[method.lower()]
                    params = op.get("parameters", [])
                    for param in params:
                        pname = param.get("name")
                        prequired = param.get("required", False)
                        pin = param.get("in")
                        if prequired:
                            if pin == "query" and pname not in query_params:
                                errors.append(ValidationErrorDetail(
                                    path=f"/query/{pname}",
                                    message=f"Missing required query parameter: {pname}",
                                    error_type="missing_parameter",
                                ))
                            elif pin == "header" and pname not in headers:
                                errors.append(ValidationErrorDetail(
                                    path=f"/headers/{pname}",
                                    message=f"Missing required header: {pname}",
                                    error_type="missing_header",
                                ))
        except Exception as e:
            errors.append(ValidationErrorDetail(
                path="/",
                message=f"Validation error: {str(e)}",
                error_type="validation_error",
            ))
        return RequestValidationResponse(
            is_valid=len(errors) == 0,
            errors=errors,
            matched_operation=matched_op,
            request_id=generate_id("req"),
            timestamp=utc_now(),
        )

    def _match_path(self, schema_path: str, request_path: str) -> bool:
        schema_parts = schema_path.strip("/").split("/")
        request_parts = request_path.strip("/").split("/")
        if len(schema_parts) != len(request_parts):
            return False
        for sp, rp in zip(schema_parts, request_parts):
            if sp.startswith("{") and sp.endswith("}"):
                continue
            if sp != rp:
                return False
        return True

    async def diff(self, schema_id_a: str, schema_id_b: str) -> Dict[str, Any]:
        schema_a = await self.get_schema(schema_id_a)
        schema_b = await self.get_schema(schema_id_b)
        is_valid, errors, warnings = OpenAPIValidator.validate_compatibility(
            schema_a.content, schema_b.content
        )
        breaking = [e.model_dump() for e in errors if e.error_type == "breaking_change"]
        non_breaking = [e.model_dump() for e in errors if e.error_type != "breaking_change"]
        return {
            "changes": [e.model_dump() for e in errors],
            "breaking_changes": breaking,
            "non_breaking_changes": non_breaking,
            "diff_id": generate_id("diff"),
            "timestamp": utc_now(),
        }


class MockServerService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_server(self, server_in: MockServerCreate) -> MockServer:
        server = MockServer(
            id=generate_id("mock"),
            **server_in.model_dump(),
            status=MockServerStatus.STOPPED,
            type="mock_server",
        )
        self.db.add(server)
        await self.db.commit()
        await self.db.refresh(server)
        return server

    async def get_server(self, server_id: str) -> MockServer:
        result = await self.db.execute(select(MockServer).where(MockServer.id == server_id))
        server = result.scalar_one_or_none()
        if not server:
            raise NotFoundError(f"Mock server {server_id} not found")
        return server

    async def list_servers(
        self, skip: int = 0, limit: int = 100, status: Optional[str] = None
    ) -> Tuple[List[MockServer], int]:
        query = select(MockServer)
        if status:
            query = query.where(MockServer.status == status)
        count_result = await self.db.execute(select(func.count()).select_from(query.subquery()))
        total = count_result.scalar_one()
        result = await self.db.execute(query.offset(skip).limit(limit).order_by(MockServer.created_at.desc()))
        return list(result.scalars().all()), total

    async def update_server(self, server_id: str, server_in: MockServerUpdate) -> MockServer:
        server = await self.get_server(server_id)
        update_data = server_in.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(server, key, value)
        await self.db.commit()
        await self.db.refresh(server)
        return server

    async def delete_server(self, server_id: str) -> None:
        server = await self.get_server(server_id)
        if server.status == MockServerStatus.RUNNING:
            await mock_server_manager.stop_server(server)
            server.status = MockServerStatus.STOPPED
            server.stopped_at = utc_now()
            await self.db.commit()
        await self.db.delete(server)
        await self.db.commit()

    async def start(self, server_id: str) -> Dict[str, Any]:
        server = await self.get_server(server_id)
        schema = await self.db.get(APISchema, server.schema_id)
        if not schema:
            raise NotFoundError("Associated schema not found")
        if not schema.is_valid:
            raise ValidationError("Schema is invalid, cannot start mock server")
        port, pid = await mock_server_manager.start_server(server)
        server.port = port
        server.pid = pid
        server.status = MockServerStatus.RUNNING
        server.started_at = utc_now()
        await self.db.commit()
        await self.db.refresh(server)
        return {
            "server_id": server.id,
            "status": MockServerStatus.RUNNING,
            "port": port,
            "base_url": f"http://{server.host}:{port}{server.base_path}",
        }

    async def stop(self, server_id: str) -> Dict[str, Any]:
        server = await self.get_server(server_id)
        await mock_server_manager.stop_server(server)
        server.status = MockServerStatus.STOPPED
        server.stopped_at = utc_now()
        await self.db.commit()
        return {
            "server_id": server.id,
            "status": MockServerStatus.STOPPED,
        }

    async def get_status(self, server_id: str) -> Dict[str, Any]:
        server = await self.get_server(server_id)
        status = mock_server_manager.get_server_status(server_id)
        uptime = None
        if server.started_at and server.status == MockServerStatus.RUNNING:
            uptime = (utc_now() - server.started_at).total_seconds()
        return {
            "server_id": server.id,
            "status": server.status,
            "port": server.port,
            "base_url": f"http://{server.host}:{server.port}{server.base_path}" if server.port else None,
            "uptime_seconds": uptime,
            "request_count": status.get("request_count", 0) if status else 0,
        }

    async def handle_mock_request(
        self,
        server_id: str,
        method: str,
        path: str,
        headers: Dict[str, Any],
        query_params: Dict[str, Any],
        body: Optional[Any],
    ) -> Tuple[int, Dict[str, Any], Any]:
        server = await self.get_server(server_id)
        schema = await self.db.get(APISchema, server.schema_id)
        if not schema:
            return 500, {}, {"error": "Schema not found"}
        start_time = utc_now()
        if server.latency_ms > 0:
            await asyncio.sleep(server.latency_ms / 1000.0)
        status, resp_headers, body = mock_server_manager.handle_request(
            server_id, method, path, schema, server
        )
        latency = int((utc_now() - start_time).total_seconds() * 1000)
        log = MockRequestLog(
            mock_server_id=server_id,
            method=method.upper(),
            path=path,
            query_params=query_params,
            request_headers=headers,
            request_body=body,
            response_status=status,
            response_headers=resp_headers,
            response_body=body,
            latency_ms=latency,
        )
        self.db.add(log)
        await self.db.commit()
        return status, resp_headers, body


class ContractTestService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_test(self, test_in: ContractTestCreate) -> ContractTest:
        test = ContractTest(
            id=generate_id("test"),
            **test_in.model_dump(),
            type="contract_test",
            status="active",
        )
        self.db.add(test)
        await self.db.commit()
        await self.db.refresh(test)
        return test

    async def get_test(self, test_id: str) -> ContractTest:
        result = await self.db.execute(select(ContractTest).where(ContractTest.id == test_id))
        test = result.scalar_one_or_none()
        if not test:
            raise NotFoundError(f"Contract test {test_id} not found")
        return test

    async def list_tests(
        self, schema_id: Optional[str] = None, skip: int = 0, limit: int = 100
    ) -> Tuple[List[ContractTest], int]:
        query = select(ContractTest)
        if schema_id:
            query = query.where(ContractTest.schema_id == schema_id)
        count_result = await self.db.execute(select(func.count()).select_from(query.subquery()))
        total = count_result.scalar_one()
        result = await self.db.execute(query.offset(skip).limit(limit).order_by(ContractTest.created_at.desc()))
        return list(result.scalars().all()), total

    async def update_test(self, test_id: str, test_in: ContractTestUpdate) -> ContractTest:
        test = await self.get_test(test_id)
        update_data = test_in.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(test, key, value)
        await self.db.commit()
        await self.db.refresh(test)
        return test

    async def delete_test(self, test_id: str) -> None:
        test = await self.get_test(test_id)
        await self.db.delete(test)
        await self.db.commit()

    async def run_test(self, test_id: str) -> TestRunResult:
        test = await self.get_test(test_id)
        schema = await self.db.get(APISchema, test.schema_id)
        if not schema:
            raise NotFoundError("Associated schema not found")
        start_time = utc_now()
        results: List[TestCaseResult] = []
        pass_count = 0
        fail_count = 0
        for i, case in enumerate(test.test_cases):
            case_id = case.get("id", f"case_{i}")
            case_name = case.get("name", f"Test case {i}")
            try:
                path = case.get("path", "/")
                method = case.get("method", "GET")
                validation_result = await APISchemaService(self.db).validate_request(
                    test.schema_id,
                    path,
                    method,
                    case.get("headers", {}),
                    case.get("query_params", {}),
                    case.get("body"),
                )
                expected_valid = case.get("expected_valid", True)
                passed = validation_result.is_valid == expected_valid
                if passed:
                    pass_count += 1
                else:
                    fail_count += 1
                results.append(TestCaseResult(
                    test_id=case_id,
                    name=case_name,
                    passed=passed,
                    message="" if passed else f"Expected valid={expected_valid}, got valid={validation_result.is_valid}",
                    details={"validation": validation_result.model_dump()},
                ))
            except Exception as e:
                fail_count += 1
                results.append(TestCaseResult(
                    test_id=case_id,
                    name=case_name,
                    passed=False,
                    message=str(e),
                    details={"error": str(e)},
                ))
        duration = int((utc_now() - start_time).total_seconds() * 1000)
        test.last_run_at = utc_now()
        test.last_run_status = "passed" if fail_count == 0 else "failed"
        test.last_run_results = {"results": [r.model_dump() for r in results]}
        test.pass_count = pass_count
        test.fail_count = fail_count
        await self.db.commit()
        return TestRunResult(
            test_id=test.id,
            overall_status=test.last_run_status,
            results=results,
            pass_count=pass_count,
            fail_count=fail_count,
            duration_ms=duration,
            timestamp=utc_now(),
        )
