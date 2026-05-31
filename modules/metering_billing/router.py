from datetime import datetime
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_db
from .models import (
    UsageRecordCreate,
    UsageRecordResponse,
    PricingPlanCreate,
    PricingPlanResponse,
    BillCreate,
    BillResponse,
    BillDetailResponse,
    ResourceType,
    BillingStatus,
)
from .service import MeteringService, PricingService, BillingService

router = APIRouter(prefix="/metering", tags=["用量计量与计费"])


@router.post("/usage", response_model=Dict[str, Any], status_code=201)
async def collect_usage(
    usage_data: UsageRecordCreate,
    db: AsyncSession = Depends(get_db),
):
    service = MeteringService(db)
    record = await service.collect_usage(usage_data)
    return {
        "code": 201,
        "data": record.model_dump(),
        "message": "用量采集成功",
    }


@router.post("/usage/batch", response_model=Dict[str, Any], status_code=201)
async def batch_collect_usage(
    usage_records: List[UsageRecordCreate],
    db: AsyncSession = Depends(get_db),
):
    service = MeteringService(db)
    records = await service.batch_collect_usage(usage_records)
    return {
        "code": 201,
        "data": [r.model_dump() for r in records],
        "total": len(records),
        "message": "批量用量采集成功",
    }


@router.get("/usage", response_model=Dict[str, Any])
async def get_usage_records(
    tenant_id: str,
    resource_type: Optional[ResourceType] = Query(None),
    start_time: Optional[datetime] = Query(None),
    end_time: Optional[datetime] = Query(None),
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
    db: AsyncSession = Depends(get_db),
):
    service = MeteringService(db)
    records = await service.get_usage_records(
        tenant_id, resource_type, start_time, end_time, limit, offset
    )
    return {
        "code": 200,
        "data": [r.model_dump() for r in records],
        "total": len(records),
        "message": "查询成功",
    }


@router.get("/usage/summary", response_model=Dict[str, Any])
async def get_usage_summary(
    tenant_id: str,
    resource_type: Optional[ResourceType] = Query(None),
    start_time: Optional[datetime] = Query(None),
    end_time: Optional[datetime] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = MeteringService(db)
    summary = await service.get_usage_summary(
        tenant_id, resource_type, start_time, end_time
    )
    return {
        "code": 200,
        "data": summary,
        "message": "查询成功",
    }


@router.post("/pricing/plans", response_model=Dict[str, Any], status_code=201)
async def create_pricing_plan(
    plan_data: PricingPlanCreate,
    db: AsyncSession = Depends(get_db),
):
    service = PricingService(db)
    plan = await service.create_pricing_plan(plan_data)
    return {
        "code": 201,
        "data": plan.model_dump(),
        "message": "定价方案创建成功",
    }


@router.get("/pricing/plans/{plan_id}", response_model=Dict[str, Any])
async def get_pricing_plan(
    plan_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = PricingService(db)
    plan = await service.get_pricing_plan(plan_id)
    return {
        "code": 200,
        "data": plan.model_dump(),
        "message": "查询成功",
    }


@router.get("/pricing/plans", response_model=Dict[str, Any])
async def get_active_pricing_plans(
    resource_type: Optional[ResourceType] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = PricingService(db)
    plans = await service.get_active_pricing_plans(resource_type)
    return {
        "code": 200,
        "data": [p.model_dump() for p in plans],
        "total": len(plans),
        "message": "查询成功",
    }


@router.post("/bills", response_model=Dict[str, Any], status_code=201)
async def generate_bill(
    bill_data: BillCreate,
    db: AsyncSession = Depends(get_db),
):
    service = BillingService(db)
    bill = await service.generate_bill(bill_data)
    return {
        "code": 201,
        "data": bill.model_dump(),
        "message": "账单生成成功",
    }


@router.get("/bills/{bill_id}", response_model=Dict[str, Any])
async def get_bill(
    bill_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = BillingService(db)
    bill = await service.get_bill(bill_id, tenant_id)
    return {
        "code": 200,
        "data": bill.model_dump(),
        "message": "查询成功",
    }


@router.get("/bills", response_model=Dict[str, Any])
async def list_bills(
    tenant_id: Optional[str] = Query(None),
    status: Optional[BillingStatus] = Query(None),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    db: AsyncSession = Depends(get_db),
):
    service = BillingService(db)
    bills = await service.list_bills(tenant_id, status, limit, offset)
    return {
        "code": 200,
        "data": [b.model_dump() for b in bills],
        "total": len(bills),
        "message": "查询成功",
    }


@router.patch("/bills/{bill_id}/status", response_model=Dict[str, Any])
async def update_bill_status(
    bill_id: str,
    status: BillingStatus,
    paid_amount: Optional[float] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = BillingService(db)
    bill = await service.update_bill_status(bill_id, status, paid_amount)
    return {
        "code": 200,
        "data": bill.model_dump(),
        "message": "账单状态更新成功",
    }
