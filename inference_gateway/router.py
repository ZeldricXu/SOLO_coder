from fastapi import APIRouter, HTTPException, Query
from typing import List, Optional, Dict, Any

from .schemas import (
    ModelProvider,
    InferenceRequest,
    InferenceResponse,
    LoadBalanceStrategy,
    FallbackPolicy,
    ProviderConfig,
    GatewayStats,
)
from .service import inference_gateway_service
from common.schemas import BaseResponse
from common.logger import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/api/v1/inference", tags=["推理路由网关"])


@router.post("/chat", response_model=BaseResponse[InferenceResponse])
async def chat_completion(request: InferenceRequest):
    """聊天补全推理"""
    try:
        result = await inference_gateway_service.infer(request)
        return BaseResponse(data=result, message="推理完成")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to process inference: {str(e)}")
        raise HTTPException(status_code=500, detail=f"推理失败: {str(e)}")


@router.post("/providers", response_model=BaseResponse[bool])
async def register_provider(config: ProviderConfig):
    """注册模型Provider"""
    try:
        inference_gateway_service.register_provider(config)
        return BaseResponse(data=True, message="Provider注册成功")
    except Exception as e:
        logger.error(f"Failed to register provider: {str(e)}")
        raise HTTPException(status_code=500, detail=f"注册Provider失败: {str(e)}")


@router.delete("/providers/{provider}", response_model=BaseResponse[bool])
async def unregister_provider(provider: ModelProvider):
    """注销模型Provider"""
    try:
        inference_gateway_service.unregister_provider(provider)
        return BaseResponse(data=True, message="Provider注销成功")
    except Exception as e:
        logger.error(f"Failed to unregister provider: {str(e)}")
        raise HTTPException(status_code=500, detail=f"注销Provider失败: {str(e)}")


@router.get("/providers", response_model=BaseResponse[List[Dict[str, Any]]])
async def list_providers():
    """列出所有已注册的Provider"""
    try:
        result = inference_gateway_service.list_providers()
        return BaseResponse(data=result, message="获取成功")
    except Exception as e:
        logger.error(f"Failed to list providers: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取Provider列表失败: {str(e)}")


@router.get("/stats", response_model=BaseResponse[GatewayStats])
async def get_gateway_stats():
    """获取网关统计信息"""
    try:
        result = inference_gateway_service.get_gateway_stats()
        return BaseResponse(data=result, message="获取成功")
    except Exception as e:
        logger.error(f"Failed to get gateway stats: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取统计信息失败: {str(e)}")


@router.get("/strategies/load-balance", response_model=BaseResponse[List[str]])
async def list_load_balance_strategies():
    """列出支持的负载均衡策略"""
    strategies = [s.value for s in LoadBalanceStrategy]
    return BaseResponse(data=strategies, message="获取成功")


@router.get("/strategies/fallback", response_model=BaseResponse[List[str]])
async def list_fallback_policies():
    """列出支持的Fallback策略"""
    policies = [p.value for p in FallbackPolicy]
    return BaseResponse(data=policies, message="获取成功")
