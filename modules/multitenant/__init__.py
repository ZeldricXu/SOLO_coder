from .models import (
    Tenant,
    TenantConfig,
    TenantQuota,
    TenantMember,
    TenantStatus,
    TenantTier,
    TenantCreate,
    TenantResponse,
    TenantConfigCreate,
    TenantConfigResponse,
    TenantQuotaCreate,
    TenantQuotaResponse,
    TenantMemberCreate,
    TenantMemberResponse,
)
from .service import (
    TenantService,
    TenantConfigService,
    TenantQuotaService,
    TenantMemberService,
    MultiTenantService,
)
from .router import router

__all__ = [
    "Tenant",
    "TenantConfig",
    "TenantQuota",
    "TenantMember",
    "TenantStatus",
    "TenantTier",
    "TenantCreate",
    "TenantResponse",
    "TenantConfigCreate",
    "TenantConfigResponse",
    "TenantQuotaCreate",
    "TenantQuotaResponse",
    "TenantMemberCreate",
    "TenantMemberResponse",
    "TenantService",
    "TenantConfigService",
    "TenantQuotaService",
    "TenantMemberService",
    "MultiTenantService",
    "router",
]


class MultiTenantModule:
    name = "multitenant"
    description = "租户数据隔离、个性化配置与资源配额管理模块"
    router = router

    def __init__(self):
        pass
