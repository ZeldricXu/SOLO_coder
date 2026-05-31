from fastapi import APIRouter, HTTPException, Query
from typing import List, Optional

from .schemas import (
    JobStatus,
    JobPriority,
    GPUJob,
    GPUJobRequest,
    GPUJobResponse,
    JobCancelRequest,
    JobCancelResponse,
    ClusterStatusResponse,
    GPUComputeNode,
)
from .service import gpu_scheduler_service
from common.schemas import BaseResponse
from common.logger import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/api/v1/gpu-scheduler", tags=["GPU任务调度"])


@router.post("/jobs", response_model=BaseResponse[GPUJobResponse])
async def submit_job(request: GPUJobRequest):
    """提交GPU任务"""
    try:
        result = await gpu_scheduler_service.submit_job(request)
        return BaseResponse(data=result, message="任务提交成功")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to submit job: {str(e)}")
        raise HTTPException(status_code=500, detail=f"提交任务失败: {str(e)}")


@router.get("/jobs/{job_id}", response_model=BaseResponse[GPUJob])
async def get_job(job_id: str):
    """获取任务详情"""
    try:
        result = gpu_scheduler_service.get_job(job_id)
        return BaseResponse(data=result, message="获取成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to get job: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取任务详情失败: {str(e)}")


@router.get("/jobs", response_model=BaseResponse[List[GPUJob]])
async def list_jobs(
    status: Optional[JobStatus] = Query(default=None, description="任务状态过滤"),
    priority: Optional[JobPriority] = Query(default=None, description="优先级过滤"),
    job_type: Optional[str] = Query(default=None, description="任务类型过滤"),
    submitted_by: Optional[str] = Query(default=None, description="提交者过滤"),
    node_id: Optional[str] = Query(default=None, description="节点ID过滤"),
    limit: int = Query(default=100, ge=1, le=1000, description="返回数量限制"),
):
    """列出任务"""
    try:
        result = gpu_scheduler_service.list_jobs(
            status=status,
            priority=priority,
            job_type=job_type,
            submitted_by=submitted_by,
            node_id=node_id,
            limit=limit,
        )
        return BaseResponse(data=result, message="获取成功")
    except Exception as e:
        logger.error(f"Failed to list jobs: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取任务列表失败: {str(e)}")


@router.post("/jobs/cancel", response_model=BaseResponse[JobCancelResponse])
async def cancel_job(request: JobCancelRequest):
    """取消任务"""
    try:
        result = await gpu_scheduler_service.cancel_job(
            request.job_id, request.force, request.reason
        )
        return BaseResponse(data=result, message="任务取消成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to cancel job: {str(e)}")
        raise HTTPException(status_code=500, detail=f"取消任务失败: {str(e)}")


@router.patch("/jobs/{job_id}/progress", response_model=BaseResponse[GPUJob])
async def update_job_progress(
    job_id: str,
    progress: float = Query(..., ge=0.0, le=1.0, description="任务进度 0-1"),
    metrics: Optional[dict] = None,
):
    """更新任务进度"""
    try:
        result = await gpu_scheduler_service.update_job_progress(job_id, progress, metrics)
        return BaseResponse(data=result, message="进度更新成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to update job progress: {str(e)}")
        raise HTTPException(status_code=500, detail=f"更新进度失败: {str(e)}")


@router.get("/cluster/status", response_model=BaseResponse[ClusterStatusResponse])
async def get_cluster_status():
    """获取集群状态"""
    try:
        result = gpu_scheduler_service.get_cluster_status()
        return BaseResponse(data=result, message="获取成功")
    except Exception as e:
        logger.error(f"Failed to get cluster status: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取集群状态失败: {str(e)}")


@router.post("/nodes", response_model=BaseResponse[GPUComputeNode])
async def register_node(node: GPUComputeNode):
    """注册计算节点"""
    try:
        result = gpu_scheduler_service.register_node(node)
        return BaseResponse(data=result, message="节点注册成功")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to register node: {str(e)}")
        raise HTTPException(status_code=500, detail=f"注册节点失败: {str(e)}")


@router.delete("/nodes/{node_id}", response_model=BaseResponse[bool])
async def unregister_node(node_id: str):
    """注销计算节点"""
    try:
        result = gpu_scheduler_service.unregister_node(node_id)
        if not result:
            raise HTTPException(status_code=404, detail=f"节点 {node_id} 未找到")
        return BaseResponse(data=result, message="节点注销成功")
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to unregister node: {str(e)}")
        raise HTTPException(status_code=500, detail=f"注销节点失败: {str(e)}")
