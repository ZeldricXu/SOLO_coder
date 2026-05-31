from fastapi import APIRouter, HTTPException, Query
from typing import List, Optional

from .schemas import (
    FeatureRegistrationRequest,
    FeatureRegistrationResponse,
    FeatureOnlineGetRequest,
    FeatureOnlineGetResponse,
    FeatureOfflineFetchRequest,
    FeatureOfflineFetchResponse,
    FeatureIngestRequest,
    FeatureIngestResponse,
    ConsistencyCheckRequest,
    ConsistencyCheckResponse,
    FeatureGroupInfo,
)
from .service import feature_store_service
from common.schemas import BaseResponse, PaginatedResponse, PaginatedData
from common.logger import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/api/v1/feature-store", tags=["特征存储服务"])


@router.post("/register", response_model=BaseResponse[FeatureRegistrationResponse])
async def register_feature_group(request: FeatureRegistrationRequest):
    """注册特征组"""
    try:
        result = await feature_store_service.register_feature_group(request)
        return BaseResponse(data=result, message="特征组注册成功")
    except Exception as e:
        logger.error(f"Failed to register feature group: {str(e)}")
        raise HTTPException(status_code=400, detail=f"注册特征组失败: {str(e)}")


@router.post("/online/get", response_model=BaseResponse[FeatureOnlineGetResponse])
async def get_online_features(request: FeatureOnlineGetRequest):
    """在线获取特征"""
    try:
        result = await feature_store_service.get_online_features(request)
        return BaseResponse(data=result, message="获取在线特征成功")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to get online features: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取在线特征失败: {str(e)}")


@router.post("/ingest", response_model=BaseResponse[FeatureIngestResponse])
async def ingest_features(request: FeatureIngestRequest):
    """写入特征数据"""
    try:
        result = await feature_store_service.ingest_features(request)
        return BaseResponse(data=result, message="特征写入成功")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to ingest features: {str(e)}")
        raise HTTPException(status_code=500, detail=f"写入特征失败: {str(e)}")


@router.post("/offline/fetch", response_model=BaseResponse[FeatureOfflineFetchResponse])
async def fetch_offline_features(request: FeatureOfflineFetchRequest):
    """离线回溯特征数据"""
    try:
        result = await feature_store_service.fetch_offline_features(request)
        return BaseResponse(data=result, message="获取离线特征成功")
    except Exception as e:
        logger.error(f"Failed to fetch offline features: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取离线特征失败: {str(e)}")


@router.post("/consistency/check", response_model=BaseResponse[ConsistencyCheckResponse])
async def check_consistency(request: ConsistencyCheckRequest):
    """检查线上线下特征一致性"""
    try:
        result = await feature_store_service.check_consistency(request)
        return BaseResponse(data=result, message="一致性检查完成")
    except Exception as e:
        logger.error(f"Failed to check consistency: {str(e)}")
        raise HTTPException(status_code=500, detail=f"一致性检查失败: {str(e)}")


@router.get("/groups", response_model=BaseResponse[List[FeatureGroupInfo]])
async def list_feature_groups(
    entity_name: Optional[str] = Query(default=None, description="实体名称过滤"),
):
    """列出所有特征组"""
    try:
        result = feature_store_service.list_feature_groups(entity_name)
        return BaseResponse(data=result, message="获取特征组列表成功")
    except Exception as e:
        logger.error(f"Failed to list feature groups: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取特征组列表失败: {str(e)}")


@router.get("/groups/{group_id}", response_model=BaseResponse[FeatureGroupInfo])
async def get_feature_group(group_id: str):
    """获取指定特征组详情"""
    try:
        groups = feature_store_service.list_feature_groups()
        group = next((g for g in groups if g.feature_group_id == group_id), None)
        if not group:
            raise HTTPException(status_code=404, detail=f"特征组 {group_id} 未找到")
        return BaseResponse(data=group, message="获取成功")
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get feature group: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取特征组失败: {str(e)}")
