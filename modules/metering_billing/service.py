from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple

from sqlalchemy import select, and_, func
from sqlalchemy.ext.asyncio import AsyncSession

from core.exceptions import ValidationError, NotFoundError, ConflictError
from core.utils import validate_params, utc_now
from .models import (
    ResourceUsageRecord,
    UsageRecordCreate,
    UsageRecordResponse,
    PricingPlan,
    PricingPlanCreate,
    PricingPlanResponse,
    Bill,
    BillCreate,
    BillResponse,
    BillDetailResponse,
    BillingItem,
    BillingItemResponse,
    ResourceType,
    BillingCycle,
    BillingStatus,
    CollectionStatus,
)


class MeteringService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def collect_usage(self, usage_data: UsageRecordCreate) -> UsageRecordResponse:
        validation_rules = {
            "tenant_id": lambda x: x is not None and len(x) > 0,
            "quantity": lambda x: x is not None and x >= 0,
            "unit": lambda x: x is not None and len(x) > 0,
        }
        validate_params(usage_data.model_dump(), validation_rules)

        record = ResourceUsageRecord(
            **usage_data.model_dump(),
            collected_at=utc_now(),
            status=CollectionStatus.COMPLETED,
        )
        self.db.add(record)
        await self.db.flush()

        return UsageRecordResponse.model_validate(record)

    async def batch_collect_usage(
        self, usage_records: List[UsageRecordCreate]
    ) -> List[UsageRecordResponse]:
        results = []
        for record_data in usage_records:
            result = await self.collect_usage(record_data)
            results.append(result)
        return results

    async def get_usage_records(
        self,
        tenant_id: str,
        resource_type: Optional[ResourceType] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[UsageRecordResponse]:
        query = select(ResourceUsageRecord).where(
            ResourceUsageRecord.tenant_id == tenant_id
        )
        conditions = []

        if resource_type:
            conditions.append(ResourceUsageRecord.resource_type == resource_type)
        if start_time:
            conditions.append(ResourceUsageRecord.collected_at >= start_time)
        if end_time:
            conditions.append(ResourceUsageRecord.collected_at <= end_time)

        if conditions:
            query = query.where(and_(*conditions))

        query = query.order_by(ResourceUsageRecord.collected_at.desc()).limit(limit).offset(offset)
        result = await self.db.execute(query)
        records = result.scalars().all()

        return [UsageRecordResponse.model_validate(r) for r in records]

    async def get_usage_summary(
        self,
        tenant_id: str,
        resource_type: Optional[ResourceType] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
    ) -> Dict[str, Any]:
        query = select(
            ResourceUsageRecord.resource_type,
            func.sum(ResourceUsageRecord.quantity).label("total_usage"),
            func.count(ResourceUsageRecord.record_id).label("record_count"),
        ).where(ResourceUsageRecord.tenant_id == tenant_id)

        conditions = []
        if resource_type:
            conditions.append(ResourceUsageRecord.resource_type == resource_type)
        if start_time:
            conditions.append(ResourceUsageRecord.collected_at >= start_time)
        if end_time:
            conditions.append(ResourceUsageRecord.collected_at <= end_time)

        if conditions:
            query = query.where(and_(*conditions))

        query = query.group_by(ResourceUsageRecord.resource_type)
        result = await self.db.execute(query)
        summaries = result.all()

        return {
            "tenant_id": tenant_id,
            "period": {"start": start_time, "end": end_time},
            "usage_by_type": {
                row.resource_type.value: {
                    "total_usage": row.total_usage,
                    "record_count": row.record_count,
                }
                for row in summaries
            },
        }


class PricingService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_pricing_plan(
        self, plan_data: PricingPlanCreate
    ) -> PricingPlanResponse:
        validation_rules = {
            "name": lambda x: x is not None and len(x.strip()) > 0,
            "unit_price": lambda x: x is not None and x >= 0,
            "unit": lambda x: x is not None and len(x) > 0,
        }
        validate_params(plan_data.model_dump(), validation_rules)

        plan = PricingPlan(**plan_data.model_dump())
        self.db.add(plan)
        await self.db.flush()

        return PricingPlanResponse.model_validate(plan)

    async def get_pricing_plan(self, plan_id: str) -> PricingPlanResponse:
        query = select(PricingPlan).where(PricingPlan.plan_id == plan_id)
        result = await self.db.execute(query)
        plan = result.scalar_one_or_none()

        if not plan:
            raise NotFoundError(f"定价方案 {plan_id} 不存在")

        return PricingPlanResponse.model_validate(plan)

    async def get_active_pricing_plans(
        self, resource_type: Optional[ResourceType] = None
    ) -> List[PricingPlanResponse]:
        query = select(PricingPlan).where(PricingPlan.is_active == True)
        if resource_type:
            query = query.where(PricingPlan.resource_type == resource_type)

        result = await self.db.execute(query)
        plans = result.scalars().all()

        return [PricingPlanResponse.model_validate(p) for p in plans]

    def calculate_tiered_price(
        self,
        quantity: float,
        unit_price: float,
        tiered_pricing: Dict[str, Any],
    ) -> float:
        if not tiered_pricing or "tiers" not in tiered_pricing:
            return quantity * unit_price

        tiers = sorted(tiered_pricing["tiers"], key=lambda t: t.get("from", 0))
        remaining = quantity
        total_cost = 0.0

        for tier in tiers:
            tier_from = tier.get("from", 0)
            tier_to = tier.get("to", float("inf"))
            tier_price = tier.get("price", unit_price)

            if remaining <= 0:
                break

            tier_quantity = min(remaining, max(0, tier_to - tier_from))
            if tier_quantity > 0:
                total_cost += tier_quantity * tier_price
                remaining -= tier_quantity

        if remaining > 0:
            total_cost += remaining * unit_price

        return total_cost


class BillingService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.metering = MeteringService(db)
        self.pricing = PricingService(db)

    def _generate_invoice_number(self, tenant_id: str, period: datetime) -> str:
        period_str = period.strftime("%Y%m")
        return f"INV-{tenant_id}-{period_str}-{utc_now().strftime('%H%M%S')}"

    async def calculate_bill_amount(
        self,
        tenant_id: str,
        resource_type: ResourceType,
        period_start: datetime,
        period_end: datetime,
    ) -> Tuple[float, List[Dict[str, Any]]]:
        usage_summary = await self.metering.get_usage_summary(
            tenant_id, resource_type, period_start, period_end
        )

        plans = await self.pricing.get_active_pricing_plans(resource_type)
        plan_map = {p.resource_type: p for p in plans}

        billing_items = []
        total_amount = 0.0

        for r_type, usage in usage_summary.get("usage_by_type", {}).items():
            plan = plan_map.get(ResourceType(r_type))
            if not plan:
                continue

            quantity = usage["total_usage"]
            item_total = self.pricing.calculate_tiered_price(
                quantity, plan.unit_price, plan.tiered_pricing
            )

            billing_items.append(
                {
                    "resource_type": r_type,
                    "description": f"{r_type} 资源使用费",
                    "quantity": quantity,
                    "unit_price": plan.unit_price,
                    "unit": plan.unit,
                    "subtotal": item_total,
                    "pricing_plan_id": plan.plan_id,
                }
            )
            total_amount += item_total

        return total_amount, billing_items

    async def generate_bill(self, bill_data: BillCreate) -> BillDetailResponse:
        validation_rules = {
            "tenant_id": lambda x: x is not None and len(x) > 0,
            "period_start": lambda x: x is not None,
            "period_end": lambda x: x is not None and x > bill_data.period_start,
        }
        validate_params(bill_data.model_dump(), validation_rules)

        query = select(Bill).where(
            and_(
                Bill.tenant_id == bill_data.tenant_id,
                Bill.billing_cycle == bill_data.billing_cycle,
                Bill.period_start == bill_data.period_start,
                Bill.period_end == bill_data.period_end,
                Bill.status != BillingStatus.CANCELLED,
            )
        )
        result = await self.db.execute(query)
        if result.scalar_one_or_none():
            raise ConflictError("该周期账单已存在")

        total_amount, billing_items_data = await self.calculate_bill_amount(
            bill_data.tenant_id,
            ResourceType.STORAGE,
            bill_data.period_start,
            bill_data.period_end,
        )

        for r_type in ResourceType:
            if r_type == ResourceType.STORAGE:
                continue
            amt, items = await self.calculate_bill_amount(
                bill_data.tenant_id,
                r_type,
                bill_data.period_start,
                bill_data.period_end,
            )
            total_amount += amt
            billing_items_data.extend(items)

        invoice_number = self._generate_invoice_number(
            bill_data.tenant_id, bill_data.period_start
        )

        bill = Bill(
            **bill_data.model_dump(),
            invoice_number=invoice_number,
            total_amount=total_amount,
            status=BillingStatus.GENERATED,
            issued_at=utc_now(),
        )
        self.db.add(bill)
        await self.db.flush()

        billing_items = []
        for item_data in billing_items_data:
            if item_data["quantity"] > 0:
                item = BillingItem(
                    bill_id=bill.bill_id,
                    **item_data,
                )
                self.db.add(item)
                billing_items.append(item)

        await self.db.flush()

        return BillDetailResponse(
            **bill.__dict__,
            items=[BillingItemResponse.model_validate(item) for item in billing_items],
        )

    async def get_bill(self, bill_id: str, tenant_id: Optional[str] = None) -> BillDetailResponse:
        query = select(Bill).where(Bill.bill_id == bill_id)
        if tenant_id:
            query = query.where(Bill.tenant_id == tenant_id)

        result = await self.db.execute(query)
        bill = result.scalar_one_or_none()

        if not bill:
            raise NotFoundError(f"账单 {bill_id} 不存在")

        items_query = select(BillingItem).where(BillingItem.bill_id == bill_id)
        items_result = await self.db.execute(items_query)
        items = items_result.scalars().all()

        return BillDetailResponse(
            **bill.__dict__,
            items=[BillingItemResponse.model_validate(item) for item in items],
        )

    async def list_bills(
        self,
        tenant_id: Optional[str] = None,
        status: Optional[BillingStatus] = None,
        limit: int = 50,
        offset: int = 0,
    ) -> List[BillResponse]:
        query = select(Bill)
        conditions = []

        if tenant_id:
            conditions.append(Bill.tenant_id == tenant_id)
        if status:
            conditions.append(Bill.status == status)

        if conditions:
            query = query.where(and_(*conditions))

        query = query.order_by(Bill.created_at.desc()).limit(limit).offset(offset)
        result = await self.db.execute(query)
        bills = result.scalars().all()

        return [BillResponse.model_validate(b) for b in bills]

    async def update_bill_status(
        self, bill_id: str, new_status: BillingStatus, paid_amount: Optional[float] = None
    ) -> BillResponse:
        query = select(Bill).where(Bill.bill_id == bill_id)
        result = await self.db.execute(query)
        bill = result.scalar_one_or_none()

        if not bill:
            raise NotFoundError(f"账单 {bill_id} 不存在")

        if new_status == BillingStatus.PAID:
            bill.paid_at = utc_now()
            if paid_amount is not None:
                bill.paid_amount = paid_amount
            else:
                bill.paid_amount = bill.total_amount

        bill.status = new_status
        self.db.add(bill)
        await self.db.flush()

        return BillResponse.model_validate(bill)
