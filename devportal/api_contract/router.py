from typing import Any, Dict, List, Optional
from fastapi import APIRouter, Depends, Query, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import get_db
from ..core.schemas import APIResponse, PaginatedResponse
from ..core.dependencies import get_current_user, PermissionChecker
from ..core.models import User
from .models import SchemaType, MockServerStatus
from .schemas import (
    APISchemaCreate,
    APISchemaUpdate,
    APISchemaResponse,
    MockServerCreate,
    MockServerUpdate,
    MockServerResponse,
    MockServerStatusResponse,
    ContractTestCreate,
    ContractTestUpdate,
    ContractTestResponse,
    ValidationResult,
    FullValidationResponse,
    TestRunResult,
    RequestValidationRequest,
    RequestValidationResponse,
    ImportSchemaRequest,
    DiffRequest,
    DiffResponse,
    SchemaVersionResponse,
)
from .services import (
    APISchemaService,
    MockServerService,
    ContractTestService,
    SchemaValidationService,
)

router = APIRouter(prefix="/api-contract", tags=["API Contract Testing"])


@router.post("/schemas", response_model=APIResponse[APISchemaResponse], status_code=201)
async def create_schema(
    schema_in: APISchemaCreate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:create"])),
):
    service = APISchemaService(db)
    schema = await service.create_schema(schema_in)
    return APIResponse(code=201, data=schema)


@router.post("/schemas/import", response_model=APIResponse[APISchemaResponse], status_code=201)
async def import_schema(
    request: ImportSchemaRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:create"])),
):
    service = APISchemaService(db)
    schema = await service.import_from_url(request.url, request.name, request.namespace)
    return APIResponse(code=201, data=schema)


@router.get("/schemas", response_model=PaginatedResponse[APISchemaResponse])
async def list_schemas(
    namespace: Optional[str] = None,
    schema_type: Optional[SchemaType] = None,
    is_valid: Optional[bool] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:read"])),
):
    service = APISchemaService(db)
    skip = (page - 1) * page_size
    schemas, total = await service.list_schemas(namespace, schema_type, is_valid, skip, page_size)
    return PaginatedResponse(
        code=200, data=schemas, total=total, page=page, page_size=page_size
    )


@router.get("/schemas/{schema_id}", response_model=APIResponse[APISchemaResponse])
async def get_schema(
    schema_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:read"])),
):
    service = APISchemaService(db)
    schema = await service.get_schema(schema_id)
    return APIResponse(code=200, data=schema)


@router.patch("/schemas/{schema_id}", response_model=APIResponse[APISchemaResponse])
async def update_schema(
    schema_id: str,
    schema_in: APISchemaUpdate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:update"])),
):
    service = APISchemaService(db)
    schema = await service.update_schema(schema_id, schema_in)
    return APIResponse(code=200, data=schema)


@router.delete("/schemas/{schema_id}", response_model=APIResponse[Dict[str, Any]])
async def delete_schema(
    schema_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:delete"])),
):
    service = APISchemaService(db)
    await service.delete_schema(schema_id)
    return APIResponse(code=200, data={"id": schema_id, "deleted": True})


@router.post("/schemas/{schema_id}/validate", response_model=APIResponse[FullValidationResponse])
async def validate_schema(
    schema_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:validate"])),
):
    service = APISchemaService(db)
    results = await service.validate(schema_id)
    overall = all(r.is_valid for r in results)
    return APIResponse(
        code=200,
        data=FullValidationResponse(
            schema_id=schema_id,
            overall_valid=overall,
            results=results,
            timestamp=results[0].timestamp if results else __import__("datetime").datetime.utcnow(),
        ),
    )


@router.post("/schemas/validate-request", response_model=APIResponse[RequestValidationResponse])
async def validate_request(
    request: RequestValidationRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    service = APISchemaService(db)
    result = await service.validate_request(
        request.schema_id,
        request.path,
        request.method,
        request.headers,
        request.query_params,
        request.body,
    )
    return APIResponse(code=200, data=result)


@router.post("/schemas/diff", response_model=APIResponse[DiffResponse])
async def diff_schemas(
    request: DiffRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:read"])),
):
    service = APISchemaService(db)
    result = await service.diff(request.schema_id_a, request.schema_id_b)
    return APIResponse(code=200, data=DiffResponse(**result))


@router.post("/mock-servers", response_model=APIResponse[MockServerResponse], status_code=201)
async def create_mock_server(
    server_in: MockServerCreate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:create"])),
):
    service = MockServerService(db)
    server = await service.create_server(server_in)
    return APIResponse(code=201, data=server)


@router.get("/mock-servers", response_model=PaginatedResponse[MockServerResponse])
async def list_mock_servers(
    status: Optional[MockServerStatus] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:read"])),
):
    service = MockServerService(db)
    skip = (page - 1) * page_size
    servers, total = await service.list_servers(skip, page_size, status)
    return PaginatedResponse(
        code=200, data=servers, total=total, page=page, page_size=page_size
    )


@router.get("/mock-servers/{server_id}", response_model=APIResponse[MockServerResponse])
async def get_mock_server(
    server_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:read"])),
):
    service = MockServerService(db)
    server = await service.get_server(server_id)
    return APIResponse(code=200, data=server)


@router.patch("/mock-servers/{server_id}", response_model=APIResponse[MockServerResponse])
async def update_mock_server(
    server_id: str,
    server_in: MockServerUpdate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:update"])),
):
    service = MockServerService(db)
    server = await service.update_server(server_id, server_in)
    return APIResponse(code=200, data=server)


@router.delete("/mock-servers/{server_id}", response_model=APIResponse[Dict[str, Any]])
async def delete_mock_server(
    server_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:delete"])),
):
    service = MockServerService(db)
    await service.delete_server(server_id)
    return APIResponse(code=200, data={"id": server_id, "deleted": True})


@router.post("/mock-servers/{server_id}/start", response_model=APIResponse[MockServerStatusResponse])
async def start_mock_server(
    server_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:manage"])),
):
    service = MockServerService(db)
    result = await service.start(server_id)
    return APIResponse(code=200, data=MockServerStatusResponse(**result))


@router.post("/mock-servers/{server_id}/stop", response_model=APIResponse[MockServerStatusResponse])
async def stop_mock_server(
    server_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:manage"])),
):
    service = MockServerService(db)
    result = await service.stop(server_id)
    return APIResponse(code=200, data=MockServerStatusResponse(**result))


@router.get("/mock-servers/{server_id}/status", response_model=APIResponse[MockServerStatusResponse])
async def get_mock_server_status(
    server_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:read"])),
):
    service = MockServerService(db)
    result = await service.get_status(server_id)
    return APIResponse(code=200, data=MockServerStatusResponse(**result))


@router.api_route("/mock-servers/{server_id}/mock/{path:path}", methods=["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"])
async def handle_mock_request(
    server_id: str,
    path: str,
    request: Request,
    db: AsyncSession = Depends(get_db),
):
    service = MockServerService(db)
    body = None
    if request.method in ["POST", "PUT", "PATCH"]:
        try:
            body = await request.json()
        except Exception:
            body = await request.body()
    status, headers, resp_body = await service.handle_mock_request(
        server_id,
        request.method,
        "/" + path,
        dict(request.headers),
        dict(request.query_params),
        body,
    )
    import json
    if isinstance(resp_body, dict) or isinstance(resp_body, list):
        return Response(
            content=json.dumps(resp_body),
            status_code=status,
            headers={"Content-Type": "application/json", **headers},
        )
    return Response(content=str(resp_body), status_code=status, headers=headers)


@router.post("/contract-tests", response_model=APIResponse[ContractTestResponse], status_code=201)
async def create_contract_test(
    test_in: ContractTestCreate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:create"])),
):
    service = ContractTestService(db)
    test = await service.create_test(test_in)
    return APIResponse(code=201, data=test)


@router.get("/contract-tests", response_model=PaginatedResponse[ContractTestResponse])
async def list_contract_tests(
    schema_id: Optional[str] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:read"])),
):
    service = ContractTestService(db)
    skip = (page - 1) * page_size
    tests, total = await service.list_tests(schema_id, skip, page_size)
    return PaginatedResponse(
        code=200, data=tests, total=total, page=page, page_size=page_size
    )


@router.get("/contract-tests/{test_id}", response_model=APIResponse[ContractTestResponse])
async def get_contract_test(
    test_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:read"])),
):
    service = ContractTestService(db)
    test = await service.get_test(test_id)
    return APIResponse(code=200, data=test)


@router.patch("/contract-tests/{test_id}", response_model=APIResponse[ContractTestResponse])
async def update_contract_test(
    test_id: str,
    test_in: ContractTestUpdate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:update"])),
):
    service = ContractTestService(db)
    test = await service.update_test(test_id, test_in)
    return APIResponse(code=200, data=test)


@router.delete("/contract-tests/{test_id}", response_model=APIResponse[Dict[str, Any]])
async def delete_contract_test(
    test_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:delete"])),
):
    service = ContractTestService(db)
    await service.delete_test(test_id)
    return APIResponse(code=200, data={"id": test_id, "deleted": True})


@router.post("/contract-tests/{test_id}/run", response_model=APIResponse[TestRunResult])
async def run_contract_test(
    test_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["api_contract:validate"])),
):
    service = ContractTestService(db)
    result = await service.run_test(test_id)
    return APIResponse(code=200, data=result)
