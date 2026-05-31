from typing import List, Optional, Dict, Any
from datetime import datetime
import time
import os

from .schemas import (
    DocumentInfo,
    DocumentParseRequest,
    DocumentParseResponse,
    ChunkingRequest,
    ChunkingResponse,
    EmbeddingRequest,
    EmbeddingResponse,
    DocumentPipelineRequest,
    DocumentPipelineResponse,
)
from .parsers import get_parser, detect_format
from .chunker import get_chunker
from common.logger import get_logger
from common.utils import generate_id, utc_now

logger = get_logger(__name__)


class DocumentPipelineService:
    def __init__(self):
        self.processed_documents: Dict[str, DocumentInfo] = {}

    async def parse_document(self, request: DocumentParseRequest) -> DocumentParseResponse:
        start_time = time.time()
        doc_id = generate_id("doc_")

        format = request.format or detect_format(request.file_name)
        if not format:
            raise ValueError(f"Could not detect format for file: {request.file_name}")

        size_bytes = 0
        if request.file_path and os.path.exists(request.file_path):
            size_bytes = os.path.getsize(request.file_path)
        elif request.file_content:
            size_bytes = len(request.file_content)

        parser = get_parser(format)

        try:
            text_content, metadata = parser.parse(
                file_path=request.file_path,
                file_content=request.file_content,
                extract_images=request.extract_images,
                extract_tables=request.extract_tables,
            )

            doc_info = DocumentInfo(
                document_id=doc_id,
                name=request.file_name,
                format=format,
                size_bytes=size_bytes,
                page_count=metadata.get("pages"),
                metadata=metadata,
                created_at=utc_now(),
            )

            self.processed_documents[doc_id] = doc_info

            duration_ms = (time.time() - start_time) * 1000

            logger.info(f"Parsed document {doc_id} ({request.file_name}) in {duration_ms:.2f}ms")

            return DocumentParseResponse(
                document=doc_info,
                text_content=text_content,
                extracted_tables=metadata.get("tables"),
                extracted_images=metadata.get("images"),
                parse_duration_ms=duration_ms,
            )
        except Exception as e:
            logger.error(f"Failed to parse document {request.file_name}: {str(e)}")
            raise

    async def chunk_text(self, request: ChunkingRequest) -> ChunkingResponse:
        chunker = get_chunker(request.strategy)

        chunks = chunker.chunk(
            text=request.text,
            chunk_size=request.chunk_size,
            chunk_overlap=request.chunk_overlap,
            separators=request.separators,
        )

        try:
            import tiktoken
            encoding = tiktoken.encoding_for_model(request.model_name or "gpt-3.5-turbo")
            for chunk in chunks:
                chunk.token_count = len(encoding.encode(chunk.content))
        except ImportError:
            logger.warning("tiktoken not installed, skipping token count")

        for chunk in chunks:
            if request.document_id:
                chunk.metadata["document_id"] = request.document_id

        logger.info(f"Created {len(chunks)} chunks using {request.strategy.value} strategy")

        return ChunkingResponse(
            chunks=chunks,
            total_chunks=len(chunks),
            strategy=request.strategy,
            chunk_size=request.chunk_size,
            chunk_overlap=request.chunk_overlap,
        )

    async def create_embeddings(self, request: EmbeddingRequest) -> EmbeddingResponse:
        start_time = time.time()

        try:
            from sentence_transformers import SentenceTransformer
            model = SentenceTransformer('all-MiniLM-L6-v2')

            embeddings = []
            for i in range(0, len(request.texts), request.batch_size):
                batch = request.texts[i:i + request.batch_size]
                batch_embeddings = model.encode(batch, normalize_embeddings=request.normalize_embeddings)
                embeddings.extend(batch_embeddings.tolist())

            dimension = len(embeddings[0]) if embeddings else 384
            total_tokens = sum(len(text.split()) for text in request.texts)

            duration_ms = (time.time() - start_time) * 1000

            logger.info(f"Created {len(embeddings)} embeddings (dim={dimension}) in {duration_ms:.2f}ms")

            return EmbeddingResponse(
                embeddings=embeddings,
                model_name=request.model_name,
                embedding_dim=dimension,
                total_tokens=total_tokens,
                duration_ms=duration_ms,
            )

        except ImportError:
            logger.warning("sentence-transformers not installed, using mock embeddings")
            import random
            dimension = 1536
            embeddings = [
                [random.uniform(-1, 1) for _ in range(dimension)]
                for _ in request.texts
            ]
            total_tokens = sum(len(text.split()) for text in request.texts)
            duration_ms = (time.time() - start_time) * 1000

            return EmbeddingResponse(
                embeddings=embeddings,
                model_name=request.model_name,
                embedding_dim=dimension,
                total_tokens=total_tokens,
                duration_ms=duration_ms,
            )

    async def process_pipeline(self, request: DocumentPipelineRequest) -> DocumentPipelineResponse:
        pipeline_id = generate_id("pipe_")
        stages: Dict[str, float] = {}
        total_start = time.time()

        parse_request = DocumentParseRequest(
            file_path=request.file_path,
            file_content=request.file_content,
            file_name=request.file_name,
            format=request.format,
            extract_images=request.extract_images,
            extract_tables=request.extract_tables,
        )

        parse_start = time.time()
        parse_result = await self.parse_document(parse_request)
        stages["parse"] = (time.time() - parse_start) * 1000

        chunk_request = ChunkingRequest(
            text=parse_result.text_content,
            strategy=request.chunking_strategy,
            chunk_size=request.chunk_size,
            chunk_overlap=request.chunk_overlap,
            document_id=parse_result.document.document_id,
        )

        chunk_start = time.time()
        chunk_result = await self.chunk_text(chunk_request)
        stages["chunking"] = (time.time() - chunk_start) * 1000

        embeddings = None
        if request.embedding_model:
            embed_request = EmbeddingRequest(
                texts=[chunk.content for chunk in chunk_result.chunks],
                model_name=request.embedding_model,
            )
            embed_start = time.time()
            embed_result = await self.create_embeddings(embed_request)
            stages["embedding"] = (time.time() - embed_start) * 1000
            embeddings = embed_result.embeddings

        if request.metadata:
            parse_result.document.metadata.update(request.metadata)

        total_duration = (time.time() - total_start) * 1000
        stages["total"] = total_duration

        logger.info(f"Pipeline {pipeline_id} completed in {total_duration:.2f}ms")

        return DocumentPipelineResponse(
            pipeline_id=pipeline_id,
            document=parse_result.document,
            chunks=chunk_result.chunks,
            embeddings=embeddings,
            total_duration_ms=total_duration,
            stages=stages,
        )

    def get_document_info(self, doc_id: str) -> DocumentInfo:
        if doc_id not in self.processed_documents:
            raise ValueError(f"Document {doc_id} not found")
        return self.processed_documents[doc_id]

    def list_documents(self, limit: int = 100) -> List[DocumentInfo]:
        docs = list(self.processed_documents.values())
        docs.sort(key=lambda d: d.created_at, reverse=True)
        return docs[:limit]


document_pipeline_service = DocumentPipelineService()
