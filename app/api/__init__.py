from fastapi import APIRouter
from app.api.routes import (
    router, config_router, storage_router, classification_router,
    dp_router, audit_router, notification_router, mpc_router,
    migration_router, health_router
)

api_router = APIRouter()

api_router.include_router(router)
api_router.include_router(config_router)
api_router.include_router(storage_router)
api_router.include_router(classification_router)
api_router.include_router(dp_router)
api_router.include_router(audit_router)
api_router.include_router(notification_router)
api_router.include_router(mpc_router)
api_router.include_router(migration_router)
api_router.include_router(health_router)

__all__ = ["api_router"]
