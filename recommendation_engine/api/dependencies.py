from typing import Optional
from contextlib import asynccontextmanager
from fastapi import Request, Header, HTTPException, status
from loguru import logger

from config import settings
from recommendation_engine.infrastructure import (
    get_redis_client,
    get_postgres_client,
    close_redis_client,
    close_postgres_client,
    RedisClient,
    PostgresClient,
)
from recommendation_engine.user_profile_service import (
    get_user_profile_service,
    close_user_profile_service,
    UserProfileService,
)
from recommendation_engine.content_embedding_index import (
    get_content_embedding_index,
    close_content_embedding_index,
    ContentEmbeddingIndex,
)
from recommendation_engine.collaborative_filter import (
    get_collaborative_filter,
    close_collaborative_filter,
    CollaborativeFilter,
)
from recommendation_engine.realtime_rank_pipeline import (
    get_rank_pipeline,
    close_rank_pipeline,
    RealtimeRankPipeline,
)
from recommendation_engine.ab_test_router import (
    get_ab_test_router,
    close_ab_test_router,
    ABTestRouter,
)
from recommendation_engine.feedback_collector import (
    get_feedback_collector,
    close_feedback_collector,
    FeedbackCollector,
)
from recommendation_engine.model_serving_gateway import (
    get_model_gateway,
    close_model_gateway,
    ModelServingGateway,
)


@asynccontextmanager
async def app_lifespan(app):
    logger.info("Starting recommendation engine service...")

    redis = await get_redis_client()
    postgres = await get_postgres_client()

    await postgres.init_tables()

    user_profile_service = await get_user_profile_service(redis, postgres)
    content_index = await get_content_embedding_index(redis, postgres)
    cf_service = await get_collaborative_filter(redis)
    rank_pipeline = await get_rank_pipeline(redis, postgres)
    abtest_router = await get_ab_test_router(postgres)
    feedback_collector = await get_feedback_collector()
    model_gateway = await get_model_gateway()

    app.state.redis = redis
    app.state.postgres = postgres
    app.state.user_profile_service = user_profile_service
    app.state.content_index = content_index
    app.state.cf_service = cf_service
    app.state.rank_pipeline = rank_pipeline
    app.state.abtest_router = abtest_router
    app.state.feedback_collector = feedback_collector
    app.state.model_gateway = model_gateway

    logger.info("All services initialized successfully")

    yield

    logger.info("Shutting down recommendation engine service...")

    await close_model_gateway()
    await close_feedback_collector()
    await close_ab_test_router()
    await close_rank_pipeline()
    await close_collaborative_filter()
    await close_content_embedding_index()
    await close_user_profile_service()
    await close_postgres_client()
    await close_redis_client()

    logger.info("All services closed successfully")


async def get_redis(request: Request) -> RedisClient:
    return request.app.state.redis


async def get_postgres(request: Request) -> PostgresClient:
    return request.app.state.postgres


async def get_user_profile_svc(request: Request) -> UserProfileService:
    return request.app.state.user_profile_service


async def get_content_index_svc(request: Request) -> ContentEmbeddingIndex:
    return request.app.state.content_index


async def get_cf_svc(request: Request) -> CollaborativeFilter:
    return request.app.state.cf_service


async def get_rank_pipeline_svc(request: Request) -> RealtimeRankPipeline:
    return request.app.state.rank_pipeline


async def get_abtest_router_svc(request: Request) -> ABTestRouter:
    return request.app.state.abtest_router


async def get_feedback_collector_svc(request: Request) -> FeedbackCollector:
    return request.app.state.feedback_collector


async def get_model_gateway_svc(request: Request) -> ModelServingGateway:
    return request.app.state.model_gateway


async def verify_api_key(x_api_key: Optional[str] = Header(None)):
    if not x_api_key:
        return
    expected_api_key = "dev-api-key"
    if x_api_key != expected_api_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid API key",
        )
