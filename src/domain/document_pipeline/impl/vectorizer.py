from typing import List, Dict, Any
from ..models import DocumentChunk, VectorEmbedding
from ..interfaces import TextVectorizerPort
from src.core import generate_id
import logging
import hashlib

logger = logging.getLogger(__name__)


class TextVectorizer(TextVectorizerPort):
    def __init__(self, default_dimension: int = 384):
        self.default_dimension = default_dimension
        self._model_dimensions = {
            "default-embedding": 384,
            "all-MiniLM-L6-v2": 384,
            "all-mpnet-base-v2": 768,
            "text-embedding-ada-002": 1536,
            "bge-large-zh": 1024,
            "bge-base-zh": 768,
            "bge-small-zh": 512,
        }

    async def vectorize(
        self,
        chunks: List[DocumentChunk],
        model_name: str,
    ) -> List[VectorEmbedding]:
        dimension = self._model_dimensions.get(model_name, self.default_dimension)
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

        logger.debug(f"已向量化 {len(embeddings)} 个chunk，维度={dimension}")
        return embeddings

    def _generate_pseudo_embedding(self, text: str, dimension: int) -> List[float]:
        hash_obj = hashlib.sha256(text.encode("utf-8"))
        hash_bytes = hash_obj.digest()

        vector = []
        for i in range(dimension):
            byte_idx = i % len(hash_bytes)
            byte_val = hash_bytes[byte_idx]
            next_byte_val = hash_bytes[(byte_idx + 1) % len(hash_bytes)]

            value = (byte_val * 256 + next_byte_val) / 65535.0
            value = (value - 0.5) * 2

            if i % 3 == 0:
                value *= 0.8
            elif i % 5 == 0:
                value *= 1.2

            vector.append(round(value, 6))

        norm = sum(x * x for x in vector) ** 0.5
        if norm > 0:
            vector = [x / norm for x in vector]

        return vector

    def get_supported_models(self) -> Dict[str, int]:
        return dict(self._model_dimensions)

    def get_model_dimension(self, model_name: str) -> int:
        return self._model_dimensions.get(model_name, self.default_dimension)
