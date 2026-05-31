from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from src.common.models import APIResponse
from src.contract_testing.models import (
    SchemaDefinition,
    SchemaType,
    MockServerConfig,
    MockEndpoint,
    ContractTestResult,
)
from src.contract_testing.validator import SchemaValidator
from src.contract_testing.mock_server import MockServerManager

router = APIRouter(prefix="/contract", tags=["Contract Testing"])

_validator: Optional[SchemaValidator] = None
_mock_manager: Optional[MockServerManager] = None


def get_validator() -> SchemaValidator:
    global _validator
    if _validator is None:
        _validator = SchemaValidator()
    return _validator


def get_mock_manager() -> MockServerManager:
    global _mock_manager
    if _mock_manager is None:
        _mock_manager = MockServerManager()
    return _mock_manager


class RegisterSchemaRequest(BaseModel):
    name: str
    type: SchemaType
    version: str = "1.0.0"
    content: Dict[str, Any]
    description: str = ""


class ValidateRequestRequest(BaseModel):
    schema_id: str
    path: str
    method: str
    body: Optional[Any] = None
    headers: Optional[Dict[str, str]] = None
    query_params: Optional[Dict[str, Any]] = None


class ValidateResponseRequest(BaseModel):
    schema_id: str
    path: str
    method: str
    status_code: int
    body: Optional[Any] = None


class CreateMockServerRequest(BaseModel):
    schema_id: str
    name: Optional[str] = None


class UpdateEndpointRequest(BaseModel):
    status_code: Optional[int] = None
    mock_response: Optional[Any] = None
    delay_ms: Optional[int] = None
    headers: Optional[Dict[str, str]] = None


@router.get("/schemas")
async def list_schemas(
    validator: SchemaValidator = Depends(get_validator),
) -> APIResponse:
    return APIResponse(data=[s.model_dump() for s in validator.list_schemas()])


@router.post("/schemas", status_code=201)
async def register_schema(
    request: RegisterSchemaRequest,
    validator: SchemaValidator = Depends(get_validator),
) -> APIResponse:
    schema = SchemaDefinition(
        name=request.name,
        type=request.type,
        version=request.version,
        content=request.content,
        description=request.description,
    )
    schema_id = validator.register_schema(schema)
    return APIResponse(code=201, data={"schema_id": schema_id})


@router.get("/schemas/{schema_id}")
async def get_schema(
    schema_id: str,
    validator: SchemaValidator = Depends(get_validator),
) -> APIResponse:
    schema = validator.get_schema(schema_id)
    if not schema:
        raise HTTPException(status_code=404, detail="Schema not found")
    return APIResponse(data=schema.model_dump())


@router.delete("/schemas/{schema_id}")
async def delete_schema(
    schema_id: str,
    validator: SchemaValidator = Depends(get_validator),
) -> APIResponse:
    deleted = validator.delete_schema(schema_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Schema not found")
    return APIResponse(data={"schema_id": schema_id, "deleted": True})


@router.post("/validate/request")
async def validate_request(
    request: ValidateRequestRequest,
    validator: SchemaValidator = Depends(get_validator),
) -> APIResponse:
    result = validator.validate_request(
        request.schema_id,
        request.path,
        request.method,
        body=request.body,
        headers=request.headers,
        query_params=request.query_params,
    )
    return APIResponse(data=result.model_dump())


@router.post("/validate/response")
async def validate_response(
    request: ValidateResponseRequest,
    validator: SchemaValidator = Depends(get_validator),
) -> APIResponse:
    result = validator.validate_response(
        request.schema_id,
        request.path,
        request.method,
        request.status_code,
        body=request.body,
    )
    return APIResponse(data=result.model_dump())


@router.get("/mock-servers")
async def list_mock_servers(
    mock_manager: MockServerManager = Depends(get_mock_manager),
) -> APIResponse:
    return APIResponse(data=[c.model_dump() for c in mock_manager.list_servers()])


@router.post("/mock-servers", status_code=201)
async def create_mock_server(
    request: CreateMockServerRequest,
    validator: SchemaValidator = Depends(get_validator),
    mock_manager: MockServerManager = Depends(get_mock_manager),
) -> APIResponse:
    schema = validator.get_schema(request.schema_id)
    if not schema:
        raise HTTPException(status_code=404, detail="Schema not found")
    config = mock_manager.create_server(schema, request.name)
    return APIResponse(code=201, data=config.model_dump())


@router.get("/mock-servers/{server_id}")
async def get_mock_server(
    server_id: str,
    mock_manager: MockServerManager = Depends(get_mock_manager),
) -> APIResponse:
    config = mock_manager.get_config(server_id)
    if not config:
        raise HTTPException(status_code=404, detail="Mock server not found")
    return APIResponse(data=config.model_dump())


@router.delete("/mock-servers/{server_id}")
async def delete_mock_server(
    server_id: str,
    mock_manager: MockServerManager = Depends(get_mock_manager),
) -> APIResponse:
    deleted = mock_manager.delete_server(server_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Mock server not found")
    return APIResponse(data={"server_id": server_id, "deleted": True})


@router.patch("/mock-servers/{server_id}/endpoints/{endpoint_id}")
async def update_mock_endpoint(
    server_id: str,
    endpoint_id: str,
    updates: UpdateEndpointRequest,
    mock_manager: MockServerManager = Depends(get_mock_manager),
) -> APIResponse:
    update_dict = updates.model_dump(exclude_unset=True)
    endpoint = mock_manager.update_endpoint(server_id, endpoint_id, update_dict)
    if not endpoint:
        raise HTTPException(status_code=404, detail="Endpoint not found")
    return APIResponse(data=endpoint.model_dump())


@router.get("/mock-servers/{server_id}/logs")
async def get_mock_server_logs(
    server_id: str,
    limit: int = 100,
    mock_manager: MockServerManager = Depends(get_mock_manager),
) -> APIResponse:
    server = mock_manager.get_server(server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Mock server not found")
    logs = server.get_call_logs(limit)
    return APIResponse(data=[log.model_dump() for log in logs])


@router.delete("/mock-servers/{server_id}/logs")
async def clear_mock_server_logs(
    server_id: str,
    mock_manager: MockServerManager = Depends(get_mock_manager),
) -> APIResponse:
    server = mock_manager.get_server(server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Mock server not found")
    server.clear_logs()
    return APIResponse(data={"cleared": True})


@router.post("/mock-servers/{server_id}/invoke")
async def invoke_mock_endpoint(
    server_id: str,
    method: str,
    path: str,
    request: Request,
    mock_manager: MockServerManager = Depends(get_mock_manager),
) -> APIResponse:
    server = mock_manager.get_server(server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Mock server not found")
    body = None
    if method.upper() in ["POST", "PUT", "PATCH"]:
        try:
            body = await request.json()
        except Exception:
            pass
    result = await server.handle_request(
        method=method,
        path=path,
        headers=dict(request.headers),
        query_string=request.url.query,
        body=body,
    )
    return APIResponse(data=result)
