from typing import List, Optional
import re
from abc import ABC, abstractmethod

from .schemas import Chunk, ChunkingStrategy
from common.utils import generate_id
from common.logger import get_logger

logger = get_logger(__name__)


class BaseChunker(ABC):
    strategy: ChunkingStrategy

    @abstractmethod
    def chunk(self, text: str, chunk_size: int, chunk_overlap: int, **kwargs) -> List[Chunk]:
        pass


class FixedSizeChunker(BaseChunker):
    strategy = ChunkingStrategy.FIXED_SIZE

    def chunk(self, text: str, chunk_size: int, chunk_overlap: int, **kwargs) -> List[Chunk]:
        chunks = []
        start = 0
        index = 0

        while start < len(text):
            end = min(start + chunk_size, len(text))

            if end < len(text):
                last_space = text.rfind(" ", start, end)
                last_newline = text.rfind("\n", start, end)
                break_point = max(last_space, last_newline)
                if break_point > start + chunk_size // 2:
                    end = break_point + 1

            chunk_text = text[start:end].strip()
            if chunk_text:
                chunks.append(
                    Chunk(
                        chunk_id=generate_id("chunk_"),
                        content=chunk_text,
                        index=index,
                        start_pos=start,
                        end_pos=end,
                        word_count=len(chunk_text.split()),
                    )
                )
                index += 1

            start = end - chunk_overlap
            if start >= len(text):
                break
            if start == end:
                start = end

        return chunks


class RecursiveChunker(BaseChunker):
    strategy = ChunkingStrategy.RECURSIVE

    def chunk(self, text: str, chunk_size: int, chunk_overlap: int, **kwargs) -> List[Chunk]:
        separators = kwargs.get("separators") or ["\n\n", "\n", ". ", "! ", "? ", " ", ""]

        def split_recursive(text: str, seps: List[str]) -> List[str]:
            if not seps or len(text) <= chunk_size:
                return [text]

            sep = seps[0]
            if sep and sep in text:
                parts = text.split(sep)
            else:
                return split_recursive(text, seps[1:])

            result = []
            current = ""

            for part in parts:
                if len(current) + len(part) + len(sep) <= chunk_size:
                    current += (sep if current else "") + part
                else:
                    if current:
                        result.append(current)
                    if len(part) > chunk_size:
                        result.extend(split_recursive(part, seps[1:]))
                    else:
                        current = part

            if current:
                result.append(current)

            return result

        raw_chunks = split_recursive(text, separators)
        chunks = []

        for i, chunk_text in enumerate(raw_chunks):
            chunk_text = chunk_text.strip()
            if chunk_text:
                chunks.append(
                    Chunk(
                        chunk_id=generate_id("chunk_"),
                        content=chunk_text,
                        index=i,
                        start_pos=text.find(chunk_text),
                        end_pos=text.find(chunk_text) + len(chunk_text),
                        word_count=len(chunk_text.split()),
                    )
                )

        return chunks


class ParagraphChunker(BaseChunker):
    strategy = ChunkingStrategy.PARAGRAPH

    def chunk(self, text: str, chunk_size: int, chunk_overlap: int, **kwargs) -> List[Chunk]:
        paragraphs = re.split(r"\n\s*\n", text.strip())

        chunks = []
        current_paragraphs: List[str] = []
        current_length = 0
        index = 0

        for para in paragraphs:
            para_len = len(para)

            if current_length + para_len <= chunk_size or not current_paragraphs:
                current_paragraphs.append(para)
                current_length += para_len + 2
            else:
                chunk_text = "\n\n".join(current_paragraphs).strip()
                if chunk_text:
                    chunks.append(
                        Chunk(
                            chunk_id=generate_id("chunk_"),
                            content=chunk_text,
                            index=index,
                            start_pos=text.find(chunk_text),
                            end_pos=text.find(chunk_text) + len(chunk_text),
                            word_count=len(chunk_text.split()),
                        )
                    )
                    index += 1

                current_paragraphs = [para]
                current_length = para_len

        if current_paragraphs:
            chunk_text = "\n\n".join(current_paragraphs).strip()
            if chunk_text:
                chunks.append(
                    Chunk(
                        chunk_id=generate_id("chunk_"),
                        content=chunk_text,
                        index=index,
                        start_pos=text.find(chunk_text),
                        end_pos=text.find(chunk_text) + len(chunk_text),
                        word_count=len(chunk_text.split()),
                    )
                )

        return chunks


class SentenceChunker(BaseChunker):
    strategy = ChunkingStrategy.SENTENCE

    def chunk(self, text: str, chunk_size: int, chunk_overlap: int, **kwargs) -> List[Chunk]:
        sentence_endings = re.compile(r"(?<=[.!?])\s+")
        sentences = sentence_endings.split(text.strip())

        chunks = []
        current_sentences: List[str] = []
        current_length = 0
        index = 0

        for sentence in sentences:
            sent_len = len(sentence)

            if current_length + sent_len + 1 <= chunk_size or not current_sentences:
                current_sentences.append(sentence)
                current_length += sent_len + 1
            else:
                chunk_text = " ".join(current_sentences).strip()
                if chunk_text:
                    chunks.append(
                        Chunk(
                            chunk_id=generate_id("chunk_"),
                            content=chunk_text,
                            index=index,
                            start_pos=text.find(chunk_text),
                            end_pos=text.find(chunk_text) + len(chunk_text),
                            word_count=len(chunk_text.split()),
                        )
                    )
                    index += 1

                if chunk_overlap > 0 and len(current_sentences) > 1:
                    overlap_count = max(1, chunk_overlap // chunk_size * len(current_sentences))
                    current_sentences = current_sentences[-int(overlap_count):]
                    current_length = sum(len(s) for s in current_sentences) + len(current_sentences)
                else:
                    current_sentences = []
                    current_length = 0

                current_sentences.append(sentence)
                current_length += sent_len + 1

        if current_sentences:
            chunk_text = " ".join(current_sentences).strip()
            if chunk_text:
                chunks.append(
                    Chunk(
                        chunk_id=generate_id("chunk_"),
                        content=chunk_text,
                        index=index,
                        start_pos=text.find(chunk_text),
                        end_pos=text.find(chunk_text) + len(chunk_text),
                        word_count=len(chunk_text.split()),
                    )
                )

        return chunks


class MarkdownChunker(BaseChunker):
    strategy = ChunkingStrategy.MARKDOWN

    def chunk(self, text: str, chunk_size: int, chunk_overlap: int, **kwargs) -> List[Chunk]:
        heading_pattern = re.compile(r'^(#{1,6}\s+.+)$', re.MULTILINE)

        sections = []
        current_section = ""
        current_heading = None

        for line in text.split("\n"):
            if heading_pattern.match(line):
                if current_section:
                    sections.append((current_heading, current_section.strip()))
                current_heading = line.strip()
                current_section = line + "\n"
            else:
                current_section += line + "\n"

        if current_section:
            sections.append((current_heading, current_section.strip()))

        chunks = []
        index = 0

        for heading, content in sections:
            if len(content) <= chunk_size:
                if content:
                    chunks.append(
                        Chunk(
                            chunk_id=generate_id("chunk_"),
                            content=content,
                            index=index,
                            start_pos=text.find(content),
                            end_pos=text.find(content) + len(content),
                            word_count=len(content.split()),
                            metadata={"heading": heading} if heading else {},
                        )
                    )
                    index += 1
            else:
                sub_chunks = FixedSizeChunker().chunk(content, chunk_size, chunk_overlap)
                for sc in sub_chunks:
                    sc.index = index
                    if heading:
                        sc.metadata["heading"] = heading
                    chunks.append(sc)
                    index += 1

        return chunks


CHUNKER_MAP = {
    ChunkingStrategy.FIXED_SIZE: FixedSizeChunker,
    ChunkingStrategy.RECURSIVE: RecursiveChunker,
    ChunkingStrategy.PARAGRAPH: ParagraphChunker,
    ChunkingStrategy.SENTENCE: SentenceChunker,
    ChunkingStrategy.MARKDOWN: MarkdownChunker,
}


def get_chunker(strategy: ChunkingStrategy) -> BaseChunker:
    chunker_class = CHUNKER_MAP.get(strategy)
    if not chunker_class:
        raise ValueError(f"No chunker available for strategy: {strategy}")
    return chunker_class()
