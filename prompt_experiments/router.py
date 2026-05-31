from fastapi import APIRouter, HTTPException, Query
from typing import List, Optional

from .schemas import (
    PromptStatus,
    ExperimentStatus,
    PromptVersion,
    PromptCreateRequest,
    PromptUpdateRequest,
    PromptVersionResponse,
    ABExperimentCreateRequest,
    ABExperimentUpdateRequest,
    ABExperimentResponse,
    ABVariant,
    PromptComparisonRequest,
    PromptComparisonResponse,
    ExperimentResult,
)
from .service import prompt_experiment_service
from common.schemas import BaseResponse
from common.logger import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/api/v1/prompt-experiments", tags=["Prompt实验管理"])


@router.post("/prompts", response_model=BaseResponse[PromptVersion])
async def create_prompt(request: PromptCreateRequest):
    """创建新的Prompt"""
    try:
        result = await prompt_experiment_service.create_prompt(request)
        return BaseResponse(data=result, message="Prompt创建成功")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to create prompt: {str(e)}")
        raise HTTPException(status_code=500, detail=f"创建Prompt失败: {str(e)}")


@router.put("/prompts/{prompt_id}", response_model=BaseResponse[PromptVersion])
async def update_prompt(prompt_id: str, request: PromptUpdateRequest):
    """更新Prompt（创建新版本）"""
    try:
        result = await prompt_experiment_service.update_prompt(prompt_id, request)
        return BaseResponse(data=result, message="Prompt更新成功")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to update prompt: {str(e)}")
        raise HTTPException(status_code=500, detail=f"更新Prompt失败: {str(e)}")


@router.get("/prompts/{prompt_id}", response_model=BaseResponse[PromptVersionResponse])
async def get_prompt_versions(prompt_id: str):
    """获取Prompt的所有版本"""
    try:
        result = prompt_experiment_service.get_prompt_versions(prompt_id)
        return BaseResponse(data=result, message="获取成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to get prompt versions: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取Prompt版本失败: {str(e)}")


@router.get("/prompts/{prompt_id}/versions/{version}", response_model=BaseResponse[PromptVersion])
async def get_prompt_version(prompt_id: str, version: int):
    """获取指定版本的Prompt"""
    try:
        result = prompt_experiment_service.get_prompt_version(prompt_id, version)
        return BaseResponse(data=result, message="获取成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to get prompt version: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取Prompt版本失败: {str(e)}")


@router.get("/prompts", response_model=BaseResponse[List[PromptVersion]])
async def list_prompts(
    status: Optional[PromptStatus] = Query(default=None, description="按状态过滤"),
):
    """列出所有Prompt（最新版本）"""
    try:
        result = prompt_experiment_service.list_prompts(status)
        return BaseResponse(data=result, message="获取成功")
    except Exception as e:
        logger.error(f"Failed to list prompts: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取Prompt列表失败: {str(e)}")


@router.post("/experiments", response_model=BaseResponse[ABExperimentResponse])
async def create_experiment(request: ABExperimentCreateRequest):
    """创建AB实验"""
    try:
        result = await prompt_experiment_service.create_experiment(request)
        return BaseResponse(data=result, message="实验创建成功")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to create experiment: {str(e)}")
        raise HTTPException(status_code=500, detail=f"创建实验失败: {str(e)}")


@router.put("/experiments/{experiment_id}", response_model=BaseResponse[ABExperimentResponse])
async def update_experiment(experiment_id: str, request: ABExperimentUpdateRequest):
    """更新实验配置"""
    try:
        result = await prompt_experiment_service.update_experiment(experiment_id, request)
        return BaseResponse(data=result, message="实验更新成功")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to update experiment: {str(e)}")
        raise HTTPException(status_code=500, detail=f"更新实验失败: {str(e)}")


@router.get("/experiments/{experiment_id}", response_model=BaseResponse[ABExperimentResponse])
async def get_experiment(experiment_id: str):
    """获取实验详情"""
    try:
        result = prompt_experiment_service.get_experiment(experiment_id)
        return BaseResponse(data=result, message="获取成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to get experiment: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取实验详情失败: {str(e)}")


@router.get("/experiments", response_model=BaseResponse[List[ABExperimentResponse]])
async def list_experiments(
    status: Optional[ExperimentStatus] = Query(default=None, description="按状态过滤"),
    created_by: Optional[str] = Query(default=None, description="按创建者过滤"),
):
    """列出所有实验"""
    try:
        result = prompt_experiment_service.list_experiments(status, created_by)
        return BaseResponse(data=result, message="获取成功")
    except Exception as e:
        logger.error(f"Failed to list experiments: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取实验列表失败: {str(e)}")


@router.get("/experiments/{experiment_id}/allocate", response_model=BaseResponse[Optional[ABVariant]])
async def allocate_variant(
    experiment_id: str,
    user_id: Optional[str] = Query(default=None),
    session_id: Optional[str] = Query(default=None),
):
    """分配实验变体"""
    try:
        result = prompt_experiment_service.allocate_variant(experiment_id, user_id, session_id)
        if result is None:
            raise HTTPException(status_code=404, detail=f"实验 {experiment_id} 不存在或未运行")
        return BaseResponse(data=result, message="分配成功")
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to allocate variant: {str(e)}")
        raise HTTPException(status_code=500, detail=f"分配变体失败: {str(e)}")


@router.post("/experiments/{experiment_id}/results", response_model=BaseResponse[ExperimentResult])
async def record_experiment_result(
    experiment_id: str,
    variant_id: str,
    metric_values: dict,
):
    """记录实验结果"""
    try:
        from .schemas import MetricType
        typed_metrics = {MetricType(k): v for k, v in metric_values.items()}
        result = await prompt_experiment_service.record_experiment_result(
            experiment_id, variant_id, typed_metrics
        )
        return BaseResponse(data=result, message="结果记录成功")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to record experiment result: {str(e)}")
        raise HTTPException(status_code=500, detail=f"记录结果失败: {str(e)}")


@router.post("/compare", response_model=BaseResponse[PromptComparisonResponse])
async def compare_prompts(request: PromptComparisonRequest):
    """对比多个Prompt版本的效果"""
    try:
        result = await prompt_experiment_service.compare_prompts(request)
        return BaseResponse(data=result, message="对比完成")
    except Exception as e:
        logger.error(f"Failed to compare prompts: {str(e)}")
        raise HTTPException(status_code=500, detail=f"对比Prompt失败: {str(e)}")
