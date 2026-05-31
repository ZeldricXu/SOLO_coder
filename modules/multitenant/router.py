from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, Query, Body
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_db
from .models import (
    TenantCreate,
    TenantResponse,
    TenantConfigCreate,
    TenantConfigResponse,
    TenantQuotaCreate,
    TenantQuotaResponse,
    TenantMemberCreate,
    TenantMemberResponse,
    TenantStatus,
    TenantTier,
)
from .service import (
    TenantService,
    TenantConfigService,
    TenantQuotaService,
    TenantMemberService,
)

router = APIRouter(prefix="/tenants", tags=["多租户隔离策略"])


@router.post("", response_model=Dict[str, Any], status_code=201)
async def create_tenant(
    tenant_data: TenantCreate,
    db: AsyncSession = Depends(get_db),
):
    service = TenantService(db)
    tenant = await service.create_tenant(tenant_data)
    return {
        "code": 201,
        "data": tenant.model_dump(),
        "message": "租户创建成功",
    }


@router.get("/{tenant_id}", response_model=Dict[str, Any])
async def get_tenant(
    tenant_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = TenantService(db)
    tenant = await service.get_tenant(tenant_id)
    return {
        "code": 200,
        "data": tenant.model_dump(),
        "message": "查询成功",
    }


@router.get("", response_model=Dict[str, Any])
async def list_tenants(
    status: Optional[TenantStatus] = Query(None),
    tier: Optional[TenantTier] = Query(None),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    db: AsyncSession = Depends(get_db),
):
    service = TenantService(db)
    tenants = await service.list_tenants(status, tier, limit, offset)
    return {
        "code": 200,
        "data": [t.model_dump() for t in tenants],
        "total": len(tenants),
        "message": "查询成功",
    }


@router.patch("/{tenant_id}/status", response_model=Dict[str, Any])
async def update_tenant_status(
    tenant_id: str,
    status: TenantStatus,
    db: AsyncSession = Depends(get_db),
):
    service = TenantService(db)
    tenant = await service.update_tenant_status(tenant_id, status)
    return {
        "code": 200,
        "data": tenant.model_dump(),
        "message": "租户状态更新成功",
    }


@router.delete("/{tenant_id}", response_model=Dict[str, Any])
async def delete_tenant(
    tenant_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = TenantService(db)
    await service.delete_tenant(tenant_id)
    return {
        "code": 200,
        "message": "租户删除成功",
    }


@router.post("/{tenant_id}/configs", response_model=Dict[str, Any], status_code=201)
async def set_config(
    tenant_id: str,
    namespace: str,
    key: str,
    value: Dict[str, Any] = Body(...),
    value_type: str = Body("json"),
    description: Optional[str] = Body(None),
    is_encrypted: bool = Body(False),
    db: AsyncSession = Depends(get_db),
):
    service = TenantConfigService(db)
    config_data = TenantConfigCreate(
        tenant_id=tenant_id,
        namespace=namespace,
        key=key,
        value=value,
        value_type=value_type,
        description=description,
        is_encrypted=is_encrypted,
    )
    config = await service.set_config(config_data)
    return {
        "code": 201,
        "data": config.model_dump(),
        "message": "配置设置成功",
    }


@router.get("/{tenant_id}/configs/{namespace}/{key}", response_model=Dict[str, Any])
async def get_config(
    tenant_id: str,
    namespace: str,
    key: str,
    db: AsyncSession = Depends(get_db),
):
    service = TenantConfigService(db)
    config = await service.get_config(tenant_id, namespace, key)
    return {
        "code": 200,
        "data": config.model_dump(),
        "message": "查询成功",
    }


@router.get("/{tenant_id}/configs/{namespace}", response_model=Dict[str, Any])
async def get_namespace_configs(
    tenant_id: str,
    namespace: str,
    db: AsyncSession = Depends(get_db),
):
    service = TenantConfigService(db)
    configs = await service.get_namespace_configs(tenant_id, namespace)
    return {
        "code": 200,
        "data": [c.model_dump() for c in configs],
        "total": len(configs),
        "message": "查询成功",
    }


@router.post("/{tenant_id}/quotas", response_model=Dict[str, Any], status_code=201)
async def create_quota(
    tenant_id: str,
    quota_data: TenantQuotaCreate,
    db: AsyncSession = Depends(get_db),
):
    service = TenantQuotaService(db)
    quota_data.tenant_id = tenant_id
    quota = await service.create_quota(quota_data)
    return {
        "code": 201,
        "data": quota.model_dump(),
        "message": "配额创建成功",
    }


@router.get("/{tenant_id}/quotas", response_model=Dict[str, Any])
async def get_tenant_quotas(
    tenant_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = TenantQuotaService(db)
    quotas = await service.get_tenant_quotas(tenant_id)
    return {
        "code": 200,
        "data": [q.model_dump() for q in quotas],
        "total": len(quotas),
        "message": "查询成功",
    }


@router.post("/{tenant_id}/quotas/consume", response_model=Dict[str, Any])
async def consume_quota(
    tenant_id: str,
    resource_type: str,
    amount: float = 1.0,
    db: AsyncSession = Depends(get_db),
):
    service = TenantQuotaService(db)
    quota = await service.check_and_consume_quota(tenant_id, resource_type, amount)
    return {
        "code": 200,
        "data": quota.model_dump(),
        "message": "配额消费成功",
    }


@router.post("/quotas/{quota_id}/reset", response_model=Dict[str, Any])
async def reset_quota(
    quota_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = TenantQuotaService(db)
    quota = await service.reset_quota(quota_id)
    return {
        "code": 200,
        "data": quota.model_dump(),
        "message": "配额重置成功",
    }


@router.post("/{tenant_id}/members", response_model=Dict[str, Any], status_code=201)
async def add_member(
    tenant_id: str,
    member_data: TenantMemberCreate,
    db: AsyncSession = Depends(get_db),
):
    service = TenantMemberService(db)
    member_data.tenant_id = tenant_id
    member = await service.add_member(member_data)
    return {
        "code": 201,
        "data": member.model_dump(),
        "message": "成员添加成功",
    }


@router.get("/{tenant_id}/members", response_model=Dict[str, Any])
async def get_tenant_members(
    tenant_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = TenantMemberService(db)
    members = await service.get_tenant_members(tenant_id)
    return {
        "code": 200,
        "data": [m.model_dump() for m in members],
        "total": len(members),
        "message": "查询成功",
    }


@router.get("/{tenant_id}/members/check-access", response_model=Dict[str, Any])
async def check_tenant_access(
    tenant_id: str,
    user_id: str,
    permission: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = TenantMemberService(db)
    has_access = await service.check_tenant_access(user_id, tenant_id, permission)
    return {
        "code": 200,
        "data": {"has_access": has_access},
        "message": "检查完成",
    }
