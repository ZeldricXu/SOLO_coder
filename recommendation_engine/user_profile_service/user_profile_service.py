from typing import Optional, List, Dict, Any
from datetime import datetime, timezone
import json
import asyncio
from loguru import logger
import numpy as np
from cachetools import TTLCache, LRUCache

from recommendation_engine.infrastructure import RedisClient, PostgresClient
from recommendation_engine.models.schemas import (
    UserProfile,
    UserBehaviorEvent,
    InterestTag,
)
from .behavior_aggregator import BehaviorAggregator
from config import settings


class UserProfileService:
    _instance: Optional["UserProfileService"] = None

    def __new__(cls) -> "UserProfileService":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    async def initialize(
        self,
        redis_client: RedisClient,
        postgres_client: PostgresClient,
    ) -> None:
        self._redis = redis_client
        self._postgres = postgres_client
        self._behavior_aggregator = BehaviorAggregator()
        self._profile_cache: TTLCache[str, UserProfile] = TTLCache(
            maxsize=10000, ttl=settings.user_profile_ttl_seconds
        )
        self._content_tags_cache: LRUCache[str, List[str]] = LRUCache(maxsize=100000)
        self._content_embeddings_cache: LRUCache[str, np.ndarray] = LRUCache(maxsize=100000)
        self._version_counter_key = f"{settings.user_profile_version_key_prefix}:counter"
        logger.info("UserProfileService initialized")

    def _get_profile_key(self, user_id: str, version: Optional[str] = None) -> str:
        if version:
            return f"user:profile:{user_id}:{version}"
        return f"user:profile:{user_id}"

    def _get_events_key(self, user_id: str) -> str:
        return f"user:events:{user_id}"

    def _get_version_key(self, user_id: str, profile_version: int) -> str:
        return f"{settings.user_profile_version_key_prefix}:{user_id}:{profile_version}"

    async def _get_next_profile_version(self, user_id: str) -> int:
        counter_key = f"{self._version_counter_key}:{user_id}"
        return await self._redis.incr(counter_key)

    async def ingest_behavior_event(self, event: UserBehaviorEvent) -> None:
        try:
            event_json = event.model_dump(mode="json")
            await self._redis.lpush(
                self._get_events_key(event.user_id),
                event_json,
            )
            await self._redis.ltrim(
                self._get_events_key(event.user_id), 0, 999
            )
            await self._redis.expire(
                self._get_events_key(event.user_id),
                settings.user_profile_ttl_seconds,
            )

            await self._invalidate_profile_cache(event.user_id)
            await self._update_realtime_stats(event)

            logger.debug(
                f"Ingested behavior event for user {event.user_id}: {event.event_type}"
            )
        except Exception as e:
            logger.error(f"Failed to ingest behavior event: {e}")

    async def ingest_behavior_events_batch(
        self, events: List[UserBehaviorEvent]
    ) -> None:
        if not events:
            return

        tasks = [self.ingest_behavior_event(event) for event in events]
        await asyncio.gather(*tasks, return_exceptions=True)
        logger.info(f"Ingested {len(events)} behavior events in batch")

    async def _update_realtime_stats(self, event: UserBehaviorEvent) -> None:
        stats_key = f"user:stats:{event.user_id}"
        now = datetime.now(timezone.utc)

        pipe = self._redis.pipeline()
        pipe.hincrby(stats_key, f"event:{event.event_type}", 1)
        pipe.hincrbyfloat(stats_key, f"score:{event.event_type}", 1.0)
        pipe.hset(stats_key, {"last_activity": now.isoformat()})
        pipe.expire(stats_key, settings.realtime_counter_window_seconds)
        await pipe.execute()

    async def _get_user_events(self, user_id: str, limit: int = 200) -> List[UserBehaviorEvent]:
        events_data = await self._redis.lrange(
            self._get_events_key(user_id), 0, limit - 1
        )
        events = []
        for data in events_data:
            try:
                if isinstance(data, str):
                    event_dict = json.loads(data)
                else:
                    event_dict = data
                event = UserBehaviorEvent(**event_dict)
                events.append(event)
            except Exception as e:
                logger.warning(f"Failed to parse event for user {user_id}: {e}")
        return events

    async def _get_offline_tags(self, user_id: str) -> List[InterestTag]:
        cache_key = f"user:offline_tags:{user_id}"
        cached = await self._redis.get_json(cache_key)
        if cached:
            return [InterestTag(**tag) for tag in cached]

        rows = await self._postgres.fetch(
            """
            SELECT tag_id, tag_name, weight, version, updated_at
            FROM user_offline_tags
            WHERE user_id = $1
            ORDER BY weight DESC
            LIMIT 50
            """,
            user_id,
        )

        offline_tags = []
        for row in rows:
            tag = InterestTag(
                tag_id=str(row["tag_id"]),
                tag_name=str(row["tag_name"]),
                weight=float(row["weight"]),
                version=str(row["version"]),
                updated_at=row["updated_at"],
            )
            offline_tags.append(tag)

        if offline_tags:
            await self._redis.set(
                cache_key,
                [tag.model_dump(mode="json") for tag in offline_tags],
                ttl_seconds=settings.user_profile_ttl_seconds,
            )

        return offline_tags

    async def _get_content_tags_batch(
        self, content_ids: List[str]
    ) -> Dict[str, List[str]]:
        result: Dict[str, List[str]] = {}
        missing_ids = []

        for content_id in content_ids:
            cached = self._content_tags_cache.get(content_id)
            if cached is not None:
                result[content_id] = cached
            else:
                missing_ids.append(content_id)

        if not missing_ids:
            return result

        placeholders = ", ".join(f"${i+1}" for i in range(len(missing_ids)))
        rows = await self._postgres.fetch(
            f"""
            SELECT content_id, tags
            FROM content_items
            WHERE content_id IN ({placeholders})
            """,
            *missing_ids,
        )

        for row in rows:
            content_id = str(row["content_id"])
            tags = row["tags"] or []
            if isinstance(tags, str):
                try:
                    tags = json.loads(tags)
                except json.JSONDecodeError:
                    tags = [tags]
            result[content_id] = list(tags)
            self._content_tags_cache[content_id] = list(tags)

        for content_id in missing_ids:
            if content_id not in result:
                result[content_id] = []
                self._content_tags_cache[content_id] = []

        return result

    async def _get_content_embeddings_batch(
        self, content_ids: List[str]
    ) -> Dict[str, np.ndarray]:
        result: Dict[str, np.ndarray] = {}
        missing_ids = []

        for content_id in content_ids:
            cached = self._content_embeddings_cache.get(content_id)
            if cached is not None:
                result[content_id] = cached
            else:
                missing_ids.append(content_id)

        if not missing_ids:
            return result

        placeholders = ", ".join(f"${i+1}" for i in range(len(missing_ids)))
        rows = await self._postgres.fetch(
            f"""
            SELECT content_id, embedding
            FROM content_items
            WHERE content_id IN ({placeholders})
            AND embedding IS NOT NULL
            """,
            *missing_ids,
        )

        for row in rows:
            content_id = str(row["content_id"])
            embedding_data = row["embedding"]
            if embedding_data:
                if isinstance(embedding_data, str):
                    try:
                        embedding_array = np.array(json.loads(embedding_data), dtype=np.float32)
                    except json.JSONDecodeError:
                        embedding_array = np.zeros(settings.faiss_embedding_dim, dtype=np.float32)
                elif isinstance(embedding_data, (list, np.ndarray)):
                    embedding_array = np.array(embedding_data, dtype=np.float32)
                else:
                    try:
                        import struct
                        embedding_array = np.frombuffer(embedding_data, dtype=np.float32)
                    except Exception:
                        embedding_array = np.zeros(settings.faiss_embedding_dim, dtype=np.float32)

                if len(embedding_array) != settings.faiss_embedding_dim:
                    embedding_array = np.zeros(settings.faiss_embedding_dim, dtype=np.float32)

                result[content_id] = embedding_array
                self._content_embeddings_cache[content_id] = embedding_array

        for content_id in missing_ids:
            if content_id not in result:
                result[content_id] = np.zeros(settings.faiss_embedding_dim, dtype=np.float32)
                self._content_embeddings_cache[content_id] = result[content_id]

        return result

    async def get_user_profile(
        self, user_id: str, profile_version: Optional[int] = None
    ) -> Optional[UserProfile]:
        if profile_version is not None:
            return await self._get_profile_version(user_id, profile_version)

        cached_profile = self._profile_cache.get(user_id)
        if cached_profile:
            return cached_profile

        profile_data = await self._redis.get_json(self._get_profile_key(user_id))
        if profile_data:
            try:
                profile = UserProfile(**profile_data)
                self._profile_cache[user_id] = profile
                return profile
            except Exception as e:
                logger.warning(f"Failed to parse cached profile for {user_id}: {e}")

        profile = await self._build_user_profile(user_id)
        if profile:
            await self._save_profile_to_redis(profile)
            self._profile_cache[user_id] = profile

        return profile

    async def _build_user_profile(self, user_id: str) -> UserProfile:
        events = await self._get_user_events(user_id, limit=200)
        offline_tags = await self._get_offline_tags(user_id)

        content_ids = list({event.content_id for event in events})
        content_tags_map = await self._get_content_tags_batch(content_ids)
        content_embeddings_map = await self._get_content_embeddings_batch(content_ids)

        stats = self._behavior_aggregator.aggregate(user_id, events)
        realtime_tags = self._behavior_aggregator.generate_interest_tags(
            user_id, content_tags_map
        )
        user_vector = self._behavior_aggregator.generate_user_vector(
            user_id, content_embeddings_map, settings.faiss_embedding_dim
        )

        current_version = await self._get_next_profile_version(user_id)

        profile = UserProfile(
            user_id=user_id,
            version="v1",
            profile_version=current_version,
            user_vector=user_vector.tolist(),
            interest_tags=realtime_tags,
            offline_tags=offline_tags,
            realtime_behavior_stats=stats,
            updated_at=datetime.now(timezone.utc),
        )

        await self._archive_profile_version(profile)

        return profile

    async def _save_profile_to_redis(self, profile: UserProfile) -> None:
        profile_json = profile.model_dump(mode="json")
        await self._redis.set(
            self._get_profile_key(profile.user_id),
            profile_json,
            ttl_seconds=settings.user_profile_ttl_seconds,
        )

    async def _archive_profile_version(self, profile: UserProfile) -> None:
        version_key = self._get_version_key(profile.user_id, profile.profile_version)
        profile_json = profile.model_dump(mode="json")

        await self._redis.set(
            version_key,
            profile_json,
            ttl_seconds=settings.user_profile_version_ttl_seconds,
        )

        await self._postgres.insert(
            "user_profile_versions",
            {
                "user_id": profile.user_id,
                "profile_version": profile.profile_version,
                "profile_data": json.dumps(profile_json, ensure_ascii=False),
                "created_at": datetime.now(timezone.utc),
            },
        )

    async def _get_profile_version(
        self, user_id: str, profile_version: int
    ) -> Optional[UserProfile]:
        version_key = self._get_version_key(user_id, profile_version)
        cached = await self._redis.get_json(version_key)
        if cached:
            return UserProfile(**cached)

        row = await self._postgres.fetchrow(
            """
            SELECT profile_data
            FROM user_profile_versions
            WHERE user_id = $1 AND profile_version = $2
            """,
            user_id,
            profile_version,
        )

        if row and row["profile_data"]:
            try:
                profile_data = (
                    json.loads(row["profile_data"])
                    if isinstance(row["profile_data"], str)
                    else row["profile_data"]
                )
                return UserProfile(**profile_data)
            except Exception as e:
                logger.error(f"Failed to parse archived profile: {e}")

        logger.warning(f"Profile version {profile_version} not found for user {user_id}, returning empty profile")
        return self._create_empty_profile(user_id)

    def _create_empty_profile(self, user_id: str) -> UserProfile:
        return UserProfile(
            user_id=user_id,
            version="v1",
            profile_version=1,
            user_vector=[0.0] * settings.faiss_embedding_dim,
            interest_tags=[],
            offline_tags=[],
            realtime_behavior_stats={
                "click_count": 0.0,
                "expose_count": 0.0,
                "stay_count": 0.0,
                "share_count": 0.0,
                "purchase_count": 0.0,
                "total_duration_seconds": 0.0,
                "last_activity": 0.0,
            },
            demographics=None,
            created_at=datetime.now(timezone.utc),
            updated_at=datetime.now(timezone.utc),
            experiment_group=None,
        )

    async def _invalidate_profile_cache(self, user_id: str) -> None:
        self._profile_cache.pop(user_id, None)
        await self._redis.delete(self._get_profile_key(user_id))

    async def update_offline_tags(
        self,
        user_id: str,
        tags: List[InterestTag],
        version: str = "v1",
    ) -> None:
        try:
            await self._postgres.transaction(
                [
                    (
                        """
                        INSERT INTO user_offline_tags
                            (user_id, tag_id, tag_name, weight, version, updated_at)
                        VALUES ($1, $2, $3, $4, $5, CURRENT_TIMESTAMP)
                        ON CONFLICT (user_id, tag_id)
                        DO UPDATE SET
                            tag_name = EXCLUDED.tag_name,
                            weight = EXCLUDED.weight,
                            version = EXCLUDED.version,
                            updated_at = CURRENT_TIMESTAMP
                        """,
                        [user_id, tag.tag_id, tag.tag_name, tag.weight, version],
                    )
                    for tag in tags
                ]
            )

            cache_key = f"user:offline_tags:{user_id}"
            await self._redis.delete(cache_key)
            await self._invalidate_profile_cache(user_id)

            logger.info(f"Updated {len(tags)} offline tags for user {user_id}")
        except Exception as e:
            logger.error(f"Failed to update offline tags for user {user_id}: {e}")
            raise

    async def get_user_merged_tags(
        self, user_id: str, top_k: int = 20
    ) -> List[InterestTag]:
        profile = await self.get_user_profile(user_id)
        if not profile:
            return []

        merged_tags = profile.merge_tags()
        return merged_tags[:top_k]

    async def get_user_interest_vector(
        self, user_id: str
    ) -> Optional[np.ndarray]:
        profile = await self.get_user_profile(user_id)
        if not profile or not profile.user_vector:
            return None
        return np.array(profile.user_vector, dtype=np.float32)

    async def get_user_statistics(
        self, user_id: str
    ) -> Dict[str, Any]:
        profile = await self.get_user_profile(user_id)
        if not profile:
            return {}

        return {
            "user_id": user_id,
            "profile_version": profile.profile_version,
            "realtime_stats": profile.realtime_behavior_stats,
            "interest_tag_count": len(profile.interest_tags),
            "offline_tag_count": len(profile.offline_tags),
            "top_interests": [
                (t.tag_name, round(t.weight, 4))
                for t in profile.merge_tags()[:5]
            ],
            "updated_at": profile.updated_at.isoformat(),
        }

    async def list_profile_versions(
        self, user_id: str, limit: int = 10
    ) -> List[Dict[str, Any]]:
        rows = await self._postgres.fetch(
            """
            SELECT profile_version, created_at
            FROM user_profile_versions
            WHERE user_id = $1
            ORDER BY created_at DESC
            LIMIT $2
            """,
            user_id,
            limit,
        )

        return [
            {
                "profile_version": row["profile_version"],
                "created_at": row["created_at"].isoformat(),
            }
            for row in rows
        ]

    async def delete_user_profile(self, user_id: str) -> None:
        await self._redis.delete(
            self._get_profile_key(user_id),
            self._get_events_key(user_id),
            f"user:stats:{user_id}",
            f"user:offline_tags:{user_id}",
        )
        self._profile_cache.pop(user_id, None)
        logger.info(f"Deleted profile cache for user {user_id}")


_user_profile_service: Optional[UserProfileService] = None


async def get_user_profile_service(
    redis_client: Optional[RedisClient] = None,
    postgres_client: Optional[PostgresClient] = None,
) -> UserProfileService:
    global _user_profile_service
    if _user_profile_service is None:
        if redis_client is None or postgres_client is None:
            raise RuntimeError("Redis and Postgres clients are required for initialization")
        _user_profile_service = UserProfileService()
        await _user_profile_service.initialize(redis_client, postgres_client)
    return _user_profile_service


def close_user_profile_service() -> None:
    global _user_profile_service
    _user_profile_service = None
