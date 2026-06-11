from fastapi import APIRouter
from app.api.v1 import auth, heatmap, tiles, analysis, prediction, data, admin

api_router = APIRouter(prefix="/api/v1")

api_router.include_router(auth.router)
api_router.include_router(heatmap.router)
api_router.include_router(tiles.router)
api_router.include_router(analysis.router)
api_router.include_router(prediction.router)
api_router.include_router(data.router)
api_router.include_router(admin.router)


@api_router.get("/")
async def api_root():
    return {
        "name": "城市交通流量三维可视化平台 API",
        "version": "1.0.0",
        "endpoints": {
            "auth": "/api/v1/auth",
            "heatmap": "/api/v1/heatmap",
            "tiles": "/api/v1/tiles",
            "analysis": "/api/v1/analysis",
            "prediction": "/api/v1/prediction",
            "data": "/api/v1/data",
            "admin": "/api/v1/admin",
        }
    }
