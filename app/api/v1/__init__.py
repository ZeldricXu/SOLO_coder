from fastapi import APIRouter

from app.api.v1.documents import router as documents_router
from app.api.v1.extractions import router as extractions_router
from app.api.v1.review import router as review_router
from app.api.v1.batches import router as batches_router
from app.api.v1.models import router as models_router
from app.api.v1.ab_test import router as ab_test_router

api_router = APIRouter(prefix="/api/v1")

api_router.include_router(documents_router)
api_router.include_router(extractions_router)
api_router.include_router(review_router)
api_router.include_router(batches_router)
api_router.include_router(models_router)
api_router.include_router(ab_test_router)

__all__ = [
    "api_router",
    "documents_router",
    "extractions_router",
    "review_router",
    "batches_router",
    "models_router",
    "ab_test_router",
]
