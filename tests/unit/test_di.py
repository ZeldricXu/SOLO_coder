"""
单元测试: DI容器
"""

import pytest
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from src.di import ServiceProvider, ServiceNotFoundError, injectable


class MockLogger:
    def __init__(self):
        self.messages = []

    def info(self, msg, **kw):
        self.messages.append(("info", msg))

    def debug(self, msg, **kw):
        self.messages.append(("debug", msg))

    def warning(self, msg, **kw):
        self.messages.append(("warning", msg))

    def error(self, msg, **kw):
        self.messages.append(("error", msg))

    def critical(self, msg, **kw):
        self.messages.append(("critical", msg))

    def with_trace(self, ctx):
        return self

    def with_context(self, **kw):
        return self


class MyService:
    def __init__(self, logger: MockLogger):
        self.logger = logger


def test_register_and_get():
    provider = ServiceProvider()
    provider.register(MockLogger, singleton=True)
    logger = provider.get(MockLogger)
    assert isinstance(logger, MockLogger)


def test_singleton_same_instance():
    provider = ServiceProvider()
    provider.register(MockLogger, singleton=True)
    a = provider.get(MockLogger)
    b = provider.get(MockLogger)
    assert a is b


def test_transient_different_instances():
    provider = ServiceProvider()
    provider.register(MockLogger, singleton=False)
    a = provider.get(MockLogger)
    b = provider.get(MockLogger)
    assert a is not b


def test_register_instance():
    provider = ServiceProvider()
    instance = MockLogger()
    provider.register_instance(MockLogger, instance)
    assert provider.get(MockLogger) is instance


def test_auto_resolution():
    provider = ServiceProvider()
    provider.register(MockLogger)
    provider.register(MyService)
    svc = provider.get(MyService)
    assert isinstance(svc, MyService)
    assert isinstance(svc.logger, MockLogger)


def test_not_found():
    provider = ServiceProvider()
    with pytest.raises(ServiceNotFoundError):
        provider.get(MockLogger)


def test_has():
    provider = ServiceProvider()
    assert provider.has(MockLogger) is False
    provider.register(MockLogger)
    assert provider.has(MockLogger) is True


def test_injectable_decorator():
    @injectable(singleton=False)
    class TransientService:
        pass

    assert TransientService._di_singleton is False
