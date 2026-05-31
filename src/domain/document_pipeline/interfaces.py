from typing import Protocol, List, Optional, Dict, Any, runtime_checkable
from abc import abstractmethod

from .models import (
    Document,
    DocumentChunk,
    VectorEmbedding,
    ParseRequest,
    ChunkRequest,
    VectorizeRequest,
    PipelineRequest,
    PipelineResult,
)
from src.core import RunInstance


@runtime_checkable
class DocumentParserPort(Protocol):
    @abstractmethod
    async def parse(self, document: Document) -> str:
        ...


@runtime_checkable
class DocumentChunkerPort(Protocol):
    @abstractmethod
    async def chunk(
        self,
        document_id: str,
        text: str,
        strategy: Any,
        chunk_size: int,
        chunk_overlap: int,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> List[DocumentChunk]:
        ...


@runtime_checkable
class TextVectorizerPort(Protocol):
    @abstractmethod
    async def vectorize(
        self,
        chunks: List[DocumentChunk],
        model_name: str,
    ) -> List[VectorEmbedding]:
        ...


@runtime_checkable
class DocumentPipelineServicePort(Protocol):
    @abstractmethod
    async def parse_document(
        self, request: ParseRequest, trace_id: Optional[str] = None
    ) -> Dict[str, Any]:
        ...

    @abstractmethod
    async def chunk_document(
        self, request: ChunkRequest, trace_id: Optional[str] = None
    ) -> Dict[str, Any]:
        ...

    @abstractmethod
    async def vectorize_chunks(
        self, request: VectorizeRequest, trace_id: Optional[str] = None
    ) -> Dict[str, Any]:
        ...

    @abstractmethod
    async def run_pipeline(
        self, request: PipelineRequest, trace_id: Optional[str] = None
    ) -> PipelineResult:
        ...

    @abstractmethod
    def get_run_status(self, run_id: str) -> Optional[RunInstance]:
        ...
