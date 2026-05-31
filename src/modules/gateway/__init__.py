"""
API网关核心 - 整合tracing/middleware/一致性保障
只依赖 domain.contracts 中的抽象，通过DI注入具体实现

支持动态配置热更新特性：
- 配置源 + 观察者模式
- 中间件热插拔
- 一致性策略热切换
- 处理器动态注册/替换/卸载
- 配置版本号 + 配置变更回调
"""

from __future__ import annotations

import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Set

from src.domain.contracts.tracing import LoggerProtocol, Request, Response, TraceContext
from src.domain.contracts.gateway import GatewayMiddleware, HandlerFunc, ConsistencyPolicy
from src.domain.errors.gateway import GatewayError
from src.domain.models.gateway import ConsistencyCheckResult
from src.modules.gateway.tracing import TraceManager, SimpleTraceContext
from src.modules.gateway.middleware import (
    SimpleResponse,
    ConsistencyGuard,
)


@dataclass
class GatewayConfig:
    """网关配置对象 - 可序列化"""
    service_name: str = "api-gateway"
    consistency_policy: ConsistencyPolicy = ConsistencyPolicy.AT_LEAST_ONCE
    rate_limit_max_requests: int = 100
    rate_limit_window_seconds: int = 60
    auth_api_keys: Dict[str, str] = field(default_factory=dict)
    enabled_middlewares: Set[str] = field(default_factory=set)
    config_version: int = 1


class ConfigObserver(ABC):
    """配置观察者 - 配置变更回调接口"""
    @abstractmethod
    def on_config_changed(self, old_config: GatewayConfig, new_config: GatewayConfig) -> None: ...


class ConfigSource:
    """配置源 - 支持热更新

    实现观察者模式，配置变更时通知所有观察者
    """

    def __init__(self, initial_config: Optional[GatewayConfig] = None) -> None:
        self._config: GatewayConfig = initial_config or GatewayConfig()
        self._observers: List[ConfigObserver] = []

    @property
    def config(self) -> GatewayConfig:
        return self._config

    def add_observer(self, observer: ConfigObserver) -> None:
        self._observers.append(observer)

    def remove_observer(self, observer: ConfigObserver) -> None:
        self._observers.remove(observer)

    def update_config(self, new_config: GatewayConfig) -> None:
        old_config = self._config
        new_config.config_version = old_config.config_version + 1
        self._config = new_config
        for observer in self._observers:
            observer.on_config_changed(old_config, new_config)

    def update_partial(self, **kwargs: Any) -> None:
        new_config = GatewayConfig(
            service_name=kwargs.get("service_name", self._config.service_name),
            consistency_policy=kwargs.get("consistency_policy", self._config.consistency_policy),
            rate_limit_max_requests=kwargs.get("rate_limit_max_requests",
                                          self._config.rate_limit_max_requests),
            rate_limit_window_seconds=kwargs.get("rate_limit_window_seconds",
                                        self._config.rate_limit_window_seconds),
            auth_api_keys=kwargs.get("auth_api_keys", self._config.auth_api_keys),
            enabled_middlewares=kwargs.get("enabled_middlewares",
                                      self._config.enabled_middlewares),
            config_version=self._config.config_version,
        )
        self.update_config(new_config)


class RequestLogger:
    def __init__(self, logger: LoggerProtocol) -> None:
        self._logger = logger

    def log_request(self, request: Request, trace_ctx: TraceContext) -> None:
        logger = self._logger.with_trace(trace_ctx)
        logger.info(
            "Incoming request",
            method=request.method,
            path=request.path,
            request_id=request.request_id,
            query_params=request.query_params,
            content_length=len(request.body) if request.body else 0,
        )

    def log_response(
        self,
        request: Request,
        response: Response,
        trace_ctx: TraceContext,
        duration: float,
    ) -> None:
        logger = self._logger.with_trace(trace_ctx)
        log_method = logger.info if response.status_code < 400 else logger.error
        log_method(
            "Request completed",
            method=request.method,
            path=request.path,
            status_code=response.status_code,
            duration_ms=round(duration * 1000, 2),
            request_id=request.request_id,
            content_length=len(response.body) if response.body else 0,
        )


class ApiGateway(ConfigObserver):
    """
    API网关 - 依赖注入所有子组件
    整合请求日志、链路追踪、中间件、数据一致性

    动态配置热更新特性：
    - 配置源驱动
    - 中间件热插拔
    - 一致性策略热切换
    - 处理器动态注册/替换/卸载
    """

    def __init__(
        self,
        logger: LoggerProtocol,
        service_name: Optional[str] = None,
        consistency_policy: Optional[ConsistencyPolicy] = None,
        config_source: Optional[ConfigSource] = None,
    ) -> None:
        if config_source is None:
            initial_config = GatewayConfig(
                service_name=service_name or "api-gateway",
                consistency_policy=consistency_policy or ConsistencyPolicy.AT_LEAST_ONCE,
            )
            config_source = ConfigSource(initial_config=initial_config)
        self._logger = logger
        self._request_logger = RequestLogger(logger)
        self._config_source = config_source or ConfigSource()
        self._config_source.add_observer(self)

        cfg = self._config_source.config
        self._trace_manager = TraceManager(cfg.service_name)
        self._consistency_guard = ConsistencyGuard(policy=cfg.consistency_policy, logger=logger)

        self._middlewares: Dict[str, GatewayMiddleware] = {}
        self._middleware_order: List[str] = []
        self._handlers: Dict[str, HandlerFunc] = {}

        self._on_config_change_callbacks: List[Callable[[GatewayConfig, GatewayConfig], None]] = []

    def on_config_changed(self, old_config: GatewayConfig, new_config: GatewayConfig) -> None:
        """配置变更回调 - 观察者模式实现"""
        if old_config.consistency_policy != new_config.consistency_policy:
            self._consistency_guard = ConsistencyGuard(
                policy=new_config.consistency_policy,
                logger=self._logger,
            )

        if old_config.service_name != new_config.service_name:
            self._trace_manager = TraceManager(new_config.service_name)

        for callback in self._on_config_change_callbacks:
            callback(old_config, new_config)

        if self._logger:
            self._logger.info(
                "Gateway config updated",
                version=new_config.config_version,
                policy=new_config.consistency_policy.value,
                service_name=new_config.service_name,
            )

    def add_config_change_callback(self, callback: Callable[[GatewayConfig, GatewayConfig], None]) -> None:
        """注册配置变更回调"""
        self._on_config_change_callbacks.append(callback)

    def add_middleware(
        self,
        name_or_middleware,
        middleware: Optional[GatewayMiddleware] = None,
        position: Optional[int] = None,
    ) -> None:
        """热插拔：添加中间件

        支持两种调用方式：
            1. add_middleware(name, middleware, position=None) - 新API
            2. add_middleware(middleware) - 旧API兼容，name自动生成

        Args:
            name_or_middleware: 中间件名称或中间件实例
            middleware: 中间件实例（第一个参数是名称时使用）
            position: 插入位置（None表示末尾）
        """
        if isinstance(name_or_middleware, str):
            name = name_or_middleware
            mw = middleware
        else:
            mw = name_or_middleware
            name = f"middleware_{len(self._middlewares)}"

        if name in self._middlewares:
            self._middleware_order.remove(name)

        if position is None:
            self._middleware_order.append(name)
        else:
            self._middleware_order.insert(position, name)

        self._middlewares[name] = mw

        if self._logger:
            self._logger.info("Middleware added", name=name, position=position)

    def remove_middleware(self, name: str) -> Optional[GatewayMiddleware]:
        """热插拔：移除中间件"""
        if name in self._middlewares:
            self._middleware_order.remove(name)
            mw = self._middlewares.pop(name)
            if self._logger:
                self._logger.info("Middleware removed", name=name)
            return mw
        return None

    def clear_middlewares(self) -> None:
        """热插拔：清空所有中间件"""
        self._middlewares.clear()
        self._middleware_order.clear()

    def get_middleware_names(self) -> List[str]:
        """获取已注册中间件名称列表（按顺序）"""
        return list(self._middleware_order)

    def register_handler(self, path: str, handler: HandlerFunc) -> None:
        """注册处理器"""
        self._handlers[path] = handler

    def replace_handler(self, path: str, handler: HandlerFunc) -> Optional[HandlerFunc]:
        """运行时替换处理器"""
        old = self._handlers.get(path)
        self._handlers[path] = handler
        return old

    def unregister_handler(self, path: str) -> Optional[HandlerFunc]:
        """卸载处理器"""
        return self._handlers.pop(path, None)

    def update_config(self, new_config: GatewayConfig) -> None:
        """热更新整个配置"""
        self._config_source.update_config(new_config)

    def update_consistency_policy(self, policy: ConsistencyPolicy) -> None:
        """热切换一致性策略"""
        self._config_source.update_partial(consistency_policy=policy)

    def update_rate_limit(self, max_requests: int, window_seconds: int) -> None:
        """热更新限流配置"""
        self._config_source.update_partial(
            rate_limit_max_requests=max_requests,
            rate_limit_window_seconds=window_seconds,
        )

    def add_api_key(self, key: str, user: str) -> None:
        """热添加API密钥"""
        new_keys = dict(self._config_source.config.auth_api_keys)
        new_keys[key] = user
        self._config_source.update_partial(auth_api_keys=new_keys)

    def remove_api_key(self, key: str) -> None:
        """热移除API密钥"""
        new_keys = dict(self._config_source.config.auth_api_keys)
        new_keys.pop(key, None)
        self._config_source.update_partial(auth_api_keys=new_keys)

    def get_config(self) -> GatewayConfig:
        """获取当前配置"""
        return self._config_source.config

    def _extract_trace_headers(self, response: Response, trace_ctx: TraceContext) -> Response:
        response.headers["X-Trace-Id"] = trace_ctx.trace_id
        response.headers["X-Span-Id"] = trace_ctx.span_id
        return response

    async def _dispatch(self, request: Request) -> Response:
        handler = self._handlers.get(request.path)
        if handler:
            return await handler(request)
        return SimpleResponse(status_code=404, body=b"Not Found")

    def _get_ordered_middlewares(self) -> List[GatewayMiddleware]:
        return [self._middlewares[name] for name in self._middleware_order]

    async def process_request(self, request: Request) -> Response:
        start_time = time.time()
        trace_ctx = self._trace_manager.create_context(request)
        gateway_span = self._trace_manager.start_span(trace_ctx, "gateway.request")

        consistency_result = self._consistency_guard.check_consistency(request)

        try:
            self._request_logger.log_request(request, trace_ctx)

            middlewares = self._get_ordered_middlewares()

            for middleware in middlewares:
                early_response = await middleware.process_request(request, trace_ctx)
                if early_response:
                    gateway_span.finish("early_return")
                    duration = time.time() - start_time
                    self._request_logger.log_response(request, early_response, trace_ctx, duration)
                    return self._extract_trace_headers(early_response, trace_ctx)

            handler_span = self._trace_manager.start_span(trace_ctx, f"handler.{request.path}")
            try:
                response = await self._dispatch(request)
                handler_span.finish("success" if response.status_code < 400 else "error")
            except Exception as e:
                handler_span.finish("error")
                self._logger.with_trace(trace_ctx).error(
                    "Handler exception", error=str(e), path=request.path
                )
                response = SimpleResponse(
                    status_code=500, body=f"Internal Server Error: {e}".encode()
                )

            for middleware in reversed(middlewares):
                response = await middleware.process_response(request, response, trace_ctx)

            response = await self._consistency_guard.process_response(request, response, trace_ctx)

            gateway_span.finish("success" if response.status_code < 400 else "error")
            duration = time.time() - start_time
            self._request_logger.log_response(request, response, trace_ctx, duration)
            return self._extract_trace_headers(response, trace_ctx)

        except GatewayError:
            gateway_span.finish("error")
            raise
        except Exception as e:
            gateway_span.finish("error")
            self._logger.with_trace(trace_ctx).error(
                "Gateway processing error", error=str(e), path=request.path
            )
            response = SimpleResponse(status_code=500, body=b"Internal Server Error")
            duration = time.time() - start_time
            self._request_logger.log_response(request, response, trace_ctx, duration)
            return self._extract_trace_headers(response, trace_ctx)

    def get_trace_info(self, trace_id: str) -> Dict[str, Any]:
        return self._trace_manager.get_trace_tree(trace_id)

    def check_consistency(self, request: Request) -> ConsistencyCheckResult:
        return self._consistency_guard.check_consistency(request)
