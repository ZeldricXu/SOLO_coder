from fastapi import APIRouter
from app.models.schemas import HealthResponse
from app.core.config import settings
from datetime import datetime

router = APIRouter()


@router.get("/health", response_model=HealthResponse)
async def health_check():
    return HealthResponse(
        status="ok",
        version=settings.VERSION,
        timestamp=datetime.utcnow().isoformat()
    )


@router.get("/info")
async def system_info():
    return {
        "name": settings.PROJECT_NAME,
        "version": settings.VERSION,
        "api_version": settings.API_V1_STR,
        "features": {
            "file_upload": True,
            "vector_search": True,
            "rag_chat": True,
            "streaming": True
        },
        "config": {
            "max_file_size": settings.MAX_FILE_SIZE,
            "allowed_file_types": settings.ALLOWED_FILE_TYPES,
            "chunk_size": settings.CHUNK_SIZE,
            "chunk_overlap": settings.CHUNK_OVERLAP,
            "top_k": settings.TOP_K
        }
    }
