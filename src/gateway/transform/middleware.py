from typing import Optional
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.middleware.cors import CORSMiddleware as StarletteCORSMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.types import ASGIApp

from gateway.config import get_settings
from gateway.transform.pipeline import get_transform_pipeline
from gateway.db.repository import TransformRuleRepository
from gateway.db import get_db
from gateway.logger import get_logger

logger = get_logger("transform-middleware")


class CORSMiddleware(StarletteCORSMiddleware):
    def __init__(self, app: ASGIApp):
        settings = get_settings()
        super().__init__(
            app,
            allow_origins=settings.gateway.cors_origins,
            allow_methods=settings.gateway.cors_methods,
            allow_headers=settings.gateway.cors_headers,
            allow_credentials=True,
            max_age=600,
        )


class TransformMiddleware(BaseHTTPMiddleware):
    def __init__(self, app):
        super().__init__(app)
        self.pipeline = get_transform_pipeline()
        self._rules_loaded = False

    async def dispatch(self, request: Request, call_next):
        if not self._rules_loaded:
            await self._load_global_rules()
            self._rules_loaded = True

        path = request.url.path

        if self._should_skip(path):
            return await call_next(request)

        route_match = getattr(request.state, "route_match", None)
        if route_match and route_match.route.transform_request:
            self.pipeline.load_route_rules(route_match.route.transform_request)

        context = {
            "request_id": getattr(request.state, "request_id", ""),
            "user": getattr(request.state, "user", {}),
            "path": path,
            "method": request.method,
            "content_type": request.headers.get("content-type", ""),
            "timestamp": getattr(request.state, "start_time", 0),
        }

        if getattr(request.state, "security_sanitized", False) and hasattr(request.state, "sanitized_headers"):
            modified_headers = dict(request.state.sanitized_headers)
        else:
            modified_headers = dict(request.headers)
        modified_headers = await self.pipeline.transform_request_headers(modified_headers, path, context)

        if getattr(request.state, "security_sanitized", False) and hasattr(request.state, "sanitized_query") and request.state.sanitized_query is not None:
            modified_query = request.state.sanitized_query
        else:
            modified_query = request.url.query
        if modified_query:
            modified_query = await self.pipeline.transform_request_query(modified_query, path, context)

        if getattr(request.state, "security_sanitized", False) and hasattr(request.state, "sanitized_body") and request.state.sanitized_body is not None:
            modified_body = request.state.sanitized_body
        elif hasattr(request.state, "cached_body"):
            modified_body = request.state.cached_body
        else:
            modified_body = await request.body()
            request.state.cached_body = modified_body
        if modified_body:
            modified_body = await self.pipeline.transform_request_body(modified_body, path, context)

        request.state.modified_headers = modified_headers
        request.state.modified_query = modified_query
        request.state.modified_body = modified_body

        response = await call_next(request)

        response_headers = dict(response.headers)
        response_headers = await self.pipeline.transform_response_headers(response_headers, path, context)

        for key, value in response_headers.items():
            response.headers[key] = value

        if route_match and route_match.route.transform_response:
            self.pipeline.load_route_rules(route_match.route.transform_response)

        response_body = b""
        if hasattr(response, "body"):
            response_body = response.body
        elif hasattr(response, "raw_headers"):
            try:
                response_body = b"".join([chunk async for chunk in response.body_iterator])
            except Exception:
                pass

        if response_body and response.headers.get("content-type", "").startswith("application/json"):
            response_body = await self.pipeline.transform_response_body(response_body, path, context)
            response = Response(
                content=response_body,
                status_code=response.status_code,
                headers=dict(response.headers),
                media_type=response.headers.get("content-type"),
            )

        new_status = await self.pipeline.transform_response_status(response.status_code, path, context)
        if new_status != response.status_code:
            response.status_code = new_status

        if response.status_code >= 400:
            try:
                body = {}
                if response_body:
                    import json
                    body = json.loads(response_body.decode("utf-8"))
                new_status, standardized = await self.pipeline.transform_error_response(
                    response.status_code, body, path
                )
                response = JSONResponse(
                    status_code=new_status,
                    content=standardized,
                    headers=dict(response.headers),
                )
            except Exception:
                pass

        return response

    async def _load_global_rules(self) -> None:
        try:
            async for session in get_db():
                repo = TransformRuleRepository(session)
                rules = await repo.get_all_active()
                rules_config = []
                for rule in rules:
                    rules_config.append({
                        "type": rule.rule_type,
                        "path_pattern": rule.path_pattern,
                        "priority": rule.priority,
                        "enabled": rule.is_active,
                        **rule.config,
                    })
                self.pipeline.load_rules(rules_config)
                break
        except Exception as e:
            logger.error("Failed to load transform rules", error=str(e))

    def _should_skip(self, path: str) -> bool:
        skip_paths = [
            "/health",
            "/metrics",
            "/docs",
            "/openapi.json",
            "/redoc",
            "/portal/",
            "/static/",
        ]
        return any(path.startswith(p) for p in skip_paths)
