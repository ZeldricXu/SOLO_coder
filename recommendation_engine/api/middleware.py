from fastapi import Request, Response
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.concurrency import iterate_in_threadpool
from loguru import logger
import time
import uuid
import json
from typing import Callable, Awaitable


class RequestIdMiddleware(BaseHTTPMiddleware):
    async def dispatch(
        self, request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        request_id = request.headers.get("x-request-id", str(uuid.uuid4()))
        request.state.request_id = request_id

        start_time = time.time()
        client_ip = request.client.host if request.client else "unknown"

        logger.info(
            f"Request started: {request.method} {request.url.path} "
            f"request_id={request_id} client_ip={client_ip}"
        )

        try:
            response = await call_next(request)
            process_time_ms = (time.time() - start_time) * 1000

            response.headers["x-request-id"] = request_id
            response.headers["x-process-time-ms"] = f"{process_time_ms:.2f}"

            logger.info(
                f"Request completed: {request.method} {request.url.path} "
                f"status={response.status_code} request_id={request_id} "
                f"duration_ms={process_time_ms:.2f}"
            )

            return response
        except Exception as e:
            process_time_ms = (time.time() - start_time) * 1000
            logger.error(
                f"Request failed: {request.method} {request.url.path} "
                f"error={str(e)} request_id={request_id} "
                f"duration_ms={process_time_ms:.2f}",
                exc_info=True,
            )
            raise


class LoggingMiddleware(BaseHTTPMiddleware):
    async def dispatch(
        self, request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        response = await call_next(request)

        if response.status_code >= 400:
            try:
                response_body = [section async for section in response.body_iterator]
                response.body_iterator = iterate_in_threadpool(iter(response_body))
                body_text = b"".join(response_body).decode("utf-8", errors="replace")
                logger.warning(
                    f"Error response body: {body_text[:500]}"
                )
            except Exception:
                pass

        return response


class CORSMiddlewareCustom:
    def __init__(
        self,
        allow_origins: list = None,
        allow_methods: list = None,
        allow_headers: list = None,
        allow_credentials: bool = True,
    ):
        self.allow_origins = allow_origins or ["*"]
        self.allow_methods = allow_methods or ["*"]
        self.allow_headers = allow_headers or ["*"]
        self.allow_credentials = allow_credentials

    async def __call__(self, request: Request, call_next):
        origin = request.headers.get("origin")

        if request.method == "OPTIONS":
            response = Response(status_code=204)
        else:
            response = await call_next(request)

        if origin:
            if "*" in self.allow_origins or origin in self.allow_origins:
                response.headers["Access-Control-Allow-Origin"] = origin
                response.headers["Access-Control-Allow-Methods"] = ", ".join(
                    self.allow_methods
                )
                response.headers["Access-Control-Allow-Headers"] = ", ".join(
                    self.allow_headers
                )
                if self.allow_credentials:
                    response.headers["Access-Control-Allow-Credentials"] = "true"
                response.headers["Access-Control-Max-Age"] = "3600"

        return response


def register_middlewares(app):
    from fastapi.middleware.cors import CORSMiddleware

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.add_middleware(RequestIdMiddleware)
    app.add_middleware(LoggingMiddleware)
