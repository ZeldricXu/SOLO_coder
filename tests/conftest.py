"""
DIDAuth Service Test Suite
Conftest file with shared fixtures and configurations
"""
import asyncio
import logging
import os
import sys
from datetime import datetime, timedelta
from unittest.mock import Mock, MagicMock, patch, AsyncMock

import pytest
from faker import Faker

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

fake = Faker()
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


@pytest.fixture(scope="session")
def event_loop():
    """Create an event loop for the entire test session."""
    loop = asyncio.get_event_loop_policy().new_event_loop()
    yield loop
    loop.close()


@pytest.fixture
def mock_meter_registry():
    """Mock Micrometer meter registry."""
    registry = MagicMock()
    registry.counter = Mock(return_value=MagicMock(increment=Mock()))
    registry.gauge = Mock(return_value=MagicMock(set=Mock()))
    registry.timer = Mock(return_value=MagicMock())
    return registry


@pytest.fixture
def mock_object_mapper():
    """Mock Jackson ObjectMapper."""
    mapper = MagicMock()
    mapper.writeValueAsString = Mock(return_value="{}")
    mapper.readValue = Mock(return_value={})
    return mapper


@pytest.fixture
def mock_web_client_builder():
    """Mock WebClient builder."""
    response = MagicMock()
    response.bodyToMono = Mock(return_value=AsyncMock())

    retrieve = MagicMock()
    retrieve.bodyToMono = Mock(return_value=response.bodyToMono())
    retrieve.onStatus = Mock(return_value=retrieve)

    spec = MagicMock()
    spec.retrieve = Mock(return_value=retrieve)
    spec.bodyValue = Mock(return_value=spec)
    spec.uri = Mock(return_value=spec)

    client = MagicMock()
    client.post = Mock(return_value=spec)
    client.get = Mock(return_value=spec)

    builder = MagicMock()
    builder.build = Mock(return_value=client)
    return builder


@pytest.fixture
def sample_trace_id():
    """Generate a sample trace ID."""
    return "trace_" + fake.sha256()[:16]


@pytest.fixture
def sample_user_id():
    """Generate a sample user ID."""
    return "user_" + fake.uuid4().replace("-", "")[:12]


@pytest.fixture
def base_test_context():
    """Provide a base test context with common mocks."""
    return {
        "trace_id": sample_trace_id,
        "user_id": sample_user_id,
        "timestamp": datetime.utcnow(),
    }
