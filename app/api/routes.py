"""
FastAPI routes for the platform.
"""

from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Body, Query, Path
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.logging import get_logger
from app.scheduler import Task, WorkflowScheduler
from app.lineage import LineageDAGBuilder
from app.cdc import CDCFactory, CDCCaptureConfig, DatabaseType
from app.monitoring import MonitoringService, AlertRule, AggregationType, ComparisonOperator
from app.notification import NotificationService, Notification, NotificationChannel, NotificationPriority
from app.quality import DataQualityService, QualityRule, QualityRuleType, QualitySeverity
from app.streaming import StreamingQueryPipeline
from app.vector import VectorSearchService, IndexType, DistanceMetric
from app.utils import generate_id, now_iso


router = APIRouter()
logger = get_logger("api")

scheduler = WorkflowScheduler()
lineage_builder = LineageDAGBuilder()
cdc_config = CDCCaptureConfig(database_type=DatabaseType.MYSQL)
cdc_pipeline = CDCFactory.create_pipeline(cdc_config)
monitoring = MonitoringService()
notifications = NotificationService()
quality = DataQualityService()
streaming = StreamingQueryPipeline()
vector_service = VectorSearchService()


class ResourceCreateRequest(BaseModel):
    type: str = Field(..., description="Resource type")
    config: Dict[str, Any] = Field(default_factory=dict, description="Resource configuration")
    labels: Dict[str, str] = Field(default_factory=dict, description="Resource labels")


class ResourceResponse(BaseModel):
    id: str
    status: str
    config: Dict[str, Any] = Field(default_factory=dict)
    labels: Dict[str, str] = Field(default_factory=dict)


class BatchOperation(BaseModel):
    action: str
    id: str
    parameters: Dict[str, Any] = Field(default_factory=dict)


class BatchRequest(BaseModel):
    operations: List[BatchOperation]


class SQLParseRequest(BaseModel):
    sql: str = Field(..., description="SQL to parse")
    dialect: str = Field(default="mysql", description="SQL dialect")


class QueryRequest(BaseModel):
    sql: str = Field(..., description="Streaming SQL query")


class VectorAddRequest(BaseModel):
    vectors: List[List[float]]
    metadatas: Optional[List[Dict[str, Any]]] = None


class VectorSearchRequest(BaseModel):
    query: List[float]
    k: int = Field(default=10, ge=1, le=100)


class QualityRuleRequest(BaseModel):
    rule_id: str
    name: str
    rule_type: str
    database: str
    table: str
    column: Optional[str] = None
    parameters: Dict[str, Any] = Field(default_factory=dict)
    severity: str = Field(default="high")
    enabled: bool = True


class NotificationRequest(BaseModel):
    title: str
    content: str
    priority: str = Field(default="medium")
    channels: List[str] = Field(default_factory=list)
    recipients: List[str] = Field(default_factory=list)
    tags: Dict[str, str] = Field(default_factory=dict)


@router.post("/api/v1/resources", status_code=201, response_model=ResourceResponse)
async def create_resource(request: ResourceCreateRequest):
    resource_id = f"rsc_{generate_id(length=6)}"
    logger.info(
        "Resource created",
        resource_id=resource_id,
        resource_type=request.type
    )
    return ResourceResponse(
        id=resource_id,
        status="provisioning",
        config=request.config,
        labels=request.labels
    )


@router.get("/api/v1/resources/{resource_id}/status")
async def get_resource_status(resource_id: str = Path(..., description="Resource ID")):
    logger.debug("Resource status query", resource_id=resource_id)
    return {
        "code": 200,
        "data": {
            "id": resource_id,
            "status": "completed",
            "progress": 1.0
        }
    }


@router.post("/api/v1/resources/batch")
async def batch_operations(request: BatchRequest):
    results = []
    for op in request.operations:
        result = {
            "id": op.id,
            "action": op.action,
            "success": True
        }
        results.append(result)
    
    batch_id = f"batch_{generate_id(length=6)}"
    logger.info(
        "Batch operation completed",
        batch_id=batch_id,
        operation_count=len(request.operations)
    )
    
    return {
        "code": 200,
        "data": {
            "batch_id": batch_id,
            "results": results
        }
    }


@router.post("/api/v1/lineage/parse")
async def parse_lineage(request: SQLParseRequest):
    try:
        lineage_builder.build_from_sql(request.sql, dialect=request.dialect)
        graph = lineage_builder.export_graph()
        return {
            "code": 200,
            "data": {
                "nodes": graph["nodes"],
                "edges": graph["edges"],
                "has_cycle": lineage_builder.has_cycle(),
                "topological_order": lineage_builder.topological_sort()
            }
        }
    except Exception as e:
        logger.error("Lineage parsing failed", error=str(e))
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/api/v1/lineage/{node_id}/upstream")
async def get_upstream(node_id: str):
    upstream = lineage_builder.get_upstream(node_id)
    return {
        "code": 200,
        "data": {"node_id": node_id, "upstream": upstream}
    }


@router.get("/api/v1/lineage/{node_id}/downstream")
async def get_downstream(node_id: str):
    downstream = lineage_builder.get_downstream(node_id)
    return {
        "code": 200,
        "data": {"node_id": node_id, "downstream": downstream}
    }


@router.post("/api/v1/scheduler/start")
async def start_scheduler():
    scheduler.start()
    return {"code": 200, "data": {"status": "running"}}


@router.post("/api/v1/scheduler/stop")
async def stop_scheduler():
    scheduler.stop()
    return {"code": 200, "data": {"status": "stopped"}}


@router.get("/api/v1/scheduler/tasks")
async def list_tasks():
    tasks = scheduler._registry.list_all()
    return {
        "code": 200,
        "data": [
            {
                "task_id": t.task_id,
                "name": t.name,
                "status": t.status.value if hasattr(t.status, "value") else str(t.status),
                "dependencies": t.dependencies,
                "result": str(t.result) if t.result else None
            }
            for t in tasks
        ]
    }


@router.post("/api/v1/cdc/start")
async def start_cdc():
    await cdc_pipeline.start()
    return {"code": 200, "data": {"status": "running"}}


@router.post("/api/v1/cdc/stop")
async def stop_cdc():
    await cdc_pipeline.stop()
    return {"code": 200, "data": {"status": "stopped"}}


@router.get("/api/v1/cdc/stats")
async def cdc_stats():
    return {"code": 200, "data": cdc_pipeline.get_capture_stats()}


@router.get("/api/v1/metrics")
async def get_metrics():
    metrics_list = monitoring.metrics._store.get_metric_names()
    return {"code": 200, "data": {"metrics": metrics_list}}


@router.post("/api/v1/alerts/rules")
async def create_alert_rule(
    rule_id: str = Body(..., embed=True),
    name: str = Body(..., embed=True),
    metric_name: str = Body(..., embed=True),
    aggregation: str = Body(..., embed=True),
    operator: str = Body(..., embed=True),
    threshold: float = Body(..., embed=True),
    window_seconds: int = Body(..., embed=True),
    severity: str = Body(default="warning", embed=True)
):
    from app.models import AlertSeverity
    
    rule = AlertRule(
        rule_id=rule_id,
        name=name,
        metric_name=metric_name,
        aggregation=AggregationType(aggregation),
        operator=ComparisonOperator(operator),
        threshold=threshold,
        window_seconds=window_seconds,
        severity=AlertSeverity(severity)
    )
    monitoring.alerts.add_rule(rule)
    return {"code": 200, "data": {"rule_id": rule_id, "status": "created"}}


@router.get("/api/v1/alerts/active")
async def get_active_alerts():
    alerts = monitoring.alerts.get_active_alerts()
    return {
        "code": 200,
        "data": [
            {
                "alert_id": a.alert_id,
                "rule_id": a.rule_id,
                "rule_name": a.rule_name,
                "metric_name": a.metric_name,
                "severity": a.severity.value if hasattr(a.severity, "value") else str(a.severity),
                "value": a.value,
                "threshold": a.threshold,
                "timestamp": a.timestamp.isoformat()
            }
            for a in alerts
        ]
    }


@router.post("/api/v1/notifications/send")
async def send_notification(request: NotificationRequest):
    notification = Notification(
        notification_id=f"notif_{generate_id(length=8)}",
        title=request.title,
        content=request.content,
        priority=NotificationPriority(request.priority),
        channels=[NotificationChannel(c) for c in request.channels] if request.channels else [NotificationChannel.WEBHOOK],
        recipients=request.recipients,
        tags=request.tags
    )
    
    if not notifications._running:
        notifications.start()
    
    success = await notifications.enqueue(notification)
    return {"code": 200, "data": {"notification_id": notification.notification_id, "queued": success}}


@router.get("/api/v1/notifications/stats")
async def notification_stats():
    return {"code": 200, "data": notifications.get_stats()}


@router.post("/api/v1/quality/rules")
async def create_quality_rule(request: QualityRuleRequest):
    rule = QualityRule(
        rule_id=request.rule_id,
        name=request.name,
        rule_type=QualityRuleType(request.rule_type),
        database=request.database,
        table=request.table,
        column=request.column,
        parameters=request.parameters,
        severity=QualitySeverity(request.severity),
        enabled=request.enabled
    )
    quality.rules.add_rule(rule)
    return {"code": 200, "data": {"rule_id": rule.rule_id, "status": "created"}}


@router.get("/api/v1/quality/rules")
async def list_quality_rules():
    rules = quality.rules.list_rules()
    return {
        "code": 200,
        "data": [
            {
                "rule_id": r.rule_id,
                "name": r.name,
                "rule_type": r.rule_type.value if hasattr(r.rule_type, "value") else str(r.rule_type),
                "database": r.database,
                "table": r.table,
                "column": r.column,
                "severity": r.severity.value if hasattr(r.severity, "value") else str(r.severity),
                "enabled": r.enabled
            }
            for r in rules
        ]
    }


@router.get("/api/v1/quality/stats")
async def quality_stats():
    return {"code": 200, "data": quality.get_stats()}


@router.post("/api/v1/streaming/parse")
async def parse_streaming_query(request: QueryRequest):
    try:
        result = streaming.process(request.sql)
        return {
            "code": 200,
            "data": {
                "parsed_sources": [s.name for s in result["parsed"].sources],
                "projections": result["parsed"].projection_columns,
                "window_type": result["parsed"].window_spec.window_type.value if result["parsed"].window_spec else None,
                "optimizations": result["optimizations"]
            }
        }
    except Exception as e:
        logger.error("Streaming query parsing failed", error=str(e))
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/api/v1/vector/{index_name}/create")
async def create_vector_index(
    index_name: str,
    index_type: str = Body(default="flat", embed=True),
    dimension: int = Body(default=128, embed=True),
    metric: str = Body(default="cosine", embed=True)
):
    try:
        vector_service.create_index(
            index_name,
            IndexType(index_type),
            dimension,
            DistanceMetric(metric)
        )
        return {"code": 200, "data": {"index_name": index_name, "created": True}}
    except Exception as e:
        logger.error("Vector index creation failed", error=str(e))
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/api/v1/vector/{index_name}/add")
async def add_vectors(index_name: str, request: VectorAddRequest):
    import numpy as np
    
    index = vector_service.get_index(index_name)
    if not index:
        raise HTTPException(status_code=404, detail=f"Index {index_name} not found")
    
    ids = []
    for i, vec_list in enumerate(request.vectors):
        vec = np.array(vec_list, dtype=np.float32)
        metadata = request.metadatas[i] if request.metadatas and i < len(request.metadatas) else None
        vid = index.add(vec, metadata)
        ids.append(vid)
    
    index.build()
    return {"code": 200, "data": {"ids": ids, "count": len(ids)}}


@router.post("/api/v1/vector/{index_name}/search")
async def search_vectors(index_name: str, request: VectorSearchRequest):
    import numpy as np
    
    index = vector_service.get_index(index_name)
    if not index:
        raise HTTPException(status_code=404, detail=f"Index {index_name} not found")
    
    query = np.array(request.query, dtype=np.float32)
    results = index.search(query, k=request.k)
    
    return {
        "code": 200,
        "data": [
            {
                "id": r.id,
                "distance": r.distance,
                "similarity": r.similarity,
                "metadata": r.metadata
            }
            for r in results
        ]
    }


@router.get("/api/v1/vector/indices")
async def list_vector_indices():
    return {"code": 200, "data": vector_service.list_indices()}


@router.get("/health")
async def health_check():
    return {
        "code": 200,
        "data": {
            "status": "healthy",
            "timestamp": now_iso(),
            "version": "1.0.0"
        }
    }
