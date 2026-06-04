from typing import Optional, List, Dict, Tuple, Any
import os
import asyncio
from datetime import datetime, timedelta, timezone
from loguru import logger
import numpy as np
from cachetools import TTLCache

from recommendation_engine.infrastructure import RedisClient, KafkaConsumerClient
from recommendation_engine.models.schemas import OnlineCFUpdateEvent
from .als_trainer import ALSTrainer
from config import settings


class CollaborativeFilter:
    _instance: Optional["CollaborativeFilter"] = None

    def __new__(cls) -> "CollaborativeFilter":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    async def initialize(
        self,
        redis_client: RedisClient,
        als_trainer: Optional[ALSTrainer] = None,
    ) -> None:
        self._redis = redis_client
        self._trainer = als_trainer or ALSTrainer()
        self._recall_cache: TTLCache[str, List[Tuple[str, float]]] = TTLCache(
            maxsize=10000, ttl=300
        )
        self._cold_start_items: List[Tuple[str, float]] = []
        self._cold_start_item_set: set = set()
        self._online_item_factors: Dict[str, np.ndarray] = {}

        loaded = self._trainer.load_model()
        if not loaded:
            logger.warning("No pre-trained ALS model found, starting with empty model")

        await self._load_cold_start_items()

        if settings.hot_reload_enabled:
            asyncio.create_task(self._hot_reload_worker())

        if settings.cf_online_update_enabled:
            asyncio.create_task(self._online_update_consumer())

        logger.info("CollaborativeFilter initialized")

    async def close(self) -> None:
        logger.info("CollaborativeFilter closed")

    async def _load_cold_start_items(self) -> None:
        try:
            cached = await self._redis.get_json("cold_start:items")
            if cached:
                self._cold_start_items = [
                    (str(i[0]), float(i[1])) for i in cached
                ]
                self._cold_start_item_set = {item[0] for item in self._cold_start_items}
                logger.info(f"Loaded {len(self._cold_start_items)} cold start items")
        except Exception as e:
            logger.warning(f"Failed to load cold start items: {e}")

    async def _hot_reload_worker(self) -> None:
        while True:
            try:
                await asyncio.sleep(settings.hot_reload_interval_seconds)
                if os.path.exists(settings.als_model_path):
                    mtime = os.path.getmtime(settings.als_model_path)
                    if hasattr(self, "_last_mtime") and mtime != self._last_mtime:
                        logger.info("ALS model updated, reloading...")
                        if self._trainer.load_model():
                            self._recall_cache.clear()
                    self._last_mtime = mtime
            except Exception as e:
                logger.warning(f"ALS hot reload error: {e}")

    async def recommend(
        self,
        user_id: str,
        top_k: int = 100,
        exclude_items: Optional[List[str]] = None,
        use_cache: bool = True,
    ) -> List[Tuple[str, float]]:
        cache_key = f"{user_id}:{top_k}:{':' .join(sorted(exclude_items)) if exclude_items else ''}"
        if use_cache and cache_key in self._recall_cache:
            return self._recall_cache[cache_key]

        user_factor = self._trainer.get_user_factor(user_id)
        if user_factor is None:
            results = await self._cold_start_recommend(user_id, top_k, exclude_items)
        else:
            all_item_ids = list(self._trainer._item_id_map.keys())
            scores = []
            for item_id in all_item_ids:
                item_factor = self.get_online_item_factor(item_id)
                if item_factor is not None:
                    score = float(np.dot(user_factor, np.array(item_factor)))
                    scores.append((item_id, score))
            scores.sort(key=lambda x: x[1], reverse=True)
            exclude_set = set(exclude_items) if exclude_items else set()
            results = []
            for item_id, score in scores:
                if item_id not in exclude_set:
                    results.append((item_id, score))
                    if len(results) >= top_k:
                        break

        if not results:
            results = await self._cold_start_recommend(user_id, top_k, exclude_items)

        if use_cache:
            self._recall_cache[cache_key] = results

        return results

    async def _cold_start_recommend(
        self,
        user_id: str,
        top_k: int,
        exclude_items: Optional[List[str]] = None,
    ) -> List[Tuple[str, float]]:
        exclude_set = set(exclude_items) if exclude_items else set()
        results = []
        for item_id, score in self._cold_start_items:
            if item_id not in exclude_set:
                results.append((item_id, score))
                if len(results) >= top_k:
                    break
        return results

    async def recommend_for_users(
        self,
        user_ids: List[str],
        top_k: int = 100,
        exclude_items_map: Optional[Dict[str, List[str]]] = None,
    ) -> Dict[str, List[Tuple[str, float]]]:
        exclude_items_map = exclude_items_map or {}
        results = {}

        for user_id in user_ids:
            exclude = exclude_items_map.get(user_id)
            results[user_id] = await self.recommend(
                user_id, top_k=top_k, exclude_items=exclude
            )

        return results

    async def similar_items(
        self,
        item_id: str,
        top_k: int = 50,
    ) -> List[Tuple[str, float]]:
        return self._trainer.similar_items(item_id, top_k=top_k)

    async def batch_similar_items(
        self,
        item_ids: List[str],
        top_k: int = 50,
    ) -> Dict[str, List[Tuple[str, float]]]:
        results = {}
        for item_id in item_ids:
            results[item_id] = await self.similar_items(item_id, top_k=top_k)
        return results

    async def train_model(
        self,
        interactions: List[Tuple[str, str, float]],
        save_after_training: bool = True,
    ) -> Dict[str, Any]:
        logger.info(f"Starting ALS training with {len(interactions)} interactions")
        metrics = self._trainer.train(interactions)

        if save_after_training:
            self._trainer.save_model()
            self._recall_cache.clear()

        return metrics

    async def train_model_from_events(
        self,
        events: List[Tuple[str, str, str]],
        save_after_training: bool = True,
    ) -> Dict[str, Any]:
        logger.info(f"Starting ALS training from {len(events)} events")
        metrics = self._trainer.train_from_events(events)

        if save_after_training:
            self._trainer.save_model()
            self._recall_cache.clear()

        return metrics

    async def update_cold_start_items(
        self,
        items: List[Tuple[str, float]],
    ) -> None:
        self._cold_start_items = sorted(items, key=lambda x: x[1], reverse=True)
        self._cold_start_item_set = {item[0] for item in self._cold_start_items}
        await self._redis.set(
            "cold_start:items",
            [[i[0], i[1]] for i in self._cold_start_items],
            ttl_seconds=86400,
        )
        logger.info(f"Updated {len(self._cold_start_items)} cold start items")

    async def get_user_factor(
        self,
        user_id: str,
    ) -> Optional[List[float]]:
        factor = self._trainer.get_user_factor(user_id)
        if factor is not None:
            return factor.tolist()
        return None

    async def get_item_factor(
        self,
        item_id: str,
    ) -> Optional[List[float]]:
        factor = self._trainer.get_item_factor(item_id)
        if factor is not None:
            return factor.tolist()
        return None

    async def predict_score(
        self,
        user_id: str,
        item_id: str,
    ) -> float:
        user_factor = self._trainer.get_user_factor(user_id)
        item_factor = self._trainer.get_item_factor(item_id)

        if user_factor is None or item_factor is None:
            return 0.0

        return float(np.dot(user_factor, item_factor))

    async def predict_scores_batch(
        self,
        user_id: str,
        item_ids: List[str],
    ) -> Dict[str, float]:
        user_factor = self._trainer.get_user_factor(user_id)
        if user_factor is None:
            return {item_id: 0.0 for item_id in item_ids}

        scores = {}
        for item_id in item_ids:
            item_factor = self._trainer.get_item_factor(item_id)
            if item_factor is not None:
                scores[item_id] = float(np.dot(user_factor, item_factor))
            else:
                scores[item_id] = 0.0

        return scores

    def get_model_stats(self) -> Dict[str, Any]:
        stats = self._trainer.get_model_stats()
        stats.update(
            {
                "cache_size": len(self._recall_cache),
                "cold_start_items": len(self._cold_start_items),
            }
        )
        return stats

    async def health_check(self) -> bool:
        try:
            stats = self.get_model_stats()
            return stats["has_model"] or len(self._cold_start_items) > 0
        except Exception:
            return False

    async def initialize_new_item(self, content_id: str, content_embedding: List[float], seed_users: Optional[List[str]] = None) -> bool:
        new_factor = self._trainer.initialize_cold_start_item(content_id, content_embedding)
        if new_factor is None:
            return False
        self._online_item_factors[content_id] = new_factor
        self._cold_start_item_set.add(content_id)
        await self._redis.set(f"cf:online:{content_id}", new_factor.tolist(), ttl_seconds=86400)
        if seed_users:
            interactions = [(user_id, content_id, 1.0) for user_id in seed_users]
            await self._trainer.update_item_factor_online(
                content_id,
                interactions,
                learning_rate=settings.cf_online_learning_rate,
                regularization=settings.cf_online_regularization,
            )
        return True

    async def process_online_interaction(self, event: OnlineCFUpdateEvent) -> None:
        interactions = [(event.user_id, event.content_id, event.weight)]
        updated_factor = self._trainer.update_item_factor_online(
            event.content_id,
            interactions,
            learning_rate=settings.cf_online_learning_rate,
            regularization=settings.cf_online_regularization,
        )
        if updated_factor is not None:
            self._online_item_factors[event.content_id] = updated_factor
            await self._redis.set(f"cf:online:{event.content_id}", updated_factor.tolist(), ttl_seconds=86400)
        keys_to_clear = [key for key in self._recall_cache if key.startswith(f"{event.user_id}:")]
        for key in keys_to_clear:
            del self._recall_cache[key]

    async def _online_update_consumer(self) -> None:
        if KafkaConsumerClient is None:
            logger.warning("Kafka consumer not available, skipping online update consumer")
            return
        try:
            consumer = KafkaConsumerClient(
                topics=[settings.cf_online_update_kafka_topic],
                group_id=f"{settings.kafka_consumer_group_id}-cf-online",
                auto_offset_reset="latest",
            )
            await consumer.initialize()
            logger.info("CF online update consumer started")

            async def handler(key: Any, value: Any, partition: int, offset: int) -> None:
                try:
                    event = OnlineCFUpdateEvent(**value)
                    await self.process_online_interaction(event)
                except Exception as e:
                    logger.error(f"Failed to process online CF update: {e}")

            await consumer.consume(handler)
        except Exception as e:
            logger.error(f"CF online update consumer failed: {e}")

    def get_online_item_factor(self, content_id: str) -> Optional[List[float]]:
        if content_id in self._online_item_factors:
            return self._online_item_factors[content_id].tolist()
        factor = self._trainer.get_item_factor(content_id)
        if factor is not None:
            return factor.tolist()
        return None

    def is_cold_start_item(self, content_id: str, content_info: Optional[Dict] = None) -> bool:
        if content_id in self._online_item_factors:
            return True
        if content_id in self._cold_start_item_set:
            return True
        if content_info and "publish_time" in content_info:
            try:
                publish_time = datetime.fromisoformat(content_info["publish_time"])
                age = datetime.now(timezone.utc) - publish_time
                if age < timedelta(hours=settings.cf_cold_start_max_age_hours):
                    return True
            except Exception:
                pass
        item_idx = self._trainer._item_id_map.get(content_id)
        if item_idx is not None and self._trainer._item_factors is not None:
            interaction_count = 0
            if hasattr(self._trainer, "_interaction_counts"):
                interaction_count = self._trainer._interaction_counts.get(item_idx, 0)
            if interaction_count < settings.cf_cold_start_min_interactions:
                return True
        return False


_collaborative_filter: Optional[CollaborativeFilter] = None


async def get_collaborative_filter(
    redis_client: Optional[RedisClient] = None,
) -> CollaborativeFilter:
    global _collaborative_filter
    if _collaborative_filter is None:
        if redis_client is None:
            raise RuntimeError("Redis client is required for initialization")
        _collaborative_filter = CollaborativeFilter()
        await _collaborative_filter.initialize(redis_client)
    return _collaborative_filter


def close_collaborative_filter() -> None:
    global _collaborative_filter
    _collaborative_filter = None
