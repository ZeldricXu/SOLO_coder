from fastapi import FastAPI
from src.api.rest.query_api import router as query_router
from src.api.rest.lineage_api import router as lineage_router
from src.api.rest.lifecycle_api import router as lifecycle_router
from src.api.rest.cdc_api import router as cdc_router
from src.api.rest.metadata_api import router as metadata_router
from src.api.rest.vector_api import router as vector_router
from src.api.rest.timeseries_api import router as timeseries_router
from src.api.rest.quality_api import router as quality_router


def create_app() -> FastAPI:
    app = FastAPI(
        title="StreamSQL",
        description="流式数据处理与查询平台",
        version="1.0.0",
    )

    app.include_router(query_router, prefix="/api/v1/query", tags=["流式查询解析"])
    app.include_router(lineage_router, prefix="/api/v1/lineage", tags=["数据血缘解析"])
    app.include_router(lifecycle_router, prefix="/api/v1/lifecycle", tags=["数据生命周期管理"])
    app.include_router(cdc_router, prefix="/api/v1/cdc", tags=["CDC增量捕获"])
    app.include_router(metadata_router, prefix="/api/v1/metadata", tags=["元数据采集爬虫"])
    app.include_router(vector_router, prefix="/api/v1/vector", tags=["向量索引构建"])
    app.include_router(timeseries_router, prefix="/api/v1/timeseries", tags=["时序数据压缩"])
    app.include_router(quality_router, prefix="/api/v1/quality", tags=["数据质量校验"])

    @app.get("/health")
    async def health_check():
        return {"status": "healthy", "service": "StreamSQL"}

    @app.get("/")
    async def root():
        return {
            "name": "StreamSQL",
            "version": "1.0.0",
            "description": "流式数据处理与查询平台",
            "modules": [
                "流式查询解析", "数据血缘解析", "数据生命周期管理",
                "CDC增量捕获", "元数据采集爬虫", "向量索引构建",
                "时序数据压缩", "数据质量校验",
            ],
        }

    return app
