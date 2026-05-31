from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional

from sqlalchemy import select, and_, func
from sqlalchemy.ext.asyncio import AsyncSession

from core.exceptions import ValidationError, NotFoundError, ConflictError, PermissionDeniedError
from core.utils import validate_params, utc_now
from .models import (
    Tenant,
    TenantCreate,
    TenantResponse,
    TenantConfig,
    TenantConfigCreate,
    TenantConfigResponse,
    TenantQuota,
    TenantQuotaCreate,
    TenantQuotaResponse,
    TenantMember,
    TenantMemberCreate,
    TenantMemberResponse,
    TenantStatus,
    TenantTier,
)


class TenantService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_tenant(self, tenant_data: TenantCreate) -> TenantResponse:
        validation_rules = {
            "name": lambda x: x is not None and len(x.strip()) > 0,
            "contact_email": lambda x: x is not None and "@" in x,
        }
        validate_params(tenant_data.model_dump(), validation_rules)

        query = select(Tenant).where(
            and_(Tenant.name == tenant_data.name, Tenant.is_deleted == False)
        )
        result = await self.db.execute(query)
        if result.scalar_one_or_none():
            raise ConflictError(f"租户名称 {tenant_data.name} 已存在")

        tenant_data_dict = tenant_data.model_dump()
        if tenant_data_dict.get("display_name") is None:
            tenant_data_dict["display_name"] = tenant_data.name
        tenant = Tenant(**tenant_data_dict)
        self.db.add(tenant)
        await self.db.flush()

        await self._initialize_tenant_quotas(tenant)
        await self._initialize_tenant_configs(tenant)

        return TenantResponse.model_validate(tenant)

    async def _initialize_tenant_quotas(self, tenant: Tenant) -> None:
        default_quotas = {
            TenantTier.FREE: [
                ("storage", 10.0, "GB"),
                ("api_calls", 10000, "calls"),
                ("users", 5, "users"),
                ("tickets", 100, "tickets"),
            ],
            TenantTier.BASIC: [
                ("storage", 100.0, "GB"),
                ("api_calls", 100000, "calls"),
                ("users", 50, "users"),
                ("tickets", 1000, "tickets"),
            ],
            TenantTier.PROFESSIONAL: [
                ("storage", 1000.0, "GB"),
                ("api_calls", 1000000, "calls"),
                ("users", 500, "users"),
                ("tickets", 10000, "tickets"),
            ],
            TenantTier.ENTERPRISE: [
                ("storage", 10000.0, "GB"),
                ("api_calls", 10000000, "calls"),
                ("users", 5000, "users"),
                ("tickets", 100000, "tickets"),
            ],
        }

        quotas = default_quotas.get(tenant.tier, default_quotas[TenantTier.BASIC])
        for resource_type, limit, unit in quotas:
            quota = TenantQuota(
                tenant_id=tenant.tenant_id,
                resource_type=resource_type,
                limit=limit,
                unit=unit,
            )
            self.db.add(quota)
        await self.db.flush()

    async def _initialize_tenant_configs(self, tenant: Tenant) -> None:
        default_configs = [
            ("appearance", "theme", {"primary_color": "#3b82f6", "dark_mode": False}),
            ("features", "enabled_modules", {"tickets": True, "billing": True, "sla": True}),
            ("notifications", "channels", {"email": True, "sms": False, "in_app": True}),
            ("security", "password_policy", {"min_length": 8, "require_uppercase": True}),
        ]

        for namespace, key, value in default_configs:
            config = TenantConfig(
                tenant_id=tenant.tenant_id,
                namespace=namespace,
                key=key,
                value=value,
                is_system=True,
            )
            self.db.add(config)
        await self.db.flush()

    async def get_tenant(self, tenant_id: str) -> TenantResponse:
        query = select(Tenant).where(
            and_(Tenant.tenant_id == tenant_id, Tenant.is_deleted == False)
        )
        result = await self.db.execute(query)
        tenant = result.scalar_one_or_none()

        if not tenant:
            raise NotFoundError(f"租户 {tenant_id} 不存在")

        return TenantResponse.model_validate(tenant)

    async def list_tenants(
        self,
        status: Optional[TenantStatus] = None,
        tier: Optional[TenantTier] = None,
        limit: int = 50,
        offset: int = 0,
    ) -> List[TenantResponse]:
        query = select(Tenant).where(Tenant.is_deleted == False)
        if status:
            query = query.where(Tenant.status == status)
        if tier:
            query = query.where(Tenant.tier == tier)

        query = query.order_by(Tenant.created_at.desc()).limit(limit).offset(offset)
        result = await self.db.execute(query)
        tenants = result.scalars().all()

        return [TenantResponse.model_validate(t) for t in tenants]

    async def update_tenant_status(
        self, tenant_id: str, new_status: TenantStatus
    ) -> TenantResponse:
        tenant = await self._get_tenant_raw(tenant_id)
        tenant.status = new_status
        self.db.add(tenant)
        await self.db.flush()
        return TenantResponse.model_validate(tenant)

    async def _get_tenant_raw(self, tenant_id: str) -> Tenant:
        query = select(Tenant).where(
            and_(Tenant.tenant_id == tenant_id, Tenant.is_deleted == False)
        )
        result = await self.db.execute(query)
        tenant = result.scalar_one_or_none()
        if not tenant:
            raise NotFoundError(f"租户 {tenant_id} 不存在")
        return tenant

    async def delete_tenant(self, tenant_id: str) -> None:
        tenant = await self._get_tenant_raw(tenant_id)
        tenant.is_deleted = True
        tenant.deleted_at = utc_now()
        tenant.status = TenantStatus.INACTIVE
        self.db.add(tenant)
        await self.db.flush()


class TenantConfigService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def set_config(self, config_data: TenantConfigCreate) -> TenantConfigResponse:
        validation_rules = {
            "tenant_id": lambda x: x is not None and len(x) > 0,
            "namespace": lambda x: x is not None and len(x) > 0,
            "key": lambda x: x is not None and len(x) > 0,
        }
        validate_params(config_data.model_dump(), validation_rules)

        query = select(TenantConfig).where(
            and_(
                TenantConfig.tenant_id == config_data.tenant_id,
                TenantConfig.namespace == config_data.namespace,
                TenantConfig.key == config_data.key,
            )
        )
        result = await self.db.execute(query)
        existing = result.scalar_one_or_none()

        if existing:
            if not existing.is_overridable:
                raise PermissionDeniedError("系统配置不允许覆盖")
            existing.value = config_data.value
            existing.value_type = config_data.value_type
            existing.is_encrypted = config_data.is_encrypted
            existing.is_overridable = config_data.is_overridable
            config = existing
        else:
            config = TenantConfig(**config_data.model_dump())
            self.db.add(config)

        await self.db.flush()
        return TenantConfigResponse.model_validate(config)

    async def get_config(
        self, tenant_id: str, namespace: str, key: str
    ) -> TenantConfigResponse:
        query = select(TenantConfig).where(
            and_(
                TenantConfig.tenant_id == tenant_id,
                TenantConfig.namespace == namespace,
                TenantConfig.key == key,
            )
        )
        result = await self.db.execute(query)
        config = result.scalar_one_or_none()

        if not config:
            raise NotFoundError(f"配置 {namespace}.{key} 不存在")

        return TenantConfigResponse.model_validate(config)

    async def get_namespace_configs(
        self, tenant_id: str, namespace: str
    ) -> List[TenantConfigResponse]:
        query = select(TenantConfig).where(
            and_(
                TenantConfig.tenant_id == tenant_id,
                TenantConfig.namespace == namespace,
            )
        )
        result = await self.db.execute(query)
        configs = result.scalars().all()

        return [TenantConfigResponse.model_validate(c) for c in configs]


class TenantQuotaService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_quota(self, quota_data: TenantQuotaCreate) -> TenantQuotaResponse:
        validation_rules = {
            "tenant_id": lambda x: x is not None and len(x) > 0,
            "resource_type": lambda x: x is not None and len(x) > 0,
            "limit": lambda x: x is not None and x >= 0,
        }
        validate_params(quota_data.model_dump(), validation_rules)

        quota = TenantQuota(**quota_data.model_dump())
        self.db.add(quota)
        await self.db.flush()

        return self._build_quota_response(quota)

    def _build_quota_response(self, quota: TenantQuota) -> TenantQuotaResponse:
        remaining = max(0, quota.limit - quota.used)
        usage_percent = (quota.used / quota.limit * 100) if quota.limit > 0 else 0.0

        return TenantQuotaResponse(
            quota_id=quota.quota_id,
            tenant_id=quota.tenant_id,
            resource_type=quota.resource_type,
            limit=quota.limit,
            used=quota.used,
            remaining=remaining,
            usage_percent=round(usage_percent, 2),
            warning_threshold=quota.warning_threshold,
            unit=quota.unit,
            reset_period=quota.reset_period,
            is_hard_limit=quota.is_hard_limit,
            last_reset_at=quota.last_reset_at,
        )

    async def check_and_consume_quota(
        self, tenant_id: str, resource_type: str, amount: float = 1.0
    ) -> TenantQuotaResponse:
        query = select(TenantQuota).where(
            and_(
                TenantQuota.tenant_id == tenant_id,
                TenantQuota.resource_type == resource_type,
            )
        )
        result = await self.db.execute(query)
        quota = result.scalar_one_or_none()

        if not quota:
            raise NotFoundError(f"配额 {resource_type} 不存在")

        new_usage = quota.used + amount
        usage_percent = (new_usage / quota.limit * 100) if quota.limit > 0 else 0.0

        if quota.is_hard_limit and new_usage > quota.limit:
            raise ValidationError(
                f"配额不足: {resource_type} 已使用 {quota.used}{quota.unit}, 限制 {quota.limit}{quota.unit}"
            )

        quota.used = new_usage
        self.db.add(quota)
        await self.db.flush()

        return self._build_quota_response(quota)

    async def get_tenant_quotas(
        self, tenant_id: str
    ) -> List[TenantQuotaResponse]:
        query = select(TenantQuota).where(TenantQuota.tenant_id == tenant_id)
        result = await self.db.execute(query)
        quotas = result.scalars().all()

        return [self._build_quota_response(q) for q in quotas]

    async def reset_quota(self, quota_id: str) -> TenantQuotaResponse:
        query = select(TenantQuota).where(TenantQuota.quota_id == quota_id)
        result = await self.db.execute(query)
        quota = result.scalar_one_or_none()

        if not quota:
            raise NotFoundError(f"配额 {quota_id} 不存在")

        quota.used = 0.0
        quota.last_reset_at = utc_now()
        self.db.add(quota)
        await self.db.flush()

        return self._build_quota_response(quota)


class TenantMemberService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def add_member(self, member_data: TenantMemberCreate) -> TenantMemberResponse:
        validation_rules = {
            "tenant_id": lambda x: x is not None and len(x) > 0,
            "user_id": lambda x: x is not None and len(x) > 0,
        }
        validate_params(member_data.model_dump(), validation_rules)

        query = select(TenantMember).where(
            and_(
                TenantMember.tenant_id == member_data.tenant_id,
                TenantMember.user_id == member_data.user_id,
            )
        )
        result = await self.db.execute(query)
        if result.scalar_one_or_none():
            raise ConflictError("用户已是该租户成员")

        member = TenantMember(**member_data.model_dump())
        self.db.add(member)
        await self.db.flush()

        return TenantMemberResponse.model_validate(member)

    async def get_tenant_members(self, tenant_id: str) -> List[TenantMemberResponse]:
        query = select(TenantMember).where(
            and_(TenantMember.tenant_id == tenant_id, TenantMember.is_active == True)
        )
        result = await self.db.execute(query)
        members = result.scalars().all()

        return [TenantMemberResponse.model_validate(m) for m in members]

    async def check_tenant_access(
        self, user_id: str, tenant_id: str, required_permission: Optional[str] = None
    ) -> bool:
        query = select(TenantMember).where(
            and_(
                TenantMember.tenant_id == tenant_id,
                TenantMember.user_id == user_id,
                TenantMember.is_active == True,
            )
        )
        result = await self.db.execute(query)
        member = result.scalar_one_or_none()

        if not member:
            return False

        if required_permission and required_permission not in member.permissions:
            return False

        return True


class MultiTenantService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.tenant_service = TenantService(db)
        self.config_service = TenantConfigService(db)
        self.quota_service = TenantQuotaService(db)
        self.member_service = TenantMemberService(db)
