from recommendation_engine.api.dependencies import (
    app_lifespan,
    get_redis,
    get_postgres,
    get_user_profile_svc,
    get_content_index_svc,
    get_cf_svc,
    get_rank_pipeline_svc,
    get_abtest_router_svc,
    get_feedback_collector_svc,
    get_model_gateway_svc,
    verify_api_key,
)
from recommendation_engine.api.middleware import register_middlewares

__all__ = [
    "app_lifespan",
    "get_redis",
    "get_postgres",
    "get_user_profile_svc",
    "get_content_index_svc",
    "get_cf_svc",
    "get_rank_pipeline_svc",
    "get_abtest_router_svc",
    "get_feedback_collector_svc",
    "get_model_gateway_svc",
    "verify_api_key",
    "register_middlewares",
]
