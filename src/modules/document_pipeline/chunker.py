from typing import List, Dict, Any
from .types import DocumentChunk, ChunkingStrategy
from src.core import ValidationError, generate_id
import logging
import re

logger = logging.getLogger(__name__)


class DocumentChunker:
    def __init__(self):
        self._strategies = {
            ChunkingStrategy.FIXED_SIZE: self._chunk_fixed_size,
            ChunkingStrategy.SEMANTIC: self._chunk_semantic,
            ChunkingStrategy.RECURSIVE: self._chunk_recursive,
            ChunkingStrategy.PARAGRAPH: self._chunk_paragraph,
        }

    async def chunk(
        self,
        document_id: str,
        text: str,
        strategy: ChunkingStrategy,
        chunk_size: int = 1000,
        chunk_overlap: int = 200,
        metadata: Dict[str, Any] = None,
    ) -> List[DocumentChunk]:
        logger.info(
            f"Chunking document {document_id}, strategy={strategy}, "
            f"chunk_size={chunk_size}, overlap={chunk_overlap}"
        )
        chunker = self._strategies.get(strategy)
        if not chunker:
            raise ValidationError(f"Unsupported chunking strategy: {strategy}")

        chunks = await chunker(document_id, text, chunk_size, chunk_overlap)
        for i, chunk in enumerate(chunks):
            chunk.metadata.update(metadata or {})
            chunk.metadata["chunk_index"] = i
            chunk.metadata["total_chunks"] = len(chunks)
        return chunks

    async def _chunk_fixed_size(
        self, document_id: str, text: str, chunk_size: int, chunk_overlap: int
    ) -> List[DocumentChunk]:
        chunks = []
        start = 0
        text_len = len(text)

        while start < text_len:
            end = min(start + chunk_size, text_len)
            chunk_text = text[start:end]
            chunks.append(
                DocumentChunk(
                    chunk_id=generate_id("chk"),
                    document_id=document_id,
                    content=chunk_text,
                    start_index=start,
                    end_index=end,
                )
            )
            start += chunk_size - chunk_overlap
            if start >= text_len:
                break

        return chunks

    async def _chunk_recursive(
        self, document_id: str, text: str, chunk_size: int, chunk_overlap: int
    ) -> List[DocumentChunk]:
        separators = ["\n\n", "\n", ". ", " ", ""]
        return await self._recursive_split(
            document_id, text, separators, chunk_size, chunk_overlap, 0
        )

    async def _recursive_split(
        self,
        document_id: str,
        text: str,
        separators: List[str],
        chunk_size: int,
        chunk_overlap: int,
        start_offset: int,
    ) -> List[DocumentChunk]:
        if len(text) <= chunk_size:
            return [
                DocumentChunk(
                    chunk_id=generate_id("chk"),
                    document_id=document_id,
                    content=text,
                    start_index=start_offset,
                    end_index=start_offset + len(text),
                )
            ]

        separator = separators[0]
        next_separators = separators[1:] if len(separators) > 1 else separators

        if separator:
            parts = text.split(separator)
        else:
            parts = list(text)

        chunks = []
        current_parts = []
        current_len = 0
        current_start = start_offset

        for part in parts:
            part_len = len(part) + (len(separator) if separator else 0)
            if current_len + part_len > chunk_size and current_parts:
                chunk_text = separator.join(current_parts)
                if len(chunk_text) > 0:
                    chunks.append(
                        DocumentChunk(
                            chunk_id=generate_id("chk"),
                            document_id=document_id,
                            content=chunk_text,
                            start_index=current_start,
                            end_index=current_start + len(chunk_text),
                        )
                    )
                    overlap_len = min(chunk_overlap, len(chunk_text))
                    current_start += len(chunk_text) - overlap_len
                    current_parts = []
                    current_len = 0
            current_parts.append(part)
            current_len += part_len

        if current_parts:
            chunk_text = separator.join(current_parts)
            if len(chunk_text) > 0:
                chunks.append(
                    DocumentChunk(
                        chunk_id=generate_id("chk"),
                        document_id=document_id,
                        content=chunk_text,
                        start_index=current_start,
                        end_index=current_start + len(chunk_text),
                    )
                )

        if next_separators and any(len(c.content) > chunk_size for c in chunks):
            result = []
            offset = start_offset
            for chunk in chunks:
                if len(chunk.content) > chunk_size:
                    sub_chunks = await self._recursive_split(
                        document_id,
                        chunk.content,
                        next_separators,
                        chunk_size,
                        chunk_overlap,
                        chunk.start_index,
                    )
                    result.extend(sub_chunks)
                else:
                    result.append(chunk)
            return result

        return chunks

    async def _chunk_paragraph(
        self, document_id: str, text: str, chunk_size: int, chunk_overlap: int
    ) -> List[DocumentChunk]:
        paragraphs = re.split(r"\n\s*\n", text)
        chunks = []
        current_paragraphs = []
        current_len = 0

        for para in paragraphs:
            para_len = len(para)
            if current_len + para_len > chunk_size and current_paragraphs:
                chunk_text = "\n\n".join(current_paragraphs)
                chunks.append(
                    DocumentChunk(
                        chunk_id=generate_id("chk"),
                        document_id=document_id,
                        content=chunk_text,
                        start_index=0,
                        end_index=len(chunk_text),
                    )
                )
                current_paragraphs = []
                current_len = 0
            current_paragraphs.append(para)
            current_len += para_len

        if current_paragraphs:
            chunk_text = "\n\n".join(current_paragraphs)
            chunks.append(
                DocumentChunk(
                    chunk_id=generate_id("chk"),
                    document_id=document_id,
                    content=chunk_text,
                    start_index=0,
                    end_index=len(chunk_text),
                )
            )

        return chunks

    async def _chunk_semantic(
        self, document_id: str, text: str, chunk_size: int, chunk_overlap: int
    ) -> List[DocumentChunk]:
        logger.info(f"Using semantic chunking for document {document_id}")
        sentences = re.split(r"(?<=[.!?])\s+", text)
        chunks = []
        current_sentences = []
        current_len = 0

        for sent in sentences:
            sent_len = len(sent)
            if current_len + sent_len > chunk_size and current_sentences:
                chunk_text = " ".join(current_sentences)
                chunks.append(
                    DocumentChunk(
                        chunk_id=generate_id("chk"),
                        document_id=document_id,
                        content=chunk_text,
                        start_index=0,
                        end_index=len(chunk_text),
                    )
                )
                overlap_count = min(
                    len(current_sentences),
                    max(1, int(chunk_overlap / max(chunk_size / len(current_sentences), 1))),
                )
                current_sentences = current_sentences[-overlap_count:] if overlap_count > 0 else []
                current_len = sum(len(s) for s in current_sentences)
            current_sentences.append(sent)
            current_len += sent_len

        if current_sentences:
            chunk_text = " ".join(current_sentences)
            chunks.append(
                DocumentChunk(
                    chunk_id=generate_id("chk"),
                    document_id=document_id,
                    content=chunk_text,
                    start_index=0,
                    end_index=len(chunk_text),
                )
            )

        return chunks
