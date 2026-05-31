import pytest
import asyncio
from typing import AsyncGenerator, Generator
from fastapi.testclient import TestClient
from unittest.mock import MagicMock

from streamsql.core.config import ConfigManager
from streamsql.core.context import ProcessingContext, ContextManager
from streamsql.core.events import EventBus
from streamsql.main import create_app


@pytest.fixture(scope="session")
def event_loop() -> Generator:
    loop = asyncio.get_event_loop_policy().new_event_loop()
    yield loop
    loop.close()


@pytest.fixture(scope="session")
def test_config():
    config_manager = ConfigManager()
    return config_manager.load_config("config/default.yml")


@pytest.fixture
def processing_context():
    ctx = ProcessingContext(trace_id="test-trace-123")
    yield ctx
    ctx.cleanup()


@pytest.fixture
def event_bus():
    bus = EventBus()
    yield bus
    bus.clear_subscribers()


@pytest.fixture
def context_manager():
    return ContextManager()


@pytest.fixture
def app(test_config):
    return create_app(test_config)


@pytest.fixture
def client(app):
    return TestClient(app)


@pytest.fixture
def mock_data_source():
    return {
        "type": "mysql",
        "host": "localhost",
        "port": 3306,
        "database": "test_db",
        "username": "test_user",
        "password": "test_pass",
    }


@pytest.fixture
def sample_table_schema():
    return {
        "name": "users",
        "columns": [
            {"name": "id", "type": "INT", "nullable": False, "is_primary": True},
            {"name": "name", "type": "VARCHAR(255)", "nullable": False},
            {"name": "email", "type": "VARCHAR(255)", "nullable": True},
            {"name": "created_at", "type": "DATETIME", "nullable": False},
        ],
    }


@pytest.fixture
def sample_sql_query():
    return "SELECT id, name, email FROM users WHERE created_at > '2024-01-01'"


@pytest.fixture
def sample_cdc_event():
    return {
        "type": "insert",
        "database": "test_db",
        "table": "users",
        "data": {"id": 1, "name": "Test User", "email": "test@example.com"},
        "timestamp": "2024-01-01T00:00:00Z",
        "binlog_position": "mysql-bin.000001:12345",
    }


@pytest.fixture
def sample_vector_data():
    return [
        {"id": 1, "text": "Hello world", "vector": [0.1, 0.2, 0.3, 0.4, 0.5]},
        {"id": 2, "text": "Good morning", "vector": [0.2, 0.3, 0.4, 0.5, 0.6]},
        {"id": 3, "text": "Good evening", "vector": [0.3, 0.4, 0.5, 0.6, 0.7]},
    ]


@pytest.fixture
def sample_timeseries_data():
    return [
        {"timestamp": "2024-01-01T00:00:00Z", "value": 10.5, "metric": "temperature"},
        {"timestamp": "2024-01-01T00:01:00Z", "value": 11.2, "metric": "temperature"},
        {"timestamp": "2024-01-01T00:02:00Z", "value": 12.8, "metric": "temperature"},
        {"timestamp": "2024-01-01T00:03:00Z", "value": 13.1, "metric": "temperature"},
        {"timestamp": "2024-01-01T00:04:00Z", "value": 14.5, "metric": "temperature"},
    ]


@pytest.fixture
def sample_quality_rules():
    return [
        {"type": "null_check", "column": "name", "params": {}},
        {"type": "range_check", "column": "age", "params": {"min": 0, "max": 150}},
        {"type": "regex_check", "column": "email", "params": {"pattern": r"^[^@]+@[^@]+\.[^@]+$"}},
    ]
