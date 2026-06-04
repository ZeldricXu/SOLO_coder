import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.interval import IntervalTrigger

from app.config import settings
from app.database import engine, Base, SessionLocal
from app.routes import (
    pages_router,
    health_router,
    metrics_router,
    alert_router,
    slow_sql_router,
    asset_router,
    duty_router,
    log_router,
    preference_router,
)
from app.services import HealthService, AlertService
from app.context_processors import init_app
from app.logging_config import setup_logging
from app.metrics import PrometheusMiddleware, get_metrics_response, HEALTH_CHECK_COUNT, ALERT_TRIGGERED_COUNT, ACTIVE_ALERTS

setup_logging(log_level=settings.log_level, debug=settings.debug)
logger = logging.getLogger(__name__)

scheduler = AsyncIOScheduler()


async def health_check_job():
    db = SessionLocal()
    try:
        health_service = HealthService(db)
        results = await health_service.check_all_services()

        for result in results:
            service = health_service.get_service_by_id(result.service_id)
            service_name = service.name if service else f"service_{result.service_id}"
            HEALTH_CHECK_COUNT.labels(
                service=service_name,
                status=result.status
            ).inc()

        logger.info(f"Health check completed, {len(results)} services checked")
    except Exception as e:
        logger.error(f"Health check job error: {e}")
    finally:
        db.close()


async def alert_evaluation_job():
    db = SessionLocal()
    try:
        alert_service = AlertService(db)
        triggered = alert_service.evaluate_rules()

        for alert in triggered:
            rule = alert_service.get_rule_by_id(alert.rule_id)
            rule_name = rule.name if rule else f"rule_{alert.rule_id}"
            ALERT_TRIGGERED_COUNT.labels(
                level=alert.level,
                rule_name=rule_name
            ).inc()

        for level in ["P0", "P1", "P2", "P3"]:
            active_count = len([a for a in alert_service.get_alert_history(status="firing") if a.level == level])
            ACTIVE_ALERTS.labels(level=level).set(active_count)

        if triggered:
            logger.info(f"Alert evaluation completed, {len(triggered)} alerts triggered")
        else:
            logger.info("Alert evaluation completed, no alerts triggered")
    except Exception as e:
        logger.error(f"Alert evaluation job error: {e}")
    finally:
        db.close()


@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(bind=engine)
    logger.info("Database tables created")

    scheduler.add_job(
        health_check_job,
        IntervalTrigger(seconds=settings.health_check_interval),
        id="health_check",
        replace_existing=True,
    )
    logger.info(f"Health check job scheduled, interval: {settings.health_check_interval}s")

    scheduler.add_job(
        alert_evaluation_job,
        IntervalTrigger(seconds=settings.alert_evaluate_interval),
        id="alert_evaluation",
        replace_existing=True,
    )
    logger.info(f"Alert evaluation job scheduled, interval: {settings.alert_evaluate_interval}s")

    scheduler.start()
    logger.info("Scheduler started")

    yield

    scheduler.shutdown()
    logger.info("Scheduler shutdown")


app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    description="运维监控大盘 - 统一的运维监控管理平台",
    lifespan=lifespan,
)

if settings.enable_metrics:
    app.add_middleware(PrometheusMiddleware)

app.mount("/static", StaticFiles(directory="static"), name="static")

init_app(app)

app.include_router(pages_router)
app.include_router(health_router)
app.include_router(metrics_router)
app.include_router(alert_router)
app.include_router(slow_sql_router)
app.include_router(asset_router)
app.include_router(duty_router)
app.include_router(log_router)
app.include_router(preference_router)


@app.get("/api/healthz")
async def healthz():
    return {
        "status": "ok",
        "app": settings.app_name,
        "version": settings.app_version,
    }


@app.get("/internal/metrics")
async def metrics():
    return get_metrics_response()


@app.middleware("http")
async def add_cache_control(request: Request, call_next):
    response = await call_next(request)
    if request.url.path.startswith("/static/"):
        response.headers["Cache-Control"] = "public, max-age=3600"
    else:
        response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
    return response


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        reload=settings.debug,
        log_level="info",
    )
