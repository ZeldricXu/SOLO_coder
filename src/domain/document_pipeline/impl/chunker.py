from typing import List, Dict, Any, Optional
from ..models import DocumentChunk, ChunkingStrategy
from ..interfaces import DocumentChunkerPort
from src.core import generate_id
import logging
import re

logger = logging.getLogger(__name__)


class DocumentChunker(DocumentChunkerPort):
    async def chunk(
        self,
        document_id: str,
        text: str,
        strategy: ChunkingStrategy,
        chunk_size: int,
        chunk_overlap: int,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> List[DocumentChunk]:
        if not text:
            return []

        if strategy == ChunkingStrategy.FIXED_SIZE:
            return await self._chunk_fixed_size(
                document_id, text, chunk_size, chunk_overlap, metadata
            )
        elif strategy == ChunkingStrategy.RECURSIVE:
            return await self._chunk_recursive(
                document_id, text, chunk_size, chunk_overlap, metadata
            )
        elif strategy == ChunkingStrategy.PARAGRAPH:
            return await self._chunk_paragraph(
                document_id, text, chunk_size, chunk_overlap, metadata
            )
        elif strategy == ChunkingStrategy.SEMANTIC:
            return await self._chunk_semantic(
                document_id, text, chunk_size, chunk_overlap, metadata
            )
        else:
            raise ValueError(f"未知的切分策略: {strategy}")

    async def _chunk_fixed_size(
        self,
        document_id: str,
        text: str,
        chunk_size: int,
        chunk_overlap: int,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> List[DocumentChunk]:
        chunks = []
        start = 0
        text_len = len(text)

        while start < text_len:
            end = min(start + chunk_size, text_len)
            chunk_text = text[start:end]

            chunks.append(
                DocumentChunk(
                    chunk_id=generate_id("chunk"),
                    document_id=document_id,
                    content=chunk_text,
                    start_index=start,
                    end_index=end,
                    metadata=metadata or {},
                )
            )

            start += chunk_size - chunk_overlap
            if start >= text_len:
                break

        return chunks

    async def _chunk_recursive(
        self,
        document_id: str,
        text: str,
        chunk_size: int,
        chunk_overlap: int,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> List[DocumentChunk]:
        chunks = []
        separators = ["\n\n", "\n", ". ", "! ", "? ", " ", ""]
        current_text = text

        for sep in separators:
            if len(current_text) <= chunk_size:
                break
            parts = current_text.split(sep) if sep else list(current_text)
            current_chunks = []
            current_chunk = ""

            for part in parts:
                if sep:
                    candidate = current_chunk + sep + part if current_chunk else part
                else:
                    candidate = current_chunk + part

                if len(candidate) <= chunk_size:
                    current_chunk = candidate
                else:
                    if current_chunk:
                        current_chunks.append(current_chunk)
                    if len(part) > chunk_size:
                        if sep:
                            sub_chunks = await self._chunk_fixed_size(
                                document_id, part, chunk_size, chunk_overlap, metadata
                            )
                            current_chunks.extend([c.content for c in sub_chunks])
                        else:
                            current_chunks.append(part[:chunk_size])
                            current_chunk = part[chunk_size - chunk_overlap:]
                    else:
                        current_chunk = part

            if current_chunk:
                current_chunks.append(current_chunk)

            if all(len(c) <= chunk_size for c in current_chunks):
                break

            current_text = sep.join(current_chunks)

        result_chunks = []
        start = 0
        for chunk_text in current_chunks if "current_chunks" in locals() else [text]:
            end = start + len(chunk_text)
            result_chunks.append(
                DocumentChunk(
                    chunk_id=generate_id("chunk"),
                    document_id=document_id,
                    content=chunk_text,
                    start_index=start,
                    end_index=end,
                    metadata=metadata or {},
                )
            )
            start = end - chunk_overlap

        return result_chunks

    async def _chunk_paragraph(
        self,
        document_id: str,
        text: str,
        chunk_size: int,
        chunk_overlap: int,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> List[DocumentChunk]:
        paragraphs = re.split(r"\n\s*\n", text)
        chunks = []
        current_chunk = ""
        start_index = 0

        for para in paragraphs:
            para_clean = para.strip()
            if not para_clean:
                continue

            if len(current_chunk) + len(para_clean) + 2 <= chunk_size:
                current_chunk += "\n\n" + para_clean if current_chunk else para_clean
            else:
                if current_chunk:
                    end_index = start_index + len(current_chunk)
                    chunks.append(
                        DocumentChunk(
                            chunk_id=generate_id("chunk"),
                            document_id=document_id,
                            content=current_chunk,
                            start_index=start_index,
                            end_index=end_index,
                            metadata=metadata or {},
                        )
                    )
                    start_index = end_index - chunk_overlap

                if len(para_clean) > chunk_size:
                    sub_chunks = await self._chunk_fixed_size(
                        document_id, para_clean, chunk_size, chunk_overlap, metadata
                    )
                    chunks.extend(sub_chunks)
                    current_chunk = ""
                    start_index = text.find(para_clean) + len(para_clean)
                else:
                    current_chunk = para_clean[max(0, len(para_clean) - chunk_overlap):]

        if current_chunk:
            chunks.append(
                DocumentChunk(
                    chunk_id=generate_id("chunk"),
                    document_id=document_id,
                    content=current_chunk,
                    start_index=start_index,
                    end_index=start_index + len(current_chunk),
                    metadata=metadata or {},
                )
            )

        return chunks

    async def _chunk_semantic(
        self,
        document_id: str,
        text: str,
        chunk_size: int,
        chunk_overlap: int,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> List[DocumentChunk]:
        sentences = re.split(r"(?<=[.!?。！？])\s+", text)
        chunks = []
        current_chunk = ""
        current_sentences = []
        start_index = 0

        for sent in sentences:
            sent_clean = sent.strip()
            if not sent_clean:
                continue

            candidate = current_chunk + " " + sent_clean if current_chunk else sent_clean

            if len(candidate) <= chunk_size:
                current_chunk = candidate
                current_sentences.append(sent_clean)
            else:
                if current_chunk:
                    end_index = start_index + len(current_chunk)
                    chunks.append(
                        DocumentChunk(
                            chunk_id=generate_id("chunk"),
                            document_id=document_id,
                            content=current_chunk,
                            start_index=start_index,
                            end_index=end_index,
                            metadata={**(metadata or {}), "sentence_count": len(current_sentences)},
                        )
                    )

                    overlap_start = max(0, len(current_sentences) - 2)
                    overlap_text = " ".join(current_sentences[overlap_start:])
                    start_index = end_index - len(overlap_text)
                    current_chunk = overlap_text + " " + sent_clean
                    current_sentences = current_sentences[overlap_start:] + [sent_clean]
                else:
                    if len(sent_clean) > chunk_size:
                        sub_chunks = await self._chunk_fixed_size(
                            document_id, sent_clean, chunk_size, chunk_overlap, metadata
                        )
                        chunks.extend(sub_chunks)
                    else:
                        current_chunk = sent_clean
                        current_sentences = [sent_clean]

        if current_chunk:
            chunks.append(
                DocumentChunk(
                    chunk_id=generate_id("chunk"),
                    document_id=document_id,
                    content=current_chunk,
                    start_index=start_index,
                    end_index=start_index + len(current_chunk),
                    metadata={**(metadata or {}), "sentence_count": len(current_sentences)},
                )
            )

        return chunks

    def get_supported_strategies(self) -> List[ChunkingStrategy]:
        return [
            ChunkingStrategy.FIXED_SIZE,
            ChunkingStrategy.RECURSIVE,
            ChunkingStrategy.PARAGRAPH,
            ChunkingStrategy.SEMANTIC,
        ]
