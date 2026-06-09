from fastapi import APIRouter, Depends
from sqlalchemy import text
from sqlalchemy.orm import Session
from datetime import datetime

from app.core.database import get_db
from app.core.cache import CacheManager
from app.schemas.common import APIResponse

router = APIRouter(tags=["Health"])


@router.get("/", summary="健康检查")
async def health_check(db: Session = Depends(get_db)):
    status = "healthy"
    checks = {}

    try:
        db.execute(text("SELECT 1"))
        checks["database"] = "healthy"
    except Exception as e:
        checks["database"] = f"unhealthy: {str(e)}"
        status = "unhealthy"

    try:
        cache = CacheManager()
        if cache.redis_client:
            await cache.set("health_check", "ok", ttl=10)
            result = await cache.get("health_check")
            checks["redis"] = "healthy" if result == "ok" else "unhealthy: cache miss"
        else:
            checks["redis"] = "disabled"
    except Exception as e:
        checks["redis"] = f"unhealthy: {str(e)}"
        status = "unhealthy"

    checks["timestamp"] = datetime.utcnow().isoformat()

    return APIResponse(
        code=0,
        message=status,
        data={
            "status": status,
            "checks": checks,
            "version": "1.0.0",
        },
    )


@router.get("/liveness", summary="存活检查")
async def liveness_check():
    return APIResponse(
        code=0,
        message="alive",
        data={"status": "alive", "timestamp": datetime.utcnow().isoformat()},
    )


@router.get("/readiness", summary="就绪检查")
async def readiness_check(db: Session = Depends(get_db)):
    try:
        db.execute(text("SELECT 1"))
        return APIResponse(
            code=0,
            message="ready",
            data={"status": "ready", "timestamp": datetime.utcnow().isoformat()},
        )
    except Exception as e:
        return APIResponse(
            code=503,
            message="not ready",
            data={"status": "not_ready", "error": str(e)},
        )
