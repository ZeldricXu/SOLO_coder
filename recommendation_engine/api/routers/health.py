from fastapi import APIRouter, Depends, Request, status
from typing import Dict, Any
from datetime import datetime

from recommendation_engine.models.schemas import HealthStatus
from recommendation_engine.api.dependencies import (
    get_redis,
    get_postgres,
    get_feedback_collector_svc,
    get_model_gateway_svc,
)
from recommendation_engine.infrastructure import RedisClient, PostgresClient
from recommendation_engine.feedback_collector import FeedbackCollector
from recommendation_engine.model_serving_gateway import ModelServingGateway

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthStatus)
async def health_check(
    request: Request,
    redis: RedisClient = Depends(get_redis),
    postgres: PostgresClient = Depends(get_postgres),
    feedback_collector: FeedbackCollector = Depends(get_feedback_collector_svc),
    model_gateway: ModelServingGateway = Depends(get_model_gateway_svc),
):
    components: Dict[str, str] = {}

    try:
        redis_ok = await redis.health_check()
        components["redis"] = "healthy" if redis_ok else "unhealthy"
    except Exception:
        components["redis"] = "unhealthy"

    try:
        pg_ok = await postgres.health_check()
        components["postgres"] = "healthy" if pg_ok else "unhealthy"
    except Exception:
        components["postgres"] = "unhealthy"

    try:
        fb_stats = feedback_collector.get_stats()
        components["feedback_collector"] = "healthy" if fb_stats.get("running", False) else "unhealthy"
    except Exception:
        components["feedback_collector"] = "unknown"

    try:
        mg_stats = model_gateway.get_stats()
        components["model_gateway"] = "healthy" if mg_stats.get("running", False) else "unhealthy"
    except Exception:
        components["model_gateway"] = "unknown"

    overall_status = "healthy"
    if any(v == "unhealthy" for v in components.values()):
        overall_status = "degraded"

    return HealthStatus(
        service=request.app.state.service_name,
        status=overall_status,
        version="1.0.0",
        components=components,
    )


@router.get("/health/live", status_code=status.HTTP_200_OK)
async def liveness_probe():
    return {"status": "alive", "timestamp": datetime.utcnow().isoformat()}


@router.get("/health/ready", status_code=status.HTTP_200_OK)
async def readiness_probe(
    redis: RedisClient = Depends(get_redis),
    postgres: PostgresClient = Depends(get_postgres),
):
    redis_ok = await redis.health_check()
    pg_ok = await postgres.health_check()

    if redis_ok and pg_ok:
        return {"status": "ready", "timestamp": datetime.utcnow().isoformat()}
    else:
        return {"status": "not_ready", "timestamp": datetime.utcnow().isoformat()}, status.HTTP_503_SERVICE_UNAVAILABLE


@router.get("/stats")
async def get_system_stats(
    feedback_collector: FeedbackCollector = Depends(get_feedback_collector_svc),
    model_gateway: ModelServingGateway = Depends(get_model_gateway_svc),
):
    return {
        "feedback_collector": feedback_collector.get_stats(),
        "model_gateway": model_gateway.get_stats(),
        "timestamp": datetime.utcnow().isoformat(),
    }
