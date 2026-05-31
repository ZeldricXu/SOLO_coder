from typing import Dict, Any, Optional, List
from datetime import datetime
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type

from ..models import (
    Document,
    ParseRequest,
    ChunkRequest,
    VectorizeRequest,
    PipelineRequest,
    PipelineResult,
)
from ..interfaces import (
    DocumentParserPort,
    DocumentChunkerPort,
    TextVectorizerPort,
    DocumentPipelineServicePort,
)
from ..impl.parser import DocumentParser
from ..impl.chunker import DocumentChunker
from ..impl.vectorizer import TextVectorizer
from ..impl.cache import MultiLevelCache

from src.core import (
    init_context,
    emit_event,
    get_metrics_collector,
    ValidationError,
    TimeoutError,
    PlatformError,
    generate_id,
    RunInstance,
)
import logging

logger = logging.getLogger(__name__)


class DocumentPipelineService(DocumentPipelineServicePort):
    def __init__(
        self,
        parser: Optional[DocumentParserPort] = None,
        chunker: Optional[DocumentChunkerPort] = None,
        vectorizer: Optional[TextVectorizerPort] = None,
        cache: Optional[MultiLevelCache] = None,
    ):
        self.parser: DocumentParserPort = parser or DocumentParser()
        self.chunker: DocumentChunkerPort = chunker or DocumentChunker()
        self.vectorizer: TextVectorizerPort = vectorizer or TextVectorizer()
        self.cache: MultiLevelCache = cache or MultiLevelCache(
            l1_max_size=1000,
            l1_ttl_seconds=300,
            l2_ttl_seconds=3600,
        )
        self._active_runs: Dict[str, RunInstance] = {}
        self._metrics = get_metrics_collector()

    async def parse_document(self, request: ParseRequest, trace_id: Optional[str] = None) -> Dict[str, Any]:
        with init_context(trace_id, operation="parse_document"):
            self._metrics.increment("document_parse_requests")
            timer_id = self._metrics.start_timer("document_parse")

            try:
                document_id = request.document_id or generate_id("doc")

                cached = await self.cache.get("parse", document_id, request.format.value)
                if cached:
                    self._metrics.increment("document_parse_cache_hit")
                    emit_event(
                        "document.parse.cached",
                        {"document_id": document_id, "source": "cache"},
                        source="document_pipeline",
                    )
                    return cached

                document = Document(
                    document_id=document_id,
                    format=request.format,
                    content=request.content,
                    metadata=request.metadata,
                )

                text = await self._parse_with_retry(document)

                result = {
                    "document_id": document_id,
                    "text": text,
                    "length": len(text),
                    "metadata": document.metadata,
                }

                await self.cache.set(result, "parse", document_id, request.format.value)

                emit_event(
                    "document.parsed",
                    {"document_id": document_id, "format": request.format, "text_length": len(text)},
                    source="document_pipeline",
                )

                self._metrics.increment("document_parse_success")
                return result

            except ValidationError:
                self._metrics.increment("document_parse_validation_error")
                raise
            except Exception as e:
                self._metrics.increment("document_parse_error")
                logger.error(f"Document parse failed: {e}")
                raise PlatformError(f"文档解析失败: {str(e)}")
            finally:
                self._metrics.stop_timer(timer_id)

    async def chunk_document(self, request: ChunkRequest, trace_id: Optional[str] = None) -> Dict[str, Any]:
        with init_context(trace_id, operation="chunk_document"):
            self._metrics.increment("document_chunk_requests")
            timer_id = self._metrics.start_timer("document_chunk")

            try:
                cache_key = (
                    "chunk",
                    request.document_id,
                    request.strategy.value,
                    str(request.chunk_size),
                    str(request.chunk_overlap),
                )
                cached = await self.cache.get(*cache_key)
                if cached:
                    self._metrics.increment("document_chunk_cache_hit")
                    return cached

                chunks = await self.chunker.chunk(
                    document_id=request.document_id,
                    text=request.text,
                    strategy=request.strategy,
                    chunk_size=request.chunk_size,
                    chunk_overlap=request.chunk_overlap,
                    metadata=request.metadata,
                )

                result = {
                    "document_id": request.document_id,
                    "chunks": chunks,
                    "chunk_count": len(chunks),
                }

                await self.cache.set(result, *cache_key)

                emit_event(
                    "document.chunked",
                    {"document_id": request.document_id, "chunk_count": len(chunks)},
                    source="document_pipeline",
                )

                self._metrics.increment("document_chunk_success")
                return result

            except Exception as e:
                self._metrics.increment("document_chunk_error")
                logger.error(f"Document chunking failed: {e}")
                raise PlatformError(f"文档切分失败: {str(e)}")
            finally:
                self._metrics.stop_timer(timer_id)

    async def vectorize_chunks(self, request: VectorizeRequest, trace_id: Optional[str] = None) -> Dict[str, Any]:
        with init_context(trace_id, operation="vectorize_chunks"):
            self._metrics.increment("vectorize_requests")
            timer_id = self._metrics.start_timer("vectorize")

            try:
                chunk_ids = "-".join(sorted([c.chunk_id for c in request.chunks]))
                cache_key = ("vectorize", chunk_ids, request.model_name)
                cached = await self.cache.get(*cache_key)
                if cached:
                    self._metrics.increment("vectorize_cache_hit")
                    return cached

                embeddings = await self.vectorizer.vectorize(
                    chunks=request.chunks,
                    model_name=request.model_name,
                )

                result = {
                    "embeddings": embeddings,
                    "count": len(embeddings),
                    "dimension": embeddings[0].dimension if embeddings else 0,
                    "model_name": request.model_name,
                }

                await self.cache.set(result, *cache_key)

                emit_event(
                    "chunks.vectorized",
                    {"chunk_count": len(request.chunks), "model": request.model_name},
                    source="document_pipeline",
                )

                self._metrics.increment("vectorize_success")
                return result

            except Exception as e:
                self._metrics.increment("vectorize_error")
                logger.error(f"Vectorization failed: {e}")
                raise PlatformError(f"向量化失败: {str(e)}")
            finally:
                self._metrics.stop_timer(timer_id)

    async def run_pipeline(self, request: PipelineRequest, trace_id: Optional[str] = None) -> PipelineResult:
        run_id = generate_id("run")
        started_at = datetime.utcnow()
        document_id = request.document_id or generate_id("doc")

        run_instance = RunInstance(
            run_id=run_id,
            entity_id=document_id,
            phase="initializing",
            progress=0.0,
            started_at=started_at,
        )
        self._active_runs[run_id] = run_instance

        with init_context(trace_id or run_id, operation="run_pipeline", run_id=run_id):
            self._metrics.increment("pipeline_requests")
            timer_id = self._metrics.start_timer("pipeline")

            try:
                cache_key = (
                    "pipeline",
                    document_id,
                    request.format.value,
                    request.chunking_strategy.value,
                    str(request.chunk_size),
                    str(request.chunk_overlap),
                    request.embedding_model,
                )
                cached = await self.cache.get(*cache_key)
                if cached:
                    self._metrics.increment("pipeline_cache_hit")
                    run_instance.phase = "completed"
                    run_instance.progress = 1.0
                    run_instance.completed_at = datetime.utcnow()
                    self._update_run(run_instance)
                    return PipelineResult(**cached)

                run_instance.phase = "parsing"
                run_instance.progress = 0.1
                self._update_run(run_instance)

                document = Document(
                    document_id=document_id,
                    format=request.format,
                    content=request.content,
                    metadata=request.metadata,
                )
                text = await self._parse_with_retry(document)
                logger.info(f"Parsed document {document_id}, length={len(text)}")

                run_instance.phase = "chunking"
                run_instance.progress = 0.4
                self._update_run(run_instance)

                chunks = await self.chunker.chunk(
                    document_id=document_id,
                    text=text,
                    strategy=request.chunking_strategy,
                    chunk_size=request.chunk_size,
                    chunk_overlap=request.chunk_overlap,
                    metadata=request.metadata,
                )
                logger.info(f"Chunked document {document_id} into {len(chunks)} chunks")

                run_instance.phase = "vectorizing"
                run_instance.progress = 0.8
                self._update_run(run_instance)

                embeddings = await self.vectorizer.vectorize(
                    chunks=chunks,
                    model_name=request.embedding_model,
                )
                logger.info(f"Vectorized {len(embeddings)} chunks for document {document_id}")

                run_instance.phase = "completed"
                run_instance.progress = 1.0
                run_instance.completed_at = datetime.utcnow()
                self._update_run(run_instance)

                result = PipelineResult(
                    document_id=document_id,
                    chunks=chunks,
                    embeddings=embeddings,
                    processing_time=(datetime.utcnow() - started_at).total_seconds(),
                    started_at=started_at,
                    completed_at=datetime.utcnow(),
                )

                await self.cache.set(result.model_dump(), *cache_key)

                emit_event(
                    "pipeline.completed",
                    {
                        "document_id": document_id,
                        "run_id": run_id,
                        "chunk_count": len(chunks),
                        "processing_time": result.processing_time,
                    },
                    source="document_pipeline",
                )

                self._metrics.increment("pipeline_success")
                return result

            except Exception as e:
                run_instance.phase = "failed"
                run_instance.error_detail = str(e)
                run_instance.completed_at = datetime.utcnow()
                self._update_run(run_instance)

                self._metrics.increment("pipeline_error")
                logger.error(f"Pipeline failed for document {document_id}: {e}")

                emit_event(
                    "pipeline.failed",
                    {"document_id": document_id, "run_id": run_id, "error": str(e)},
                    source="document_pipeline",
                )

                raise PlatformError(f"文档处理管道执行失败: {str(e)}")
            finally:
                self._metrics.stop_timer(timer_id)

    def get_run_status(self, run_id: str) -> Optional[RunInstance]:
        return self._active_runs.get(run_id)

    async def invalidate_cache(self, document_id: str) -> Dict[str, Any]:
        count = await self.cache.invalidate_document(document_id)
        return {
            "document_id": document_id,
            "invalidated_count": count,
        }

    async def warm_cache(self, entries: List[tuple]) -> Dict[str, Any]:
        warmed = await self.cache.warm_up(entries)
        return {
            "warmed_count": warmed,
            "total": len(entries),
        }

    async def get_cache_stats(self) -> Dict[str, Any]:
        return self.cache.get_stats()

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=1, max=5),
        retry=retry_if_exception_type((TimeoutError, Exception)),
        reraise=True,
    )
    async def _parse_with_retry(self, document: Document) -> str:
        return await self.parser.parse(document)

    def _update_run(self, run_instance: RunInstance) -> None:
        self._active_runs[run_instance.run_id] = run_instance
        emit_event(
            "run.updated",
            {
                "run_id": run_instance.run_id,
                "phase": run_instance.phase,
                "progress": run_instance.progress,
            },
            source="document_pipeline",
        )
