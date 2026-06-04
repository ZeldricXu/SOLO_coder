import factory
from factory import Faker, LazyFunction, SubFactory
from faker import Faker as FakerLib
from datetime import datetime, timezone
import uuid
import random
from typing import List, Dict, Any

from recommendation_engine.models.schemas import (
    UserBehaviorEvent,
    UserProfile,
    InterestTag,
    ContentItem,
    ContentEmbedding,
    FeedbackEvent,
    ABTestExperiment,
    RecommendRequest,
    RecommendResponse,
    ModelInferenceRequest,
)

_faker_lib = FakerLib()


def get_utc_now():
    return datetime.now(timezone.utc)


def generate_user_id():
    return f"user_{_faker_lib.uuid4()[:8]}"


def generate_content_id():
    return f"content_{_faker_lib.uuid4()[:8]}"


def generate_embedding(dim: int = 768) -> List[float]:
    return [random.uniform(-1.0, 1.0) for _ in range(dim)]


CATEGORIES = ["tech", "sports", "finance", "entertainment", "education", "health", "travel", "food"]
TAGS = ["python", "ai", "basketball", "stocks", "movies", "yoga", "japan", "pizza", "music", "gaming"]
EVENT_TYPES = ["click", "stay", "purchase", "share", "expose", "collect"]


class InterestTagFactory(factory.Factory):
    class Meta:
        model = InterestTag

    tag_id = LazyFunction(lambda: f"tag_{random.choice(TAGS)}")
    tag_name = LazyFunction(lambda: random.choice(TAGS))
    weight = LazyFunction(lambda: round(random.uniform(0.1, 1.0), 2))
    version = "v1"
    updated_at = LazyFunction(get_utc_now)


class UserBehaviorEventFactory(factory.Factory):
    class Meta:
        model = UserBehaviorEvent

    event_id = LazyFunction(lambda: str(uuid.uuid4()))
    user_id = LazyFunction(generate_user_id)
    content_id = LazyFunction(generate_content_id)
    event_type = LazyFunction(lambda: random.choice(EVENT_TYPES))
    timestamp = LazyFunction(get_utc_now)
    duration_seconds = LazyFunction(
        lambda: round(random.uniform(1.0, 300.0), 2)
        if random.random() > 0.5 else None
    )
    page = LazyFunction(lambda: f"/{random.choice(CATEGORIES)}/{generate_content_id()}")
    position = LazyFunction(lambda: random.randint(0, 50) if random.random() > 0.3 else None)
    device_type = LazyFunction(lambda: random.choice(["mobile", "desktop", "tablet"]))
    extra = LazyFunction(
        lambda: {"source": random.choice(["organic", "search", "social"])}
        if random.random() > 0.5 else None
    )


class ContentItemFactory(factory.Factory):
    class Meta:
        model = ContentItem

    content_id = LazyFunction(generate_content_id)
    title = Faker("sentence", nb_words=8)
    content_type = LazyFunction(
        lambda: random.choice(["article", "video", "image", "product"])
    )
    categories = LazyFunction(lambda: random.sample(CATEGORIES, k=random.randint(1, 3)))
    tags = LazyFunction(lambda: random.sample(TAGS, k=random.randint(1, 5)))
    author = Faker("name")
    publish_time = LazyFunction(get_utc_now)
    popularity_score = LazyFunction(lambda: round(random.uniform(0.0, 100.0), 2))
    metadata = LazyFunction(
        lambda: {"views": random.randint(100, 10000), "likes": random.randint(0, 1000)}
    )
    embedding = None


class ContentEmbeddingFactory(factory.Factory):
    class Meta:
        model = ContentEmbedding

    content_id = LazyFunction(generate_content_id)
    embedding = LazyFunction(generate_embedding)
    embedding_type = LazyFunction(lambda: random.choice(["text", "image", "multimodal"]))
    model_version = LazyFunction(lambda: f"v{random.randint(1, 5)}")
    created_at = LazyFunction(get_utc_now)


class UserProfileFactory(factory.Factory):
    class Meta:
        model = UserProfile

    user_id = LazyFunction(generate_user_id)
    version = "v1"
    profile_version = LazyFunction(lambda: random.randint(1, 100))
    user_vector = LazyFunction(generate_embedding)
    interest_tags = factory.List([SubFactory(InterestTagFactory) for _ in range(5)])
    offline_tags = factory.List([SubFactory(InterestTagFactory) for _ in range(3)])
    realtime_behavior_stats = LazyFunction(
        lambda: {
            "click_count": random.randint(0, 100),
            "stay_time_total": round(random.uniform(0, 3600), 2),
            "purchase_count": random.randint(0, 20),
        }
    )
    demographics = LazyFunction(
        lambda: {
            "age_group": random.choice(["18-24", "25-34", "35-44", "45+"]),
            "gender": random.choice(["male", "female", "other"]),
        }
        if random.random() > 0.5 else None
    )
    created_at = LazyFunction(get_utc_now)
    updated_at = LazyFunction(get_utc_now)
    experiment_group = LazyFunction(
        lambda: random.choice(["control", "experiment_a", "experiment_b"])
        if random.random() > 0.5 else None
    )


class FeedbackEventFactory(factory.Factory):
    class Meta:
        model = FeedbackEvent

    event_id = LazyFunction(lambda: str(uuid.uuid4()))
    user_id = LazyFunction(generate_user_id)
    content_id = LazyFunction(generate_content_id)
    event_type = LazyFunction(lambda: random.choice(["expose", "click", "purchase", "share", "collect"]))
    timestamp = LazyFunction(get_utc_now)
    request_id = LazyFunction(lambda: str(uuid.uuid4()) if random.random() > 0.3 else None)
    scene = LazyFunction(lambda: random.choice(["home", "search", "detail", "related"]))
    position = LazyFunction(lambda: random.randint(0, 50) if random.random() > 0.3 else None)
    value = LazyFunction(lambda: round(random.uniform(0.0, 5.0), 2) if random.random() > 0.4 else None)
    extra = LazyFunction(
        lambda: {"device": random.choice(["ios", "android", "web"])}
        if random.random() > 0.5 else None
    )


class ABTestExperimentFactory(factory.Factory):
    class Meta:
        model = ABTestExperiment

    experiment_id = LazyFunction(lambda: f"exp_{uuid.uuid4().hex[:8]}")
    name = Faker("sentence", nb_words=4)
    layer = LazyFunction(lambda: random.choice(["recall_layer", "rank_layer", "rerank_layer"]))
    version = LazyFunction(lambda: f"v{random.randint(1, 10)}")
    status = LazyFunction(lambda: random.choice(["active", "paused", "ended"]))
    traffic_percentage = LazyFunction(lambda: random.randint(10, 100))
    control_group = "control"
    experiment_groups = LazyFunction(
        lambda: random.sample(
            ["experiment_a", "experiment_b", "experiment_c"],
            k=random.randint(1, 3)
        )
    )
    config = LazyFunction(
        lambda: {
            "recall_weight": round(random.uniform(0.5, 1.5), 2),
            "mmr_lambda": round(random.uniform(0.5, 0.9), 2),
            "enable_new_feature": random.choice([True, False]),
        }
    )
    created_at = LazyFunction(get_utc_now)
    updated_at = LazyFunction(get_utc_now)


class RecommendRequestFactory(factory.Factory):
    class Meta:
        model = RecommendRequest

    user_id = LazyFunction(generate_user_id)
    scene = LazyFunction(lambda: random.choice(["home", "search", "detail", "category"]))
    request_id = LazyFunction(lambda: str(uuid.uuid4()))
    top_n = LazyFunction(lambda: random.randint(5, 50))
    context = LazyFunction(
        lambda: {"query": _faker_lib.word()}
        if random.random() > 0.5 else None
    )
    exclude_content_ids = LazyFunction(
        lambda: [generate_content_id() for _ in range(random.randint(0, 5))]
    )


class ModelInferenceRequestFactory(factory.Factory):
    class Meta:
        model = ModelInferenceRequest

    model_name = LazyFunction(
        lambda: random.choice(["ctr_model", "cvr_model", "rank_model", "embedding_model"])
    )
    model_version = LazyFunction(lambda: f"{random.randint(1, 5)}" if random.random() > 0.5 else None)
    inputs = LazyFunction(
        lambda: {
            "features": [generate_embedding(10) for _ in range(random.randint(1, 10))]
        }
    )
    request_id = LazyFunction(lambda: str(uuid.uuid4()))
    timeout_ms = LazyFunction(lambda: random.randint(1000, 30000))


def create_batch_events(count: int, user_id: str = None) -> List[UserBehaviorEvent]:
    uid = user_id or generate_user_id()
    return [
        UserBehaviorEventFactory(user_id=uid)
        for _ in range(count)
    ]


def create_batch_content_items(count: int) -> List[ContentItem]:
    return [ContentItemFactory() for _ in range(count)]


def create_interactions(count: int) -> List[tuple]:
    return [
        (generate_user_id(), generate_content_id(), round(random.uniform(0.1, 5.0), 2))
        for _ in range(count)
    ]
