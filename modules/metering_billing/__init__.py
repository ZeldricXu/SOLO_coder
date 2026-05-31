from .models import (
    ResourceUsageRecord,
    PricingPlan,
    BillingItem,
    Bill,
    ResourceType,
    BillingCycle,
    BillingStatus,
    CollectionStatus,
    UsageRecordCreate,
    UsageRecordResponse,
    PricingPlanCreate,
    PricingPlanResponse,
    BillingItemResponse,
    BillCreate,
    BillResponse,
    BillDetailResponse,
)
from .service import MeteringService, PricingService, BillingService
from .router import router

__all__ = [
    "ResourceUsageRecord",
    "PricingPlan",
    "BillingItem",
    "Bill",
    "ResourceType",
    "BillingCycle",
    "BillingStatus",
    "CollectionStatus",
    "UsageRecordCreate",
    "UsageRecordResponse",
    "PricingPlanCreate",
    "PricingPlanResponse",
    "BillingItemResponse",
    "BillCreate",
    "BillResponse",
    "BillDetailResponse",
    "MeteringService",
    "PricingService",
    "BillingService",
    "router",
]


class MeteringBillingModule:
    name = "metering_billing"
    description = "租户资源用量采集、按量计费与账单生成模块"
    router = router

    def __init__(self):
        pass
