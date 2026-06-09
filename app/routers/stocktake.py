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
from app.schemas.stocktake import (
    StocktakePlan,
    StocktakePlanCreate,
    StocktakePlanUpdate,
    StocktakeTask,
    StocktakeTaskCreate,
    StocktakeResult,
    StocktakeResultCreate,
    StocktakeAdjustment,
    StocktakeGenerateRequest,
    StocktakeGenerateResponse,
    CountResultRequest,
    AdjustmentReviewRequest,
    StocktakeDifferenceResponse,
    StocktakePlanStatisticsResponse,
    ABCCategoryAnalysis,
    StocktakeSyncRequest,
    StocktakeSyncResponse,
    StocktakePlanListFilter,
    StocktakeTaskListFilter,
    StocktakeResultListFilter,
    StocktakePlanStatus,
    StocktakeTaskStatus,
    AdjustmentStatus,
)
from app.services.stocktake_service import create_stocktake_service
from app.utils.exceptions import InventoryException

router = APIRouter(prefix="/api/v1/stocktake", tags=["库存盘点管理"])


@router.get("/plans", response_model=APIResponse[PaginatedResponse[StocktakePlan]])
def list_stocktake_plans(
    warehouse_id: Optional[int] = Query(None, description="仓库ID"),
    status: Optional[StocktakePlanStatus] = Query(None, description="计划状态"),
    plan_type: Optional[str] = Query(None, description="计划类型"),
    start_date: Optional[datetime] = Query(None, description="开始日期"),
    end_date: Optional[datetime] = Query(None, description="结束日期"),
    sort_by: str = Query("created_at", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
):
    try:
        stocktake_service = create_stocktake_service(db)

        filters = StocktakePlanListFilter(
            warehouse_id=warehouse_id,
            status=status,
            plan_type=plan_type,
            start_date=start_date,
            end_date=end_date,
        )

        skip = (paginated.page - 1) * paginated.page_size
        plans = stocktake_service.list_plans(
            filters=filters,
            skip=skip,
            limit=paginated.page_size,
            sort_by=sort_by,
            sort_order=sort_order,
        )
        total = stocktake_service.count_plans(filters)
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=plans,
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


@router.get("/plans/statistics", response_model=APIResponse[StocktakePlanStatisticsResponse])
def get_plan_statistics(
    warehouse_id: Optional[int] = Query(None, description="仓库ID"),
    start_date: Optional[datetime] = Query(None, description="开始日期"),
    end_date: Optional[datetime] = Query(None, description="结束日期"),
    db: Session = Depends(get_db),
):
    try:
        stocktake_service = create_stocktake_service(db)
        stats = stocktake_service.get_statistics(
            warehouse_id=warehouse_id,
            start_date=start_date,
            end_date=end_date,
        )
        return APIResponse(data=stats)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/plans/abc-analysis", response_model=APIResponse[list[ABCCategoryAnalysis]])
def get_abc_analysis(
    warehouse_id: int = Query(..., description="仓库ID"),
    db: Session = Depends(get_db),
):
    try:
        stocktake_service = create_stocktake_service(db)
        analysis = stocktake_service.get_abc_analysis(warehouse_id)
        return APIResponse(data=analysis)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/plans/{plan_id}", response_model=APIResponse[StocktakePlan])
def get_plan(
    plan_id: int,
    db: Session = Depends(get_db),
):
    try:
        stocktake_service = create_stocktake_service(db)
        plan = stocktake_service.get_plan(plan_id)
        return APIResponse(data=plan)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/plans", response_model=APIResponse[StocktakePlan])
@audit_logger.log_action(action="CREATE", resource_type="stocktake_plan")
def create_plan(
    plan_data: StocktakePlanCreate,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        stocktake_service = create_stocktake_service(db, current_user_id)
        plan = stocktake_service.create_plan(plan_data)
        return APIResponse(data=plan)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.put("/plans/{plan_id}", response_model=APIResponse[StocktakePlan])
@audit_logger.log_action(action="UPDATE", resource_type="stocktake_plan")
def update_plan(
    plan_id: int,
    plan_data: StocktakePlanUpdate,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        stocktake_service = create_stocktake_service(db, current_user_id)
        plan = stocktake_service.update_plan(plan_id, plan_data)
        return APIResponse(data=plan)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/plans/generate", response_model=APIResponse[StocktakeGenerateResponse])
@audit_logger.log_action(action="GENERATE", resource_type="stocktake_plan")
def generate_plan(
    request: StocktakeGenerateRequest,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        stocktake_service = create_stocktake_service(db, current_user_id)
        result = stocktake_service.generate_plan(request)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/plans/{plan_id}/start", response_model=APIResponse[StocktakePlan])
@audit_logger.log_action(action="START", resource_type="stocktake_plan")
def start_plan(
    plan_id: int,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        stocktake_service = create_stocktake_service(db, current_user_id)
        plan = stocktake_service.start_plan(plan_id)
        return APIResponse(data=plan)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/plans/{plan_id}/complete", response_model=APIResponse[StocktakePlan])
@audit_logger.log_action(action="COMPLETE", resource_type="stocktake_plan")
def complete_plan(
    plan_id: int,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        stocktake_service = create_stocktake_service(db, current_user_id)
        plan = stocktake_service.complete_plan(plan_id)
        return APIResponse(data=plan)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/plans/{plan_id}/tasks", response_model=APIResponse[PaginatedResponse[StocktakeTask]])
def list_plan_tasks(
    plan_id: int,
    status: Optional[StocktakeTaskStatus] = Query(None, description="任务状态"),
    assignee_id: Optional[int] = Query(None, description="分配人ID"),
    sort_by: str = Query("created_at", description="排序字段"),
    sort_order: str = Query("asc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
):
    try:
        stocktake_service = create_stocktake_service(db)

        filters = StocktakeTaskListFilter(
            plan_id=plan_id,
            status=status,
            assignee_id=assignee_id,
        )

        skip = (paginated.page - 1) * paginated.page_size
        tasks = stocktake_service.list_tasks(
            filters=filters,
            skip=skip,
            limit=paginated.page_size,
            sort_by=sort_by,
            sort_order=sort_order,
        )
        total = stocktake_service.count_tasks(filters)
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=tasks,
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


@router.post("/tasks/{task_id}/assign", response_model=APIResponse[StocktakeTask])
@audit_logger.log_action(action="ASSIGN", resource_type="stocktake_task")
def assign_task(
    task_id: int,
    assignee_id: int = Body(..., embed=True),
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        stocktake_service = create_stocktake_service(db, current_user_id)
        task = stocktake_service.assign_task(task_id, assignee_id)
        return APIResponse(data=task)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/tasks/{task_id}/start", response_model=APIResponse[StocktakeTask])
@audit_logger.log_action(action="START", resource_type="stocktake_task")
def start_task(
    task_id: int,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        stocktake_service = create_stocktake_service(db, current_user_id)
        task = stocktake_service.start_task(task_id)
        return APIResponse(data=task)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/tasks/{task_id}/complete", response_model=APIResponse[StocktakeTask])
@audit_logger.log_action(action="COMPLETE", resource_type="stocktake_task")
def complete_task(
    task_id: int,
    remarks: Optional[str] = Body(None, embed=True),
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        stocktake_service = create_stocktake_service(db, current_user_id)
        task = stocktake_service.complete_task(task_id, remarks)
        return APIResponse(data=task)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/tasks/{task_id}/count", response_model=APIResponse[StocktakeResult])
@audit_logger.log_action(action="COUNT", resource_type="stocktake_result")
def count_result(
    task_id: int,
    request: CountResultRequest,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        stocktake_service = create_stocktake_service(db, current_user_id)
        result = stocktake_service.count_result(task_id, request)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/plans/{plan_id}/results", response_model=APIResponse[PaginatedResponse[StocktakeResult]])
def list_plan_results(
    plan_id: int,
    has_difference: Optional[bool] = Query(None, description="是否有差异"),
    sku_id: Optional[int] = Query(None, description="SKU ID"),
    sort_by: str = Query("created_at", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
):
    try:
        stocktake_service = create_stocktake_service(db)

        filters = StocktakeResultListFilter(
            plan_id=plan_id,
            has_difference=has_difference,
            sku_id=sku_id,
        )

        skip = (paginated.page - 1) * paginated.page_size
        results = stocktake_service.list_results(
            filters=filters,
            skip=skip,
            limit=paginated.page_size,
            sort_by=sort_by,
            sort_order=sort_order,
        )
        total = stocktake_service.count_results(filters)
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=results,
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


@router.get("/plans/{plan_id}/differences", response_model=APIResponse[list[StocktakeDifferenceResponse]])
def list_plan_differences(
    plan_id: int,
    min_difference: Optional[int] = Query(None, description="最小差异数量"),
    db: Session = Depends(get_db),
):
    try:
        stocktake_service = create_stocktake_service(db)
        differences = stocktake_service.list_differences(
            plan_id, min_difference
        )
        return APIResponse(data=differences)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/adjustments", response_model=APIResponse[PaginatedResponse[StocktakeAdjustment]])
def list_adjustments(
    status: Optional[AdjustmentStatus] = Query(None, description="调整单状态"),
    plan_id: Optional[int] = Query(None, description="盘点计划ID"),
    sort_by: str = Query("created_at", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
):
    try:
        stocktake_service = create_stocktake_service(db)
        skip = (paginated.page - 1) * paginated.page_size
        adjustments = stocktake_service.list_adjustments(
            status=status,
            plan_id=plan_id,
            skip=skip,
            limit=paginated.page_size,
            sort_by=sort_by,
            sort_order=sort_order,
        )
        total = stocktake_service.count_adjustments(
            status=status, plan_id=plan_id
        )
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=adjustments,
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


@router.get("/adjustments/{adjustment_id}", response_model=APIResponse[StocktakeAdjustment])
def get_adjustment(
    adjustment_id: int,
    db: Session = Depends(get_db),
):
    try:
        stocktake_service = create_stocktake_service(db)
        adjustment = stocktake_service.get_adjustment(adjustment_id)
        return APIResponse(data=adjustment)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/adjustments/{adjustment_id}/approve", response_model=APIResponse[StocktakeAdjustment])
@audit_logger.log_action(action="APPROVE", resource_type="stocktake_adjustment")
def approve_adjustment(
    adjustment_id: int,
    request: Optional[AdjustmentReviewRequest] = Body(None),
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        stocktake_service = create_stocktake_service(db, current_user_id)
        adjustment = stocktake_service.approve_adjustment(
            adjustment_id, request
        )
        return APIResponse(data=adjustment)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/adjustments/{adjustment_id}/reject", response_model=APIResponse[StocktakeAdjustment])
@audit_logger.log_action(action="REJECT", resource_type="stocktake_adjustment")
def reject_adjustment(
    adjustment_id: int,
    request: Optional[AdjustmentReviewRequest] = Body(None),
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        stocktake_service = create_stocktake_service(db, current_user_id)
        adjustment = stocktake_service.reject_adjustment(
            adjustment_id, request
        )
        return APIResponse(data=adjustment)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/adjustments/{adjustment_id}/execute", response_model=APIResponse[StocktakeAdjustment])
@audit_logger.log_action(action="EXECUTE", resource_type="stocktake_adjustment")
def execute_adjustment(
    adjustment_id: int,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        stocktake_service = create_stocktake_service(db, current_user_id)
        adjustment = stocktake_service.execute_adjustment(adjustment_id)
        return APIResponse(data=adjustment)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/sync", response_model=APIResponse[StocktakeSyncResponse])
def sync_stocktake_data(
    request: StocktakeSyncRequest,
    db: Session = Depends(get_db),
    current_user_id: int = Depends(lambda: 1),
):
    try:
        stocktake_service = create_stocktake_service(db, current_user_id)
        result = stocktake_service.sync_data(request)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e
