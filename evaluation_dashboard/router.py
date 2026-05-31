from fastapi import APIRouter, HTTPException, Query
from typing import List, Optional
from datetime import datetime

from .schemas import (
    MetricType,
    DriftType,
    OfflineEvaluationRequest,
    OfflineEvaluationResponse,
    OnlineMetrics,
    DriftDetectionRequest,
    DriftDetectionResponse,
    DashboardResponse,
    MetricTimeSeries,
)
from .service import evaluation_dashboard_service
from common.schemas import BaseResponse
from common.logger import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/api/v1/evaluation", tags=["模型评估看板"])


@router.post("/offline/evaluate", response_model=BaseResponse[OfflineEvaluationResponse])
async def run_offline_evaluation(request: OfflineEvaluationRequest):
    """运行离线评估"""
    try:
        result = await evaluation_dashboard_service.run_offline_evaluation(request)
        return BaseResponse(data=result, message="离线评估完成")
    except Exception as e:
        logger.error(f"Failed to run offline evaluation: {str(e)}")
        raise HTTPException(status_code=500, detail=f"离线评估失败: {str(e)}")


@router.get("/offline/{eval_id}", response_model=BaseResponse[OfflineEvaluationResponse])
async def get_offline_evaluation(eval_id: str):
    """获取离线评估结果"""
    try:
        result = evaluation_dashboard_service.get_offline_evaluation(eval_id)
        return BaseResponse(data=result, message="获取成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to get offline evaluation: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取评估结果失败: {str(e)}")


@router.get("/offline", response_model=BaseResponse[List[OfflineEvaluationResponse]])
async def list_offline_evaluations(
    model_name: Optional[str] = Query(default=None, description="模型名称过滤"),
):
    """列出离线评估记录"""
    try:
        result = evaluation_dashboard_service.list_offline_evaluations(model_name)
        return BaseResponse(data=result, message="获取成功")
    except Exception as e:
        logger.error(f"Failed to list offline evaluations: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取评估列表失败: {str(e)}")


@router.post("/online/collect", response_model=BaseResponse[OnlineMetrics])
async def collect_online_metrics(
    model_name: str,
    metrics_data: dict,
):
    """收集在线指标"""
    try:
        from .schemas import MetricType
        typed_metrics = {MetricType(k): v for k, v in metrics_data.items()}
        result = await evaluation_dashboard_service.collect_online_metrics(model_name, typed_metrics)
        return BaseResponse(data=result, message="指标收集成功")
    except Exception as e:
        logger.error(f"Failed to collect online metrics: {str(e)}")
        raise HTTPException(status_code=500, detail=f"收集指标失败: {str(e)}")


@router.get("/online/{model_name}", response_model=BaseResponse[List[OnlineMetrics]])
async def get_online_metrics(
    model_name: str,
    start_time: Optional[datetime] = Query(default=None),
    end_time: Optional[datetime] = Query(default=None),
):
    """获取在线指标历史"""
    try:
        result = evaluation_dashboard_service.get_online_metrics(model_name, start_time, end_time)
        return BaseResponse(data=result, message="获取成功")
    except Exception as e:
        logger.error(f"Failed to get online metrics: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取在线指标失败: {str(e)}")


@router.post("/drift/detect", response_model=BaseResponse[DriftDetectionResponse])
async def detect_drift(request: DriftDetectionRequest):
    """检测漂移"""
    try:
        result = await evaluation_dashboard_service.detect_drift(request)
        return BaseResponse(data=result, message="漂移检测完成")
    except Exception as e:
        logger.error(f"Failed to detect drift: {str(e)}")
        raise HTTPException(status_code=500, detail=f"漂移检测失败: {str(e)}")


@router.get("/dashboard/{model_name}", response_model=BaseResponse[DashboardResponse])
async def get_dashboard(model_name: str):
    """获取模型看板数据"""
    try:
        result = evaluation_dashboard_service.get_dashboard(model_name)
        return BaseResponse(data=result, message="获取成功")
    except Exception as e:
        logger.error(f"Failed to get dashboard: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取看板数据失败: {str(e)}")


@router.get("/timeseries/{model_name}/{metric_name}", response_model=BaseResponse[MetricTimeSeries])
async def get_metric_timeseries(
    model_name: str,
    metric_name: MetricType,
    start_time: Optional[datetime] = Query(default=None),
    end_time: Optional[datetime] = Query(default=None),
):
    """获取指标时间序列数据"""
    try:
        result = evaluation_dashboard_service.get_metric_timeseries(
            model_name, metric_name, start_time, end_time
        )
        return BaseResponse(data=result, message="获取成功")
    except Exception as e:
        logger.error(f"Failed to get metric timeseries: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取时间序列失败: {str(e)}")
