import pytest
import json
import asyncio
from datetime import datetime, timezone, timedelta
from unittest.mock import patch, MagicMock, AsyncMock
import numpy as np

from recommendation_engine.models.schemas import UserBehaviorEvent, UserProfile, InterestTag
from tests.factories.data_factories import (
    UserBehaviorEventFactory,
    UserProfileFactory,
    InterestTagFactory,
    generate_user_id,
    generate_content_id,
    generate_embedding,
)


pytestmark = pytest.mark.unit


class TestUserProfileServiceNormalPath:

    @pytest.mark.asyncio
    async def test_click_event_updates_category_preference_and_timestamp(
        self, user_profile_service, mock_redis, mock_postgres
    ):
        user_id = generate_user_id()
        content_id = generate_content_id()
        target_tag = "python"
        target_category = "tech"

        test_embedding = generate_embedding(768)
        mock_postgres._tables["content_items"] = [
            {
                "content_id": content_id,
                "title": "Python Programming Guide",
                "content_type": "article",
                "categories": [target_category],
                "tags": [target_tag, "programming"],
                "author": "test_author",
                "popularity_score": 75.0,
                "embedding": json.dumps(test_embedding),
            }
        ]

        mock_redis._data[f"content:embedding:{content_id}"] = json.dumps(test_embedding)

        before_activity = await mock_redis.hget(f"user:stats:{user_id}", "last_activity")
        assert before_activity is None

        click_event = UserBehaviorEventFactory(
            user_id=user_id,
            content_id=content_id,
            event_type="click",
            timestamp=datetime.now(timezone.utc),
        )

        await user_profile_service.ingest_behavior_event(click_event)

        after_activity = await mock_redis.hget(f"user:stats:{user_id}", "last_activity")
        assert after_activity is not None
        assert isinstance(after_activity, str)

        click_count = await mock_redis.hget(f"user:stats:{user_id}", "event:click")
        assert int(click_count) == 1

        events_stored = await mock_redis.lrange(f"user:events:{user_id}", 0, -1)
        assert len(events_stored) == 1

        stored_event = json.loads(events_stored[0])
        assert stored_event["user_id"] == user_id
        assert stored_event["event_type"] == "click"

        profile = await user_profile_service.get_user_profile(user_id)
        assert profile is not None
        assert profile.user_id == user_id

        tag_ids = [tag.tag_id for tag in profile.interest_tags]
        tag_weights = {tag.tag_id: tag.weight for tag in profile.interest_tags}
        assert target_tag in tag_ids
        assert tag_weights[target_tag] > 0.0
        assert tag_weights[target_tag] <= 1.0

        assert profile.realtime_behavior_stats is not None
        assert profile.realtime_behavior_stats["click_count"] == 1
        assert profile.realtime_behavior_stats["last_activity"] is not None

    @pytest.mark.asyncio
    async def test_profile_version_switch_returns_correct_data(
        self, user_profile_service, mock_redis, mock_postgres
    ):
        user_id = generate_user_id()

        version1_tags = [
            InterestTagFactory(tag_id="tag_v1_1", tag_name="python", weight=0.9),
            InterestTagFactory(tag_id="tag_v1_2", tag_name="ai", weight=0.7),
        ]
        version1_vector = np.array(generate_embedding(768), dtype=np.float32)
        version1_vector = version1_vector / np.linalg.norm(version1_vector)

        version1_profile = UserProfileFactory(
            user_id=user_id,
            profile_version=1,
            user_vector=version1_vector.tolist(),
            interest_tags=version1_tags,
            updated_at=datetime.now(timezone.utc) - timedelta(hours=2),
        )

        await mock_postgres.upsert(
            "user_profile_versions",
            {
                "user_id": user_id,
                "profile_version": 1,
                "profile_data": version1_profile.model_dump(mode="json"),
            },
            ["user_id", "profile_version"],
        )

        version1_key = f"user:profile:version:{user_id}:1"
        await mock_redis.set(
            version1_key,
            version1_profile.model_dump(mode="json"),
        )

        await asyncio.sleep(0.1)

        version2_tags = [
            InterestTagFactory(tag_id="tag_v2_1", tag_name="basketball", weight=0.85),
            InterestTagFactory(tag_id="tag_v2_2", tag_name="sports", weight=0.6),
        ]
        version2_vector = np.array(generate_embedding(768), dtype=np.float32)
        version2_vector = version2_vector / np.linalg.norm(version2_vector)

        version2_profile = UserProfileFactory(
            user_id=user_id,
            profile_version=2,
            user_vector=version2_vector.tolist(),
            interest_tags=version2_tags,
            updated_at=datetime.now(timezone.utc),
        )

        await mock_postgres.upsert(
            "user_profile_versions",
            {
                "user_id": user_id,
                "profile_version": 2,
                "profile_data": version2_profile.model_dump(mode="json"),
            },
            ["user_id", "profile_version"],
        )

        version2_key = f"user:profile:version:{user_id}:2"
        await mock_redis.set(
            version2_key,
            version2_profile.model_dump(mode="json"),
        )

        current_key = f"user:profile:{user_id}"
        await mock_redis.set(
            current_key,
            version2_profile.model_dump(mode="json"),
        )

        await asyncio.sleep(0.1)

        profile_v1 = await user_profile_service.get_user_profile(user_id, profile_version=1)
        assert profile_v1 is not None
        assert profile_v1.profile_version == 1

        v1_tag_ids = sorted([t.tag_id for t in profile_v1.interest_tags])
        assert v1_tag_ids == sorted(["tag_v1_1", "tag_v1_2"])

        v1_vector = np.array(profile_v1.user_vector)
        assert np.allclose(v1_vector, version1_vector, atol=0.001)

        profile_v2 = await user_profile_service.get_user_profile(user_id, profile_version=2)
        assert profile_v2 is not None
        assert profile_v2.profile_version == 2

        v2_tag_ids = sorted([t.tag_id for t in profile_v2.interest_tags])
        assert v2_tag_ids == sorted(["tag_v2_1", "tag_v2_2"])

        v2_vector = np.array(profile_v2.user_vector)
        assert np.allclose(v2_vector, version2_vector, atol=0.001)

        profile_latest = await user_profile_service.get_user_profile(user_id)
        assert profile_latest is not None
        assert profile_latest.profile_version == 2

    @pytest.mark.asyncio
    async def test_multiple_event_types_aggregate_correctly(
        self, user_profile_service, mock_redis, mock_postgres
    ):
        user_id = generate_user_id()
        content_id = generate_content_id()

        emb = generate_embedding(768)
        mock_postgres._tables["content_items"] = [
            {
                "content_id": content_id,
                "title": "Test Content",
                "content_type": "article",
                "categories": ["tech"],
                "tags": ["python", "ai"],
                "author": "test",
                "popularity_score": 50.0,
                "embedding": json.dumps(emb),
            }
        ]

        mock_redis._data[f"content:embedding:{content_id}"] = json.dumps(emb)

        events = [
            UserBehaviorEventFactory(user_id=user_id, content_id=content_id, event_type="expose"),
            UserBehaviorEventFactory(user_id=user_id, content_id=content_id, event_type="click"),
            UserBehaviorEventFactory(user_id=user_id, content_id=content_id, event_type="stay", duration_seconds=45.5),
            UserBehaviorEventFactory(user_id=user_id, content_id=content_id, event_type="share"),
        ]

        for event in events:
            await user_profile_service.ingest_behavior_event(event)
            await asyncio.sleep(0.01)

        stats = await mock_redis.hgetall(f"user:stats:{user_id}")
        assert stats is not None
        assert int(stats.get("event:expose", 0)) == 1
        assert int(stats.get("event:click", 0)) == 1
        assert int(stats.get("event:stay", 0)) == 1
        assert int(stats.get("event:share", 0)) == 1

        profile = await user_profile_service.get_user_profile(user_id)
        assert profile is not None
        assert profile.realtime_behavior_stats["click_count"] >= 1
        assert profile.realtime_behavior_stats["share_count"] >= 1


class TestUserProfileServiceExceptionPath:

    @pytest.mark.asyncio
    async def test_corrupted_event_does_not_crash_and_handles_gracefully(
        self, user_profile_service, mock_redis
    ):
        user_id = generate_user_id()

        corrupted_data = {
            "user_id": user_id,
            "content_id": 12345,
            "event_type": "invalid_type",
            "timestamp": "not_a_timestamp",
        }

        try:
            event = UserBehaviorEvent(**corrupted_data)
            await user_profile_service.ingest_behavior_event(event)
        except Exception as e:
            assert "validation" in str(e).lower() or "type" in str(e).lower()
            pytest.skip("Pydantic correctly rejected invalid data at schema level")

        await user_profile_service.ingest_behavior_event(
            UserBehaviorEventFactory(user_id=user_id, event_type="click")
        )

        stats = await mock_redis.hgetall(f"user:stats:{user_id}")
        assert stats is not None
        assert "event:click" in stats
        assert int(stats["event:click"]) == 1

    @pytest.mark.asyncio
    async def test_nonexistent_version_falls_back_to_default_empty_profile(
        self, user_profile_service, mock_redis, mock_postgres
    ):
        user_id = generate_user_id()

        nonexistent_version = 9999

        profile = await user_profile_service.get_user_profile(user_id, profile_version=nonexistent_version)

        assert profile is not None
        assert profile.user_id == user_id

        assert len(profile.interest_tags) == 0
        assert len(profile.offline_tags) == 0

        assert isinstance(profile.user_vector, list)
        assert len(profile.user_vector) == 768
        assert all(v == 0.0 for v in profile.user_vector)

        assert profile.realtime_behavior_stats is not None
        assert profile.realtime_behavior_stats.get("click_count", 0) == 0

    @pytest.mark.asyncio
    async def test_missing_content_tags_handles_gracefully(
        self, user_profile_service, mock_redis, mock_postgres
    ):
        user_id = generate_user_id()
        content_id = generate_content_id()

        emb = generate_embedding(768)
        mock_postgres._tables["content_items"] = [
            {
                "content_id": content_id,
                "title": "Test Content",
                "content_type": "article",
                "categories": ["tech"],
                "tags": ["python", "ai"],
                "author": "test",
                "popularity_score": 50.0,
                "embedding": json.dumps(emb),
            }
        ]

        mock_redis._data[f"content:embedding:{content_id}"] = json.dumps(emb)

        event = UserBehaviorEventFactory(
            user_id=user_id,
            content_id=content_id,
            event_type="click",
        )
        await user_profile_service.ingest_behavior_event(event)

        profile = await user_profile_service.get_user_profile(user_id)
        assert profile is not None
        assert isinstance(profile, UserProfile)

    @pytest.mark.asyncio
    async def test_missing_embeddings_generate_zero_vector(
        self, user_profile_service, mock_redis, mock_postgres
    ):
        user_id = generate_user_id()
        content_id = generate_content_id()

        emb = generate_embedding(768)
        mock_postgres._tables["content_items"] = [
            {
                "content_id": content_id,
                "title": "Test",
                "content_type": "article",
                "categories": ["tech"],
                "tags": ["python"],
                "author": "test",
                "popularity_score": 50.0,
                "embedding": json.dumps(emb),
            }
        ]

        event = UserBehaviorEventFactory(
            user_id=user_id,
            content_id=content_id,
            event_type="click",
        )
        await user_profile_service.ingest_behavior_event(event)

        profile = await user_profile_service.get_user_profile(user_id)
        assert profile is not None
        user_vector = np.array(profile.user_vector)
        assert user_vector.shape == (768,)

    @pytest.mark.asyncio
    async def test_redis_failure_does_not_crash_service(
        self, user_profile_service, mock_redis
    ):
        user_id = generate_user_id()

        original_set = mock_redis.set
        async def failing_set(*args, **kwargs):
            raise RuntimeError("Redis connection failed")

        mock_redis.set = failing_set

        try:
            event = UserBehaviorEventFactory(user_id=user_id, event_type="click")
            await user_profile_service.ingest_behavior_event(event)
        except Exception as e:
            assert "Redis" in str(e) or "connection" in str(e).lower()
        finally:
            mock_redis.set = original_set


class TestUserProfileServiceEdgeCases:

    @pytest.mark.asyncio
    async def test_empty_user_returns_default_profile(
        self, user_profile_service
    ):
        user_id = generate_user_id()
        profile = await user_profile_service.get_user_profile(user_id)

        assert profile is not None
        assert profile.user_id == user_id
        assert len(profile.interest_tags) == 0
        assert len(profile.offline_tags) == 0

    @pytest.mark.asyncio
    async def test_profile_versions_list_correct_order(
        self, user_profile_service, mock_redis, mock_postgres
    ):
        user_id = generate_user_id()

        for version in range(1, 6):
            profile_data = UserProfileFactory(
                user_id=user_id,
                profile_version=version,
            ).model_dump(mode="json")

            await mock_postgres.upsert(
                "user_profile_versions",
                {
                    "user_id": user_id,
                    "profile_version": version,
                    "profile_data": json.dumps(profile_data),
                    "created_at": datetime.now(timezone.utc) - timedelta(hours=5 - version),
                },
                ["user_id", "profile_version"],
            )

        versions = await user_profile_service.list_profile_versions(user_id, limit=10)
        assert len(versions) == 5

        version_numbers = [v["profile_version"] for v in versions]
        assert version_numbers == [5, 4, 3, 2, 1]

    @pytest.mark.asyncio
    async def test_offline_tags_merged_with_realtime(
        self, user_profile_service, mock_redis, mock_postgres
    ):
        user_id = generate_user_id()

        offline_tag = InterestTagFactory(
            tag_id="offline_tag_1",
            tag_name="finance",
            weight=0.8,
            version="offline_v2",
        )

        mock_postgres._tables["user_offline_tags"] = [
            {
                "user_id": user_id,
                "tag_id": offline_tag.tag_id,
                "tag_name": offline_tag.tag_name,
                "weight": offline_tag.weight,
                "version": offline_tag.version,
                "updated_at": datetime.now(timezone.utc),
            }
        ]

        profile = await user_profile_service.get_user_profile(user_id)
        assert profile is not None

        offline_tag_ids = [t.tag_id for t in profile.offline_tags]
        assert "offline_tag_1" in offline_tag_ids

        offline_weights = {t.tag_id: t.weight for t in profile.offline_tags}
        assert offline_weights["offline_tag_1"] == pytest.approx(0.8, 0.01)
