from datetime import datetime
from typing import Optional
from fastapi import APIRouter, Depends, HTTPException, Query, Body
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.audit import audit_logger
from app.schemas.common import (
    APIResponse,
    PaginatedResponse,
    PaginatedParams,
    SuccessResponse,
)
from app.schemas.document import (
    Document,
    DocumentDetail,
    DocumentCreate,
    DocumentUpdate,
    DocumentConfirmRequest,
    DocumentCompleteRequest,
    ScanItemRequest,
    ScanItemResponse,
    BatchScanRequest,
    BatchScanResponse,
    DocumentItemCreate,
    DocumentItem,
    DocumentTraceResponse,
    DocumentPrintTemplate,
    DocumentStatisticsResponse,
    DocumentListFilter,
    DocumentType,
    DocumentStatus,
)
from app.services.document_service import create_document_service
from app.utils.exceptions import InventoryException

router = APIRouter(prefix="/api/v1/documents", tags=["出入库单据管理"])


@router.get("", response_model=APIResponse[PaginatedResponse[Document]])
def list_documents(
    document_type: Optional[DocumentType] = Query(None, description="单据类型"),
    status: Optional[DocumentStatus] = Query(None, description="单据状态"),
    warehouse_id: Optional[int] = Query(None, description="仓库ID"),
    supplier_id: Optional[int] = Query(None, description="供应商ID"),
    customer_id: Optional[int] = Query(None, description="客户ID"),
    start_date: Optional[datetime] = Query(None, description="开始日期"),
    end_date: Optional[datetime] = Query(None, description="结束日期"),
    document_no: Optional[str] = Query(None, description="单据编号"),
    sort_by: str = Query("created_at", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
):
    try:
        document_service = create_document_service(db)

        filters = DocumentListFilter(
            document_type=document_type,
            status=status,
            warehouse_id=warehouse_id,
            supplier_id=supplier_id,
            customer_id=customer_id,
            start_date=start_date,
            end_date=end_date,
            document_no=document_no,
        )

        skip = (paginated.page - 1) * paginated.page_size
        documents = document_service.list_documents(
            filters=filters,
            skip=skip,
            limit=paginated.page_size,
            sort_by=sort_by,
            sort_order=sort_order,
        )
        total = document_service.count_documents(filters)
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=documents,
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


@router.get("/statistics", response_model=APIResponse[DocumentStatisticsResponse])
def get_document_statistics(
    start_date: Optional[datetime] = Query(None, description="开始日期"),
    end_date: Optional[datetime] = Query(None, description="结束日期"),
    warehouse_id: Optional[int] = Query(None, description="仓库ID"),
    db: Session = Depends(get_db),
):
    try:
        document_service = create_document_service(db)
        stats = document_service.get_statistics(
            start_date=start_date,
            end_date=end_date,
            warehouse_id=warehouse_id,
        )
        return APIResponse(data=stats)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{document_id}", response_model=APIResponse[DocumentDetail])
def get_document(
    document_id: int,
    db: Session = Depends(get_db),
):
    try:
        document_service = create_document_service(db)
        document = document_service.get_document(document_id)
        return APIResponse(data=document)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("", response_model=APIResponse[Document])
@audit_logger.log_action(action="CREATE", resource_type="document")
def create_document(
    document_data: DocumentCreate,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        document_service = create_document_service(db, current_user_id)
        document = document_service.create_document(document_data)
        return APIResponse(data=document)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.put("/{document_id}", response_model=APIResponse[Document])
@audit_logger.log_action(action="UPDATE", resource_type="document")
def update_document(
    document_id: int,
    document_data: DocumentUpdate,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        document_service = create_document_service(db, current_user_id)
        document = document_service.update_document(document_id, document_data)
        return APIResponse(data=document)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.delete("/{document_id}", response_model=APIResponse[SuccessResponse])
@audit_logger.log_action(action="DELETE", resource_type="document")
def delete_document(
    document_id: int,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        document_service = create_document_service(db, current_user_id)
        document_service.delete_document(document_id)
        return APIResponse(data=SuccessResponse(success=True, message="单据删除成功"))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{document_id}/confirm", response_model=APIResponse[Document])
@audit_logger.log_action(action="CONFIRM", resource_type="document")
def confirm_document(
    document_id: int,
    request: Optional[DocumentConfirmRequest] = Body(None),
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        document_service = create_document_service(db, current_user_id)
        document = document_service.confirm_document(
            document_id, request
        )
        return APIResponse(data=document)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{document_id}/complete", response_model=APIResponse[Document])
@audit_logger.log_action(action="COMPLETE", resource_type="document")
def complete_document(
    document_id: int,
    request: Optional[DocumentCompleteRequest] = Body(None),
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        document_service = create_document_service(db, current_user_id)
        document = document_service.complete_document(
            document_id, request
        )
        return APIResponse(data=document)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{document_id}/cancel", response_model=APIResponse[Document])
@audit_logger.log_action(action="CANCEL", resource_type="document")
def cancel_document(
    document_id: int,
    reason: Optional[str] = Body(None, embed=True),
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        document_service = create_document_service(db, current_user_id)
        document = document_service.cancel_document(
            document_id, reason
        )
        return APIResponse(data=document)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{document_id}/items", response_model=APIResponse[DocumentItem])
@audit_logger.log_action(action="CREATE", resource_type="document_item")
def add_document_item(
    document_id: int,
    item_data: DocumentItemCreate,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        document_service = create_document_service(db, current_user_id)
        item = document_service.add_document_item(document_id, item_data)
        return APIResponse(data=item)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.delete("/{document_id}/items/{item_id}", response_model=APIResponse[SuccessResponse])
@audit_logger.log_action(action="DELETE", resource_type="document_item")
def delete_document_item(
    document_id: int,
    item_id: int,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        document_service = create_document_service(db, current_user_id)
        document_service.delete_document_item(document_id, item_id)
        return APIResponse(data=SuccessResponse(success=True, message="明细删除成功"))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/scan", response_model=APIResponse[ScanItemResponse])
@audit_logger.log_action(action="SCAN", resource_type="document")
def scan_item(
    scan_request: ScanItemRequest,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        document_service = create_document_service(db, current_user_id)
        result = document_service.scan_item(scan_request)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/scan/batch", response_model=APIResponse[BatchScanResponse])
@audit_logger.log_action(action="BATCH_SCAN", resource_type="document")
def batch_scan(
    batch_request: BatchScanRequest,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        document_service = create_document_service(db, current_user_id)
        result = document_service.batch_scan(batch_request)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{document_id}/print", response_model=APIResponse[DocumentPrintTemplate])
def print_document(
    document_id: int,
    template_name: Optional[str] = Query(None, description="模板名称"),
    db: Session = Depends(get_db),
):
    try:
        document_service = create_document_service(db)
        template = document_service.get_print_template(
            document_id, template_name
        )
        return APIResponse(data=template)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{document_id}/trace", response_model=APIResponse[DocumentTraceResponse])
def get_document_trace(
    document_id: int,
    db: Session = Depends(get_db),
):
    try:
        document_service = create_document_service(db)
        trace = document_service.get_document_trace(document_id)
        return APIResponse(data=trace)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e
