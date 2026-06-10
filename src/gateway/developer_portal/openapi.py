from typing import Any, Dict, List, Optional
import httpx
import asyncio

from gateway.config import get_settings
from gateway.routing.router import get_router
from gateway.logger import get_logger

logger = get_logger("openapi")


class OpenAPIAggregator:
    def __init__(self):
        self.settings = get_settings()
        self.router = get_router()
        self._cache: Dict[str, Dict[str, Any]] = {}
        self._http_client = httpx.AsyncClient(timeout=10.0)

    async def aggregate(self) -> Dict[str, Any]:
        routes = self.router.get_all_routes()

        spec = {
            "openapi": "3.0.3",
            "info": {
                "title": "API Gateway - Unified API Documentation",
                "description": "Unified API documentation for all services behind the API Gateway",
                "version": "1.0.0",
                "contact": {
                    "name": "API Platform Team",
                    "email": "api-platform@example.com",
                },
            },
            "servers": [
                {
                    "url": f"http://{self.settings.gateway.host}:{self.settings.gateway.port}",
                    "description": "API Gateway",
                }
            ],
            "paths": {},
            "components": {
                "securitySchemes": {
                    "bearerAuth": {
                        "type": "http",
                        "scheme": "bearer",
                        "bearerFormat": "JWT",
                    },
                    "apiKeyAuth": {
                        "type": "apiKey",
                        "in": "header",
                        "name": "X-API-Key",
                    },
                    "mutualTLS": {
                        "type": "mutualTLS",
                    },
                },
                "schemas": {
                    "Error": {
                        "type": "object",
                        "properties": {
                            "error": {
                                "type": "object",
                                "properties": {
                                    "code": {"type": "string"},
                                    "status": {"type": "integer"},
                                    "message": {"type": "string"},
                                    "detail": {"type": "string"},
                                    "request_id": {"type": "string"},
                                },
                            },
                        },
                    },
                },
            },
            "tags": [],
        }

        unique_tags = set()

        for route in routes:
            if not route.is_active:
                continue

            tag = route.name
            unique_tags.add(tag)

            path_item = {}
            methods = route.methods or ["GET", "POST", "PUT", "DELETE", "PATCH"]

            for method in methods:
                operation = {
                    "tags": [tag],
                    "summary": f"{method.upper()} {route.path}",
                    "description": route.description or f"Route: {route.name}",
                    "operationId": f"{tag}_{method.lower()}",
                    "parameters": [],
                    "responses": {
                        "200": {"description": "Successful response"},
                        "400": {"description": "Bad Request", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Error"}}}},
                        "401": {"description": "Unauthorized", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Error"}}}},
                        "403": {"description": "Forbidden", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Error"}}}},
                        "404": {"description": "Not Found", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Error"}}}},
                        "429": {"description": "Too Many Requests", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Error"}}}},
                        "500": {"description": "Internal Server Error", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Error"}}}},
                        "503": {"description": "Service Unavailable", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Error"}}}},
                    },
                }

                if route.auth_required:
                    security = []
                    if route.auth_strategy == "jwt" or route.auth_strategy == "oauth2":
                        security.append({"bearerAuth": []})
                    elif route.auth_strategy == "api_key":
                        security.append({"apiKeyAuth": []})
                    elif route.auth_strategy == "mtls":
                        security.append({"mutualTLS": []})
                    else:
                        security.append({"bearerAuth": []})
                        security.append({"apiKeyAuth": []})
                    operation["security"] = security

                if route.rate_limit_enabled:
                    operation["x-rate-limit"] = {
                        "per_user": route.rate_limit_per_user or self.settings.rate_limit.default_user_limit,
                        "per_api": route.rate_limit_per_api or self.settings.rate_limit.default_api_limit,
                        "window": f"{self.settings.rate_limit.window_seconds}s",
                        "burst_multiplier": self.settings.rate_limit.burst_multiplier,
                    }

                if route.circuit_breaker_enabled:
                    operation["x-circuit-breaker"] = {
                        "enabled": True,
                        "timeout": f"{route.timeout}s",
                        "retries": route.retry_count,
                    }

                path_item[method.lower()] = operation

            spec["paths"][route.path] = path_item

        spec["tags"] = [{"name": tag, "description": f"API group: {tag}"} for tag in sorted(unique_tags)]

        return spec

    async def fetch_service_openapi(self, service_url: str) -> Optional[Dict[str, Any]]:
        try:
            response = await self._http_client.get(f"{service_url.rstrip('/')}/openapi.json")
            response.raise_for_status()
            return response.json()
        except Exception as e:
            logger.warning("Failed to fetch OpenAPI spec", url=service_url, error=str(e))
            return None

    async def close(self) -> None:
        await self._http_client.aclose()


_aggregator_instance: Optional[OpenAPIAggregator] = None


def get_openapi_aggregator() -> OpenAPIAggregator:
    global _aggregator_instance
    if _aggregator_instance is None:
        _aggregator_instance = OpenAPIAggregator()
    return _aggregator_instance
