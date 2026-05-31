import logging
import os
import pickle
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np

from src.infrastructure.config.settings import VectorConfig

logger = logging.getLogger(__name__)


@dataclass
class VectorDocument:
    doc_id: str
    vector: np.ndarray
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "doc_id": self.doc_id,
            "metadata": self.metadata,
        }


@dataclass
class IndexStats:
    total_vectors: int = 0
    dimension: int = 0
    index_type: str = ""
    memory_usage_mb: float = 0.0
    is_trained: bool = False


class EmbeddingIndex:
    def __init__(self, config: Optional[VectorConfig] = None):
        self._config = config or VectorConfig()
        self._dimension = self._config.dimension
        self._index = None
        self._doc_ids: List[str] = []
        self._metadata: Dict[str, Dict[str, Any]] = {}
        self._vectors: Optional[np.ndarray] = None

    def build(self, documents: List[VectorDocument]) -> None:
        if not documents:
            return

        vectors = np.array([doc.vector for doc in documents], dtype=np.float32)
        self._doc_ids = [doc.doc_id for doc in documents]
        self._metadata = {doc.doc_id: doc.metadata for doc in documents}

        if vectors.shape[1] != self._dimension:
            logger.warning(f"Vector dimension mismatch: expected {self._dimension}, got {vectors.shape[1]}")
            self._dimension = vectors.shape[1]

        self._vectors = vectors
        self._build_faiss_index(vectors)

    def _build_faiss_index(self, vectors: np.ndarray) -> None:
        try:
            import faiss

            n_vectors = vectors.shape[0]

            if self._config.index_type == "FLAT":
                self._index = faiss.IndexFlatL2(self._dimension)
            elif self._config.index_type == "IVF_FLAT":
                nlist = min(self._config.nlist, n_vectors // 10) if n_vectors > 10 else 1
                quantizer = faiss.IndexFlatL2(self._dimension)
                self._index = faiss.IndexIVFFlat(quantizer, self._dimension, nlist)
                self._index.train(vectors)
            elif self._config.index_type == "IVF_PQ":
                nlist = min(self._config.nlist, n_vectors // 10) if n_vectors > 10 else 1
                m = min(8, self._dimension // 4) if self._dimension >= 32 else 1
                quantizer = faiss.IndexFlatL2(self._dimension)
                self._index = faiss.IndexIVFPQ(quantizer, self._dimension, nlist, m, 8)
                self._index.train(vectors)
            elif self._config.index_type == "HNSW":
                self._index = faiss.IndexHNSWFlat(self._dimension, 32)
            else:
                self._index = faiss.IndexFlatL2(self._dimension)

            self._index.add(vectors)
            logger.info(f"Built {self._config.index_type} index with {n_vectors} vectors, dimension={self._dimension}")

        except ImportError:
            logger.warning("faiss not available, using brute-force search")
            self._index = None
            self._vectors = vectors

    def add(self, document: VectorDocument) -> None:
        vector = document.vector.reshape(1, -1).astype(np.float32)
        self._doc_ids.append(document.doc_id)
        self._metadata[document.doc_id] = document.metadata

        if self._index is not None:
            try:
                import faiss
                self._index.add(vector)
            except Exception:
                self._index = None

        if self._vectors is not None:
            self._vectors = np.vstack([self._vectors, vector])
        else:
            self._vectors = vector

    def remove(self, doc_id: str) -> bool:
        if doc_id not in self._doc_ids:
            return False

        idx = self._doc_ids.index(doc_id)
        self._doc_ids.remove(doc_id)
        self._metadata.pop(doc_id, None)

        if self._vectors is not None:
            mask = np.ones(len(self._vectors), dtype=bool)
            mask[idx] = False
            self._vectors = self._vectors[mask]
            self._build_faiss_index(self._vectors)

        return True

    def search(self, query_vector: np.ndarray, top_k: int = 10) -> List[Tuple[str, float, Dict[str, Any]]]:
        query = query_vector.reshape(1, -1).astype(np.float32)

        if self._index is not None:
            try:
                import faiss
                if hasattr(self._index, 'nprobe'):
                    self._index.nprobe = self._config.nprobe
                distances, indices = self._index.search(query, min(top_k, len(self._doc_ids)))
                results = []
                for dist, idx in zip(distances[0], indices[0]):
                    if idx >= 0 and idx < len(self._doc_ids):
                        doc_id = self._doc_ids[idx]
                        results.append((doc_id, float(dist), self._metadata.get(doc_id, {})))
                return results
            except Exception as e:
                logger.warning(f"FAISS search failed, falling back to brute-force: {e}")

        return self._brute_force_search(query_vector, top_k)

    def _brute_force_search(self, query_vector: np.ndarray, top_k: int) -> List[Tuple[str, float, Dict[str, Any]]]:
        if self._vectors is None:
            return []
        query = query_vector.reshape(1, -1).astype(np.float32)
        if self._config.metric == "L2":
            distances = np.linalg.norm(self._vectors - query, axis=1)
        else:
            norms = np.linalg.norm(self._vectors, axis=1) * np.linalg.norm(query)
            norms = np.maximum(norms, 1e-10)
            distances = 1 - np.sum(self._vectors * query, axis=1) / norms

        top_indices = np.argsort(distances)[:top_k]
        results = []
        for idx in top_indices:
            if idx < len(self._doc_ids):
                doc_id = self._doc_ids[idx]
                results.append((doc_id, float(distances[idx]), self._metadata.get(doc_id, {})))
        return results

    def get_stats(self) -> IndexStats:
        memory_mb = 0.0
        if self._vectors is not None:
            memory_mb = self._vectors.nbytes / (1024 * 1024)
        is_trained = False
        if self._index is not None:
            try:
                import faiss
                is_trained = self._index.is_trained if hasattr(self._index, 'is_trained') else True
            except Exception:
                is_trained = True
        return IndexStats(
            total_vectors=len(self._doc_ids),
            dimension=self._dimension,
            index_type=self._config.index_type,
            memory_usage_mb=round(memory_mb, 2),
            is_trained=is_trained,
        )

    def save(self, path: str) -> None:
        save_dir = Path(path)
        save_dir.mkdir(parents=True, exist_ok=True)

        if self._index is not None:
            try:
                import faiss
                faiss.write_index(self._index, str(save_dir / "index.faiss"))
            except Exception as e:
                logger.error(f"Failed to save FAISS index: {e}")

        meta = {
            "doc_ids": self._doc_ids,
            "metadata": self._metadata,
            "dimension": self._dimension,
            "index_type": self._config.index_type,
        }
        with open(save_dir / "meta.pkl", "wb") as f:
            pickle.dump(meta, f)

        if self._vectors is not None:
            np.save(str(save_dir / "vectors.npy"), self._vectors)

    def load(self, path: str) -> None:
        save_dir = Path(path)
        if not save_dir.exists():
            raise FileNotFoundError(f"Index directory not found: {path}")

        with open(save_dir / "meta.pkl", "rb") as f:
            meta = pickle.load(f)
        self._doc_ids = meta["doc_ids"]
        self._metadata = meta["metadata"]
        self._dimension = meta["dimension"]

        vectors_path = save_dir / "vectors.npy"
        if vectors_path.exists():
            self._vectors = np.load(str(vectors_path))

        faiss_path = save_dir / "index.faiss"
        if faiss_path.exists():
            try:
                import faiss
                self._index = faiss.read_index(str(faiss_path))
            except Exception as e:
                logger.warning(f"Failed to load FAISS index: {e}")
