import asyncio
import sys
from contextlib import asynccontextmanager
from typing import Any, Dict

import uvicorn
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .config import get_settings
from .db import init_db, close_db
from .utils import setup_logging, get_logger, NFTIndexerError
from .container import get_container
from .modules import (
    get_chain_adapter,
    get_multisig_module,
    get_event_listener_module,
    get_cross_chain_module,
    get_indexer_module,
    init_multisig_module,
    init_event_listener_module,
    init_cross_chain_module,
)
from .api.routers import (
    health,
    resources,
    chain,
    multisig,
    events,
    cross_chain,
    wallet,
    zkp,
    gas,
    storage,
    indexer,
)

settings = get_settings()
setup_logging(settings.logging.level)
logger = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting NFTIndexer service...")

    container = None
    try:
        await init_db()
        logger.info("Database initialized")

        container = get_container()
        await container.initialize()
        logger.info("DI container initialized")

        chain_adapter = get_chain_adapter()
        await chain_adapter.initialize()
        logger.info("Chain adapter initialized")

        multisig = await init_multisig_module(container)
        logger.info("Multi-sig module initialized")

        event_listener = await init_event_listener_module(chain_adapter, container)
        await event_listener.start()
        logger.info("Event listener initialized and started")

        cross_chain = await init_cross_chain_module(chain_adapter, container)
        logger.info("Cross-chain module initialized")

        indexer = get_indexer_module()
        await indexer.initialize()
        await indexer.start()
        logger.info("Indexer module initialized and started")

        logger.info(f"NFTIndexer service started successfully on {settings.api.host}:{settings.api.port}")

        yield

    except Exception as e:
        logger.error(f"Failed to start NFTIndexer service: {e}", exc_info=True)
        sys.exit(1)

    finally:
        logger.info("Shutting down NFTIndexer service...")

        try:
            indexer = get_indexer_module()
            await indexer.shutdown()
            logger.info("Indexer module shutdown")
        except Exception as e:
            logger.error(f"Error shutting down indexer module: {e}")

        try:
            cross_chain = get_cross_chain_module()
            await cross_chain.shutdown()
            logger.info("Cross-chain module shutdown")
        except Exception as e:
            logger.error(f"Error shutting down cross-chain module: {e}")

        try:
            event_listener = get_event_listener_module()
            await event_listener.shutdown()
            logger.info("Event listener shutdown")
        except Exception as e:
            logger.error(f"Error shutting down event listener: {e}")

        try:
            multisig = get_multisig_module()
            await multisig.shutdown()
            logger.info("Multi-sig module shutdown")
        except Exception as e:
            logger.error(f"Error shutting down multi-sig module: {e}")

        try:
            chain_adapter = get_chain_adapter()
            await chain_adapter.shutdown()
            logger.info("Chain adapter shutdown")
        except Exception as e:
            logger.error(f"Error shutting down chain adapter: {e}")

        if container:
            try:
                await container.shutdown()
                logger.info("DI container shutdown")
            except Exception as e:
                logger.error(f"Error shutting down DI container: {e}")

        try:
            await close_db()
            logger.info("Database connection closed")
        except Exception as e:
            logger.error(f"Error closing database connection: {e}")

        logger.info("NFTIndexer service shutdown complete")


def create_app() -> FastAPI:
    app = FastAPI(
        title="NFTIndexer API",
        description="NFT元数据索引与查询服务 - 企业级区块链基础设施",
        version=settings.app_version,
        lifespan=lifespan,
        docs_url="/docs" if settings.api.enable_docs else None,
        redoc_url="/redoc" if settings.api.enable_docs else None,
        openapi_url="/openapi.json" if settings.api.enable_docs else None,
    )

    if settings.api.enable_cors:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.api.cors_origins,
            allow_credentials=True,
            allow_methods=["*"],
            allow_headers=["*"],
        )

    @app.exception_handler(NFTIndexerError)
    async def nftindexer_exception_handler(request: Request, exc: NFTIndexerError):
        logger.error(f"NFTIndexer error: {exc.message}", extra={"details": exc.details})
        return JSONResponse(
            status_code=exc.code,
            content={
                "code": exc.code,
                "message": exc.message,
                "details": exc.details,
            },
        )

    @app.exception_handler(HTTPException)
    async def http_exception_handler(request: Request, exc: HTTPException):
        logger.warning(f"HTTP exception: {exc.status_code} - {exc.detail}")
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "code": exc.status_code,
                "message": exc.detail,
            },
        )

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request: Request, exc: Exception):
        logger.error(f"Unhandled exception: {str(exc)}", exc_info=True)
        return JSONResponse(
            status_code=500,
            content={
                "code": 500,
                "message": "Internal server error",
            },
        )

    app.include_router(health.router)
    app.include_router(resources.router)
    app.include_router(chain.router)
    app.include_router(multisig.router)
    app.include_router(events.router)
    app.include_router(cross_chain.router)
    app.include_router(wallet.router)
    app.include_router(zkp.router)
    app.include_router(gas.router)
    app.include_router(storage.router)
    app.include_router(indexer.router)

    @app.get("/", include_in_schema=False)
    async def root():
        return {
            "service": "NFTIndexer",
            "version": settings.app_version,
            "description": "NFT元数据索引与查询服务",
            "docs": "/docs" if settings.api.enable_docs else None,
        }

    return app


app = create_app()


def main():
    uvicorn.run(
        "nftindexer.main:app",
        host=settings.api.host,
        port=settings.api.port,
        reload=settings.api.debug,
        workers=settings.api.workers,
        log_level=settings.logging.level.lower(),
    )


if __name__ == "__main__":
    main()
