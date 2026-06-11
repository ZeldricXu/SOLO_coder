from __future__ import annotations
import os
from typing import Optional
from fastapi import (
    APIRouter,
    Depends,
    File,
    UploadFile,
    HTTPException,
    status,
    Path,
    Request,
    Query,
)
from fastapi.responses import FileResponse
from sqlalchemy import select, func
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import PermissionChecker, get_current_user
from app.core.audit import AuditLogger
from app.schemas.common import APIResponse, PaginatedParams, PaginatedResponse
from app.schemas.import_export import (
    ImportJobResponse,
    ImportJobDetailResponse,
    ImportErrorListResponse,
    ImportResponse,
    ImportResult,
    ImportJobDetail,
    ImportErrorListItem,
    SkuExportFilter,
    ProductExportFilter,
    ExportJobResponse,
)
from app.services.import_export_service import import_export_service
from app.models.import_export import ImportJob, ImportError, FileType
from app.models.user import User as UserModel


router = APIRouter()


def _get_file_type(filename: Optional[str]) -> FileType:
    if not filename:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Filename is required",
        )
    ext = os.path.splitext(filename)[1].lower()
    if ext in [".xlsx", ".xls"]:
        return FileType.EXCEL
    elif ext == ".csv":
        return FileType.CSV
    else:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Unsupported file type: {ext}. Supported types: .xlsx, .xls, .csv",
        )


@router.post(
    "/import/products",
    response_model=ImportResponse,
    summary="批量导入商品",
    dependencies=[Depends(PermissionChecker(["product:create", "product:import"]))],
)
async def import_products(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    file: UploadFile = File(..., description="Excel或CSV文件"),
):
    file_type = _get_file_type(file.filename)
    result = import_export_service.import_products_from_file(
        db,
        file=file,
        file_type=file_type,
        created_by=current_user.id,
    )

    audit_logger = AuditLogger(db)
    audit_logger.log(
        user_id=current_user.id,
        action="import_products",
        resource_type="product",
        resource_id=result["job_id"],
        new_value={
            "file_name": file.filename,
            "file_type": file_type.value,
            "total_count": result["total_count"],
            "success_count": result["success_count"],
            "failed_count": result["failed_count"],
        },
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    return ImportResponse(
        data=ImportResult(
            job_id=result["job_id"],
            total_count=result["total_count"],
            success_count=result["success_count"],
            failed_count=result["failed_count"],
            status=result["status"],
            created_products=result["created_products"],
            created_skus=result["created_skus"],
            errors=result["errors"],
        )
    )


@router.post(
    "/import/skus",
    response_model=ImportResponse,
    summary="批量导入SKU",
    dependencies=[Depends(PermissionChecker(["sku:create", "sku:import"]))],
)
async def import_skus(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    file: UploadFile = File(..., description="Excel或CSV文件"),
):
    file_type = _get_file_type(file.filename)
    result = import_export_service.import_skus_from_file(
        db,
        file=file,
        file_type=file_type,
        created_by=current_user.id,
    )

    audit_logger = AuditLogger(db)
    audit_logger.log(
        user_id=current_user.id,
        action="import_skus",
        resource_type="sku",
        resource_id=result["job_id"],
        new_value={
            "file_name": file.filename,
            "file_type": file_type.value,
            "total_count": result["total_count"],
            "success_count": result["success_count"],
            "failed_count": result["failed_count"],
        },
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    return ImportResponse(
        data=ImportResult(
            job_id=result["job_id"],
            total_count=result["total_count"],
            success_count=result["success_count"],
            failed_count=result["failed_count"],
            status=result["status"],
            created_products=result["created_products"],
            created_skus=result["created_skus"],
            errors=result["errors"],
        )
    )


@router.get(
    "/import/{job_id}",
    response_model=ImportJobDetailResponse,
    summary="查询导入任务状态",
    dependencies=[Depends(PermissionChecker(["import:read"]))],
)
async def get_import_job_status(
    db: Session = Depends(get_db),
    *,
    job_id: int = Path(..., description="导入任务ID"),
):
    job = import_export_service.get_job(db, job_id=job_id)

    error_count_stmt = select(func.count()).where(ImportError.job_id == job_id)
    error_count = db.execute(error_count_stmt).scalar_one() or 0

    job_detail = ImportJobDetail.model_validate({
        **{c.name: getattr(job, c.name) for c in job.__table__.columns},
        "error_count": error_count,
    })

    return ImportJobDetailResponse(data=job_detail)


@router.get(
    "/import/{job_id}/errors",
    summary="下载错误报告",
    dependencies=[Depends(PermissionChecker(["import:read"]))],
)
async def download_error_report(
    db: Session = Depends(get_db),
    *,
    job_id: int = Path(..., description="导入任务ID"),
):
    job = import_export_service.get_job(db, job_id=job_id)

    if not job.error_report_path or not os.path.exists(job.error_report_path):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Error report not found",
        )

    file_name = os.path.basename(job.error_report_path)
    return FileResponse(
        job.error_report_path,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        filename=file_name,
    )


@router.get(
    "/import/{job_id}/errors/list",
    response_model=ImportErrorListResponse,
    summary="获取导入错误列表",
    dependencies=[Depends(PermissionChecker(["import:read"]))],
)
async def get_import_errors(
    db: Session = Depends(get_db),
    params: PaginatedParams = Depends(),
    *,
    job_id: int = Path(..., description="导入任务ID"),
):
    job = import_export_service.get_job(db, job_id=job_id)

    stmt = select(ImportError).where(ImportError.job_id == job_id)
    count_stmt = select(func.count()).where(ImportError.job_id == job_id)

    total = db.execute(count_stmt).scalar_one() or 0

    if params.sort_by and hasattr(ImportError, params.sort_by):
        sort_column = getattr(ImportError, params.sort_by)
        if params.sort_order == "desc":
            stmt = stmt.order_by(sort_column.desc())
        else:
            stmt = stmt.order_by(sort_column.asc())
    else:
        stmt = stmt.order_by(ImportError.row_number.asc())

    offset = (params.page - 1) * params.page_size
    stmt = stmt.offset(offset).limit(params.page_size)

    errors = db.execute(stmt).scalars().all()
    items = [ImportErrorListItem.model_validate(error) for error in errors]

    total_pages = (total + params.page_size - 1) // params.page_size

    return ImportErrorListResponse(
        data=PaginatedResponse(
            items=items,
            page=params.page,
            page_size=params.page_size,
            total=total,
            total_pages=total_pages,
            has_next=params.page < total_pages,
            has_prev=params.page > 1,
        )
    )


@router.get(
    "/export/skus",
    summary="按条件导出SKU",
    dependencies=[Depends(PermissionChecker(["sku:read", "sku:export"]))],
)
async def export_skus(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    category: Optional[str] = Query(default=None, description="品类过滤"),
    warehouse_id: Optional[int] = Query(default=None, description="仓库ID过滤"),
    stock_status: Optional[str] = Query(default=None, description="库存状态: IN_STOCK/LOW_STOCK/OUT_OF_STOCK"),
    product_id: Optional[int] = Query(default=None, description="商品ID过滤"),
    sku_code: Optional[str] = Query(default=None, description="SKU编码模糊匹配"),
    status: Optional[str] = Query(default=None, description="SKU状态过滤"),
):
    filters = SkuExportFilter(
        category=category,
        warehouse_id=warehouse_id,
        stock_status=stock_status,
        product_id=product_id,
        sku_code=sku_code,
        status=status,
    )

    file_path, total_count = import_export_service.export_skus(db, filters=filters)

    audit_logger = AuditLogger(db)
    audit_logger.log(
        user_id=current_user.id,
        action="export_skus",
        resource_type="sku",
        new_value={
            "filters": filters.model_dump(),
            "total_count": total_count,
        },
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )
    db.commit()

    file_name = os.path.basename(file_path)
    return FileResponse(
        file_path,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        filename=file_name,
    )


@router.get(
    "/export/products",
    summary="导出商品及其SKU明细",
    dependencies=[Depends(PermissionChecker(["product:read", "product:export"]))],
)
async def export_products(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    category: Optional[str] = Query(default=None, description="品类过滤"),
    brand: Optional[str] = Query(default=None, description="品牌过滤"),
    status: Optional[str] = Query(default=None, description="商品状态过滤"),
    include_skus: bool = Query(default=True, description="是否包含SKU明细"),
):
    filters = ProductExportFilter(
        category=category,
        brand=brand,
        status=status,
        include_skus=include_skus,
    )

    file_path, total_count = import_export_service.export_products(db, filters=filters)

    audit_logger = AuditLogger(db)
    audit_logger.log(
        user_id=current_user.id,
        action="export_products",
        resource_type="product",
        new_value={
            "filters": filters.model_dump(),
            "total_count": total_count,
        },
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )
    db.commit()

    file_name = os.path.basename(file_path)
    return FileResponse(
        file_path,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        filename=file_name,
    )
