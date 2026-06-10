from typing import Any, Dict, List, Optional
from datetime import datetime, timedelta, timezone
from fastapi import APIRouter, Depends, HTTPException, Header, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field, EmailStr
from sqlalchemy.ext.asyncio import AsyncSession

from gateway.db import get_db
from gateway.db.models import APIKey
from gateway.db.repository import APIKeyRepository, RouteRepository
from gateway.auth.api_key import get_api_key_validator
from gateway.analytics.collector import get_analytics_collector
from gateway.routing.router import get_router
from gateway.developer_portal.openapi import get_openapi_aggregator
from gateway.config import get_settings
from gateway.logger import get_logger

logger = get_logger("developer-portal")

router = APIRouter(prefix="/api/portal", tags=["Developer Portal"])

settings = get_settings()


class APIKeyCreateRequest(BaseModel):
    name: str = Field(..., min_length=1, max_length=255)
    description: Optional[str] = None
    scopes: List[str] = Field(default_factory=list)
    allowed_paths: Optional[List[str]] = None
    rate_limit_quota: Optional[int] = None
    expires_days: Optional[int] = Field(None, ge=1, le=365)
    plan_id: Optional[str] = None
    application_note: Optional[str] = Field(None, max_length=2000)
    contact_email: Optional[str] = None


class APIKeyApproveRequest(BaseModel):
    status: str = Field(..., pattern="^(approved|rejected)$")
    approved_by: str
    rate_limit_quota: Optional[int] = None
    approval_note: Optional[str] = Field(None, max_length=2000)
    rejection_reason: Optional[str] = Field(None, max_length=2000)


class APIKeyPlanResponse(BaseModel):
    id: str
    name: str
    description: str
    rate_limit_quota: int
    price: float
    requires_approval: bool


class APIKeyResponse(BaseModel):
    id: str
    name: str
    description: Optional[str]
    status: str
    scopes: List[str]
    allowed_paths: Optional[List[str]]
    rate_limit_quota: Optional[int]
    expires_at: Optional[datetime]
    last_used_at: Optional[datetime]
    created_at: datetime
    key: Optional[str] = None
    plan_id: Optional[str] = None
    application_note: Optional[str] = None
    approval_note: Optional[str] = None
    rejection_reason: Optional[str] = None
    contact_email: Optional[str] = None


class UsageStatsResponse(BaseModel):
    total_requests: int
    success_rate: float
    error_rate: float
    avg_latency_ms: float
    p50_latency_ms: float
    p95_latency_ms: float
    p99_latency_ms: float
    rate_limited_count: int
    circuit_broken_count: int


def verify_admin_key(x_api_key: str = Header(...)):
    if x_api_key != settings.gateway.admin_api_key:
        raise HTTPException(status_code=401, detail="Invalid admin API key")
    return True


def get_current_user(request: Request):
    user = getattr(request.state, "user", {})
    if not user or not user.get("user_id"):
        raise HTTPException(status_code=401, detail="User not authenticated")
    return user


@router.get("/", response_class=HTMLResponse)
async def portal_index():
    return HTMLResponse(content=open("/Users/huangzitong/Desktop/SoloCoder6月/Code/76-80/DF1-78/src/gateway/static/index.html").read())


@router.get("/plans", response_model=List[APIKeyPlanResponse])
async def list_api_key_plans():
    portal_settings = settings.portal
    return [
        APIKeyPlanResponse(
            id=plan["id"],
            name=plan["name"],
            description=plan["description"],
            rate_limit_quota=plan["rate_limit_quota"],
            price=plan["price"],
            requires_approval=plan["requires_approval"],
        )
        for plan in portal_settings.api_key_plans
    ]


@router.get("/openapi.json")
async def get_aggregated_openapi():
    aggregator = get_openapi_aggregator()
    spec = await aggregator.aggregate()
    return spec


@router.get("/routes")
async def list_routes():
    router = get_router()
    routes = router.get_all_routes()
    return [
        {
            "id": str(route.id),
            "name": route.name,
            "path": route.path,
            "match_type": route.match_type,
            "methods": route.methods,
            "targets": [t.to_dict() for t in route.targets],
            "auth_required": route.auth_required,
            "auth_strategy": route.auth_strategy,
            "rate_limit_enabled": route.rate_limit_enabled,
            "circuit_breaker_enabled": route.circuit_breaker_enabled,
            "timeout": route.timeout,
            "version": route.version,
        }
        for route in routes
    ]


@router.get("/api-keys", response_model=List[APIKeyResponse])
async def list_api_keys(
    user: Dict[str, Any] = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    repo = APIKeyRepository(db)
    keys = await repo.list_by_user(user["user_id"])

    return [
        APIKeyResponse(
            id=str(key.id),
            name=key.name,
            description=key.description,
            status=key.status,
            scopes=key.scopes,
            allowed_paths=key.allowed_paths,
            rate_limit_quota=key.rate_limit_quota,
            expires_at=key.expires_at,
            last_used_at=key.last_used_at,
            created_at=key.created_at,
            plan_id=key.plan_id,
            application_note=key.application_note,
            approval_note=key.approval_note,
            rejection_reason=key.rejection_reason,
            contact_email=key.contact_email,
        )
        for key in keys
    ]


@router.post("/api-keys", response_model=APIKeyResponse)
async def create_api_key(
    request: APIKeyCreateRequest,
    user: Dict[str, Any] = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    repo = APIKeyRepository(db)
    validator = get_api_key_validator()
    portal_settings = settings.portal

    raw_key = validator.generate_api_key()
    hashed_key = validator.hash_key(raw_key)

    expires_at = None
    if request.expires_days:
        expires_at = datetime.now(timezone.utc) + timedelta(days=request.expires_days)

    rate_limit_quota = request.rate_limit_quota
    if request.plan_id and not rate_limit_quota:
        for plan in portal_settings.api_key_plans:
            if plan["id"] == request.plan_id:
                rate_limit_quota = plan["rate_limit_quota"]
                break

    status = "pending"
    if not portal_settings.approval_required:
        status = "approved"

    key_data = {
        "key": hashed_key,
        "name": request.name,
        "description": request.description,
        "user_id": user["user_id"],
        "tenant_id": user.get("tenant_id"),
        "scopes": request.scopes,
        "allowed_paths": request.allowed_paths,
        "rate_limit_quota": rate_limit_quota,
        "status": status,
        "expires_at": expires_at,
        "created_by": user["user_id"],
        "plan_id": request.plan_id,
        "application_note": request.application_note,
        "contact_email": request.contact_email,
    }

    key = await repo.create(key_data)

    if status == "approved" and portal_settings.auto_activate_on_approval:
        key = await repo.update_status(key.id, "approved", approved_by="system")

    logger.info("API Key created", key_id=str(key.id), user_id=user["user_id"], status=status)

    from gateway.notifications.webhook import get_webhook_notifier
    notifier = get_webhook_notifier()
    await notifier.notify("api_key.created", {
        "key_id": str(key.id),
        "name": key.name,
        "user_id": user["user_id"],
        "status": status,
        "plan_id": request.plan_id,
        "contact_email": request.contact_email,
    })

    return APIKeyResponse(
        id=str(key.id),
        name=key.name,
        description=key.description,
        status=key.status,
        scopes=key.scopes,
        allowed_paths=key.allowed_paths,
        rate_limit_quota=key.rate_limit_quota,
        expires_at=key.expires_at,
        last_used_at=key.last_used_at,
        created_at=key.created_at,
        key=raw_key,
        plan_id=key.plan_id,
        application_note=key.application_note,
        approval_note=key.approval_note,
        rejection_reason=key.rejection_reason,
        contact_email=key.contact_email,
    )


@router.get("/api-keys/{key_id}", response_model=APIKeyResponse)
async def get_api_key(
    key_id: str,
    user: Dict[str, Any] = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    from uuid import UUID
    repo = APIKeyRepository(db)
    key = await repo.get_by_id(UUID(key_id))

    if not key:
        raise HTTPException(status_code=404, detail="API Key not found")

    if key.user_id != user["user_id"]:
        raise HTTPException(status_code=403, detail="Access denied")

    return APIKeyResponse(
        id=str(key.id),
        name=key.name,
        description=key.description,
        status=key.status,
        scopes=key.scopes,
        allowed_paths=key.allowed_paths,
        rate_limit_quota=key.rate_limit_quota,
        expires_at=key.expires_at,
        last_used_at=key.last_used_at,
        created_at=key.created_at,
        plan_id=key.plan_id,
        application_note=key.application_note,
        approval_note=key.approval_note,
        rejection_reason=key.rejection_reason,
        contact_email=key.contact_email,
    )


@router.get("/api-keys/pending", response_model=List[APIKeyResponse])
async def list_pending_api_keys(
    is_admin: bool = Depends(verify_admin_key),
    db: AsyncSession = Depends(get_db),
):
    from sqlalchemy import select
    result = await db.execute(select(APIKey).where(APIKey.status == "pending").order_by(APIKey.created_at.desc()))
    keys = list(result.scalars().all())

    return [
        APIKeyResponse(
            id=str(key.id),
            name=key.name,
            description=key.description,
            status=key.status,
            scopes=key.scopes,
            allowed_paths=key.allowed_paths,
            rate_limit_quota=key.rate_limit_quota,
            expires_at=key.expires_at,
            last_used_at=key.last_used_at,
            created_at=key.created_at,
            plan_id=key.plan_id,
            application_note=key.application_note,
            approval_note=key.approval_note,
            rejection_reason=key.rejection_reason,
            contact_email=key.contact_email,
        )
        for key in keys
    ]


@router.post("/api-keys/{key_id}/approve")
async def approve_api_key(
    key_id: str,
    request: APIKeyApproveRequest,
    is_admin: bool = Depends(verify_admin_key),
    db: AsyncSession = Depends(get_db),
):
    from uuid import UUID
    repo = APIKeyRepository(db)

    update_data = {}
    if request.rate_limit_quota is not None:
        update_data["rate_limit_quota"] = request.rate_limit_quota
    if request.approval_note is not None:
        update_data["approval_note"] = request.approval_note
    if request.rejection_reason is not None:
        update_data["rejection_reason"] = request.rejection_reason

    key = await repo.update_status(
        UUID(key_id),
        request.status,
        approved_by=request.approved_by if request.status == "approved" else None,
    )

    if not key:
        raise HTTPException(status_code=404, detail="API Key not found")

    if update_data:
        key = await repo.update(UUID(key_id), update_data)

    logger.info("API Key status updated",
                key_id=key_id,
                status=request.status,
                approved_by=request.approved_by,
                has_approval_note=request.approval_note is not None,
                has_rejection_reason=request.rejection_reason is not None)

    from gateway.notifications.webhook import get_webhook_notifier
    notifier = get_webhook_notifier()

    event_type = "api_key.approved" if request.status == "approved" else "api_key.rejected"
    await notifier.notify(event_type, {
        "key_id": str(key.id),
        "name": key.name,
        "user_id": key.user_id,
        "status": request.status,
        "approved_by": request.approved_by,
        "approval_note": request.approval_note,
        "rejection_reason": request.rejection_reason,
        "contact_email": key.contact_email,
    })

    return {"status": "success", "message": f"API Key {request.status}"}


@router.get("/usage/summary")
async def get_usage_summary(
    user: Dict[str, Any] = Depends(get_current_user),
    hours: int = Query(24, ge=1, le=720),
):
    collector = get_analytics_collector()
    stats = await collector.get_usage_summary(user_id=user["user_id"], hours=hours)
    return stats


@router.get("/usage/daily")
async def get_daily_usage(
    user: Dict[str, Any] = Depends(get_current_user),
    days: int = Query(7, ge=1, le=90),
):
    collector = get_analytics_collector()
    usage = await collector.get_user_usage(user_id=user["user_id"], days=days)
    return usage


@router.get("/usage/top-apis")
async def get_top_apis(
    is_admin: bool = Depends(verify_admin_key),
    limit: int = Query(10, ge=1, le=100),
    hours: int = Query(24, ge=1, le=720),
):
    collector = get_analytics_collector()
    top_apis = await collector.get_top_apis(limit=limit, hours=hours)
    return top_apis


@router.get("/quotas")
async def get_user_quotas(
    user: Dict[str, Any] = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    repo = APIKeyRepository(db)
    keys = await repo.list_by_user(user["user_id"])

    collector = get_analytics_collector()
    today = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)

    quotas = []
    for key in keys:
        if key.status != "approved":
            continue

        quota = key.rate_limit_quota or settings.rate_limit.default_user_limit

        usage = 0
        try:
            from sqlalchemy import select, func
            from gateway.db.models import APIKeyUsage
            result = await db.execute(
                select(func.sum(APIKeyUsage.request_count))
                .where(APIKeyUsage.api_key_id == key.id)
                .where(APIKeyUsage.date >= today)
            )
            usage = result.scalar() or 0
        except Exception:
            pass

        quotas.append({
            "api_key_id": str(key.id),
            "api_key_name": key.name,
            "daily_quota": quota * 1440,
            "used_today": usage,
            "remaining": max(0, quota * 1440 - usage),
            "reset_at": (today + timedelta(days=1)).isoformat(),
        })

    return quotas
