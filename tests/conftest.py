"""
pytest 公共配置和fixture
提供所有测试模块共享的fixture和配置
"""

import pytest
import sys
import os
from unittest.mock import MagicMock, patch
from typing import Dict, Any

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


@pytest.fixture
def mock_requests():
    """
    Mock requests 模块的fixture
    提供统一的HTTP请求mock
    """
    with patch("requests.post") as mock_post, \
         patch("requests.get") as mock_get, \
         patch("requests.put") as mock_put, \
         patch("requests.delete") as mock_delete:

        mock_post.return_value = MagicMock(status_code=200, json=lambda: {"code": 200, "data": None})
        mock_get.return_value = MagicMock(status_code=200, json=lambda: {"code": 200, "data": None})
        mock_put.return_value = MagicMock(status_code=200, json=lambda: {"code": 200, "data": None})
        mock_delete.return_value = MagicMock(status_code=200, json=lambda: {"code": 200, "data": None})

        mock_session = MagicMock()
        mock_session.post = mock_post
        mock_session.get = mock_get
        mock_session.put = mock_put
        mock_session.delete = mock_delete

        yield mock_session


@pytest.fixture
def mock_context():
    """
    Mock 上下文对象的fixture
    模拟Go的context.Context
    """
    ctx = MagicMock()
    ctx.Value.return_value = None
    ctx.Done.return_value = None
    ctx.Err.return_value = None
    ctx.Background.return_value = ctx
    return ctx


@pytest.fixture
def mock_logger():
    """
    Mock 日志记录器的fixture
    """
    with patch("loglevelplatform.internal.common.logger.FromContext") as mock_from_ctx, \
         patch("loglevelplatform.internal.common.logger.Info") as mock_info, \
         patch("loglevelplatform.internal.common.logger.Error") as mock_error, \
         patch("loglevelplatform.internal.common.logger.Warn") as mock_warn, \
         patch("loglevelplatform.internal.common.logger.Debug") as mock_debug:

        mock_logger_instance = MagicMock()
        mock_logger_instance.Info = mock_info
        mock_logger_instance.Error = mock_error
        mock_logger_instance.Warn = mock_warn
        mock_logger_instance.Debug = mock_debug
        mock_from_ctx.return_value = mock_logger_instance

        yield mock_logger_instance


@pytest.fixture
def mock_database():
    """
    Mock 数据库连接的fixture
    """
    with patch("loglevelplatform.internal.common.database.GetDB") as mock_get_db:
        mock_db = MagicMock()
        mock_db.Where.return_value = mock_db
        mock_db.Model.return_value = mock_db
        mock_db.Create.return_value = MagicMock(error=None)
        mock_db.Save.return_value = MagicMock(error=None)
        mock_db.Delete.return_value = MagicMock(error=None)
        mock_db.First.return_value = MagicMock(error=None)
        mock_db.Find.return_value = MagicMock(error=None)
        mock_db.Count.return_value = MagicMock(error=None)
        mock_db.Order.return_value = mock_db
        mock_db.Limit.return_value = mock_db
        mock_db.Offset.return_value = mock_db
        mock_db.Updates.return_value = MagicMock(error=None)

        mock_get_db.return_value = mock_db
        yield mock_db


@pytest.fixture
def mock_redis():
    """
    Mock Redis客户端的fixture
    """
    with patch("loglevelplatform.internal.common.database.GetRedis") as mock_get_redis:
        mock_redis_client = MagicMock()
        mock_redis_client.Get.return_value = MagicMock()
        mock_redis_client.Set.return_value = MagicMock()
        mock_redis_client.SetEX.return_value = MagicMock()
        mock_redis_client.Delete.return_value = MagicMock()
        mock_redis_client.Exists.return_value = MagicMock()
        mock_redis_client.Expire.return_value = MagicMock()
        mock_redis_client.Ping.return_value = MagicMock()
        mock_redis_client.Pipeline.return_value = mock_redis_client

        mock_get_redis.return_value = mock_redis_client
        yield mock_redis_client


@pytest.fixture
def mock_time():
    """
    Mock 时间相关函数的fixture
    """
    import time as real_time

    fixed_time = real_time.time()

    with patch("time.time", return_value=fixed_time), \
         patch("time.Now", return_value=fixed_time), \
         patch("time.sleep") as mock_sleep:

        yield {
            "fixed_time": fixed_time,
            "mock_sleep": mock_sleep,
        }


@pytest.fixture(autouse=True)
def setup_test_env(monkeypatch):
    """
    自动应用的测试环境设置fixture
    设置环境变量和全局配置
    """
    monkeypatch.setenv("ENV", "test")
    monkeypatch.setenv("LOG_LEVEL", "debug")
    monkeypatch.setenv("DATABASE_URL", "postgres://test:test@localhost:5432/test")
    monkeypatch.setenv("REDIS_URL", "redis://localhost:6379/0")

    yield

    monkeypatch.undo()


@pytest.fixture
def sample_log_level_config() -> Dict[str, Any]:
    """
    提供示例日志级别配置数据
    """
    return {
        "namespace": "default",
        "component": "api.server",
        "level": "info",
        "updated_by": "test_user",
    }


@pytest.fixture
def sample_scheduled_task() -> Dict[str, Any]:
    """
    提供示例调度任务数据
    """
    return {
        "name": "Test Task",
        "description": "A test scheduled task",
        "type": "data_processing",
        "cron_expr": "0 * * * *",
        "payload": {"key": "value"},
        "depends_on": [],
        "timeout_seconds": 30,
        "retries": 3,
        "enabled": True,
    }


@pytest.fixture
def sample_metric_record() -> Dict[str, Any]:
    """
    提供示例监控指标数据
    """
    return {
        "type": "counter",
        "name": "http_requests_total",
        "value": 1.0,
        "labels": {"host": "node-1", "region": "cn-east"},
    }


def pytest_configure(config):
    """
    pytest配置钩子
    注册自定义标记
    """
    config.addinivalue_line(
        "markers",
        "unit: Unit tests - test individual components in isolation",
    )
    config.addinivalue_line(
        "markers",
        "integration: Integration tests - test component interactions",
    )
    config.addinivalue_line(
        "markers",
        "boundary: Boundary condition tests - test edge cases and limits",
    )
    config.addinivalue_line(
        "markers",
        "concurrency: Concurrency tests - test multi-threaded scenarios",
    )
    config.addinivalue_line(
        "markers",
        "transaction: Transaction tests - test rollback and atomicity",
    )
    config.addinivalue_line(
        "markers",
        "validation: Parameter validation tests - test input validation",
    )


def pytest_collection_modifyitems(items):
    """
    修改测试收集结果的钩子
    自动为测试添加标记
    """
    for item in items:
        test_name = item.name.lower()
        test_path = str(item.fspath).lower()

        if "boundary" in test_name or "edge" in test_name:
            item.add_marker(pytest.mark.boundary)
        if "concurrency" in test_name or "concurrent" in test_name:
            item.add_marker(pytest.mark.concurrency)
        if "rollback" in test_name or "transaction" in test_name:
            item.add_marker(pytest.mark.transaction)
        if "validation" in test_name or "invalid" in test_name or "rejected" in test_name:
            item.add_marker(pytest.mark.validation)

        if "test_log_level" in test_path:
            item.add_marker(pytest.mark.unit)
        elif "test_monitoring" in test_path:
            item.add_marker(pytest.mark.unit)
        elif "test_scheduler" in test_path:
            item.add_marker(pytest.mark.unit)
