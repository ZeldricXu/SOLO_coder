from fastapi import APIRouter

from app.api.v1.endpoints import novels, chapters, assets, write

api_router = APIRouter()

api_router.include_router(novels.router, prefix="/novels", tags=["novels"])
api_router.include_router(chapters.router, prefix="/chapters", tags=["chapters"])
api_router.include_router(assets.router, prefix="/assets", tags=["assets"])
api_router.include_router(write.router, prefix="/write", tags=["write"])
