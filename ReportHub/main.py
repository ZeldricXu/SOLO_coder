from fastapi import FastAPI
from contextlib import asynccontextmanager
from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.interval import IntervalTrigger

from reporthub.models import init_db
from reporthub.api import router
from reporthub.config import settings


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    scheduler = BackgroundScheduler()
    scheduler.start()
    app.state.scheduler = scheduler
    yield
    scheduler.shutdown()


app = FastAPI(
    title="ReportHub 报表生成与数据导出服务",
    version="1.0.0",
    description="一个报表数据管理平台，支持报表模板配置、报表数据生成、报表导出处理、报表数据统计以及报表定时调度功能。",
    lifespan=lifespan
)

app.include_router(router)


@app.get("/")
def root():
    return {
        "service": "ReportHub",
        "version": "1.0.0",
        "status": "running",
        "endpoints": {
            "reports": {
                "generate": "POST /api/v1/reports/generate",
                "export": "POST /api/v1/reports/export",
                "query": "GET /api/v1/reports/query",
                "detail": "GET /api/v1/reports/{report_id}"
            },
            "templates": {
                "create": "POST /api/v1/templates",
                "list": "GET /api/v1/templates",
                "detail": "GET /api/v1/templates/{template_id}",
                "delete": "DELETE /api/v1/templates/{template_id}"
            },
            "schedules": {
                "create": "POST /api/v1/schedules",
                "list": "GET /api/v1/schedules",
                "enable": "POST /api/v1/schedules/{schedule_id}/enable",
                "disable": "POST /api/v1/schedules/{schedule_id}/disable",
                "delete": "DELETE /api/v1/schedules/{schedule_id}"
            },
            "permissions": {
                "grant": "POST /api/v1/permissions/grant",
                "user_list": "GET /api/v1/permissions/user/{user_id}",
                "revoke": "DELETE /api/v1/permissions/{template_id}/{user_id}"
            },
            "statistics": {
                "template": "GET /api/v1/statistics/template/{template_id}",
                "trend": "GET /api/v1/statistics/template/{template_id}/trend"
            },
            "versions": {
                "list": "GET /api/v1/versions/report/{report_id}",
                "compare": "GET /api/v1/versions/compare"
            },
            "storage": {
                "usage": "GET /api/v1/storage/usage",
                "clean": "POST /api/v1/storage/clean"
            }
        }
    }


@app.get("/health")
def health():
    return {"status": "healthy"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.API_HOST,
        port=settings.API_PORT,
        reload=True
    )
