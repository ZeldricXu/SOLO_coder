from fastapi import APIRouter, Depends, Header, HTTPException
from typing import Optional, List
from datetime import datetime
from src.core import ApiResponse, get_trace_id
from src.modules.evaluation_dashboard import (
    MetricDefinition,
    OnlineMetricPoint,
    ModelComparisonRequest,
    EvaluationType,
    DriftType,
)
from src.di import DIContainer, get_container

router = APIRouter(prefix="/api/v1/evaluation", tags=["Evaluation Dashboard"])


@router.post("/metrics", response_model=ApiResponse)
async def define_metric(
    metric: MetricDefinition,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.evaluation_dashboard.define_metric(metric, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.get("/metrics", response_model=ApiResponse)
async def list_metric_definitions(
    container: DIContainer = Depends(get_container),
):
    result = await container.evaluation_dashboard.metrics_store.list_metric_definitions()
    return ApiResponse.success(result)


@router.post("/evaluations", response_model=ApiResponse)
async def create_evaluation(
    model_id: str,
    version_id: str,
    metrics: dict,
    evaluation_type: EvaluationType = EvaluationType.OFFLINE,
    dataset: str = "",
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.evaluation_dashboard.create_evaluation(
        model_id, version_id, evaluation_type, metrics, dataset, trace_id or get_trace_id()
    )
    return ApiResponse.created(result)


@router.get("/evaluations", response_model=ApiResponse)
async def list_evaluations(
    model_id: Optional[str] = None,
    evaluation_type: Optional[EvaluationType] = None,
    limit: int = 100,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.evaluation_dashboard.list_evaluations(
        model_id, evaluation_type, limit, trace_id or get_trace_id()
    )
    return ApiResponse.success(result)


@router.get("/evaluations/{evaluation_id}", response_model=ApiResponse)
async def get_evaluation(
    evaluation_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.evaluation_dashboard.get_evaluation(evaluation_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/metrics/online", response_model=ApiResponse)
async def record_online_metric(
    point: OnlineMetricPoint,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    await container.evaluation_dashboard.record_online_metric(point, trace_id or get_trace_id())
    return ApiResponse.success({"recorded": True})


@router.get("/metrics/online/stats", response_model=ApiResponse)
async def get_online_metric_stats(
    model_id: str,
    version_id: str,
    metric_name: str,
    window_minutes: int = 60,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.evaluation_dashboard.get_online_metric_stats(
        model_id, version_id, metric_name, window_minutes, trace_id or get_trace_id()
    )
    return ApiResponse.success(result)


@router.post("/compare", response_model=ApiResponse)
async def compare_models(
    request: ModelComparisonRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.evaluation_dashboard.compare_models(request, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/drift/detect", response_model=ApiResponse)
async def detect_drift(
    model_id: str,
    version_id: str,
    drift_type: DriftType,
    feature_name: Optional[str] = None,
    threshold: Optional[float] = None,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.evaluation_dashboard.detect_drift(
        model_id, version_id, feature_name, drift_type, threshold, trace_id or get_trace_id()
    )
    return ApiResponse.success(result)


@router.post("/drift/reference", response_model=ApiResponse)
async def set_drift_reference(
    model_id: str,
    version_id: str,
    data: List[float],
    feature_name: Optional[str] = None,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    await container.evaluation_dashboard.set_drift_reference(
        model_id, version_id, feature_name, data, trace_id or get_trace_id()
    )
    return ApiResponse.success({"reference_set": True})


@router.get("/dashboard", response_model=ApiResponse)
async def get_dashboard_summary(
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.evaluation_dashboard.get_dashboard_summary(trace_id or get_trace_id())
    return ApiResponse.success(result)
