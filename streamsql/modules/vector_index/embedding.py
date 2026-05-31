from __future__ import annotations

import hashlib
import math
import random
from abc import ABC, abstractmethod
from typing import Any

import numpy as np

from streamsql.core.config import ConfigManager


class EmbeddingModel(ABC):
    @abstractmethod
    def encode(self, text: str) -> np.ndarray: ...

    @abstractmethod
    def encode_batch(self, texts: list[str]) -> np.ndarray: ...

    @property
    @abstractmethod
    def dimension(self) -> int: ...


class MockEmbeddingModel(EmbeddingModel):
    def __init__(self, dimension: int = 1536):
        self._dimension = dimension

    def encode(self, text: str) -> np.ndarray:
        hash_val = int(hashlib.md5(text.encode()).hexdigest(), 16)
        rng = random.Random(hash_val)
        vec = np.array([rng.gauss(0, 1) for _ in range(self._dimension)])
        norm = np.linalg.norm(vec)
        return vec / norm if norm > 0 else vec

    def encode_batch(self, texts: list[str]) -> np.ndarray:
        return np.array([self.encode(text) for text in texts])

    @property
    def dimension(self) -> int:
        return self._dimension


class OpenAIEmbeddingModel(EmbeddingModel):
    def __init__(self, api_key: str, model: str = "text-embedding-ada-002"):
        self.api_key = api_key
        self.model = model
        self._dimension = 1536 if model == "text-embedding-ada-002" else 3072

    def encode(self, text: str) -> np.ndarray:
        try:
            import httpx
            response = httpx.post(
                "https://api.openai.com/v1/embeddings",
                headers={"Authorization": f"Bearer {self.api_key}"},
                json={"input": text, "model": self.model},
                timeout=30,
            )
            data = response.json()
            return np.array(data["data"][0]["embedding"])
        except Exception:
            mock = MockEmbeddingModel(self._dimension)
            return mock.encode(text)

    def encode_batch(self, texts: list[str]) -> np.ndarray:
        try:
            import httpx
            response = httpx.post(
                "https://api.openai.com/v1/embeddings",
                headers={"Authorization": f"Bearer {self.api_key}"},
                json={"input": texts, "model": self.model},
                timeout=60,
            )
            data = response.json()
            return np.array([item["embedding"] for item in data["data"]])
        except Exception:
            mock = MockEmbeddingModel(self._dimension)
            return mock.encode_batch(texts)

    @property
    def dimension(self) -> int:
        return self._dimension


class SentenceTransformerModel(EmbeddingModel):
    def __init__(self, model_name: str = "all-MiniLM-L6-v2"):
        self.model_name = model_name
        self._model = None
        self._dimension = 384

    def _load_model(self):
        if self._model is None:
            try:
                from sentence_transformers import SentenceTransformer
                self._model = SentenceTransformer(self.model_name)
            except ImportError:
                self._model = MockEmbeddingModel(self._dimension)
        return self._model

    def encode(self, text: str) -> np.ndarray:
        model = self._load_model()
        return model.encode(text)

    def encode_batch(self, texts: list[str]) -> np.ndarray:
        model = self._load_model()
        return model.encode(texts)

    @property
    def dimension(self) -> int:
        return self._dimension


class EmbeddingService:
    def __init__(self, model: EmbeddingModel | None = None, batch_size: int = 32):
        config = ConfigManager.get()
        self.default_dimension = config.modules.vector_index.default_dimension
        self.batch_size = batch_size
        self.model = model or MockEmbeddingModel(self.default_dimension)
        self._cache: dict[str, np.ndarray] = {}

    def encode(self, text: str, use_cache: bool = True) -> np.ndarray:
        if use_cache and text in self._cache:
            return self._cache[text]

        vector = self.model.encode(text)

        if use_cache:
            self._cache[text] = vector

        return vector

    def encode_batch(self, texts: list[str], use_cache: bool = True) -> np.ndarray:
        if use_cache:
            results: list[np.ndarray] = []
            to_encode: list[str] = []
            to_encode_indices: list[int] = []

            for i, text in enumerate(texts):
                if text in self._cache:
                    results.append(self._cache[text])
                else:
                    results.append(np.zeros(self.dimension))
                    to_encode.append(text)
                    to_encode_indices.append(i)

            if to_encode:
                encoded = self.model.encode_batch(to_encode)
                for i, text in enumerate(to_encode):
                    vec = encoded[i]
                    results[to_encode_indices[i]] = vec
                    if use_cache:
                        self._cache[text] = vec

            return np.array(results)
        else:
            return self.model.encode_batch(texts)

    def encode_documents(
        self,
        documents: list[dict[str, Any]],
        text_fields: list[str] | None = None,
    ) -> tuple[np.ndarray, list[str]]:
        if text_fields is None:
            text_fields = ["title", "content", "text"]

        texts: list[str] = []
        ids: list[str] = []

        for doc in documents:
            text_parts = []
            for field in text_fields:
                if field in doc and doc[field]:
                    text_parts.append(str(doc[field]))
            combined_text = " ".join(text_parts)
            texts.append(combined_text)
            ids.append(str(doc.get("id", hash(combined_text))))

        vectors = self.encode_batch(texts)
        return vectors, ids

    def normalize(self, vector: np.ndarray) -> np.ndarray:
        norm = np.linalg.norm(vector)
        if norm == 0:
            return vector
        return vector / norm

    def similarity(self, vec1: np.ndarray, vec2: np.ndarray) -> float:
        return float(np.dot(self.normalize(vec1), self.normalize(vec2)))

    def clear_cache(self) -> None:
        self._cache.clear()

    @property
    def dimension(self) -> int:
        return self.model.dimension

    @property
    def cache_size(self) -> int:
        return len(self._cache)
