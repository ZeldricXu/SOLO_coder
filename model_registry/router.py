from fastapi import APIRouter, HTTPException, Query
from typing import List, Optional, Dict, Any

from .schemas import (
    ModelMetadata,
    ModelVersion,
    ModelRegistrationRequest,
    ModelVersionCreateRequest,
    StageTransitionRequest,
    ModelSearchRequest,
    ModelSearchResponse,
    ModelStage,
)
from .service import model_registry_service
from common.schemas import BaseResponse
from common.logger import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/api/v1/model-registry", tags=["模型注册与版本"])


@router.post("/models", response_model=BaseResponse[ModelMetadata])
async def register_model(request: ModelRegistrationRequest):
    """注册新模型"""
    try:
        result = model_registry_service.register_model(request)
        return BaseResponse(data=result, message="模型注册成功")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to register model: {str(e)}")
        raise HTTPException(status_code=500, detail=f"注册模型失败: {str(e)}")


@router.get("/models", response_model=BaseResponse[ModelSearchResponse])
async def list_models(
    name: Optional[str] = None,
    owner: Optional[str] = None,
    task_type: Optional[str] = None,
    stage: Optional[ModelStage] = None,
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
):
    """搜索/列出模型"""
    try:
        request = ModelSearchRequest(
            name=name,
            owner=owner,
            task_type=task_type,
            stage=stage,
            limit=limit,
            offset=offset,
        )
        result = model_registry_service.search_models(request)
        return BaseResponse(data=result, message="获取成功")
    except Exception as e:
        logger.error(f"Failed to list models: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取模型列表失败: {str(e)}")


@router.get("/models/{model_id}", response_model=BaseResponse[ModelMetadata])
async def get_model(model_id: str, include_versions: bool = True):
    """获取模型详情"""
    try:
        result = model_registry_service.get_model(model_id, include_versions)
        return BaseResponse(data=result, message="获取成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to get model: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取模型详情失败: {str(e)}")


@router.delete("/models/{model_id}", response_model=BaseResponse[bool])
async def delete_model(model_id: str):
    """软删除模型"""
    try:
        result = model_registry_service.delete_model(model_id)
        return BaseResponse(data=result, message="模型删除成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to delete model: {str(e)}")
        raise HTTPException(status_code=500, detail=f"删除模型失败: {str(e)}")


@router.post("/models/{model_id}/versions", response_model=BaseResponse[ModelVersion])
async def create_model_version(request: ModelVersionCreateRequest):
    """创建模型版本"""
    try:
        result = model_registry_service.create_model_version(request)
        return BaseResponse(data=result, message="版本创建成功")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to create model version: {str(e)}")
        raise HTTPException(status_code=500, detail=f"创建版本失败: {str(e)}")


@router.get("/models/{model_id}/versions/{version}", response_model=BaseResponse[ModelVersion])
async def get_model_version(model_id: str, version: str):
    """获取模型版本详情"""
    try:
        result = model_registry_service.get_model_version(model_id, version)
        return BaseResponse(data=result, message="获取成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to get model version: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取版本详情失败: {str(e)}")


@router.get("/models/{model_id}/versions/latest", response_model=BaseResponse[ModelVersion])
async def get_latest_version(model_id: str):
    """获取最新版本"""
    try:
        result = model_registry_service.get_latest_version(model_id)
        return BaseResponse(data=result, message="获取成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to get latest version: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取最新版本失败: {str(e)}")


@router.get("/models/{model_id}/versions/production", response_model=BaseResponse[ModelVersion])
async def get_production_version(model_id: str):
    """获取生产版本"""
    try:
        result = model_registry_service.get_production_version(model_id)
        return BaseResponse(data=result, message="获取成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to get production version: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取生产版本失败: {str(e)}")


@router.post("/stage-transition", response_model=BaseResponse[ModelVersion])
async def transition_stage(request: StageTransitionRequest):
    """模型版本Stage流转"""
    try:
        result = model_registry_service.transition_stage(request)
        return BaseResponse(data=result, message="Stage流转成功")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to transition stage: {str(e)}")
        raise HTTPException(status_code=500, detail=f"Stage流转失败: {str(e)}")


@router.delete("/models/{model_id}/versions/{version}", response_model=BaseResponse[bool])
async def delete_version(model_id: str, version: str):
    """删除模型版本"""
    try:
        result = model_registry_service.delete_version(model_id, version)
        return BaseResponse(data=result, message="版本删除成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to delete version: {str(e)}")
        raise HTTPException(status_code=500, detail=f"删除版本失败: {str(e)}")


@router.get("/stages", response_model=BaseResponse[List[str]])
async def list_stages():
    """列出所有Stage"""
    stages = [s.value for s in ModelStage]
    return BaseResponse(data=stages, message="获取成功")
