from fastapi import APIRouter

from etl_engine.api.sources import router as sources_router
from etl_engine.api.pipelines import router as pipelines_router
from etl_engine.api.executions import router as executions_router
from etl_engine.api.quality import router as quality_router
from etl_engine.api.metadata import router as metadata_router
from etl_engine.api.alerts_api import router as alerts_router


def create_router() -> APIRouter:
    main_router = APIRouter()
    main_router.include_router(sources_router)
    main_router.include_router(pipelines_router)
    main_router.include_router(executions_router)
    main_router.include_router(quality_router)
    main_router.include_router(metadata_router)
    main_router.include_router(alerts_router)
    return main_router
