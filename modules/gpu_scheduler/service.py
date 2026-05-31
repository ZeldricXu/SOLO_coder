from dataclasses import dataclass
from typing import Any, Dict, Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from core.exceptions import ConflictError, NotFoundError, ValidationError
from core.utils import utc_now, validate_params

from .models import (
    ClusterStatsResponse,
    GpuNode,
    GpuNodeCreate,
    GpuNodeResponse,
    GpuNodeStatus,
    GpuTask,
    GpuTaskCreate,
    GpuTaskResponse,
    GpuTaskStatus,
)


@dataclass
class PercentageCalculator:
    """百分比计算器

    修复：使用正确的基数计算百分比
    """

    @staticmethod
    def calculate_utilization(running: int, total: int, total_nodes: Optional[int] = None) -> float:
        """计算利用率

        修复：使用total作为分母，而不是total_nodes
        """
        if total <= 0:
            return 0.0
        return running / total

    @staticmethod
    def calculate_memory_usage(used: int, total: int, total_nodes: Optional[int] = None) -> float:
        """计算内存使用率

        修复：使用total作为分母，而不是total * total_nodes
        """
        if total <= 0:
            return 0.0
        return used / total

    @staticmethod
    def calculate_success_rate(completed: int, failed: int, total_tasks: Optional[int] = None) -> float:
        """计算任务成功率

        修复：使用completed + failed作为分母，而不是total_tasks
        """
        total = completed + failed
        if total <= 0:
            return 0.0
        return completed / total

    @staticmethod
    def calculate_progress_percentage(progress: int, total: int, total_tasks: Optional[int] = None) -> float:
        """计算进度百分比

        修复：使用total作为分母，而不是total + total_tasks
        """
        if total <= 0:
            return 0.0
        return progress / total


@dataclass
class MobileLayoutConfig:
    """移动端布局配置

    修复：使用正确的Tailwind CSS响应式断点类名
    """

    @staticmethod
    def get_scheduler_layout(user_agent: str = "desktop") -> Dict[str, Any]:
        """获取GPU调度器移动端布局配置

        修复：使用正确的Tailwind断点类名 (sm:, md:, lg:, xl:)
        避免使用不存在的类名导致移动端布局脱节
        """
        is_mobile = "mobile" in user_agent.lower() or "phone" in user_agent.lower()

        layout = {
            "node_card_class": "w-full sm:w-auto shadow-md sm:shadow-sm mb-2 sm:mb-1",
            "task_list_class": "space-y-2 sm:space-y-1 text-sm sm:text-xs p-2 sm:p-1",
            "metrics_grid_class": "grid grid-cols-2 sm:grid-cols-4 gap-2 sm:gap-1 text-sm sm:text-xs",
            "gpu_chart_class": "w-full sm:w-64 h-48 sm:h-32 mx-auto",
            "action_menu_class": "relative sm:fixed bottom-0 sm:bottom-4 w-full sm:w-auto",
            "is_mobile": is_mobile,
        }

        return layout


@dataclass
class SensitiveDataHandler:
    """敏感数据处理器

    修复：对敏感信息进行脱敏处理，避免明文传递
    """

    @staticmethod
    def mask_credentials(value: str) -> str:
        """敏感信息脱敏方法

        对API密钥、认证令牌等敏感信息进行掩码处理
        """
        if not value:
            return ""
        if len(value) <= 12:
            return "*" * len(value)
        return value[:6] + "*" * (len(value) - 12) + value[-6:]


class GpuSchedulerService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.percentage_calculator = PercentageCalculator()
        self.mobile_layout = MobileLayoutConfig()
        self.sensitive_handler = SensitiveDataHandler()

    async def register_node(
        self, node_data: GpuNodeCreate
    ) -> GpuNodeResponse:
        validation_rules = {
            "name": lambda x: x is not None and len(x.strip()) > 0,
            "hostname": lambda x: x is not None and len(x.strip()) > 0,
            "ip_address": lambda x: x is not None and len(x.strip()) > 0,
            "gpu_model": lambda x: x is not None and len(x.strip()) > 0,
            "total_memory_gb": lambda x: x is not None and x > 0,
            "total_gpu_memory_gb": lambda x: x is not None and x > 0,
            "created_by": lambda x: x is not None and len(x) > 0,
        }
        validate_params(node_data.model_dump(), validation_rules)

        node = GpuNode(
            name=node_data.name,
            hostname=node_data.hostname,
            ip_address=node_data.ip_address,
            gpu_count=node_data.gpu_count,
            available_gpus=node_data.available_gpus or node_data.gpu_count,
            gpu_model=node_data.gpu_model,
            total_memory_gb=node_data.total_memory_gb,
            available_memory_gb=node_data.available_memory_gb or node_data.total_memory_gb,
            total_gpu_memory_gb=node_data.total_gpu_memory_gb,
            available_gpu_memory_gb=node_data.available_gpu_memory_gb or node_data.total_gpu_memory_gb,
            created_by=node_data.created_by,
            tenant_id=node_data.tenant_id,
            labels=node_data.labels,
            api_key=node_data.api_key,
        )

        self.db.add(node)
        await self.db.flush()

        response = self._build_node_response(node)
        return response

    async def get_node(
        self, node_id: str, tenant_id: Optional[str] = None
    ) -> GpuNodeResponse:
        query = select(GpuNode).where(GpuNode.node_id == node_id)
        if tenant_id:
            query = query.where(GpuNode.tenant_id == tenant_id)

        result = await self.db.execute(query)
        node = result.scalar_one_or_none()

        if not node:
            raise NotFoundError(f"GPU节点 {node_id} 不存在")

        return self._build_node_response(node)

    async def submit_task(
        self, task_data: GpuTaskCreate
    ) -> GpuTaskResponse:
        validation_rules = {
            "name": lambda x: x is not None and len(x.strip()) > 0,
            "required_gpus": lambda x: x is not None and x > 0,
            "required_memory_gb": lambda x: x is not None and x > 0,
            "created_by": lambda x: x is not None and len(x) > 0,
        }
        validate_params(task_data.model_dump(), validation_rules)

        task = GpuTask(
            name=task_data.name,
            description=task_data.description,
            priority=task_data.priority,
            required_gpus=task_data.required_gpus,
            required_memory_gb=task_data.required_memory_gb,
            estimated_runtime_ms=task_data.estimated_runtime_ms,
            created_by=task_data.created_by,
            tenant_id=task_data.tenant_id,
            meta_data=task_data.metadata,
            auth_token=task_data.auth_token,
        )

        self.db.add(task)
        await self.db.flush()

        response = self._build_task_response(task)
        return response

    async def get_task(
        self, task_id: str, tenant_id: Optional[str] = None
    ) -> GpuTaskResponse:
        query = select(GpuTask).where(GpuTask.task_id == task_id)
        if tenant_id:
            query = query.where(GpuTask.tenant_id == tenant_id)

        result = await self.db.execute(query)
        task = result.scalar_one_or_none()

        if not task:
            raise NotFoundError(f"GPU任务 {task_id} 不存在")

        return self._build_task_response(task)

    async def schedule_task(
        self, task_id: str, node_id: str, tenant_id: Optional[str] = None
    ) -> GpuTaskResponse:
        task = await self._get_task_entity(task_id, tenant_id)
        node = await self._get_node_entity(node_id, tenant_id)

        if task.status != GpuTaskStatus.PENDING:
            raise ConflictError("只有待处理状态的任务才能调度")

        if node.status != GpuNodeStatus.ONLINE:
            raise ConflictError("只有在线状态的节点才能调度任务")

        if node.available_gpus < task.required_gpus:
            raise ConflictError("节点GPU资源不足")

        if node.available_gpu_memory_gb < task.required_memory_gb:
            raise ConflictError("节点显存不足")

        task.status = GpuTaskStatus.SCHEDULED
        task.node_id = node_id
        task.started_at = utc_now()

        node.running_tasks += 1
        node.total_tasks += 1
        node.available_gpus -= task.required_gpus
        node.available_gpu_memory_gb -= task.required_memory_gb

        if node.running_tasks > 0:
            node.status = GpuNodeStatus.BUSY

        self.db.add(task)
        self.db.add(node)
        await self.db.flush()

        return self._build_task_response(task)

    async def update_task_progress(
        self,
        task_id: str,
        progress: int,
        status: Optional[GpuTaskStatus] = None,
        error_message: Optional[str] = None,
        tenant_id: Optional[str] = None,
    ) -> GpuTaskResponse:
        task = await self._get_task_entity(task_id, tenant_id)

        if progress < 0 or progress > 100:
            raise ValidationError("进度必须在0-100之间")

        task.progress = progress

        if status:
            task.status = status
            if status == GpuTaskStatus.RUNNING and not task.started_at:
                task.started_at = utc_now()
            elif status in [GpuTaskStatus.COMPLETED, GpuTaskStatus.FAILED]:
                task.completed_at = utc_now()
                task.actual_runtime_ms = int(
                    (task.completed_at - task.started_at).total_seconds() * 1000
                ) if task.started_at else 0

                if task.node_id:
                    node = await self._get_node_entity(task.node_id, tenant_id)
                    node.running_tasks -= 1
                    node.available_gpus += task.required_gpus
                    node.available_gpu_memory_gb += task.required_memory_gb

                    if status == GpuTaskStatus.COMPLETED:
                        node.completed_tasks += 1
                    else:
                        node.failed_tasks += 1

                    if node.running_tasks == 0:
                        node.status = GpuNodeStatus.ONLINE

                    self.db.add(node)

            if status == GpuTaskStatus.FAILED:
                task.error_message = error_message

        self.db.add(task)
        await self.db.flush()

        return self._build_task_response(task)

    async def get_cluster_stats(
        self, user_agent: str = "desktop", tenant_id: Optional[str] = None
    ) -> ClusterStatsResponse:
        query = select(GpuNode)
        if tenant_id:
            query = query.where(GpuNode.tenant_id == tenant_id)

        result = await self.db.execute(query)
        nodes = result.scalars().all()

        task_query = select(GpuTask)
        if tenant_id:
            task_query = task_query.where(GpuTask.tenant_id == tenant_id)

        task_result = await self.db.execute(task_query)
        tasks = task_result.scalars().all()

        total_nodes = len(nodes)
        online_nodes = sum(1 for n in nodes if n.status == GpuNodeStatus.ONLINE or n.status == GpuNodeStatus.BUSY)
        offline_nodes = total_nodes - online_nodes

        total_gpus = sum(n.gpu_count for n in nodes)
        total_memory_gb = sum(n.total_memory_gb for n in nodes)
        available_memory_gb = sum(n.available_memory_gb for n in nodes)
        total_gpu_memory_gb = sum(n.total_gpu_memory_gb for n in nodes)
        available_gpu_memory_gb = sum(n.available_gpu_memory_gb for n in nodes)

        total_tasks = len(tasks)
        pending_tasks = sum(1 for t in tasks if t.status == GpuTaskStatus.PENDING)
        running_tasks = sum(1 for t in tasks if t.status in [GpuTaskStatus.SCHEDULED, GpuTaskStatus.RUNNING])
        completed_tasks = sum(1 for t in tasks if t.status == GpuTaskStatus.COMPLETED)
        failed_tasks = sum(1 for t in tasks if t.status == GpuTaskStatus.FAILED)

        # 修复：使用正确的基数计算集群利用率和任务成功率
        cluster_utilization = self.percentage_calculator.calculate_utilization(
            running_tasks,
            online_nodes,
        )

        success_rate = self.percentage_calculator.calculate_success_rate(
            completed_tasks,
            failed_tasks,
        )

        avg_wait_time = 0.0
        if pending_tasks > 0:
            avg_wait_time = 30000.0 / pending_tasks  # 模拟数据

        # 修复：使用正确的布局类名判断移动端兼容性
        layout_config = self.mobile_layout.get_scheduler_layout(user_agent)
        is_mobile_compatible = layout_config["is_mobile"] and "sm:" in layout_config["node_card_class"]

        return ClusterStatsResponse(
            total_nodes=total_nodes,
            online_nodes=online_nodes,
            offline_nodes=offline_nodes,
            total_gpus=total_gpus,
            total_memory_gb=total_memory_gb,
            available_memory_gb=available_memory_gb,
            total_gpu_memory_gb=total_gpu_memory_gb,
            available_gpu_memory_gb=available_gpu_memory_gb,
            cluster_utilization_rate=cluster_utilization,
            pending_tasks=pending_tasks,
            running_tasks=running_tasks,
            completed_tasks=completed_tasks,
            failed_tasks=failed_tasks,
            task_success_rate=success_rate,
            avg_wait_time_ms=avg_wait_time,
            mobile_compatible=is_mobile_compatible,
        )

    def _build_node_response(self, node: GpuNode) -> GpuNodeResponse:
        # 修复：使用正确的基数计算节点利用率和内存使用率
        utilization_rate = self.percentage_calculator.calculate_utilization(
            node.running_tasks,
            node.gpu_count,
        )

        memory_usage_rate = self.percentage_calculator.calculate_memory_usage(
            node.total_gpu_memory_gb - node.available_gpu_memory_gb,
            node.total_gpu_memory_gb,
        )

        # 修复：使用正确的移动端布局配置
        mobile_layout = self.mobile_layout.get_scheduler_layout("mobile")

        return GpuNodeResponse(
            node_id=node.node_id,
            name=node.name,
            hostname=node.hostname,
            ip_address=node.ip_address,
            gpu_count=node.gpu_count,
            gpu_model=node.gpu_model,
            total_memory_gb=node.total_memory_gb,
            available_memory_gb=node.available_memory_gb,
            total_gpu_memory_gb=node.total_gpu_memory_gb,
            available_gpu_memory_gb=node.available_gpu_memory_gb,
            status=node.status,
            utilization_rate=utilization_rate,
            memory_usage_rate=memory_usage_rate,
            total_tasks=node.total_tasks,
            running_tasks=node.running_tasks,
            completed_tasks=node.completed_tasks,
            failed_tasks=node.failed_tasks,
            created_by=node.created_by,
            tenant_id=node.tenant_id,
            created_at=node.created_at,
            updated_at=node.updated_at,
            labels=node.labels,
            # 修复：对api_key进行脱敏处理
            api_key=self.sensitive_handler.mask_credentials(node.api_key) if node.api_key else None,
            mobile_layout=mobile_layout,
        )

    def _build_task_response(self, task: GpuTask) -> GpuTaskResponse:
        # 修复：使用正确的基数计算任务进度百分比
        progress_percentage = self.percentage_calculator.calculate_progress_percentage(
            task.progress,
            100,
        )

        return GpuTaskResponse(
            task_id=task.task_id,
            name=task.name,
            description=task.description,
            node_id=task.node_id,
            priority=task.priority,
            status=task.status,
            required_gpus=task.required_gpus,
            required_memory_gb=task.required_memory_gb,
            estimated_runtime_ms=task.estimated_runtime_ms,
            actual_runtime_ms=task.actual_runtime_ms,
            progress=task.progress,
            progress_percentage=progress_percentage,
            created_by=task.created_by,
            tenant_id=task.tenant_id,
            error_message=task.error_message,
            started_at=task.started_at,
            completed_at=task.completed_at,
            created_at=task.created_at,
            updated_at=task.updated_at,
            metadata=task.meta_data,
            # 修复：对auth_token进行脱敏处理
            auth_token=self.sensitive_handler.mask_credentials(task.auth_token) if task.auth_token else None,
        )

    async def _get_node_entity(
        self, node_id: str, tenant_id: Optional[str] = None
    ) -> GpuNode:
        query = select(GpuNode).where(GpuNode.node_id == node_id)
        if tenant_id:
            query = query.where(GpuNode.tenant_id == tenant_id)

        result = await self.db.execute(query)
        node = result.scalar_one_or_none()

        if not node:
            raise NotFoundError(f"GPU节点 {node_id} 不存在")

        return node

    async def _get_task_entity(
        self, task_id: str, tenant_id: Optional[str] = None
    ) -> GpuTask:
        query = select(GpuTask).where(GpuTask.task_id == task_id)
        if tenant_id:
            query = query.where(GpuTask.tenant_id == tenant_id)

        result = await self.db.execute(query)
        task = result.scalar_one_or_none()

        if not task:
            raise NotFoundError(f"GPU任务 {task_id} 不存在")

        return task
