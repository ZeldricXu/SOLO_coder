from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from streamsql.api import (
    metadata_router,
    cdc_router,
    query_router,
    vector_router,
    lifecycle_router,
    lineage_router,
    timeseries_router,
    quality_router,
    resources_router,
)
from streamsql.core.config import ConfigManager
from streamsql.core.events import EventBus, EventType
from streamsql import __version__


@asynccontextmanager
async def lifespan(app: FastAPI):
    config_manager = ConfigManager()
    event_bus = EventBus()

    event_bus.emit(EventType.SERVICE_STARTED, {"service": "streamsql", "version": __version__})

    yield

    event_bus.emit(EventType.SERVICE_STOPPED, {"service": "streamsql"})


def create_app(config_manager: ConfigManager | None = None) -> FastAPI:
    config = config_manager or ConfigManager()

    app = FastAPI(
        title="StreamSQL API",
        description="流式SQL计算执行引擎 - StreamSQL",
        version=__version__,
        lifespan=lifespan,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(resources_router, prefix="/api/v1")
    app.include_router(metadata_router, prefix="/api/v1")
    app.include_router(cdc_router, prefix="/api/v1")
    app.include_router(query_router, prefix="/api/v1")
    app.include_router(vector_router, prefix="/api/v1")
    app.include_router(lifecycle_router, prefix="/api/v1")
    app.include_router(lineage_router, prefix="/api/v1")
    app.include_router(timeseries_router, prefix="/api/v1")
    app.include_router(quality_router, prefix="/api/v1")

    @app.get("/")
    async def root():
        return {
            "service": "StreamSQL",
            "version": __version__,
            "status": "running",
            "modules": [
                "metadata_crawler",
                "cdc_capture",
                "streaming_query",
                "vector_index",
                "lifecycle_manager",
                "data_lineage",
                "timeseries_compression",
                "data_quality",
            ],
        }

    @app.get("/health")
    async def health():
        return {"status": "healthy", "version": __version__}

    @app.get("/api/v1/modules")
    async def list_modules():
        return {
            "code": 200,
            "data": {
                "modules": [
                    {
                        "name": "metadata_crawler",
                        "description": "自动扫描数据源提取Schema、统计信息与样例数据",
                        "status": "enabled",
                    },
                    {
                        "name": "cdc_capture",
                        "description": "数据库binlog/WAL解析、事件序列化与输出适配",
                        "status": "enabled",
                    },
                    {
                        "name": "streaming_query",
                        "description": "流式SQL语法解析、逻辑计划优化与物理计划翻译",
                        "status": "enabled",
                    },
                    {
                        "name": "vector_index",
                        "description": "Embedding向量索引构建、近似最近邻检索优化",
                        "status": "enabled",
                    },
                    {
                        "name": "lifecycle_manager",
                        "description": "冷热数据分层迁移策略、过期数据自动归档与清理",
                        "status": "enabled",
                    },
                    {
                        "name": "data_lineage",
                        "description": "SQL解析提取表/字段级血缘关系，构建DAG图谱",
                        "status": "enabled",
                    },
                    {
                        "name": "timeseries_compression",
                        "description": "时序数据压缩编码、降采样策略与多分辨率存储",
                        "status": "enabled",
                    },
                    {
                        "name": "data_quality",
                        "description": "质量规则配置、定时校验执行与异常数据标记",
                        "status": "enabled",
                    },
                ],
                "total": 8,
            },
        }

    return app


app = create_app()


def main():
    import uvicorn

    uvicorn.run(
        "streamsql.main:app",
        host="0.0.0.0",
        port=8000,
        reload=True,
    )


if __name__ == "__main__":
    main()
