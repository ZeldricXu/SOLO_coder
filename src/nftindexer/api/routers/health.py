from fastapi import APIRouter
from ...core.schemas import ResourceResponse
from ...config import get_settings
from ...utils import get_logger

logger = get_logger(__name__)
router = APIRouter(tags=["Health"])
settings = get_settings()


@router.get("/health", response_model=ResourceResponse)
async def health_check():
    return ResourceResponse(
        code=200,
        message="healthy",
        data={
            "service": "NFTIndexer",
            "version": settings.app_version,
            "status": "running",
        }
    )


@router.get("/health/ready", response_model=ResourceResponse)
async def readiness_check():
    return ResourceResponse(
        code=200,
        message="ready",
        data={
            "service": "NFTIndexer",
            "version": settings.app_version,
            "ready": True,
        }
    )
