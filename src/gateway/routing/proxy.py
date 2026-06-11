from typing import Any, Dict, Optional, Tuple
import time
import httpx
from httpx import AsyncClient, Response, TimeoutException, HTTPStatusError, RequestError

from starlette.requests import Request
from starlette.responses import StreamingResponse, Response as StarletteResponse

from gateway.config import get_settings
from gateway.observability import inject_trace_context_headers, record_upstream_request, record_upstream_error
from gateway.logger import get_logger
from gateway.routing.models import RouteMatch

logger = get_logger("proxy")

_client: Optional[AsyncClient] = None


class ProxyClient:
    def __init__(self):
        self.settings = get_settings()
        self._client = self._create_client()

    def _create_client(self) -> AsyncClient:
        return httpx.AsyncClient(
            timeout=httpx.Timeout(
                connect=5.0,
                read=self.settings.gateway.request_timeout,
                write=30.0,
                pool=10.0,
            ),
            limits=httpx.Limits(
                max_connections=1000,
                max_keepalive_connections=100,
                keepalive_expiry=30.0,
            ),
            follow_redirects=False,
            http1=True,
            http2=True,
            verify=False,
        )

    async def forward(self, request: Request, route_match: RouteMatch,
                      modified_headers: Optional[Dict[str, str]] = None,
                      modified_body: Optional[bytes] = None) -> Tuple[Response, int]:
        route = route_match.route
        target = route_match.target

        target_path = route.rewrite_path(request.url.path)
        target_url = target.url.rstrip("/") + target_path

        if request.url.query:
            target_url += f"?{request.url.query}"

        timeout = target.timeout or route.timeout or self.settings.gateway.request_timeout

        headers = dict(request.headers)
        headers.pop("host", None)
        headers.pop("connection", None)
        headers["X-Forwarded-For"] = request.client.host if request.client else "unknown"
        headers["X-Forwarded-Proto"] = request.url.scheme
        headers["X-Forwarded-Host"] = request.headers.get("host", "")
        headers["X-Gateway-Request-ID"] = request.state.request_id

        if modified_headers:
            headers.update(modified_headers)

        headers = inject_trace_context_headers(headers)

        if modified_body is not None:
            body = modified_body
        else:
            body = await request.body()

        method = request.method

        logger.debug("Forwarding request",
                     method=method,
                     target_url=target_url,
                     route_name=route.name,
                     request_id=request.state.request_id)

        upstream_start = time.time()
        upstream_latency = 0

        try:
            response = await self._client.request(
                method=method,
                url=target_url,
                headers=headers,
                content=body,
                timeout=timeout,
            )
            upstream_latency = int((time.time() - upstream_start) * 1000)
            record_upstream_request(
                route=route.name,
                target=target.url,
                status_code=response.status_code,
                duration_seconds=upstream_latency / 1000.0,
            )
            return response, upstream_latency

        except TimeoutException as e:
            logger.warning("Request timeout", target_url=target_url, error=str(e), request_id=request.state.request_id)
            record_upstream_error(route=route.name, target=target.url, error_type="timeout")
            return self._create_error_response(504, "Gateway Timeout", str(e)), 0

        except HTTPStatusError as e:
            logger.warning("HTTP status error", target_url=target_url, status_code=e.response.status_code,
                           error=str(e), request_id=request.state.request_id)
            upstream_latency = int((time.time() - upstream_start) * 1000)
            record_upstream_request(
                route=route.name,
                target=target.url,
                status_code=e.response.status_code,
                duration_seconds=upstream_latency / 1000.0,
            )
            return e.response, upstream_latency

        except RequestError as e:
            logger.error("Request error", target_url=target_url, error=str(e), request_id=request.state.request_id)
            record_upstream_error(route=route.name, target=target.url, error_type="request_error")
            return self._create_error_response(502, "Bad Gateway", str(e)), 0

        except Exception as e:
            logger.error("Unexpected error during proxy", target_url=target_url, error=str(e),
                         request_id=request.state.request_id, exc_info=True)
            record_upstream_error(route=route.name, target=target.url, error_type="exception")
            return self._create_error_response(500, "Internal Server Error", str(e)), 0

    def _create_error_response(self, status_code: int, message: str, detail: str) -> Response:
        return Response(
            status_code=status_code,
            json={
                "error": {
                    "code": status_code,
                    "message": message,
                    "detail": detail,
                }
            },
            headers={"Content-Type": "application/json"},
        )

    async def close(self) -> None:
        if self._client:
            await self._client.aclose()
            logger.info("Proxy client closed")


def convert_to_starlette_response(response: Response) -> StarletteResponse:
    if "content-encoding" in [h.lower() for h in response.headers.keys()]:
        return StreamingResponse(
            content=response.iter_bytes(),
            status_code=response.status_code,
            headers=dict(response.headers),
        )
    else:
        return StarletteResponse(
            content=response.content,
            status_code=response.status_code,
            headers=dict(response.headers),
            media_type=response.headers.get("content-type"),
        )


_proxy_instance: Optional[ProxyClient] = None


def get_proxy_client() -> ProxyClient:
    global _proxy_instance
    if _proxy_instance is None:
        _proxy_instance = ProxyClient()
    return _proxy_instance
