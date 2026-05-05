import re
from typing import List, Tuple, Optional
from dataclasses import dataclass
from app.core.config import settings


@dataclass
class TextChunk:
    content: str
    start_index: int
    end_index: int
    char_length: int


class TextChunker:
    def __init__(
        self,
        chunk_size: Optional[int] = None,
        chunk_overlap: Optional[int] = None
    ):
        self._chunk_size = chunk_size or settings.CHUNK_SIZE
        self._chunk_overlap = chunk_overlap or settings.CHUNK_OVERLAP

    def split_by_sentence(self, text: str) -> List[Tuple[str, int, int]]:
        sentence_endings = r'(?<=[。！？.!?])\s+'
        pattern = re.compile(sentence_endings)
        
        sentences = []
        last_end = 0
        
        for match in pattern.finditer(text):
            start = last_end
            end = match.end()
            sentence = text[start:end].strip()
            if sentence:
                sentences.append((sentence, start, end - 1))
            last_end = end
        
        if last_end < len(text):
            remaining = text[last_end:].strip()
            if remaining:
                sentences.append((remaining, last_end, len(text) - 1))
        
        return sentences

    def _create_overlap(
        self,
        chunks: List[Tuple[str, int, int]],
        target_overlap: int
    ) -> List[str]:
        if not chunks:
            return []
        
        result = []
        for i, (content, start, end) in enumerate(chunks):
            if i > 0:
                prev_content, prev_start, prev_end = chunks[i - 1]
                overlap_text = self._extract_overlap(
                    prev_content, content, target_overlap
                )
                if overlap_text:
                    result[-1] = result[-1] + overlap_text
                    content = overlap_text + content
            
            result.append(content)
        
        return result

    def _extract_overlap(
        self,
        prev_content: str,
        curr_content: str,
        target_overlap: int
    ) -> str:
        if target_overlap <= 0:
            return ""
        
        if len(prev_content) <= target_overlap:
            return prev_content
        
        overlap = prev_content[-target_overlap:]
        
        for i in range(len(overlap) - 1, 0, -1):
            if overlap[i] in '。！？.!?，,；;：:' or overlap[i].isspace():
                return overlap[i + 1:]
        
        return overlap

    def chunk(self, text: str) -> List[TextChunk]:
        if not text or not text.strip():
            return []
        
        sentences_with_pos = self.split_by_sentence(text)
        
        if not sentences_with_pos:
            return [TextChunk(
                content=text,
                start_index=0,
                end_index=len(text) - 1,
                char_length=len(text)
            )]
        
        chunks = []
        current_chunk_sentences = []
        current_length = 0
        
        for sentence, start, end in sentences_with_pos:
            sentence_length = end - start + 1
            
            if current_length + sentence_length > self._chunk_size and current_chunk_sentences:
                first_sentence_start = current_chunk_sentences[0][1]
                last_sentence_end = current_chunk_sentences[-1][2]
                chunk_content = text[first_sentence_start:last_sentence_end + 1]
                
                chunks.append(TextChunk(
                    content=chunk_content,
                    start_index=first_sentence_start,
                    end_index=last_sentence_end,
                    char_length=last_sentence_end - first_sentence_start + 1
                ))
                
                overlap_length = 0
                overlap_sentences = []
                for sent, s_start, s_end in reversed(current_chunk_sentences):
                    sent_len = s_end - s_start + 1
                    if overlap_length + sent_len <= self._chunk_overlap:
                        overlap_sentences.insert(0, (sent, s_start, s_end))
                        overlap_length += sent_len
                    else:
                        break
                
                current_chunk_sentences = overlap_sentences
                current_length = overlap_length
            
            current_chunk_sentences.append((sentence, start, end))
            current_length += sentence_length

        if current_chunk_sentences:
            first_sentence_start = current_chunk_sentences[0][1]
            last_sentence_end = current_chunk_sentences[-1][2]
            chunk_content = text[first_sentence_start:last_sentence_end + 1]
            
            chunks.append(TextChunk(
                content=chunk_content,
                start_index=first_sentence_start,
                end_index=last_sentence_end,
                char_length=last_sentence_end - first_sentence_start + 1
            ))

        return chunks

    def chunk_with_metadata(
        self,
        text: str,
        source_file: Optional[str] = None
    ) -> List[Tuple[str, dict]]:
        chunks = self.chunk(text)
        results = []
        
        for idx, chunk in enumerate(chunks):
            metadata = {
                "source_file": source_file or "unknown",
                "chunk_index": idx,
                "total_chunks": len(chunks),
                "start_index": chunk.start_index,
                "end_index": chunk.end_index,
                "char_length": chunk.char_length
            }
            results.append((chunk.content, metadata))
        
        return results
