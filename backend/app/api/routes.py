from typing import Dict, Any, List, Optional
from datetime import datetime
from fastapi import APIRouter, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel

from app.core.models import (
    MetricConfig,
    MetricResult,
    AlertRule,
    AlertSeverity,
    DataSourceConfig,
    DataSourceType,
    PipelineConfig,
    FieldMapping
)
from app.metrics.manager import metric_manager
from app.connectors.manager import connector_manager
from app.pipeline.manager import pipeline_manager
from app.storage.influxdb_store import influxdb_store
from app.visualization.websocket_manager import websocket_manager
from app.alerts.engine import alert_engine, ConditionEvaluator

router = APIRouter()


class APIResponse(BaseModel):
    code: int = 200
    message: str = "success"
    data: Optional[Dict[str, Any]] = None


class ErrorResponse(BaseModel):
    code: int = 400
    message: str = "error"
    details: Optional[str] = None


@router.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "services": {
            "connector_manager": connector_manager.get_all_status(),
            "metric_manager": metric_manager.get_stats(),
            "pipeline_manager": pipeline_manager.get_stats(),
            "storage": influxdb_store.get_status(),
            "websocket": websocket_manager.get_status(),
            "alerts": alert_engine.get_status()
        }
    }


@router.post("/metrics/config", response_model=APIResponse)
async def create_metric_config(config: MetricConfig):
    metric_id = await metric_manager.register_metric(config)

    if not metric_id:
        raise HTTPException(
            status_code=400,
            detail="Failed to register metric configuration"
        )

    return APIResponse(
        code=200,
        message="Metric configuration created successfully",
        data={"metric_id": metric_id}
    )


@router.get("/metrics", response_model=APIResponse)
async def list_metrics():
    metrics = metric_manager.get_all_metrics()

    metrics_list = []
    for metric_id, config in metrics.items():
        metrics_list.append({
            "metric_id": metric_id,
            "metric_name": config.metric_name,
            "source": config.source,
            "aggregation": config.aggregation.value,
            "field": config.field,
            "time_window": config.time_window,
            "group_by": config.group_by,
            "chart_type": config.chart_type,
            "is_active": config.is_active,
            "alert_rules_count": len(config.alert_rules)
        })

    return APIResponse(
        code=200,
        data={
            "metrics": metrics_list,
            "total": len(metrics_list)
        }
    )


@router.get("/metrics/{metric_id}", response_model=APIResponse)
async def get_metric(metric_id: str):
    config = metric_manager.get_metric_config(metric_id)

    if not config:
        raise HTTPException(status_code=404, detail="Metric not found")

    current_value = metric_manager.get_current_value(metric_id)

    return APIResponse(
        code=200,
        data={
            "config": {
                "metric_id": config.metric_id,
                "metric_name": config.metric_name,
                "source": config.source,
                "aggregation": config.aggregation.value,
                "field": config.field,
                "time_window": config.time_window,
                "group_by": config.group_by,
                "chart_type": config.chart_type,
                "is_active": config.is_active,
                "alert_rules": [
                    {
                        "condition": r.condition,
                        "severity": r.severity.value,
                        "notify_channel": r.notify_channel
                    }
                    for r in config.alert_rules
                ]
            },
            "current_value": current_value
        }
    )


@router.put("/metrics/{metric_id}", response_model=APIResponse)
async def update_metric(metric_id: str, config: MetricConfig):
    success = await metric_manager.update_metric_config(metric_id, config)

    if not success:
        raise HTTPException(
            status_code=404,
            detail="Metric not found or update failed"
        )

    return APIResponse(
        code=200,
        message="Metric configuration updated successfully"
    )


@router.delete("/metrics/{metric_id}", response_model=APIResponse)
async def delete_metric(metric_id: str):
    success = await metric_manager.unregister_metric(metric_id)

    if not success:
        raise HTTPException(
            status_code=404,
            detail="Metric not found or deletion failed"
        )

    return APIResponse(
        code=200,
        message="Metric configuration deleted successfully"
    )


@router.post("/metrics/{metric_id}/alerts", response_model=APIResponse)
async def add_alert_rule(metric_id: str, rule: AlertRule):
    valid, msg = ConditionEvaluator.validate_condition(rule.condition)
    if not valid:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid condition: {msg}"
        )

    success = await metric_manager.add_alert_rule(metric_id, rule)

    if not success:
        raise HTTPException(
            status_code=404,
            detail="Metric not found"
        )

    return APIResponse(
        code=200,
        message="Alert rule added successfully"
    )


@router.get("/alerts/history", response_model=APIResponse)
async def get_alert_history(metric_id: Optional[str] = None, limit: int = 50):
    alerts = alert_engine.get_alert_history(metric_id=metric_id, limit=limit)

    alerts_list = [
        {
            "alert_id": a.alert_id,
            "metric_id": a.metric_id,
            "metric_name": a.metric_name,
            "severity": a.severity.value,
            "message": a.message,
            "value": a.value,
            "threshold_condition": a.threshold_condition,
            "timestamp": a.timestamp.isoformat() + "Z",
            "group_key": a.group_key
        }
        for a in alerts
    ]

    return APIResponse(
        code=200,
        data={
            "alerts": alerts_list,
            "total": len(alerts_list)
        }
    )


@router.post("/datasources", response_model=APIResponse)
async def create_datasource(config: DataSourceConfig):
    success = await connector_manager.register_connector(config)

    if not success:
        raise HTTPException(
            status_code=400,
            detail="Failed to register data source"
        )

    return APIResponse(
        code=200,
        message="Data source registered successfully",
        data={"source_id": config.source_id}
    )


@router.get("/datasources", response_model=APIResponse)
async def list_datasources():
    statuses = connector_manager.get_all_status()

    return APIResponse(
        code=200,
        data={
            "datasources": statuses,
            "total": len(statuses)
        }
    )


@router.post("/datasources/{source_id}/start", response_model=APIResponse)
async def start_datasource(source_id: str):
    success = await connector_manager.start_connector(source_id)

    if not success:
        raise HTTPException(
            status_code=404,
            detail="Data source not found or failed to start"
        )

    return APIResponse(
        code=200,
        message="Data source started successfully"
    )


@router.post("/datasources/{source_id}/stop", response_model=APIResponse)
async def stop_datasource(source_id: str):
    success = await connector_manager.stop_connector(source_id)

    if not success:
        raise HTTPException(
            status_code=404,
            detail="Data source not found or failed to stop"
        )

    return APIResponse(
        code=200,
        message="Data source stopped successfully"
    )


@router.post("/pipelines", response_model=APIResponse)
async def create_pipeline(config: PipelineConfig):
    success = await pipeline_manager.register_pipeline(config)

    if not success:
        raise HTTPException(
            status_code=400,
            detail="Failed to register pipeline"
        )

    return APIResponse(
        code=200,
        message="Pipeline registered successfully",
        data={"source": config.source}
    )


@router.get("/pipelines", response_model=APIResponse)
async def list_pipelines():
    stats = pipeline_manager.get_stats()

    return APIResponse(
        code=200,
        data=stats
    )


@router.get("/metrics/{metric_id}/history", response_model=APIResponse)
async def get_metric_history(
    metric_id: str,
    start_time: str,
    end_time: Optional[str] = None
):
    try:
        from datetime import datetime
        start_dt = datetime.fromisoformat(start_time.replace('Z', '+00:00'))
        end_dt = None
        if end_time:
            end_dt = datetime.fromisoformat(end_time.replace('Z', '+00:00'))

        history = await influxdb_store.query_metric(
            metric_id=metric_id,
            start_time=start_dt,
            end_time=end_dt
        )

        formatted_history = []
        for point in history:
            formatted_history.append({
                "time": point["time"].isoformat() + "Z" if hasattr(point["time"], "isoformat") else str(point["time"]),
                "value": point["value"],
                "metric_id": point["metric_id"]
            })

        return APIResponse(
            code=200,
            data={
                "metric_id": metric_id,
                "history": formatted_history,
                "total_points": len(formatted_history)
            }
        )

    except ValueError as e:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid time format: {e}"
        )
