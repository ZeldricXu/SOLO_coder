from abc import ABC, abstractmethod
from typing import TypeVar, Type, Optional, Dict, Any, List
from contextlib import asynccontextmanager
import threading


T = TypeVar('T')


class InstanceLifetime:
    SINGLETON = "singleton"
    TRANSIENT = "transient"
    SCOPED = "scoped"


class ServiceDescriptor:
    def __init__(
        self,
        service_type: Type[T],
        implementation_type: Optional[Type[T]] = None,
        instance: Optional[T] = None,
        factory: Optional = None,
        lifetime: str = InstanceLifetime.SINGLETON
    ):
        self.service_type = service_type
        self.implementation_type = implementation_type or service_type
        self.instance = instance
        self.factory = factory
        self.lifetime = lifetime


class IDIContainer(ABC):
    @abstractmethod
    def register(
        self,
        service_type: Type[T],
        implementation_type: Optional[Type[T]] = None,
        instance: Optional[T] = None,
        factory: Optional = None,
        lifetime: str = InstanceLifetime.SINGLETON
    ) -> None:
        pass

    @abstractmethod
    def resolve(self, service_type: Type[T]) -> T:
        pass

    @abstractmethod
    def resolve_all(self, service_type: Type[T]) -> List[T]:
        pass

    @abstractmethod
    @asynccontextmanager
    async def create_scope(self):
        pass


class DIContainer(IDIContainer):
    def __init__(self):
        self._descriptors: Dict[Type, List[ServiceDescriptor]] = {}
        self._singletons: Dict[Type, Any] = {}
        self._lock = threading.Lock()

    def register(
        self,
        service_type: Type[T],
        implementation_type: Optional[Type[T]] = None,
        instance: Optional[T] = None,
        factory: Optional = None,
        lifetime: str = InstanceLifetime.SINGLETON
    ) -> None:
        descriptor = ServiceDescriptor(
            service_type=service_type,
            implementation_type=implementation_type,
            instance=instance,
            factory=factory,
            lifetime=lifetime
        )
        
        if service_type not in self._descriptors:
            self._descriptors[service_type] = []
        self._descriptors[service_type].append(descriptor)

    def register_instance(self, service_type: Type[T], instance: T) -> None:
        self.register(
            service_type=service_type,
            instance=instance,
            lifetime=InstanceLifetime.SINGLETON
        )

    def register_transient(
        self,
        service_type: Type[T],
        implementation_type: Optional[Type[T]] = None
    ) -> None:
        self.register(
            service_type=service_type,
            implementation_type=implementation_type,
            lifetime=InstanceLifetime.TRANSIENT
        )

    def register_singleton(
        self,
        service_type: Type[T],
        implementation_type: Optional[Type[T]] = None
    ) -> None:
        self.register(
            service_type=service_type,
            implementation_type=implementation_type,
            lifetime=InstanceLifetime.SINGLETON
        )

    def resolve(self, service_type: Type[T]) -> T:
        descriptors = self._descriptors.get(service_type)
        if not descriptors:
            raise ValueError(f"未注册服务: {service_type.__name__}")
        
        descriptor = descriptors[-1]
        return self._create_instance(descriptor)

    def resolve_all(self, service_type: Type[T]) -> List[T]:
        descriptors = self._descriptors.get(service_type, [])
        return [self._create_instance(d) for d in descriptors]

    def _create_instance(self, descriptor: ServiceDescriptor) -> Any:
        if descriptor.lifetime == InstanceLifetime.SINGLETON:
            with self._lock:
                if descriptor.service_type in self._singletons:
                    return self._singletons[descriptor.service_type]
                
                instance = self._build_instance(descriptor)
                self._singletons[descriptor.service_type] = instance
                return instance
        else:
            return self._build_instance(descriptor)

    def _build_instance(self, descriptor: ServiceDescriptor) -> Any:
        if descriptor.instance is not None:
            return descriptor.instance
        
        if descriptor.factory is not None:
            return descriptor.factory(self)
        
        implementation_type = descriptor.implementation_type
        
        try:
            return implementation_type()
        except TypeError:
            import inspect
            sig = inspect.signature(implementation_type.__init__)
            params = {}
            
            for name, param in sig.parameters.items():
                if name == 'self' or param.annotation is inspect.Parameter.empty:
                    continue
                
                try:
                    params[name] = self.resolve(param.annotation)
                except ValueError:
                    if param.default is not inspect.Parameter.empty:
                        params[name] = param.default
            
            return implementation_type(**params)

    @asynccontextmanager
    async def create_scope(self):
        yield ScopedContainer(self)


class ScopedContainer(IDIContainer):
    def __init__(self, parent: DIContainer):
        self._parent = parent
        self._scoped_instances: Dict[Type, Any] = {}

    def register(
        self,
        service_type: Type[T],
        implementation_type: Optional[Type[T]] = None,
        instance: Optional[T] = None,
        factory: Optional = None,
        lifetime: str = InstanceLifetime.SINGLETON
    ) -> None:
        raise NotImplementedError("ScopedContainer 不支持注册服务注册")

    def resolve(self, service_type: Type[T]) -> T:
        if service_type in self._scoped_instances:
            return self._scoped_instances[service_type]
        
        return self._parent.resolve(service_type)

    def resolve_all(self, service_type: Type[T]) -> List[T]:
        return self._parent.resolve_all(service_type)

    @asynccontextmanager
    async def create_scope(self):
        yield self


_container: Optional[DIContainer] = None


def get_container() -> DIContainer:
    global _container
    if _container is None:
        _container = DIContainer()
    return _container


def set_container(container: DIContainer) -> None:
    global _container
    _container = container


def get_service(service_type: Type[T]) -> T:
    return get_container().resolve(service_type)
