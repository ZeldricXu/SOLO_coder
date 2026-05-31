from fastapi import APIRouter

from .transaction_routes import router as transaction_router
from .cross_chain_routes import router as cross_chain_router
from .storage_routes import router as storage_router
from .events_routes import router as events_router
from .chain_routes import router as chain_router
from .gas_routes import router as gas_router
from .address_routes import router as address_router
from .indexer_routes import router as indexer_router
from .common_routes import router as common_router

api_router = APIRouter(prefix="/api/v1")

api_router.include_router(common_router)
api_router.include_router(transaction_router)
api_router.include_router(cross_chain_router)
api_router.include_router(storage_router)
api_router.include_router(events_router)
api_router.include_router(chain_router)
api_router.include_router(gas_router)
api_router.include_router(address_router)
api_router.include_router(indexer_router)

__all__ = ["api_router"]
