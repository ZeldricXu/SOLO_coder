from dataclasses import dataclass
from typing import Any, Dict, Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from core.exceptions import ConflictError, NotFoundError
from core.utils import utc_now, validate_params

from .models import (
    DocumentChunk,
    DocumentChunkCreate,
    DocumentChunkResponse,
    DocumentPipeline,
    DocumentPipelineCreate,
    DocumentPipelineResponse,
    DocumentStatus,
    DocumentTask,
    DocumentTaskCreate,
    DocumentTaskResponse,
    PipelineStatsResponse,
    PipelineStatus,
)


@dataclass
class PercentageCalculator:
    """百分比计算器

    修复：使用正确的基数计算百分比
    """

    @staticmethod
    def calculate_success_rate(processed: int, failed: int, total_documents: Optional[int] = None, total_chunks: Optional[int] = None) -> float:
        """计算成功率

        修复：使用processed + failed作为分母，而不是total_chunks
        """
        total = processed + failed
        if total <= 0:
            return 0.0
        return processed / total

    @staticmethod
    def calculate_progress(processed: int, total: int, total_chunks: Optional[int] = None) -> float:
        """计算进度百分比

        修复：使用total作为分母，而不是total_chunks
        """
        if total <= 0:
            return 0.0
        return processed / total


@dataclass
class MobileLayoutConfig:
    """移动端布局配置

    修复：使用正确的Tailwind CSS响应式断点类名
    """

    @staticmethod
    def get_pipeline_layout(user_agent: str = "desktop") -> Dict[str, Any]:
        """获取文档管道移动端布局配置

        修复：使用正确的Tailwind断点类名 (sm:, md:, lg:, xl:)
        避免使用不存在的类名导致移动端布局脱节
        """
        is_mobile = "mobile" in user_agent.lower() or "phone" in user_agent.lower()

        layout = {
            "progress_bar_class": "w-full h-2 sm:h-3 rounded-full",
            "stats_grid_class": "grid grid-cols-2 sm:grid-cols-4 gap-2 sm:gap-4 p-2 sm:p-4",
            "document_list_class": "space-y-2 sm:space-y-3 text-sm sm:text-base",
            "chart_container_class": "w-full sm:w-80 h-48 sm:h-64 mx-auto",
            "action_button_class": "w-full sm:w-auto py-2 px-4 text-sm sm:text-base",
            "is_mobile": is_mobile,
        }

        return layout


@dataclass
class SensitiveDataHandler:
    """敏感数据处理器

    修复：对敏感信息进行脱敏处理，避免明文传递
    """

    @staticmethod
    def mask_field(value: str) -> str:
        """敏感信息脱敏方法

        对API密钥、访问令牌等敏感信息进行掩码处理
        """
        if not value:
            return ""
        if len(value) <= 8:
            return "*" * len(value)
        return value[:4] + "*" * (len(value) - 8) + value[-4:]


class DocumentPipelineService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.percentage_calculator = PercentageCalculator()
        self.mobile_layout = MobileLayoutConfig()
        self.sensitive_handler = SensitiveDataHandler()

    async def create_pipeline(
        self, pipeline_data: DocumentPipelineCreate
    ) -> DocumentPipelineResponse:
        validation_rules = {
            "name": lambda x: x is not None and len(x.strip()) > 0,
            "created_by": lambda x: x is not None and len(x) > 0,
            "chunk_size": lambda x: x is not None and x > 0,
        }
        validate_params(pipeline_data.model_dump(), validation_rules)

        pipeline = DocumentPipeline(
            name=pipeline_data.name,
            description=pipeline_data.description,
            source_type=pipeline_data.source_type,
            chunk_size=pipeline_data.chunk_size,
            chunk_overlap=pipeline_data.chunk_overlap,
            embedding_model=pipeline_data.embedding_model,
            vector_dimension=pipeline_data.vector_dimension,
            created_by=pipeline_data.created_by,
            tenant_id=pipeline_data.tenant_id,
            config=pipeline_data.config,
            api_key=pipeline_data.api_key,
        )

        self.db.add(pipeline)
        await self.db.flush()

        response = self._build_pipeline_response(pipeline)
        return response

    async def get_pipeline(
        self, pipeline_id: str, tenant_id: Optional[str] = None
    ) -> DocumentPipelineResponse:
        query = select(DocumentPipeline).where(DocumentPipeline.pipeline_id == pipeline_id)
        if tenant_id:
            query = query.where(DocumentPipeline.tenant_id == tenant_id)

        result = await self.db.execute(query)
        pipeline = result.scalar_one_or_none()

        if not pipeline:
            raise NotFoundError(f"文档管道 {pipeline_id} 不存在")

        return self._build_pipeline_response(pipeline)

    async def submit_document_task(
        self, task_data: DocumentTaskCreate
    ) -> DocumentTaskResponse:
        validation_rules = {
            "pipeline_id": lambda x: x is not None and len(x) > 0,
            "file_name": lambda x: x is not None and len(x) > 0,
            "file_path": lambda x: x is not None and len(x) > 0,
            "created_by": lambda x: x is not None and len(x) > 0,
        }
        validate_params(task_data.model_dump(), validation_rules)

        pipeline = await self._get_pipeline_entity(task_data.pipeline_id, task_data.tenant_id)

        if pipeline.status == PipelineStatus.FAILED:
            raise ConflictError("管道已失败，无法提交新任务")

        task = DocumentTask(
            pipeline_id=task_data.pipeline_id,
            file_name=task_data.file_name,
            file_path=task_data.file_path,
            file_size=task_data.file_size,
            file_type=task_data.file_type,
            vector_store=task_data.vector_store,
            created_by=task_data.created_by,
            tenant_id=task_data.tenant_id,
            meta_data=task_data.metadata,
            access_token=task_data.access_token,
        )

        pipeline.total_documents += 1

        self.db.add(task)
        self.db.add(pipeline)
        await self.db.flush()

        response = self._build_task_response(task)
        return response

    async def get_task(
        self, task_id: str, tenant_id: Optional[str] = None
    ) -> DocumentTaskResponse:
        query = select(DocumentTask).where(DocumentTask.task_id == task_id)
        if tenant_id:
            query = query.where(DocumentTask.tenant_id == tenant_id)

        result = await self.db.execute(query)
        task = result.scalar_one_or_none()

        if not task:
            raise NotFoundError(f"文档任务 {task_id} 不存在")

        return self._build_task_response(task)

    async def update_task_progress(
        self,
        task_id: str,
        status: DocumentStatus,
        processed_chunks: int,
        total_chunks: int,
        error_message: Optional[str] = None,
        tenant_id: Optional[str] = None,
    ) -> DocumentTaskResponse:
        task = await self._get_task_entity(task_id, tenant_id)
        pipeline = await self._get_pipeline_entity(task.pipeline_id, tenant_id)

        task.status = status
        task.processed_chunks = processed_chunks
        task.total_chunks = total_chunks

        if status == DocumentStatus.COMPLETED:
            pipeline.processed_documents += 1
            pipeline.total_chunks += total_chunks
            task.processing_time_ms = int((utc_now() - task.created_at).total_seconds() * 1000)
        elif status == DocumentStatus.FAILED:
            pipeline.failed_documents += 1
            task.error_message = error_message

        if pipeline.total_documents > 0 and (pipeline.processed_documents + pipeline.failed_documents) == pipeline.total_documents:
            pipeline.status = PipelineStatus.COMPLETED

        self.db.add(task)
        self.db.add(pipeline)
        await self.db.flush()

        return self._build_task_response(task)

    async def create_chunk(
        self, chunk_data: DocumentChunkCreate
    ) -> DocumentChunkResponse:
        validation_rules = {
            "task_id": lambda x: x is not None and len(x) > 0,
            "pipeline_id": lambda x: x is not None and len(x) > 0,
            "content": lambda x: x is not None and len(x) > 0,
        }
        validate_params(chunk_data.model_dump(), validation_rules)

        chunk = DocumentChunk(
            task_id=chunk_data.task_id,
            pipeline_id=chunk_data.pipeline_id,
            chunk_index=chunk_data.chunk_index,
            content=chunk_data.content,
            word_count=chunk_data.word_count,
            token_count=chunk_data.token_count,
            embedding=chunk_data.embedding,
            tenant_id=chunk_data.tenant_id,
            meta_data=chunk_data.metadata,
        )

        self.db.add(chunk)
        await self.db.flush()

        return DocumentChunkResponse(
            chunk_id=chunk.chunk_id,
            task_id=chunk.task_id,
            pipeline_id=chunk.pipeline_id,
            chunk_index=chunk.chunk_index,
            content=chunk.content,
            word_count=chunk.word_count,
            token_count=chunk.token_count,
            has_embedding=len(chunk.embedding) > 0,
            tenant_id=chunk.tenant_id,
            created_at=chunk.created_at,
        )

    async def get_pipeline_stats(
        self, pipeline_id: str, user_agent: str = "desktop", tenant_id: Optional[str] = None
    ) -> PipelineStatsResponse:
        pipeline = await self._get_pipeline_entity(pipeline_id, tenant_id)

        # 修复：使用正确的基数计算成功率和进度
        success_rate = self.percentage_calculator.calculate_success_rate(
            pipeline.processed_documents,
            pipeline.failed_documents,
        )

        progress = self.percentage_calculator.calculate_progress(
            pipeline.processed_documents + pipeline.failed_documents,
            pipeline.total_documents,
        )

        avg_chunk_size = 0.0
        if pipeline.processed_documents > 0 and pipeline.total_chunks > 0:
            avg_chunk_size = pipeline.total_chunks / pipeline.processed_documents

        avg_processing_time = 0.0
        if pipeline.processed_documents > 0:
            avg_processing_time = 5000.0 / pipeline.processed_documents  # 模拟数据

        throughput = 0.0
        if progress > 0 and pipeline.processed_documents > 0:
            elapsed_hours = 0.1  # 模拟数据
            throughput = pipeline.processed_documents / elapsed_hours

        # 修复：使用正确的布局类名判断移动端兼容性
        layout_config = self.mobile_layout.get_pipeline_layout(user_agent)
        is_mobile_compatible = layout_config["is_mobile"] and "sm:" in layout_config["progress_bar_class"]

        return PipelineStatsResponse(
            pipeline_id=pipeline.pipeline_id,
            name=pipeline.name,
            status=pipeline.status,
            total_documents=pipeline.total_documents,
            processed_documents=pipeline.processed_documents,
            failed_documents=pipeline.failed_documents,
            total_chunks=pipeline.total_chunks,
            processing_progress=progress,
            success_rate=success_rate,
            avg_chunk_size=avg_chunk_size,
            avg_processing_time_per_document=avg_processing_time,
            throughput_per_hour=throughput,
            mobile_compatible=is_mobile_compatible,
        )

    def _build_pipeline_response(self, pipeline: DocumentPipeline) -> DocumentPipelineResponse:
        # 修复：使用正确的基数计算成功率和进度
        success_rate = self.percentage_calculator.calculate_success_rate(
            pipeline.processed_documents,
            pipeline.failed_documents,
        )

        progress = self.percentage_calculator.calculate_progress(
            pipeline.processed_documents + pipeline.failed_documents,
            pipeline.total_documents,
        )

        # 修复：使用正确的移动端布局配置
        mobile_layout = self.mobile_layout.get_pipeline_layout("mobile")

        return DocumentPipelineResponse(
            pipeline_id=pipeline.pipeline_id,
            name=pipeline.name,
            description=pipeline.description,
            source_type=pipeline.source_type,
            chunk_size=pipeline.chunk_size,
            chunk_overlap=pipeline.chunk_overlap,
            embedding_model=pipeline.embedding_model,
            vector_dimension=pipeline.vector_dimension,
            status=pipeline.status,
            total_documents=pipeline.total_documents,
            processed_documents=pipeline.processed_documents,
            failed_documents=pipeline.failed_documents,
            total_chunks=pipeline.total_chunks,
            processing_progress=progress,
            success_rate=success_rate,
            created_by=pipeline.created_by,
            tenant_id=pipeline.tenant_id,
            created_at=pipeline.created_at,
            updated_at=pipeline.updated_at,
            config=pipeline.config,
            # 修复：对api_key进行脱敏处理
            api_key=self.sensitive_handler.mask_field(pipeline.api_key) if pipeline.api_key else None,
            mobile_layout=mobile_layout,
        )

    def _build_task_response(self, task: DocumentTask) -> DocumentTaskResponse:
        # 修复：使用正确的基数计算进度
        progress = self.percentage_calculator.calculate_progress(
            task.processed_chunks,
            task.total_chunks,
        )

        return DocumentTaskResponse(
            task_id=task.task_id,
            pipeline_id=task.pipeline_id,
            file_name=task.file_name,
            file_path=task.file_path,
            file_size=task.file_size,
            file_type=task.file_type,
            status=task.status,
            total_chunks=task.total_chunks,
            processed_chunks=task.processed_chunks,
            processing_progress=progress,
            vector_store=task.vector_store,
            error_message=task.error_message,
            processing_time_ms=task.processing_time_ms,
            created_by=task.created_by,
            tenant_id=task.tenant_id,
            created_at=task.created_at,
            updated_at=task.updated_at,
            metadata=task.meta_data,
            # 修复：对access_token进行脱敏处理
            access_token=self.sensitive_handler.mask_field(task.access_token) if task.access_token else None,
        )

    async def _get_pipeline_entity(
        self, pipeline_id: str, tenant_id: Optional[str] = None
    ) -> DocumentPipeline:
        query = select(DocumentPipeline).where(DocumentPipeline.pipeline_id == pipeline_id)
        if tenant_id:
            query = query.where(DocumentPipeline.tenant_id == tenant_id)

        result = await self.db.execute(query)
        pipeline = result.scalar_one_or_none()

        if not pipeline:
            raise NotFoundError(f"文档管道 {pipeline_id} 不存在")

        return pipeline

    async def _get_task_entity(
        self, task_id: str, tenant_id: Optional[str] = None
    ) -> DocumentTask:
        query = select(DocumentTask).where(DocumentTask.task_id == task_id)
        if tenant_id:
            query = query.where(DocumentTask.tenant_id == tenant_id)

        result = await self.db.execute(query)
        task = result.scalar_one_or_none()

        if not task:
            raise NotFoundError(f"文档任务 {task_id} 不存在")

        return task
