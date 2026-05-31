import logging
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from ..config import ConfigManager, get_global_config_manager, set_global_config_manager
from ..config.sources import EnvironmentSource, JSONFileSource, MemorySource
from ..core.events import DomainEvent, EventBus, get_global_event_bus, set_global_event_bus
from ..notification import NotificationManager, get_global_notification_manager, set_global_notification_manager
from ..notification.channels import ConsoleChannel
from ..profiling import ContinuousProfiler, get_global_profiler, set_global_profiler
from ..tracing import TraceCollector, get_global_collector, set_global_collector
from ..tracing.sampling import HeadBasedSampler
from ..slo import SLOManager, get_global_slo_manager, set_global_slo_manager
from ..topology import TopologyBuilder, get_global_topology_builder, set_global_topology_builder
from ..gateway import APIGateway, Route, RequestContext
from ..anomaly_detection import AnomalyDetector, get_global_detector, set_global_detector


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)


class ResourceCreateRequest(BaseModel):
    type: str
    config: Dict[str, Any] = Field(default_factory=dict)
    labels: Dict[str, str] = Field(default_factory=dict)


class BatchOperation(BaseModel):
    action: str
    id: str
    params: Dict[str, Any] = Field(default_factory=dict)


class BatchRequest(BaseModel):
    operations: List[BatchOperation]


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Initializing Platform Engineer SDK...")

    event_bus = EventBus()
    set_global_event_bus(event_bus)

    config_manager = ConfigManager()
    env_source = EnvironmentSource(prefix="PLATFORM_")
    mem_source = MemorySource({"app": {"name": "platform-engineer", "version": "1.0.0"}})
    config_manager.add_source(env_source, priority=10)
    config_manager.add_source(mem_source, priority=20)
    config_manager.load_all()
    set_global_config_manager(config_manager)

    notification_manager = NotificationManager()
    console_channel = ConsoleChannel()
    notification_manager.register_channel("console", console_channel)
    set_global_notification_manager(notification_manager)

    collector = TraceCollector(service_name="platform-engineer")
    collector.register_sampler(HeadBasedSampler(sample_rate=1.0))
    set_global_collector(collector)

    profiler = ContinuousProfiler(enabled=False)
    set_global_profiler(profiler)

    anomaly_detector = AnomalyDetector()
    set_global_detector(anomaly_detector)

    slo_manager = SLOManager()
    set_global_slo_manager(slo_manager)

    topology_builder = TopologyBuilder()
    set_global_topology_builder(topology_builder)

    gateway = APIGateway()
    app.state.gateway = gateway

    logger.info("Platform Engineer SDK initialized successfully")
    yield
    logger.info("Shutting down Platform Engineer SDK...")
    if profiler.is_running():
        profiler.stop()
    logger.info("Platform Engineer SDK shutdown complete")


app = FastAPI(
    title="Platform Engineer SDK",
    description="多源配置加载与动态更新的生产级平台工程SDK",
    version="1.0.0",
    lifespan=lifespan,
)


@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    logger.error(f"Unhandled exception: {exc}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"code": 500, "message": "内部处理错误", "detail": str(exc)},
    )


@app.get("/")
async def root():
    return {
        "code": 200,
        "data": {
            "service": "platform-engineer",
            "version": "1.0.0",
            "status": "running",
            "modules": [
                "config",
                "notification",
                "profiling",
                "anomaly_detection",
                "tracing",
                "data_access",
                "slo",
                "topology",
                "gateway",
            ],
        },
    }


@app.get("/health")
async def health():
    return {"code": 200, "data": {"status": "healthy"}}


@app.post("/api/v1/resources", status_code=201)
async def create_resource(request: ResourceCreateRequest):
    collector = get_global_collector()
    span = collector.start_span("create_resource", service_name="api-gateway", resource_type=request.type)

    try:
        resource_id = f"rsc_{request.type[:3]}_{id(request)}"

        event = DomainEvent(
            event_type="resource.created",
            payload={"id": resource_id, "type": request.type, "config": request.config},
            source="api",
        )
        await get_global_event_bus().publish(event)

        return {"code": 201, "data": {"id": resource_id, "status": "provisioning"}}
    finally:
        collector.end_span(span)


@app.get("/api/v1/resources/{resource_id}/status")
async def get_resource_status(resource_id: str):
    return {
        "code": 200,
        "data": {
            "id": resource_id,
            "status": "completed",
            "progress": 1.0,
        },
    }


@app.post("/api/v1/resources/batch")
async def batch_operations(request: BatchRequest):
    results = []
    for op in request.operations:
        results.append({
            "id": op.id,
            "action": op.action,
            "status": "success",
        })

    batch_id = f"batch_{id(request)}"
    return {"code": 200, "data": {"batch_id": batch_id, "results": results}}


@app.get("/api/v1/config")
async def get_config():
    config_manager = get_global_config_manager()
    snapshot = config_manager.get_snapshot()
    return {"code": 200, "data": snapshot.to_dict()}


@app.post("/api/v1/config/reload")
async def reload_config():
    config_manager = get_global_config_manager()
    changes = config_manager.reload_all()
    return {"code": 200, "data": {"changes": changes}}


@app.get("/api/v1/traces")
async def list_traces(limit: int = 100):
    collector = get_global_collector()
    traces = collector.list_traces(limit=limit)
    return {"code": 200, "data": [t.to_dict() for t in traces]}


@app.get("/api/v1/traces/{trace_id}")
async def get_trace(trace_id: str):
    collector = get_global_collector()
    trace = collector.get_trace(trace_id)
    if trace is None:
        raise HTTPException(status_code=404, detail="Trace not found")
    return {"code": 200, "data": trace.to_dict()}


@app.post("/api/v1/notifications/send")
async def send_notification(channel: str, message: str):
    notification_manager = get_global_notification_manager()
    try:
        result = await notification_manager.send(
            channel=channel,
            recipient="default",
            message=message,
        )
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/v1/profiling/status")
async def profiling_status():
    profiler = get_global_profiler()
    return {
        "code": 200,
        "data": {
            "running": profiler.is_running(),
            "metrics": profiler.get_metrics(),
        },
    }


@app.post("/api/v1/profiling/start")
async def start_profiling():
    profiler = get_global_profiler()
    if profiler.is_running():
        return {"code": 200, "data": {"message": "Profiler already running"}}
    profiler.start()
    return {"code": 200, "data": {"message": "Profiler started"}}


@app.post("/api/v1/profiling/stop")
async def stop_profiling():
    profiler = get_global_profiler()
    if not profiler.is_running():
        return {"code": 200, "data": {"message": "Profiler not running"}}
    report = profiler.stop()
    return {"code": 200, "data": {"message": "Profiler stopped", "report": report}}


@app.get("/api/v1/topology")
async def get_topology():
    builder = get_global_topology_builder()
    topology = builder.build()
    return {"code": 200, "data": topology.to_dict()}


@app.get("/api/v1/slo/status")
async def get_slo_status():
    manager = get_global_slo_manager()
    return {"code": 200, "data": {"slos": [slo.dict() for slo in manager.list_slos()]}}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("platform_engineer.app.main:app", host="0.0.0.0", port=8000, reload=True)
