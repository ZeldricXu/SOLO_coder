import time
from typing import Optional
from fastapi import Request, Response
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.types import ASGIApp
from sqlalchemy.orm import Session

from app.core.database import SessionLocal
from app.core.audit import AuditLogger
from app.core.security import decode_token
from app.core.config import settings
from app.core.logging import get_logger

logger = get_logger(__name__)


class RequestIDMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: ASGIApp) -> Response:
        start_time = time.time()
        request_id = request.headers.get("X-Request-ID", str(int(time.time() * 1000)))

        request.state.request_id = request_id

        response = await call_next(request)

        process_time = (time.time() - start_time) * 1000
        response.headers["X-Request-ID"] = request_id
        response.headers["X-Process-Time"] = f"{process_time:.2f}ms"

        return response


class AuditMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: ASGIApp) -> Response:
        db: Optional[Session] = None
        try:
            if request.method in ["POST", "PUT", "PATCH", "DELETE"]:
                path = request.url.path
                if not path.startswith(f"{settings.API_PREFIX}/auth") and not path.startswith(
                    "/docs"
                ):
                    db = SessionLocal()
                    audit_logger = AuditLogger(db)

                    authorization = request.headers.get("Authorization")
                    user_id = None
                    if authorization and authorization.startswith("Bearer "):
                        try:
                            token_data = decode_token(authorization.replace("Bearer ", ""))
                            user_id = token_data.user_id
                        except Exception:
                            pass

                    resource_type = path.split("/")[3] if len(path.split("/")) > 3 else "unknown"

                    audit_logger.log(
                        user_id=user_id,
                        action=request.method.lower(),
                        resource_type=resource_type,
                        ip_address=request.client.host if request.client else None,
                        user_agent=request.headers.get("User-Agent"),
                    )
                    db.commit()
        except Exception as e:
            logger.error("Audit logging failed", error=str(e))
            if db:
                db.rollback()
        finally:
            if db:
                db.close()

        return await call_next(request)


class RateLimitMiddleware(BaseHTTPMiddleware):
    def __init__(self, app: ASGIApp):
        super().__init__(app)
        from slowapi import Limiter
        from slowapi.util import get_remote_address

        self.limiter = Limiter(key_func=get_remote_address)

    async def dispatch(self, request: Request, call_next: ASGIApp) -> Response:
        return await call_next(request)


class LoggingMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: ASGIApp) -> Response:
        start_time = time.time()

        logger.info(
            "Request started",
            method=request.method,
            path=request.url.path,
            client=request.client.host if request.client else None,
            user_agent=request.headers.get("User-Agent"),
        )

        response = await call_next(request)

        process_time = (time.time() - start_time) * 1000

        logger.info(
            "Request completed",
            method=request.method,
            path=request.url.path,
            status_code=response.status_code,
            process_time_ms=f"{process_time:.2f}",
        )

        return response
