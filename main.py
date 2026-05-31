import asyncio
import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware

from wallethub import __version__
from wallethub.config import get_settings
from wallethub.api.v1 import api_router
from wallethub.db import init_db
from wallethub.events import get_event_bus
from wallethub.core import WalletHubError

settings = get_settings()

logging.basicConfig(
    level=settings.log_level.upper(),
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"Starting {settings.app_name} v{__version__}")

    init_db()

    event_bus = get_event_bus()
    event_bus.start()

    logger.info(f"{settings.app_name} started successfully")
    yield

    event_bus = get_event_bus()
    await event_bus.stop()

    logger.info(f"{settings.app_name} shutdown complete")


app = FastAPI(
    title=settings.app_name,
    description="数字资产钱包管理服务 - WalletHub",
    version=__version__,
    lifespan=lifespan,
    docs_url="/docs",
    redoc_url="/redoc",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(WalletHubError)
async def wallethub_exception_handler(request: Request, exc: WalletHubError):
    return JSONResponse(
        status_code=exc.code,
        content=exc.to_dict(),
    )


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    return JSONResponse(
        status_code=exc.status_code,
        content={
            "code": exc.status_code,
            "message": exc.detail,
            "details": {},
        },
    )


@app.exception_handler(Exception)
async def general_exception_handler(request: Request, exc: Exception):
    logger.error(f"Unhandled exception: {str(exc)}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={
            "code": 500,
            "message": "Internal server error",
            "details": {"error": str(exc)} if settings.debug else {},
        },
    )


app.include_router(api_router)


@app.get("/", tags=["Root"])
async def root():
    return {
        "name": settings.app_name,
        "version": __version__,
        "environment": settings.environment,
        "docs": "/docs",
        "api_prefix": "/api/v1",
    }


@app.get("/health", tags=["Root"])
async def health():
    return {"status": "healthy", "version": __version__}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "main:app",
        host=settings.api_host,
        port=settings.api_port,
        reload=settings.debug,
        workers=1 if settings.debug else 4,
    )
