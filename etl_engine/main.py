from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from etl_engine.api import create_router
from etl_engine.config import settings
from etl_engine.db.session import init_db

BASE_DIR = Path(__file__).resolve().parent

_prometheus_exporter = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _prometheus_exporter

    await init_db()

    try:
        from etl_engine.metrics.prometheus import PrometheusExporter

        _prometheus_exporter = PrometheusExporter(port=settings.PROMETHEUS_PORT)
        _prometheus_exporter.start_server()
    except Exception:
        pass

    yield

    _prometheus_exporter = None


app = FastAPI(
    title="ETL Engine",
    version="0.1.0",
    description="Multi-source Data Integration & ETL Orchestration Engine",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

api_router = create_router()
app.include_router(api_router)

app.mount(
    "/static",
    StaticFiles(directory=BASE_DIR / "web" / "static"),
    name="static",
)

_templates = Jinja2Templates(directory=BASE_DIR / "web" / "templates")


@app.get("/", response_class=HTMLResponse)
async def dashboard(request: Request):
    return _templates.TemplateResponse(
        "index.html",
        {"request": request, "api_base_url": ""},
    )


@app.get("/health")
async def health():
    return {"status": "ok", "version": "0.1.0"}


@app.get("/metrics", response_class=PlainTextResponse)
async def metrics():
    if _prometheus_exporter is not None:
        return _prometheus_exporter.get_metrics()
    return ""


if __name__ == "__main__":
    uvicorn.run(
        "etl_engine.main:app",
        host=settings.API_HOST,
        port=settings.API_PORT,
        reload=settings.DEBUG,
    )
