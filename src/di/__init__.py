"""
依赖注入容器
提供 ServiceProvider + Injectable 装饰器 + 自动装配
解决高层模块对低层实现的硬依赖问题
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Type, TypeVar

T = TypeVar("T")


class ServiceNotFoundError(Exception):
    pass


@dataclass
class ServiceDescriptor:
    service_type: Type
    factory: Callable[..., Any]
    singleton: bool = True
    instance: Optional[Any] = None


class ServiceProvider:
    """
    轻量级DI容器
    - register: 注册服务工厂
    - get: 获取服务实例（自动解析依赖）
    - build: 自动装配（从已注册服务解析构造函数参数）
    """

    def __init__(self) -> None:
        self._services: Dict[Type, ServiceDescriptor] = {}
        self._named: Dict[str, ServiceDescriptor] = {}

    def register(
        self,
        service_type: Type[T],
        factory: Optional[Callable[..., T]] = None,
        singleton: bool = True,
        name: Optional[str] = None,
    ) -> "ServiceProvider":
        actual_factory = factory or service_type
        descriptor = ServiceDescriptor(
            service_type=service_type,
            factory=actual_factory,
            singleton=singleton,
        )
        self._services[service_type] = descriptor
        if name:
            self._named[name] = descriptor
        return self

    def register_instance(
        self,
        service_type: Type[T],
        instance: T,
        name: Optional[str] = None,
    ) -> "ServiceProvider":
        descriptor = ServiceDescriptor(
            service_type=service_type,
            factory=lambda: instance,
            singleton=True,
            instance=instance,
        )
        self._services[service_type] = descriptor
        if name:
            self._named[name] = descriptor
        return self

    def get(self, service_type: Type[T], name: Optional[str] = None) -> T:
        if name and name in self._named:
            return self._resolve(self._named[name])

        if service_type in self._services:
            return self._resolve(self._services[service_type])

        raise ServiceNotFoundError(
            f"Service not registered: {service_type.__name__}"
            + (f" (name={name})" if name else "")
        )

    def _resolve(self, descriptor: ServiceDescriptor) -> Any:
        if descriptor.singleton and descriptor.instance is not None:
            return descriptor.instance

        instance = self._build(descriptor.factory)

        if descriptor.singleton:
            descriptor.instance = instance

        return instance

    def _build(self, factory: Callable[..., Any]) -> Any:
        import inspect

        sig = inspect.signature(factory)
        kwargs: Dict[str, Any] = {}

        for param_name, param in sig.parameters.items():
            if param_name == "self":
                continue

            annotation = param.annotation
            if annotation is inspect.Parameter.empty:
                if param.default is not inspect.Parameter.empty:
                    continue
                continue

            if isinstance(annotation, type) and annotation in self._services:
                kwargs[param_name] = self.get(annotation)
            elif param.default is not inspect.Parameter.empty:
                continue

        return factory(**kwargs)

    def has(self, service_type: Type) -> bool:
        return service_type in self._services

    def list_services(self) -> List[Dict[str, Any]]:
        return [
            {
                "type": desc.service_type.__name__,
                "singleton": desc.singleton,
                "instantiated": desc.instance is not None,
            }
            for desc in self._services.values()
        ]


def injectable(
    singleton: bool = True,
    name: Optional[str] = None,
) -> Callable[[Type[T]], Type[T]]:
    """
    类装饰器 - 标记可注入的服务
    用法:
        @injectable()
        class MyService:
            def __init__(self, logger: LoggerProtocol): ...
    """
    def decorator(cls: Type[T]) -> Type[T]:
        cls._di_singleton = singleton
        cls._di_name = name
        return cls
    return decorator
