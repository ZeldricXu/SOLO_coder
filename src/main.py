"""Main entry point for the File Storage and Lifecycle Management System."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional, UploadFile, File

from fastapi import FastAPI, HTTPException, Request, Depends
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

from .domain.models.common import (
    EventMessage,
    FileMetadata,
    LifecyclePolicy,
    SchemaInfo,
    ScheduledTask,
    QualityRule,
)
from .domain.errors.base import AppError
from .infrastructure.config.settings import Settings, get_settings
from .infrastructure.logging.structured_logger import LogManager
from .modules.storage.storage_module import StorageModule
from .modules.timeseries.timeseries_module import TimeSeriesModule
from .modules.gateway.gateway_module import GatewayModule
from .modules.data_access.data_access_module import DataAccessModule
from .modules.metadata_crawler.metadata_crawler import MetadataCrawler
from .modules.scheduler.scheduler_module import SchedulerModule
from .modules.logging.logging_module import LoggingModule
from .modules.data_quality.data_quality_module import DataQualityModule
from .modules.streaming_query.streaming_query_module import StreamingQueryModule
from .modules.vector_index.vector_index_module import VectorIndexModule


class AppState:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.logger = LogManager().get_logger(__name__)
        self.storage_module: Optional[StorageModule] = None
        self.timeseries_module: Optional[TimeSeriesModule] = None
        self.gateway_module: Optional[GatewayModule] = None
        self.data_access_module: Optional[DataAccessModule] = None
        self.metadata_crawler: Optional[MetadataCrawler] = None
        self.scheduler_module: Optional[SchedulerModule] = None
        self.logging_module: Optional[LoggingModule] = None
        self.data_quality_module: Optional[DataQualityModule] = None
        self.streaming_query_module: Optional[StreamingQueryModule] = None
        self.vector_index_module: Optional[VectorIndexModule] = None

    async def initialize(self) -> None:
        self.logger.info("Initializing application modules...")

        self.storage_module = StorageModule(self.settings)
        self.timeseries_module = TimeSeriesModule(self.settings)
        self.gateway_module = GatewayModule(self.settings)
        self.data_access_module = DataAccessModule(self.settings)
        self.metadata_crawler = MetadataCrawler(self.settings)
        self.scheduler_module = SchedulerModule(self.settings)
        self.logging_module = LoggingModule(self.settings)
        self.data_quality_module = DataQualityModule(self.settings)
        self.streaming_query_module = StreamingQueryModule(self.settings)
        self.vector_index_module = VectorIndexModule(self.settings)

        await self.gateway_module.start()
        self.logger.info("All modules initialized successfully")

    async def shutdown(self) -> None:
        self.logger.info("Shutting down application...")
        if self.gateway_module:
            await self.gateway_module.stop()
        self.logger.info("Application shutdown complete")


app_state: Optional[AppState] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global app_state
    settings = get_settings()
    app_state = AppState(settings)
    await app_state.initialize()
    yield
    await app_state.shutdown()


app = FastAPI(
    title="File Storage and Lifecycle Management API",
    description="A comprehensive system for file storage, lifecycle management, "
                "time-series processing, data quality, and vector indexing.",
    version="1.0.0",
    lifespan=lifespan,
)


def get_app_state() -> AppState:
    if app_state is None:
        raise HTTPException(status_code=500, detail="Application not initialized")
    return app_state


@app.middleware("http")
async def request_logging_middleware(request: Request, call_next):
    state = get_app_state()
    if state.gateway_module:
        import time
        start_time = time.time()
        
        headers = dict(request.headers)
        method = request.method
        path = request.url.path
        client_ip = request.client.host if request.client else ""
        
        request_id = await state.gateway_module.request_logger.start_request(
            method=method,
            path=path,
            client_ip=client_ip,
            user_agent=headers.get("user-agent", ""),
            correlation_id=headers.get("x-correlation-id"),
        )
        
        try:
            response = await call_next(request)
            
            duration_ms = int((time.time() - start_time) * 1000)
            
            await state.gateway_module.request_logger.end_request(
                request_id=request_id,
                status_code=response.status_code,
                content_length=response.headers.get("content-length", 0),
            )
            
            response.headers["X-Request-ID"] = str(request_id)
            response.headers["X-Duration-MS"] = str(duration_ms)
            
            return response
        except Exception as e:
            await state.gateway_module.request_logger.end_request(
                request_id=request_id,
                status_code=500,
                error_message=str(e),
            )
            raise
    
    return await call_next(request)


@app.get("/")
async def root():
    return {
        "name": "File Storage and Lifecycle Management System",
        "version": "1.0.0",
        "status": "running",
    }


@app.get("/health")
async def health_check():
    return {"status": "healthy"}


# ============ Storage Module APIs ============

class UploadFileRequest(BaseModel):
    file_path: str
    content_type: Optional[str] = None
    metadata: Optional[Dict[str, Any]] = None


@app.post("/api/storage/upload", response_model=Dict[str, Any])
async def upload_file(
    file: UploadFile = File(...),
    file_path: str = "",
    metadata: Optional[str] = None,
    state: AppState = Depends(get_app_state),
):
    try:
        content = await file.read()
        meta_dict = {}
        if metadata:
            import json
            meta_dict = json.loads(metadata)

        event = EventMessage(
            event_type="storage.upload",
            payload={
                "file_path": file_path or file.filename,
                "content": content,
                "content_type": file.content_type,
                "metadata": meta_dict,
            },
            source="api",
        )
        result = await state.storage_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/storage/download/{file_id}", response_model=Dict[str, Any])
async def download_file(
    file_id: str,
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="storage.download",
            payload={"file_id": file_id},
            source="api",
        )
        result = await state.storage_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=404, detail=result.message)

        data = result.results[0] if result.results else {}
        content = data.get("content", b"")

        return StreamingResponse(
            iter([content]),
            media_type=data.get("content_type", "application/octet-stream"),
            headers={
                "Content-Disposition": f'attachment; filename="{data.get("file_name", "file")}"'
            },
        )
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/storage/files", response_model=Dict[str, Any])
async def list_files(
    prefix: Optional[str] = None,
    limit: int = 100,
    offset: int = 0,
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="storage.list",
            payload={"prefix": prefix, "limit": limit, "offset": offset},
            source="api",
        )
        result = await state.storage_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/api/storage/files/{file_id}", response_model=Dict[str, Any])
async def delete_file(
    file_id: str,
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="storage.delete",
            payload={"file_id": file_id},
            source="api",
        )
        result = await state.storage_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/lifecycle/policy", response_model=Dict[str, Any])
async def create_lifecycle_policy(
    policy: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="lifecycle.policy.create",
            payload=policy,
            source="api",
        )
        result = await state.storage_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/lifecycle/run", response_model=Dict[str, Any])
async def run_lifecycle(
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="lifecycle.apply",
            payload={},
            source="api",
        )
        result = await state.storage_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ============ Time-Series Module APIs ============

class TimeSeriesDataRequest(BaseModel):
    timestamps: List[float]
    values: List[float]
    tags: Optional[Dict[str, str]] = None


@app.post("/api/timeseries/ingest", response_model=Dict[str, Any])
async def ingest_timeseries(
    request: TimeSeriesDataRequest,
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="timeseries.ingest",
            payload=request.model_dump(),
            source="api",
        )
        result = await state.timeseries_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/timeseries/compress", response_model=Dict[str, Any])
async def compress_timeseries(
    data: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="timeseries.compress",
            payload=data,
            source="api",
        )
        result = await state.timeseries_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/timeseries/downsample", response_model=Dict[str, Any])
async def downsample_timeseries(
    data: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="timeseries.downsample",
            payload=data,
            source="api",
        )
        result = await state.timeseries_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ============ Schema & Data Access APIs ============

@app.post("/api/schema/register", response_model=Dict[str, Any])
async def register_schema(
    schema: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="schema.register",
            payload=schema,
            source="api",
        )
        result = await state.data_access_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/migration/execute", response_model=Dict[str, Any])
async def execute_migration(
    migration: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="migration.execute",
            payload=migration,
            source="api",
        )
        result = await state.data_access_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ============ Metadata Crawler APIs ============

@app.post("/api/crawler/scan", response_model=Dict[str, Any])
async def crawl_data(
    request: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="crawler.scan",
            payload=request,
            source="api",
        )
        result = await state.metadata_crawler.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ============ Scheduler APIs ============

@app.post("/api/tasks/create", response_model=Dict[str, Any])
async def create_task(
    task: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="task.create",
            payload=task,
            source="api",
        )
        result = await state.scheduler_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/tasks/execute", response_model=Dict[str, Any])
async def execute_tasks(
    request: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="task.execute",
            payload=request,
            source="api",
        )
        result = await state.scheduler_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ============ Logging APIs ============

@app.post("/api/logging/level", response_model=Dict[str, Any])
async def set_log_level(
    request: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="log.level.set",
            payload=request,
            source="api",
        )
        result = await state.logging_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/logging/query", response_model=Dict[str, Any])
async def query_logs(
    level: Optional[str] = None,
    limit: int = 100,
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="log.query",
            payload={"level": level, "limit": limit},
            source="api",
        )
        result = await state.logging_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ============ Data Quality APIs ============

@app.post("/api/quality/rules", response_model=Dict[str, Any])
async def create_quality_rule(
    rule: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="quality.rule.create",
            payload=rule,
            source="api",
        )
        result = await state.data_quality_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/quality/validate", response_model=Dict[str, Any])
async def validate_data_quality(
    request: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="quality.validate",
            payload=request,
            source="api",
        )
        result = await state.data_quality_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ============ Streaming Query APIs ============

@app.post("/api/query/parse", response_model=Dict[str, Any])
async def parse_query(
    request: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="query.parse",
            payload=request,
            source="api",
        )
        result = await state.streaming_query_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/query/execute", response_model=Dict[str, Any])
async def execute_query(
    request: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="query.execute",
            payload=request,
            source="api",
        )
        result = await state.streaming_query_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ============ Vector Index APIs ============

@app.post("/api/vector/index", response_model=Dict[str, Any])
async def create_vector_index(
    request: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="index.create",
            payload=request,
            source="api",
        )
        result = await state.vector_index_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/vector/add", response_model=Dict[str, Any])
async def add_vectors(
    request: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="index.add",
            payload=request,
            source="api",
        )
        result = await state.vector_index_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/vector/search", response_model=Dict[str, Any])
async def search_vectors(
    request: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="index.search",
            payload=request,
            source="api",
        )
        result = await state.vector_index_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/vector/hybrid-search", response_model=Dict[str, Any])
async def hybrid_search(
    request: Dict[str, Any],
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="index.search.hybrid",
            payload=request,
            source="api",
        )
        result = await state.vector_index_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/vector/stats/{index_name}", response_model=Dict[str, Any])
async def get_index_stats(
    index_name: str,
    state: AppState = Depends(get_app_state),
):
    try:
        event = EventMessage(
            event_type="index.stats",
            payload={"index_name": index_name},
            source="api",
        )
        result = await state.vector_index_module.process_event(event)
        if result.status.value == "failed":
            raise HTTPException(status_code=400, detail=result.message)
        return result.results[0] if result.results else {"success": True}
    except AppError as e:
        raise HTTPException(status_code=400, detail=e.to_dict())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.exception_handler(AppError)
async def app_error_handler(request: Request, exc: AppError):
    return JSONResponse(
        status_code=400,
        content=exc.to_dict(),
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "src.main:app",
        host="0.0.0.0",
        port=8000,
        reload=True,
    )
