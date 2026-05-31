from typing import List
import hashlib
import math
from .types import DocumentChunk, VectorEmbedding
import logging

logger = logging.getLogger(__name__)


class TextVectorizer:
    def __init__(self, default_dimension: int = 384):
        self.default_dimension = default_dimension
        self._model_dimensions = {
            "default-embedding": 384,
            "large-embedding": 1024,
            "multilingual-embedding": 768,
        }

    def get_dimension(self, model_name: str) -> int:
        return self._model_dimensions.get(model_name, self.default_dimension)

    async def vectorize(
        self, chunks: List[DocumentChunk], model_name: str = "default-embedding"
    ) -> List[VectorEmbedding]:
        logger.info(f"Vectorizing {len(chunks)} chunks using model {model_name}")
        dimension = self.get_dimension(model_name)
        embeddings = []

        for chunk in chunks:
            vector = self._generate_pseudo_embedding(chunk.content, dimension)
            embeddings.append(
                VectorEmbedding(
                    chunk_id=chunk.chunk_id,
                    vector=vector,
                    dimension=dimension,
                    model_name=model_name,
                )
            )

        logger.info(f"Generated {len(embeddings)} embeddings with dimension {dimension}")
        return embeddings

    def _generate_pseudo_embedding(self, text: str, dimension: int) -> List[float]:
        hash_bytes = hashlib.sha256(text.encode("utf-8")).digest()
        vector = []
        for i in range(dimension):
            byte_idx = i % len(hash_bytes)
            b = hash_bytes[byte_idx]
            next_b = hash_bytes[(byte_idx + 1) % len(hash_bytes)]
            value = (b * 256 + next_b) / 65535.0
            value = value * 2 - 1
            vector.append(round(value, 6))

        norm = math.sqrt(sum(x * x for x in vector))
        if norm > 0:
            vector = [round(x / norm, 6) for x in vector]

        return vector
