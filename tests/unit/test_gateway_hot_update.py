"""
单元测试: API网关 - 动态配置热更新
"""

import pytest
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from src.infra.logging import StructuredLogger, ConsoleHandler
from src.modules.gateway import (
    ApiGateway,
    GatewayConfig,
    ConfigSource,
    ConfigObserver,
)
from src.modules.gateway.middleware import (
    SimpleRequest,
    SimpleResponse,
    AuthMiddleware,
    RateLimitMiddleware,
)
from src.domain.contracts.gateway import ConsistencyPolicy


@pytest.fixture
def logger():
    return StructuredLogger(service_name="test-gateway", handlers=[ConsoleHandler()])


@pytest.fixture
def config_source():
    return ConfigSource(
        initial_config=GatewayConfig(
            service_name="test-gateway",
            consistency_policy=ConsistencyPolicy.AT_LEAST_ONCE,
        )
    )


@pytest.fixture
def gateway(logger, config_source):
    gw = ApiGateway(logger=logger, config_source=config_source)

    async def hello_handler(request):
        return SimpleResponse(status_code=200, body=b'{"message": "hello"}')

    gw.register_handler("/api/hello", hello_handler)
    return gw


class TestGatewayDynamicConfig:
    def test_config_source_initial_config(self, config_source):
        """测试初始配置"""
        cfg = config_source.config
        assert cfg.service_name == "test-gateway"
        assert cfg.consistency_policy == ConsistencyPolicy.AT_LEAST_ONCE
        assert cfg.config_version == 1

    def test_config_update_increments_version(self, config_source):
        """测试配置更新递增版本号"""
        old_version = config_source.config.config_version
        new_config = GatewayConfig(
            service_name="updated-gateway",
            consistency_policy=ConsistencyPolicy.EXACTLY_ONCE,
        )
        config_source.update_config(new_config)
        assert config_source.config.config_version == old_version + 1
        assert config_source.config.service_name == "updated-gateway"

    def test_config_partial_update(self, config_source):
        """测试局部配置更新"""
        config_source.update_partial(rate_limit_max_requests=50)
        assert config_source.config.rate_limit_max_requests == 50
        assert config_source.config.service_name == "test-gateway"

    def test_gateway_config_change_callback(self, gateway, config_source):
        """测试网关配置变更回调"""
        changes = []

        def callback(old, new):
            changes.append((old.consistency_policy, new.consistency_policy))

        gateway.add_config_change_callback(callback)

        config_source.update_partial(
            consistency_policy=ConsistencyPolicy.EXACTLY_ONCE
        )

        assert len(changes) == 1
        assert changes[0][0] == ConsistencyPolicy.AT_LEAST_ONCE
        assert changes[0][1] == ConsistencyPolicy.EXACTLY_ONCE


class TestGatewayMiddlewareHotSwap:
    def test_add_middleware(self, gateway):
        """测试热插拔添加中间件"""
        mw = AuthMiddleware(api_keys={"key1": "user1"})
        gateway.add_middleware("auth", mw)
        assert gateway.get_middleware_names() == ["auth"]

    def test_add_middleware_with_position(self, gateway):
        """测试按位置插入中间件"""
        mw1 = AuthMiddleware(api_keys={})
        mw2 = RateLimitMiddleware(max_requests=100)
        gateway.add_middleware("auth", mw1)
        gateway.add_middleware("ratelimit", mw2, position=0)
        assert gateway.get_middleware_names() == ["ratelimit", "auth"]

    def test_remove_middleware(self, gateway):
        """测试移除中间件"""
        mw = AuthMiddleware(api_keys={})
        gateway.add_middleware("auth", mw)
        removed = gateway.remove_middleware("auth")
        assert removed is not None
        assert gateway.get_middleware_names() == []

    def test_clear_middlewares(self, gateway):
        """测试清空所有中间件"""
        gateway.add_middleware("auth", AuthMiddleware(api_keys={}))
        gateway.add_middleware("ratelimit", RateLimitMiddleware())
        gateway.clear_middlewares()
        assert gateway.get_middleware_names() == []

    def test_replace_middleware_same_name(self, gateway):
        """测试同名中间件替换"""
        mw1 = AuthMiddleware(api_keys={"key1": "user1"})
        mw2 = AuthMiddleware(api_keys={"key2": "user2"})
        gateway.add_middleware("auth", mw1)
        gateway.add_middleware("auth", mw2)
        assert gateway.get_middleware_names() == ["auth"]


class TestGatewayHandlerHotSwap:
    def test_replace_handler(self, gateway):
        """测试运行时替换处理器"""
        async def new_handler(request):
            return SimpleResponse(status_code=200, body=b"new")

        old = gateway.replace_handler("/api/hello", new_handler)
        assert old is not None

    def test_unregister_handler(self, gateway):
        """测试卸载处理器"""
        removed = gateway.unregister_handler("/api/hello")
        assert removed is not None
        assert gateway.unregister_handler("/api/hello") is None


class TestGatewayRuntimeConfigUpdates:
    def test_update_consistency_policy(self, gateway):
        """测试热切换一致性策略"""
        assert gateway.get_config().consistency_policy == ConsistencyPolicy.AT_LEAST_ONCE
        gateway.update_consistency_policy(ConsistencyPolicy.EXACTLY_ONCE)
        assert gateway.get_config().consistency_policy == ConsistencyPolicy.EXACTLY_ONCE

    def test_update_rate_limit(self, gateway):
        """测试热更新限流配置"""
        gateway.update_rate_limit(50, 30)
        assert gateway.get_config().rate_limit_max_requests == 50
        assert gateway.get_config().rate_limit_window_seconds == 30

    def test_add_remove_api_key(self, gateway):
        """测试热添加/移除API密钥"""
        gateway.add_api_key("new-key", "new-user")
        assert gateway.get_config().auth_api_keys["new-key"] == "new-user"

        gateway.remove_api_key("new-key")
        assert "new-key" not in gateway.get_config().auth_api_keys
