from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, Dict

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from src.api.v1.chain_routes import router as chain_router
from src.api.v1.cross_chain_routes import router as cross_chain_router
from src.api.v1.event_routes import router as event_router
from src.api.v1.gas_routes import router as gas_router
from src.api.v1.indexer_routes import router as indexer_router
from src.api.v1.resource_routes import router as resource_router
from src.api.v1.storage_routes import router as storage_router
from src.api.v1.transaction_routes import router as transaction_router
from src.api.v1.wallet_routes import router as wallet_router
from src.api.v1.zkp_routes import router as zkp_router
from src.shared.config import settings
from src.shared.container import container
from src.shared.logger import get_logger

logger = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting application...")
    await container.initialize()
    logger.info("Application started successfully")
    yield
    logger.info("Shutting down application...")
    for chain in container.list_available_chains():
        try:
            listener = container.get_event_listener(chain)
            if listener.is_listening():
                await listener.stop_listening()
            indexer = container.get_indexer(chain)
            if indexer.is_indexing():
                await indexer.stop_indexing()
        except Exception as e:
            logger.warning(f"Error during shutdown for chain {chain}: {e}")
    logger.info("Application shutdown complete")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Blockchain Infrastructure Platform",
        description="A comprehensive blockchain infrastructure platform with 8 core modules",
        version=settings.app.version,
        lifespan=lifespan,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(chain_router, prefix="/api/v1")
    app.include_router(event_router, prefix="/api/v1")
    app.include_router(gas_router, prefix="/api/v1")
    app.include_router(wallet_router, prefix="/api/v1")
    app.include_router(zkp_router, prefix="/api/v1")
    app.include_router(cross_chain_router, prefix="/api/v1")
    app.include_router(indexer_router, prefix="/api/v1")
    app.include_router(storage_router, prefix="/api/v1")
    app.include_router(transaction_router, prefix="/api/v1")
    app.include_router(resource_router, prefix="/api/v1")

    @app.get("/")
    async def root() -> Dict[str, Any]:
        return {
            "name": settings.app.name,
            "version": settings.app.version,
            "status": "running",
            "modules": [
                "chain_interaction",
                "event_listener",
                "gas_estimator",
                "zkp_verifier",
                "address_manager",
                "cross_chain_bridge",
                "data_indexer",
                "decentralized_storage",
                "transaction_builder",
            ],
            "docs": "/docs",
        }

    @app.get("/health")
    async def health() -> Dict[str, Any]:
        return {
            "status": "healthy",
            "chains": [c.value for c in container.list_available_chains()],
        }

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "src.main:app",
        host=settings.app.host,
        port=settings.app.port,
        reload=settings.app.debug,
    )
