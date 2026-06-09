from __future__ import annotations
from datetime import datetime
from fastapi import APIRouter, Depends, HTTPException, Query, Response
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import get_current_user
from app.schemas.common import (
    APIResponse,
    PaginatedResponse,
    PaginatedParams,
    SuccessResponse,
    IdResponse,
)
from app.schemas.serial import (
    SerialNumber,
    SerialNumberDetail,
    SerialNumberCreate,
    SerialNumberUpdate,
    SerialNumberImportRequest,
    SerialNumberImportResponse,
    SerialNumberVerifyRequest,
    SerialNumberVerifyResponse,
    SerialNumberScanRequest,
    SerialNumberScanResponse,
    SerialNumberAllocateRequest,
    SerialNumberShipRequest,
    SerialNumberReturnRequest,
    SerialNumberScrapRequest,
    SerialTraceQuery,
    TraceResponse,
    TraceDirectionEnum,
    SerialNumberStatusEnum,
    SerialNumberFilterParams,
)
from app.models.user import User
from app.services.serial_service import create_serial_service
from app.utils.exceptions import InventoryException

router = APIRouter(prefix="/serials", tags=["序列号管理"])


@router.get("", response_model=APIResponse[PaginatedResponse[SerialNumber]])
def list_serials(
    sku_id: int | None = Query(None, description="SKU ID"),
    batch_id: int | None = Query(None, description="批次ID"),
    warehouse_id: int | None = Query(None, description="仓库ID"),
    status: SerialNumberStatusEnum | None = Query(None, description="序列号状态"),
    serial_code_prefix: str | None = Query(None, description="序列号前缀"),
    date_from: datetime | None = Query(None, description="创建开始日期"),
    date_to: datetime | None = Query(None, description="创建结束日期"),
    expiration_from: datetime | None = Query(None, description="到期开始日期"),
    expiration_to: datetime | None = Query(None, description="到期结束日期"),
    is_expiring: bool | None = Query(None, description="是否临期"),
    keyword: str | None = Query(None, description="关键词搜索"),
    sort_by: str | None = Query("created_at", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)

        filters = SerialNumberFilterParams(
            sku_id=sku_id,
            batch_id=batch_id,
            warehouse_id=warehouse_id,
            status=status,
            serial_code_prefix=serial_code_prefix,
            date_from=date_from,
            date_to=date_to,
            expiration_from=expiration_from,
            expiration_to=expiration_to,
            is_expiring=is_expiring,
            keyword=keyword,
        )

        serials, total, total_pages = service.list_serials(
            filters=filters,
            page=paginated.page,
            page_size=paginated.page_size,
            sort_by=sort_by,
            sort_order=sort_order,
        )

        return APIResponse(
            data=PaginatedResponse(
                items=[SerialNumber(**s) for s in serials],
                page=paginated.page,
                page_size=paginated.page_size,
                total=total,
                total_pages=total_pages,
                has_next=paginated.page < total_pages,
                has_prev=paginated.page > 1,
            )
        )
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{serial_id}", response_model=APIResponse[SerialNumberDetail])
def get_serial_detail(
    serial_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        serial = service.get_serial_detail(serial_id)
        return APIResponse(data=SerialNumberDetail(**serial))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("", response_model=APIResponse[IdResponse])
def create_serial(
    serial_in: SerialNumberCreate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        serial = service.create_serial(serial_in)
        return APIResponse(data=IdResponse(id=serial.id))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.put("/{serial_id}", response_model=APIResponse[SuccessResponse])
def update_serial(
    serial_id: int,
    serial_in: SerialNumberUpdate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        service.update_serial(serial_id, serial_in)
        return APIResponse(data=SuccessResponse(success=True))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/import", response_model=APIResponse[SerialNumberImportResponse])
def import_serials(
    request: SerialNumberImportRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        result = service.import_serials(request)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{serial_id}/allocate", response_model=APIResponse[SuccessResponse])
def allocate_serial(
    serial_id: int,
    request: SerialNumberAllocateRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        service.allocate_serial(serial_id, request)
        return APIResponse(data=SuccessResponse(success=True, message="序列号分配成功"))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{serial_id}/ship", response_model=APIResponse[SuccessResponse])
def ship_serial(
    serial_id: int,
    request: SerialNumberShipRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        service.ship_serial(serial_id, request)
        return APIResponse(data=SuccessResponse(success=True, message="序列号出库成功"))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{serial_id}/return", response_model=APIResponse[SuccessResponse])
def return_serial(
    serial_id: int,
    request: SerialNumberReturnRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        service.return_serial(serial_id, request)
        return APIResponse(data=SuccessResponse(success=True, message="序列号退回成功"))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{serial_id}/scrap", response_model=APIResponse[SuccessResponse])
def scrap_serial(
    serial_id: int,
    request: SerialNumberScrapRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        service.scrap_serial(serial_id, request)
        return APIResponse(data=SuccessResponse(success=True, message="序列号报废成功"))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/trace/forward", response_model=APIResponse[TraceResponse])
def trace_forward(
    request: SerialTraceQuery,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        if not request.serial_codes or len(request.serial_codes) == 0:
            raise InventoryException("请指定要追溯的序列号", code=400)

        trace = service.trace_forward(
            serial_code=request.serial_codes[0],
            max_depth=request.max_depth,
        )
        return APIResponse(data=trace)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/trace/backward", response_model=APIResponse[TraceResponse])
def trace_backward(
    request: SerialTraceQuery,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        if not request.serial_codes or len(request.serial_codes) == 0:
            raise InventoryException("请指定要追溯的序列号", code=400)

        trace = service.trace_backward(
            serial_code=request.serial_codes[0],
            max_depth=request.max_depth,
        )
        return APIResponse(data=trace)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/trace/{serial_code}", response_model=APIResponse[TraceResponse])
def get_full_trace(
    serial_code: str,
    max_depth: int = Query(10, ge=1, le=100, description="最大追溯深度"),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        trace = service.trace_full(
            serial_code=serial_code,
            max_depth=max_depth,
        )
        return APIResponse(data=trace)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/trace/{serial_code}/graph", response_model=APIResponse[dict])
def get_trace_graph(
    serial_code: str,
    direction: TraceDirectionEnum = Query(TraceDirectionEnum.FULL, description="追溯方向"),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        graph_data = service.get_trace_graph(serial_code, direction)
        return APIResponse(data=graph_data)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/verify", response_model=APIResponse[SerialNumberVerifyResponse])
def verify_serials(
    request: SerialNumberVerifyRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        result = service.verify_serials(request)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/scan", response_model=APIResponse[SerialNumberScanResponse])
def scan_serial(
    request: SerialNumberScanRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        result = service.scan_serial(request)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/export/csv")
def export_serials_csv(
    sku_id: int | None = Query(None, description="SKU ID"),
    batch_id: int | None = Query(None, description="批次ID"),
    warehouse_id: int | None = Query(None, description="仓库ID"),
    status: SerialNumberStatusEnum | None = Query(None, description="状态筛选"),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        csv_content, _ = service.export_serials(
            sku_id=sku_id,
            batch_id=batch_id,
            warehouse_id=warehouse_id,
            status=status,
        )

        timestamp = datetime.utcnow().strftime("%Y%m%d%H%M%S")
        filename = f"serials_{timestamp}.csv"

        return Response(
            content=csv_content,
            media_type="text/csv",
            headers={
                "Content-Disposition": f"attachment; filename={filename}"
            }
        )
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/batch-trace", response_model=APIResponse[list[TraceResponse]])
def batch_trace(
    query: SerialTraceQuery,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_serial_service(db, current_user)
        results = service.batch_trace(query)
        return APIResponse(data=results)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e
