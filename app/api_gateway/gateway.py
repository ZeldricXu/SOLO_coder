import asyncio
import time
import uuid
import re
from typing import Dict, List, Optional, Any, Callable, Tuple
from dataclasses import dataclass
from collections import defaultdict
from threading import Lock
from datetime import datetime
import httpx
from app.logging_module import get_logger
from app.config import settings
from .models import (
    RouteConfig, GatewayRequest, GatewayResponse,
    BatchRequest, BatchResponse, BatchResult
)
from .cache import ResponseCache, CacheWarmer, FastLRUCache


logger = get_logger(__name__)


class GatewayError(Exception):
    def __init__(self, code: str, status_code: int, message: str, details: Any = None):
        self.code = code
        self.status_code = status_code
        self.message = message
        self.details = details
        super().__init__(message)


@dataclass
class Route:
    config: RouteConfig
    pattern: re.Pattern
    method_upper: str = ""
    path_prefix: str = ""


class TrieNode:
    __slots__ = ('children', 'routes', 'is_end')
    
    def __init__(self):
        self.children: Dict[str, 'TrieNode'] = {}
        self.routes: List[Route] = []
        self.is_end: bool = False


class RouteTrie:
    __slots__ = ('_root', '_lock')
    
    def __init__(self):
        self._root = TrieNode()
        self._lock = Lock()
    
    def insert(self, route: Route):
        path = route.config.path
        with self._lock:
            node = self._root
            segments = path.strip('/').split('/')
            
            for segment in segments:
                if segment not in node.children:
                    node.children[segment] = TrieNode()
                node = node.children[segment]
            
            node.routes.append(route)
            node.is_end = True
            route.path_prefix = '/'.join(segments[:3]) if len(segments) > 3 else path
    
    def find(self, method: str, path: str) -> Optional[Route]:
        method_upper = method.upper()
        path_stripped = path.strip('/')
        
        with self._lock:
            node = self._root
            segments = path_stripped.split('/')
            
            exact_match = self._find_exact(node, segments, method_upper)
            if exact_match:
                return exact_match
            
            wildcard_match = self._find_wildcard(node, segments, method_upper, 0)
            return wildcard_match
    
    def _find_exact(self, node: TrieNode, segments: List[str], method: str) -> Optional[Route]:
        for segment in segments:
            if segment in node.children:
                node = node.children[segment]
            else:
                return None
        
        if node.is_end:
            for route in node.routes:
                if route.method_upper == method:
                    return route
        
        return None
    
    def _find_wildcard(
        self,
        node: TrieNode,
        segments: List[str],
        method: str,
        index: int
    ) -> Optional[Route]:
        if index == len(segments):
            if node.is_end:
                for route in node.routes:
                    if route.method_upper == method:
                        return route
            return None
        
        segment = segments[index]
        
        if segment in node.children:
            result = self._find_wildcard(node.children[segment], segments, method, index + 1)
            if result:
                return result
        
        if '*' in node.children:
            result = self._find_wildcard(node.children['*'], segments, method, index + 1)
            if result:
                return result
        
        if index == len(segments) - 1:
            for child_key in node.children:
                if child_key == '*':
                    for route in node.children[child_key].routes:
                        if route.method_upper == method:
                            return route
        
        return None


class RateLimiter:
    __slots__ = ('max_requests', 'window_seconds', '_requests', '_lock')
    
    def __init__(self, max_requests: int, window_seconds: int = 60):
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._requests: Dict[str, List[float]] = defaultdict(list)
        self._lock = Lock()
    
    def check(self, key: str) -> bool:
        now = time.time()
        window_start = now - self.window_seconds
        
        with self._lock:
            timestamps = self._requests[key]
            
            while timestamps and timestamps[0] < window_start:
                timestamps.pop(0)
            
            if len(timestamps) >= self.max_requests:
                return False
            
            timestamps.append(now)
            return True


class ProtocolConverter:
    __slots__ = ()
    
    @staticmethod
    def convert_from_http(response: httpx.Response, source_protocol: str) -> GatewayResponse:
        body = None
        if response.content:
            content_type = response.headers.get('content-type', '')
            if 'application/json' in content_type:
                try:
                    body = response.json()
                except Exception:
                    body = response.text
            else:
                body = response.text
        
        return GatewayResponse(
            status_code=response.status_code,
            headers=dict(response.headers),
            body=body,
            latency_ms=0.0
        )


class CircuitBreaker:
    __slots__ = (
        'failure_threshold', 'recovery_timeout',
        '_failures', '_state', '_last_failure_time',
        '_half_open_attempts', '_lock'
    )
    
    def __init__(self, failure_threshold: int = 5, recovery_timeout: int = 30):
        self.failure_threshold = failure_threshold
        self.recovery_timeout = recovery_timeout
        self._failures = 0
        self._state = "closed"
        self._last_failure_time: Optional[float] = None
        self._half_open_attempts = 0
        self._lock = Lock()
    
    def allow_request(self) -> bool:
        with self._lock:
            if self._state == "open":
                if (self._last_failure_time is not None and 
                    (time.time() - self._last_failure_time >= self.recovery_timeout)):
                    self._state = "half-open"
                    self._half_open_attempts = 0
                    return True
                return False
            return True
    
    def record_success(self):
        with self._lock:
            self._failures = 0
            self._state = "closed"
            self._half_open_attempts = 0
    
    def record_failure(self):
        with self._lock:
            self._failures += 1
            if self._state == "half-open":
                self._state = "open"
                self._last_failure_time = time.time()
            elif self._failures >= self.failure_threshold:
                self._state = "open"
                self._last_failure_time = time.time()
    
    @property
    def state(self) -> str:
        with self._lock:
            return self._state


class FastErrorResponse:
    __slots__ = ()
    
    @staticmethod
    def create(status_code: int, error_code: str, message: str, start_time: float) -> GatewayResponse:
        return GatewayResponse(
            status_code=status_code,
            body={"error": error_code, "message": message},
            latency_ms=(time.time() - start_time) * 1000
        )
    
    @staticmethod
    def not_found(start_time: float) -> GatewayResponse:
        return FastErrorResponse.create(404, "Not Found", "No route matched", start_time)
    
    @staticmethod
    def too_many_requests(start_time: float) -> GatewayResponse:
        return FastErrorResponse.create(429, "Too Many Requests", "Rate limit exceeded", start_time)
    
    @staticmethod
    def service_unavailable(start_time: float, message: str) -> GatewayResponse:
        return FastErrorResponse.create(503, "Service Unavailable", message, start_time)
    
    @staticmethod
    def gateway_timeout(start_time: float, message: str) -> GatewayResponse:
        return FastErrorResponse.create(504, "Gateway Timeout", message, start_time)
    
    @staticmethod
    def internal_error(start_time: float, message: str) -> GatewayResponse:
        return FastErrorResponse.create(500, "Internal Server Error", message, start_time)


class APIGateway:
    __slots__ = (
        '_routes', '_route_trie', '_rate_limiters', '_circuit_breakers',
        '_client', '_middlewares', '_converter',
        '_enable_cache', '_cache', '_cache_warmer', '_route_cache_configs',
        '_cache_stats', '_lock', '_running'
    )
    
    def __init__(
        self,
        enable_cache: bool = True,
        cache_max_size: int = 10000,
        cache_default_ttl: int = 300,
        enable_cache_persistence: bool = False,
        session_factory=None
    ):
        self._routes: Dict[str, Route] = {}
        self._route_trie = RouteTrie()
        self._rate_limiters: Dict[str, RateLimiter] = {}
        self._circuit_breakers: Dict[str, CircuitBreaker] = {}
        self._client: Optional[httpx.AsyncClient] = None
        self._middlewares: List[Callable] = []
        self._converter = ProtocolConverter()
        
        self._enable_cache = enable_cache
        self._cache: Optional[ResponseCache] = None
        self._cache_warmer: Optional[CacheWarmer] = None
        self._route_cache_configs: Dict[str, Dict[str, Any]] = {}
        
        self._cache_stats = {"hits": 0, "misses": 0, "bypassed": 0}
        self._lock = Lock()
        self._running = False
        
        if enable_cache:
            memory_cache = FastLRUCache(max_size=cache_max_size, default_ttl_seconds=cache_default_ttl)
            self._cache = ResponseCache(
                memory_cache=memory_cache,
                enable_persistence=enable_cache_persistence,
                session_factory=session_factory
            )
            self._cache_warmer = CacheWarmer(self._cache, self)
    
    async def start(self):
        if self._running:
            return
        
        self._client = httpx.AsyncClient(timeout=30.0)
        
        if self._cache:
            await self._cache.start()
        
        if self._cache_warmer and self._enable_cache:
            await self._cache_warmer.start()
        
        self._running = True
        logger.info("API Gateway started", cache_enabled=self._enable_cache)
    
    async def stop(self):
        if self._client:
            await self._client.aclose()
        
        if self._cache_warmer:
            await self._cache_warmer.stop()
        
        if self._cache:
            await self._cache.stop()
        
        self._running = False
        logger.info("API Gateway stopped")
    
    def enable_route_caching(
        self,
        path: str,
        ttl_seconds: int = 300,
        cache_methods: List[str] = None,
        include_query_params: bool = True,
        include_body: bool = False,
        include_headers: List[str] = None
    ):
        cache_methods = cache_methods or ["GET"]
        self._route_cache_configs[path] = {
            "ttl_seconds": ttl_seconds,
            "methods": {m.upper() for m in cache_methods},
            "include_query_params": include_query_params,
            "include_body": include_body,
            "include_headers": set(include_headers) if include_headers else set()
        }
        logger.info(f"Enabled caching for route", path=path, ttl=ttl_seconds)
    
    def disable_route_caching(self, path: str):
        if path in self._route_cache_configs:
            del self._route_cache_configs[path]
            logger.info(f"Disabled caching for route", path=path)
    
    def add_cache_warmup_config(
        self,
        method: str,
        path: str,
        query_params: Dict[str, Any] = None,
        body: Any = None,
        interval_seconds: int = 300,
        ttl_seconds: int = 600
    ):
        if self._cache_warmer:
            self._cache_warmer.add_warmup_config(
                method=method,
                path=path,
                query_params=query_params,
                body=body,
                interval_seconds=interval_seconds,
                ttl_seconds=ttl_seconds
            )
    
    async def invalidate_cache(self, path: str = None, cache_key: str = None) -> int:
        if not self._cache:
            return 0
        return await self._cache.invalidate(path=path, cache_key=cache_key)
    
    async def invalidate_all_cache(self):
        if self._cache:
            await self._cache.invalidate_all()
    
    def _get_cache_config(self, method: str, path: str) -> Optional[Dict[str, Any]]:
        method_upper = method.upper()
        
        for route_path, config in self._route_cache_configs.items():
            if path.startswith(route_path) or path == route_path:
                if method_upper in config.get("methods", set()):
                    return config
        
        return None
    
    def register_route(self, config: RouteConfig):
        pattern = re.compile(f"^{config.path.replace('*', '.*')}$")
        route = Route(
            config=config,
            pattern=pattern,
            method_upper=config.method.upper(),
            path_prefix=config.path
        )
        
        with self._lock:
            self._routes[config.path] = route
            self._route_trie.insert(route)
        
        if config.rate_limit:
            self._rate_limiters[config.path] = RateLimiter(
                max_requests=config.rate_limit
            )
        
        self._circuit_breakers[config.path] = CircuitBreaker()
        
        logger.info(f"Registered route: {config.method} {config.path} -> {config.target_url}")
    
    def unregister_route(self, path: str):
        with self._lock:
            if path in self._routes:
                del self._routes[path]
        
        if path in self._rate_limiters:
            del self._rate_limiters[path]
        
        if path in self._circuit_breakers:
            del self._circuit_breakers[path]
        
        logger.info(f"Unregistered route: {path}")
    
    def add_middleware(self, middleware: Callable):
        self._middlewares.append(middleware)
        logger.info(f"Added middleware")
    
    def _match_route(self, method: str, path: str) -> Optional[Route]:
        return self._route_trie.find(method, path)
    
    def _build_target_url(self, base_url: str, query_params: Dict[str, Any]) -> str:
        if not query_params:
            return base_url
        
        buffer = []
        for k, v in sorted(query_params.items()):
            buffer.append(f"{k}={v}")
        
        query_string = "&".join(buffer)
        separator = "?" if "?" not in base_url else "&"
        
        return f"{base_url}{separator}{query_string}"
    
    async def _execute_middlewares(
        self,
        request: GatewayRequest,
        start_time: float
    ) -> Optional[GatewayResponse]:
        for middleware in self._middlewares:
            try:
                result = middleware(request)
                if isinstance(result, GatewayResponse):
                    result.latency_ms = (time.time() - start_time) * 1000
                    return result
            except Exception as e:
                logger.error(f"Middleware error", error=str(e))
        
        return None
    
    async def _check_rate_limit(
        self,
        route: Route,
        request: GatewayRequest,
        start_time: float
    ) -> Optional[GatewayResponse]:
        limiter = self._rate_limiters.get(route.config.path)
        if limiter:
            rate_key = f"{request.client_ip or 'anonymous'}:{route.config.path}"
            if not limiter.check(rate_key):
                return FastErrorResponse.too_many_requests(start_time)
        return None
    
    async def _check_circuit_breaker(
        self,
        route: Route,
        start_time: float
    ) -> Optional[GatewayResponse]:
        breaker = self._circuit_breakers.get(route.config.path)
        if breaker and not breaker.allow_request():
            return FastErrorResponse.service_unavailable(start_time, "Circuit breaker open")
        return None
    
    async def _try_cache_get(
        self,
        cache_config: Dict[str, Any],
        request: GatewayRequest,
        start_time: float
    ) -> Optional[GatewayResponse]:
        if not self._cache or not cache_config:
            return None
        
        query_params = request.query_params if cache_config.get("include_query_params", True) else {}
        body = request.body if cache_config.get("include_body", False) else None
        
        cached_response, cache_hit = await self._cache.get(
            method=request.method,
            path=request.path,
            query_params=query_params,
            body=body
        )
        
        if cache_hit and cached_response:
            self._cache_stats["hits"] += 1
            cached_response.from_cache = True
            cached_response.latency_ms = (time.time() - start_time) * 1000
            logger.debug(f"Cache hit", path=request.path)
            return cached_response
        else:
            self._cache_stats["misses"] += 1
        
        return None
    
    async def _try_cache_set(
        self,
        cache_config: Dict[str, Any],
        response: GatewayResponse,
        request: GatewayRequest
    ):
        if (self._cache and cache_config and 
            200 <= response.status_code < 300):
            
            query_params = request.query_params if cache_config.get("include_query_params", True) else {}
            body = request.body if cache_config.get("include_body", False) else None
            
            await self._cache.set(
                method=request.method,
                path=request.path,
                value=response,
                ttl_seconds=cache_config.get("ttl_seconds"),
                query_params=query_params,
                body=body
            )
    
    async def route(self, request: GatewayRequest) -> GatewayResponse:
        start_time = time.time()
        
        try:
            route = self._match_route(request.method, request.path)
            
            if not route:
                return FastErrorResponse.not_found(start_time)
            
            middleware_response = await self._execute_middlewares(request, start_time)
            if middleware_response:
                return middleware_response
            
            rate_limit_response = await self._check_rate_limit(route, request, start_time)
            if rate_limit_response:
                return rate_limit_response
            
            cb_response = await self._check_circuit_breaker(route, start_time)
            if cb_response:
                return cb_response
            
            cache_config = self._get_cache_config(request.method, request.path)
            
            if cache_config:
                cached = await self._try_cache_get(cache_config, request, start_time)
                if cached:
                    return cached
            else:
                self._cache_stats["bypassed"] += 1
            
            response = await self._forward_request(request, route)
            
            breaker = self._circuit_breakers.get(route.config.path)
            if breaker:
                if 200 <= response.status_code < 500:
                    breaker.record_success()
                else:
                    breaker.record_failure()
            
            if cache_config:
                await self._try_cache_set(cache_config, response, request)
            
            response.latency_ms = (time.time() - start_time) * 1000
            return response
            
        except GatewayError as e:
            logger.error(f"Gateway error", code=e.code, message=e.message)
            return FastErrorResponse.create(
                e.status_code, e.code, e.message, start_time
            )
        except Exception as e:
            logger.error(f"Gateway routing error", error=str(e))
            return FastErrorResponse.internal_error(start_time, str(e))
    
    async def _forward_request(self, request: GatewayRequest, route: Route) -> GatewayResponse:
        config = route.config
        
        target_url = self._build_target_url(config.target_url, request.query_params)
        
        last_error: Optional[Exception] = None
        
        for attempt in range(config.retry_count + 1):
            try:
                response = await self._client.request(
                    method=config.method,
                    url=target_url,
                    headers=request.headers,
                    json=request.body,
                    timeout=config.timeout_seconds
                )
                
                if response.status_code < 500 or attempt == config.retry_count:
                    return self._converter.convert_from_http(response, config.protocol)
                
            except httpx.TimeoutException as e:
                last_error = e
                logger.warning(f"Request timeout", attempt=attempt + 1, error=str(e))
            except Exception as e:
                last_error = e
                logger.warning(f"Request failed", attempt=attempt + 1, error=str(e))
            
            if attempt < config.retry_count:
                await asyncio.sleep(0.1 * (2 ** attempt))
        
        error_msg = str(last_error) if last_error else "Request failed after retries"
        return FastErrorResponse.gateway_timeout(time.time(), error_msg)
    
    async def handle_batch(self, batch_request: BatchRequest) -> BatchResponse:
        batch_id = f"batch_{uuid.uuid4().hex[:12]}"
        
        async def execute_operation(op) -> BatchResult:
            try:
                request = GatewayRequest(
                    method="POST",
                    path=f"/api/v1/resources/{op.id}",
                    body=op.parameters,
                    trace_id=batch_id
                )
                
                route = self._match_route(request.method, request.path)
                if not route:
                    return BatchResult(
                        id=op.id,
                        success=False,
                        status_code=404,
                        error="Route not found"
                    )
                
                response = await self.route(request)
                return BatchResult(
                    id=op.id,
                    success=200 <= response.status_code < 300,
                    status_code=response.status_code,
                    data=response.body,
                    error=response.error
                )
            except Exception as e:
                return BatchResult(
                    id=op.id,
                    success=False,
                    status_code=500,
                    error=str(e)
                )
        
        tasks = [execute_operation(op) for op in batch_request.operations]
        results = await asyncio.gather(*tasks)
        
        success_count = sum(1 for r in results if r.success)
        
        return BatchResponse(
            batch_id=batch_id,
            results=results,
            total_count=len(results),
            success_count=success_count,
            failed_count=len(results) - success_count
        )
    
    def get_health(self) -> Dict[str, Any]:
        health = {
            "routes_count": len(self._routes),
            "routes": [
                {
                    "path": r.config.path,
                    "method": r.config.method,
                    "target": r.config.target_url,
                    "enabled": r.config.enabled,
                    "caching_enabled": r.config.path in self._route_cache_configs
                }
                for r in self._routes.values()
            ],
            "circuit_breakers": {
                path: breaker.state
                for path, breaker in self._circuit_breakers.items()
            },
            "cache": {
                "enabled": self._enable_cache,
                "stats": self._cache_stats.copy(),
                "cached_routes": list(self._route_cache_configs.keys())
            }
        }
        
        if self._cache:
            health["cache"]["detailed_stats"] = self._cache.get_stats()
        
        return health
    
    def get_cache_stats(self) -> Dict[str, Any]:
        if not self._cache:
            return {"enabled": False}
        
        return {
            "enabled": True,
            "gateway_stats": self._cache_stats.copy(),
            "detailed": self._cache.get_stats()
        }
