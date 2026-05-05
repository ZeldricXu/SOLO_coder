import re
from typing import List, Tuple
from app.core.config import settings


class TextChunker:
    def __init__(
        self,
        chunk_size: int = None,
        chunk_overlap: int = None
    ):
        self.chunk_size = chunk_size or settings.CHUNK_SIZE
        self.chunk_overlap = chunk_overlap or settings.CHUNK_OVERLAP

    def split_by_sentence(self, text: str) -> List[str]:
        sentence_endings = r'(?<=[。！？.!?])\s+'
        sentences = re.split(sentence_endings, text)
        sentences = [s.strip() for s in sentences if s.strip()]
        return sentences

    def merge_sentences(self, sentences: List[str]) -> List[str]:
        chunks = []
        current_chunk = []
        current_length = 0

        for sentence in sentences:
            sentence_length = len(sentence)
            
            if current_length + sentence_length > self.chunk_size and current_chunk:
                chunks.append(''.join(current_chunk))
                
                overlap_length = 0
                overlap_sentences = []
                for s in reversed(current_chunk):
                    if overlap_length + len(s) <= self.chunk_overlap:
                        overlap_sentences.insert(0, s)
                        overlap_length += len(s)
                    else:
                        break
                
                current_chunk = overlap_sentences
                current_length = overlap_length
            
            current_chunk.append(sentence)
            current_length += sentence_length

        if current_chunk:
            chunks.append(''.join(current_chunk))

        return chunks

    def chunk(self, text: str) -> List[str]:
        if not text or not text.strip():
            return []
        
        sentences = self.split_by_sentence(text)
        
        if not sentences:
            return [text]
        
        chunks = self.merge_sentences(sentences)
        
        final_chunks = []
        for chunk in chunks:
            if len(chunk) <= self.chunk_size * 2:
                final_chunks.append(chunk)
            else:
                for i in range(0, len(chunk), self.chunk_size - self.chunk_overlap):
                    end = min(i + self.chunk_size, len(chunk))
                    final_chunks.append(chunk[i:end])
        
        return final_chunks

    def chunk_with_metadata(
        self,
        text: str,
        source_file: str = None
    ) -> List[Tuple[str, dict]]:
        chunks = self.chunk(text)
        results = []
        
        for idx, chunk in enumerate(chunks):
            metadata = {
                "source_file": source_file or "unknown",
                "chunk_index": idx,
                "total_chunks": len(chunks),
                "char_length": len(chunk)
            }
            results.append((chunk, metadata))
        
        return results
