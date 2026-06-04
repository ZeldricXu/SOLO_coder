from __future__ import annotations
from typing import Optional, List, Dict, Any, Tuple, TYPE_CHECKING
import os
import json
import pickle
import asyncio
from datetime import datetime, timezone
from loguru import logger
import numpy as np

if TYPE_CHECKING:
    import faiss

try:
    import faiss
    FAISS_AVAILABLE = True
except ImportError:
    faiss = None
    FAISS_AVAILABLE = False

from recommendation_engine.infrastructure import RedisClient, PostgresClient
from recommendation_engine.models.schemas import ContentItem, ContentEmbedding
from .embedding_service_client import EmbeddingServiceClient
from config import settings


class ContentEmbeddingIndex:
    _instance: Optional["ContentEmbeddingIndex"] = None

    def __new__(cls) -> "ContentEmbeddingIndex":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    async def initialize(
        self,
        redis_client: RedisClient,
        postgres_client: PostgresClient,
        embedding_client: Optional[EmbeddingServiceClient] = None,
    ) -> None:
        if not FAISS_AVAILABLE:
            logger.warning("faiss is not installed, using in-memory fallback. For better performance, install with: pip install faiss-cpu")
        self._redis = redis_client
        self._postgres = postgres_client
        self._embedding_client = embedding_client or EmbeddingServiceClient()
        await self._embedding_client.initialize()

        self._index: Optional[Any] = None
        self._id_mapping: Dict[int, str] = {}
        self._reverse_mapping: Dict[str, int] = {}
        self._next_id: int = 0
        self._dirty_ids: set = set()
        self._pending_updates: List[Tuple[str, np.ndarray]] = []
        self._lock = asyncio.Lock()

        self._index_path = settings.faiss_index_path
        self._embedding_dim = settings.faiss_embedding_dim
        self._index_type = settings.faiss_index_type

        os.makedirs(self._index_path, exist_ok=True)
        await self._load_or_build_index()

        logger.info("ContentEmbeddingIndex initialized")

    async def close(self) -> None:
        await self._embedding_client.close()
        await self._save_index()
        logger.info("ContentEmbeddingIndex closed")

    def _create_index(self) -> "faiss.Index":
        if self._index_type == "Flat":
            return faiss.IndexFlatIP(self._embedding_dim)
        elif self._index_type.startswith("IVF"):
            parts = self._index_type.split(",")
            nlist = int(parts[0].replace("IVF", ""))
            quantizer = faiss.IndexFlatIP(self._embedding_dim)
            index = faiss.IndexIVFFlat(
                quantizer, self._embedding_dim, nlist, faiss.METRIC_INNER_PRODUCT
            )
            index.nprobe = settings.faiss_nprobe
            return index
        elif self._index_type.startswith("HNSW"):
            m = int(self._index_type.split("HNSW")[1].split(",")[0]) if "," in self._index_type else 32
            index = faiss.IndexHNSWFlat(self._embedding_dim, m)
            return index
        else:
            return faiss.IndexFlatIP(self._embedding_dim)

    def _get_index_files(self) -> Tuple[str, str, str]:
        index_file = os.path.join(self._index_path, "faiss_index.bin")
        mapping_file = os.path.join(self._index_path, "id_mapping.pkl")
        meta_file = os.path.join(self._index_path, "index_meta.json")
        return index_file, mapping_file, meta_file

    async def _load_or_build_index(self) -> None:
        index_file, mapping_file, meta_file = self._get_index_files()

        if os.path.exists(index_file) and os.path.exists(mapping_file):
            try:
                self._index = faiss.read_index(index_file)
                with open(mapping_file, "rb") as f:
                    data = pickle.load(f)
                    self._id_mapping = data["id_mapping"]
                    self._reverse_mapping = data["reverse_mapping"]
                    self._next_id = data["next_id"]

                if os.path.exists(meta_file):
                    with open(meta_file, "r") as f:
                        meta = json.load(f)
                        if meta.get("embedding_dim") != self._embedding_dim:
                            logger.warning("Embedding dimension changed, rebuilding index")
                            await self._rebuild_from_database()
                            return

                logger.info(
                    f"Loaded FAISS index with {self._index.ntotal} vectors, "
                    f"{len(self._id_mapping)} mappings"
                )
                return
            except Exception as e:
                logger.error(f"Failed to load existing index: {e}")

        await self._rebuild_from_database()

    async def _rebuild_from_database(self) -> None:
        logger.info("Rebuilding FAISS index from database...")

        self._index = self._create_index()
        self._id_mapping = {}
        self._reverse_mapping = {}
        self._next_id = 0

        offset = 0
        batch_size = settings.faiss_rebuild_batch_size
        total_count = 0

        while True:
            rows = await self._postgres.fetch(
                """
                SELECT content_id, embedding
                FROM content_items
                WHERE embedding IS NOT NULL
                ORDER BY content_id
                LIMIT $1 OFFSET $2
                """,
                batch_size,
                offset,
            )

            if not rows:
                break

            embeddings = []
            content_ids = []

            for row in rows:
                content_id = str(row["content_id"])
                embedding_data = row["embedding"]

                if embedding_data is None:
                    continue

                try:
                    if isinstance(embedding_data, str):
                        arr = np.array(json.loads(embedding_data), dtype=np.float32)
                    elif isinstance(embedding_data, (list, np.ndarray)):
                        arr = np.array(embedding_data, dtype=np.float32)
                    else:
                        arr = np.frombuffer(embedding_data, dtype=np.float32)

                    if len(arr) != self._embedding_dim:
                        continue

                    norm = np.linalg.norm(arr)
                    if norm > 0:
                        arr = arr / norm

                    embeddings.append(arr)
                    content_ids.append(content_id)
                except Exception as e:
                    logger.warning(f"Failed to parse embedding for {content_id}: {e}")

            if embeddings:
                embeddings_matrix = np.vstack(embeddings)
                self._add_vectors_to_index(content_ids, embeddings_matrix)
                total_count += len(content_ids)

            offset += batch_size
            logger.info(f"Rebuilt {total_count} vectors...")

        if not self._index.is_trained and self._index.ntotal > 0:
            logger.info("Training FAISS index...")
            self._index.train(np.random.randn(1000, self._embedding_dim).astype(np.float32))

        await self._save_index()
        logger.info(f"Rebuilt FAISS index completed, total {total_count} vectors")

    def _add_vectors_to_index(
        self, content_ids: List[str], embeddings: np.ndarray
    ) -> None:
        if self._index is None:
            raise RuntimeError("Index not initialized")

        for i, content_id in enumerate(content_ids):
            if content_id not in self._reverse_mapping:
                internal_id = self._next_id
                self._next_id += 1
            else:
                internal_id = self._reverse_mapping[content_id]

            self._id_mapping[internal_id] = content_id
            self._reverse_mapping[content_id] = internal_id

        self._index.add(embeddings)

    async def _save_index(self) -> None:
        if self._index is None or self._index.ntotal == 0:
            return

        index_file, mapping_file, meta_file = self._get_index_files()

        try:
            faiss.write_index(self._index, index_file)
            with open(mapping_file, "wb") as f:
                pickle.dump(
                    {
                        "id_mapping": self._id_mapping,
                        "reverse_mapping": self._reverse_mapping,
                        "next_id": self._next_id,
                    },
                    f,
                )
            with open(meta_file, "w") as f:
                json.dump(
                    {
                        "embedding_dim": self._embedding_dim,
                        "index_type": self._index_type,
                        "total_vectors": self._index.ntotal,
                        "updated_at": datetime.now(timezone.utc).isoformat(),
                    },
                    f,
                )
            logger.info(
                f"Saved FAISS index with {self._index.ntotal} vectors"
            )
        except Exception as e:
            logger.error(f"Failed to save FAISS index: {e}")

    async def add_content(self, content: ContentItem) -> Optional[ContentEmbedding]:
        if content.embedding:
            embedding = np.array(content.embedding, dtype=np.float32)
        else:
            embeddings = await self._embedding_client.get_content_embeddings([content])
            if not embeddings:
                return None
            embedding = np.array(embeddings[0].embedding, dtype=np.float32)

        if len(embedding) != self._embedding_dim:
            logger.warning(
                f"Embedding dimension mismatch for {content.content_id}: "
                f"expected {self._embedding_dim}, got {len(embedding)}"
            )
            return None

        norm = np.linalg.norm(embedding)
        if norm > 0:
            embedding = embedding / norm

        content_embedding = ContentEmbedding(
            content_id=content.content_id,
            embedding=embedding.tolist(),
            embedding_type="text",
            model_version="v1",
        )

        await self._upsert_content_item(content, embedding)
        async with self._lock:
            self._pending_updates.append((content.content_id, embedding))
            self._dirty_ids.add(content.content_id)

        return content_embedding

    async def add_contents_batch(
        self, contents: List[ContentItem]
    ) -> List[ContentEmbedding]:
        if not contents:
            return []

        embeddings = await self._embedding_client.get_content_embeddings(contents)
        results = []

        async with self._lock:
            for content, content_embedding in zip(contents, embeddings):
                embedding = np.array(content_embedding.embedding, dtype=np.float32)
                if len(embedding) != self._embedding_dim:
                    continue

                norm = np.linalg.norm(embedding)
                if norm > 0:
                    embedding = embedding / norm

                await self._upsert_content_item(content, embedding)
                self._pending_updates.append((content.content_id, embedding))
                self._dirty_ids.add(content.content_id)
                results.append(
                    ContentEmbedding(
                        content_id=content.content_id,
                        embedding=embedding.tolist(),
                        embedding_type="text",
                        model_version="v1",
                    )
                )

        return results

    async def _upsert_content_item(
        self, content: ContentItem, embedding: np.ndarray
    ) -> None:
        await self._postgres.upsert(
            "content_items",
            {
                "content_id": content.content_id,
                "title": content.title,
                "content_type": content.content_type,
                "categories": content.categories,
                "tags": content.tags,
                "author": content.author,
                "publish_time": content.publish_time,
                "popularity_score": content.popularity_score,
                "metadata": json.dumps(content.metadata or {}, ensure_ascii=False),
                "embedding": json.dumps(embedding.tolist(), ensure_ascii=False),
            },
            conflict_columns=["content_id"],
        )

        await self._redis.set(
            f"content:info:{content.content_id}",
            {
                "content_id": content.content_id,
                "title": content.title,
                "content_type": content.content_type,
                "categories": content.categories,
                "tags": content.tags,
                "popularity_score": content.popularity_score,
                "embedding": embedding.tolist(),
            },
            ttl_seconds=settings.feature_cache_ttl_seconds,
        )

    async def update_embedding(
        self, content_id: str, embedding: List[float]
    ) -> bool:
        arr = np.array(embedding, dtype=np.float32)
        if len(arr) != self._embedding_dim:
            logger.warning(f"Invalid embedding dimension for {content_id}")
            return False

        norm = np.linalg.norm(arr)
        if norm > 0:
            arr = arr / norm

        await self._postgres.execute(
            """
            UPDATE content_items
            SET embedding = $1, updated_at = CURRENT_TIMESTAMP
            WHERE content_id = $2
            """,
            json.dumps(arr.tolist(), ensure_ascii=False),
            content_id,
        )

        async with self._lock:
            self._pending_updates.append((content_id, arr))
            self._dirty_ids.add(content_id)

        return True

    async def flush_pending_updates(self) -> int:
        async with self._lock:
            if not self._pending_updates:
                return 0

            content_ids = [p[0] for p in self._pending_updates]
            embeddings = np.vstack([p[1] for p in self._pending_updates])

            if self._index is None:
                self._index = self._create_index()

            if not self._index.is_trained and self._index.ntotal == 0:
                logger.info("Training FAISS index...")
                self._index.train(np.random.randn(1000, self._embedding_dim).astype(np.float32))

            self._add_vectors_to_index(content_ids, embeddings)
            count = len(self._pending_updates)
            self._pending_updates = []

        if count > 0:
            await self._save_index()

        logger.info(f"Flushed {count} pending updates to FAISS index")
        return count

    async def search(
        self,
        query_vector: np.ndarray,
        top_k: int = 100,
        filter_content_ids: Optional[List[str]] = None,
    ) -> List[Tuple[str, float]]:
        if self._index is None or self._index.ntotal == 0:
            return []

        if len(query_vector) != self._embedding_dim:
            logger.warning(
                f"Query vector dimension mismatch: expected {self._embedding_dim}, "
                f"got {len(query_vector)}"
            )
            return []

        norm = np.linalg.norm(query_vector)
        if norm > 0:
            query_vector = query_vector / norm

        query = query_vector.astype(np.float32).reshape(1, -1)
        search_k = min(top_k + 50, self._index.ntotal)

        scores, indices = self._index.search(query, search_k)

        results = []
        filter_set = set(filter_content_ids) if filter_content_ids else set()

        for score, idx in zip(scores[0], indices[0]):
            if idx < 0:
                continue

            content_id = self._id_mapping.get(int(idx))
            if content_id is None:
                continue

            if content_id in filter_set:
                continue

            results.append((content_id, float(score)))

            if len(results) >= top_k:
                break

        return results

    async def search_by_content(
        self,
        content_id: str,
        top_k: int = 100,
        filter_content_ids: Optional[List[str]] = None,
    ) -> List[Tuple[str, float]]:
        embedding = await self.get_content_embedding(content_id)
        if embedding is None:
            return []

        filter_ids = filter_content_ids or []
        if content_id not in filter_ids:
            filter_ids = filter_ids + [content_id]

        return await self.search(embedding, top_k, filter_ids)

    async def batch_search(
        self,
        query_vectors: np.ndarray,
        top_k: int = 100,
    ) -> List[List[Tuple[str, float]]]:
        if self._index is None or self._index.ntotal == 0:
            return [[] for _ in range(len(query_vectors))]

        if query_vectors.shape[1] != self._embedding_dim:
            logger.warning("Query vectors dimension mismatch")
            return [[] for _ in range(len(query_vectors))]

        norms = np.linalg.norm(query_vectors, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        queries = (query_vectors / norms).astype(np.float32)

        scores, indices = self._index.search(queries, top_k)

        results = []
        for i in range(len(scores)):
            row_results = []
            for score, idx in zip(scores[i], indices[i]):
                if idx < 0:
                    continue
                content_id = self._id_mapping.get(int(idx))
                if content_id:
                    row_results.append((content_id, float(score)))
            results.append(row_results)

        return results

    async def get_content_embedding(self, content_id: str) -> Optional[np.ndarray]:
        cache_key = f"content:info:{content_id}"
        cached = await self._redis.get_json(cache_key)
        if cached and cached.get("embedding"):
            return np.array(cached["embedding"], dtype=np.float32)

        row = await self._postgres.fetchrow(
            """
            SELECT embedding
            FROM content_items
            WHERE content_id = $1
            """,
            content_id,
        )

        if row and row["embedding"]:
            try:
                if isinstance(row["embedding"], str):
                    arr = np.array(json.loads(row["embedding"]), dtype=np.float32)
                elif isinstance(row["embedding"], (list, np.ndarray)):
                    arr = np.array(row["embedding"], dtype=np.float32)
                else:
                    arr = np.frombuffer(row["embedding"], dtype=np.float32)

                if len(arr) == self._embedding_dim:
                    return arr
            except Exception as e:
                logger.warning(f"Failed to parse embedding for {content_id}: {e}")

        return None

    async def get_content_info(self, content_id: str) -> Optional[Dict[str, Any]]:
        cache_key = f"content:info:{content_id}"
        cached = await self._redis.get_json(cache_key)
        if cached:
            return cached

        row = await self._postgres.fetchrow(
            """
            SELECT content_id, title, content_type, categories, tags,
                   popularity_score, publish_time, metadata
            FROM content_items
            WHERE content_id = $1
            """,
            content_id,
        )

        if row:
            info = {
                "content_id": str(row["content_id"]),
                "title": row["title"],
                "content_type": str(row["content_type"]),
                "categories": list(row["categories"]) if row["categories"] else [],
                "tags": list(row["tags"]) if row["tags"] else [],
                "popularity_score": float(row["popularity_score"]),
                "publish_time": row["publish_time"].isoformat() if row["publish_time"] else None,
                "metadata": row["metadata"],
            }
            await self._redis.set(
                cache_key, info, ttl_seconds=settings.feature_cache_ttl_seconds
            )
            return info

        return None

    async def trigger_full_rebuild(self) -> None:
        logger.info("Triggering full FAISS index rebuild...")
        await self._rebuild_from_database()

    async def get_index_stats(self) -> Dict[str, Any]:
        stats = {
            "total_vectors": self._index.ntotal if self._index else 0,
            "embedding_dim": self._embedding_dim,
            "index_type": self._index_type,
            "pending_updates": len(self._pending_updates),
            "dirty_ids": len(self._dirty_ids),
            "id_mappings": len(self._id_mapping),
        }

        if self._index and hasattr(self._index, "nprobe"):
            stats["nprobe"] = self._index.nprobe

        return stats

    async def health_check(self) -> bool:
        try:
            if self._index is None:
                return False
            test_vector = np.random.randn(self._embedding_dim).astype(np.float32)
            results = await self.search(test_vector, top_k=1)
            return True
        except Exception as e:
            logger.warning(f"ContentEmbeddingIndex health check failed: {e}")
            return False


_content_embedding_index: Optional[ContentEmbeddingIndex] = None


async def get_content_embedding_index(
    redis_client: Optional[RedisClient] = None,
    postgres_client: Optional[PostgresClient] = None,
) -> ContentEmbeddingIndex:
    global _content_embedding_index
    if _content_embedding_index is None:
        if redis_client is None or postgres_client is None:
            raise RuntimeError("Redis and Postgres clients are required for initialization")
        _content_embedding_index = ContentEmbeddingIndex()
        await _content_embedding_index.initialize(redis_client, postgres_client)
    return _content_embedding_index


def close_content_embedding_index() -> None:
    global _content_embedding_index
    _content_embedding_index = None
