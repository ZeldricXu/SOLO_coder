import uuid
from contextlib import asynccontextmanager
from typing import Optional
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Query, Depends, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.core.database import init_db
from app.api.v1 import api_router
from app.utils.websocket_manager import ProgressWebSocketManager

logger = get_logger(__name__)
settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting application...")

    try:
        init_db()
        logger.info("Database initialized")
    except Exception as e:
        logger.error(f"Failed to initialize database: {e}", exc_info=True)

    try:
        ws_manager = ProgressWebSocketManager()
        await ws_manager.start_listener()
        logger.info("WebSocket listener started")
    except Exception as e:
        logger.error(f"Failed to start WebSocket listener: {e}", exc_info=True)

    yield

    logger.info("Shutting down application...")


app = FastAPI(
    title="DocIntel - Multi-format Document Intelligence Platform",
    description="""
    Multi-format Document Understanding and Information Extraction Pipeline
    for Insurance Claims Processing.

    Features:
    - Document processing (PDF, Word, Image, TXT)
    - OCR with PaddleOCR
    - Layout analysis with LayoutLMv3
    - Multimodal information extraction
    - Table understanding and structuring
    - Field validation with business rules
    - Human-in-the-loop review workflow
    - Batch processing with async tasks
    - Model version management and A/B testing
    """,
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(api_router)


@app.get("/", tags=["health"])
async def root():
    return {
        "name": "DocIntel - Document Intelligence Platform",
        "version": "1.0.0",
        "status": "running",
        "api_version": "v1",
        "docs": "/docs",
    }


@app.get("/health", tags=["health"])
async def health_check():
    from app.services.storage import StorageService

    storage_status = {"connected": False, "error": None}
    try:
        storage = StorageService()
        storage_status["connected"] = storage.is_connected()
    except Exception as e:
        storage_status["error"] = str(e)

    return {
        "status": "healthy",
        "timestamp": __import__("datetime").datetime.utcnow().isoformat(),
        "services": {
            "api": "running",
            "storage": storage_status,
        },
    }


@app.get("/health/detailed", tags=["health"])
async def detailed_health_check():
    from app.services.storage import StorageService
    from app.core.database import get_sync_db
    from sqlalchemy import text

    db_status = {"connected": False, "error": None}
    try:
        db = next(get_sync_db())
        db.execute(text("SELECT 1"))
        db_status["connected"] = True
    except Exception as e:
        db_status["error"] = str(e)
    finally:
        db.close()

    storage_status = {"connected": False, "error": None}
    try:
        storage = StorageService()
        storage_status["connected"] = storage.is_connected()
    except Exception as e:
        storage_status["error"] = str(e)

    cache_status = {"connected": False, "error": None}
    try:
        storage = StorageService()
        cache_status["connected"] = storage.is_cache_available()
    except Exception as e:
        cache_status["error"] = str(e)

    all_healthy = all([
        db_status["connected"],
        storage_status["connected"],
        cache_status["connected"],
    ])

    return {
        "status": "healthy" if all_healthy else "unhealthy",
        "timestamp": __import__("datetime").datetime.utcnow().isoformat(),
        "services": {
            "api": "running",
            "database": db_status,
            "storage": storage_status,
            "cache": cache_status,
        },
    }


@app.websocket("/ws")
async def websocket_endpoint(
    websocket: WebSocket,
    client_id: Optional[str] = Query(None),
    batch_id: Optional[str] = Query(None),
    document_id: Optional[str] = Query(None),
):
    if not client_id:
        client_id = str(uuid.uuid4())

    ws_manager = ProgressWebSocketManager()
    await ws_manager.handle_websocket(
        websocket=websocket,
        client_id=client_id,
        batch_id=batch_id,
        document_id=document_id,
    )


@app.websocket("/ws/batch/{batch_id}")
async def batch_websocket_endpoint(
    websocket: WebSocket,
    batch_id: str,
    client_id: Optional[str] = Query(None),
):
    if not client_id:
        client_id = str(uuid.uuid4())

    ws_manager = ProgressWebSocketManager()
    await ws_manager.handle_websocket(
        websocket=websocket,
        client_id=client_id,
        batch_id=batch_id,
    )


@app.websocket("/ws/document/{document_id}")
async def document_websocket_endpoint(
    websocket: WebSocket,
    document_id: str,
    client_id: Optional[str] = Query(None),
):
    if not client_id:
        client_id = str(uuid.uuid4())

    ws_manager = ProgressWebSocketManager()
    await ws_manager.handle_websocket(
        websocket=websocket,
        client_id=client_id,
        document_id=document_id,
    )


@app.exception_handler(Exception)
async def global_exception_handler(request, exc):
    logger.error(f"Unhandled exception: {exc}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={
            "success": False,
            "message": "Internal server error",
            "detail": str(exc) if settings.DEBUG else None,
        },
    )


@app.exception_handler(HTTPException)
async def http_exception_handler(request, exc):
    logger.warning(f"HTTP exception: {exc.status_code} - {exc.detail}")
    return JSONResponse(
        status_code=exc.status_code,
        content={
            "success": False,
            "message": exc.detail,
            "status_code": exc.status_code,
        },
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.HOST,
        port=settings.PORT,
        reload=settings.DEBUG,
        workers=settings.WORKERS,
    )
