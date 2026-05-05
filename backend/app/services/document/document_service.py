from typing import List, Optional, Dict, Any
from dataclasses import dataclass

from app.services.document.text_chunker import TextChunker
from app.services.document.file_extractor import FileExtractor, ExtractedDocument
from app.clients.base import IEmbeddingClient
from app.data.vector.base import IVectorRepository, DocumentChunk


@dataclass
class DocumentProcessingResult:
    collection_name: str
    total_chunks: int
    success_chunks: int
    source_file: str
    file_type: str


class DocumentService:
    def __init__(
        self,
        file_extractor: FileExtractor,
        text_chunker: TextChunker,
        embedding_client: IEmbeddingClient,
        vector_repository: IVectorRepository
    ):
        self._file_extractor = file_extractor
        self._text_chunker = text_chunker
        self._embedding_client = embedding_client
        self._vector_repository = vector_repository

    async def process_and_store(
        self,
        file_content: bytes,
        filename: str,
        collection_name: str
    ) -> DocumentProcessingResult:
        extracted_doc = self._file_extractor.extract(file_content, filename)
        
        chunks_with_meta = self._text_chunker.chunk_with_metadata(
            text=extracted_doc.content,
            source_file=filename
        )
        
        if not chunks_with_meta:
            return DocumentProcessingResult(
                collection_name=collection_name,
                total_chunks=0,
                success_chunks=0,
                source_file=filename,
                file_type=extracted_doc.file_type
            )

        texts = [chunk for chunk, _ in chunks_with_meta]
        metadata_list = [meta for _, meta in chunks_with_meta]

        embedding_responses = await self._embedding_client.embed_batch(texts)
        vectors = [resp.embedding for resp in embedding_responses]

        document_chunks = []
        for text, vector, metadata in zip(texts, vectors, metadata_list):
            doc_chunk = DocumentChunk(
                collection_name=collection_name,
                content=text,
                vector=vector,
                metadata=metadata
            )
            document_chunks.append(doc_chunk)

        stored_count = await self._vector_repository.upsert(
            chunks=document_chunks,
            collection_name=collection_name
        )

        return DocumentProcessingResult(
            collection_name=collection_name,
            total_chunks=len(chunks_with_meta),
            success_chunks=stored_count,
            source_file=filename,
            file_type=extracted_doc.file_type
        )

    async def get_collection_count(
        self,
        collection_name: Optional[str] = None
    ) -> int:
        return await self._vector_repository.count(collection_name)

    async def delete_collection(
        self,
        collection_name: str
    ) -> bool:
        return await self._vector_repository.delete_by_collection(collection_name)
