from recommendation_engine.api.routers.recommend import router as recommend_router
from recommendation_engine.api.routers.user_profile import router as user_profile_router
from recommendation_engine.api.routers.content_index import router as content_index_router
from recommendation_engine.api.routers.collaborative_filter import router as collaborative_filter_router
from recommendation_engine.api.routers.abtest import router as abtest_router
from recommendation_engine.api.routers.feedback import router as feedback_router
from recommendation_engine.api.routers.model_serving import router as model_serving_router
from recommendation_engine.api.routers.health import router as health_router

recommend = recommend_router
user_profile = user_profile_router
content_index = content_index_router
collaborative_filter = collaborative_filter_router
abtest = abtest_router
feedback = feedback_router
model_serving = model_serving_router
health = health_router

__all__ = [
    "recommend",
    "user_profile",
    "content_index",
    "collaborative_filter",
    "abtest",
    "feedback",
    "model_serving",
    "health",
]
