import pytest
import asyncio
from typing import Generator

from edge_platform.common.event_bus import EventBus


@pytest.fixture
def event_bus():
    return EventBus()


@pytest.fixture
def event_loop() -> Generator:
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest.fixture
def sample_config():
    return {
        "scheduler": {
            "max_retry_attempts": 3,
            "retry_delay_seconds": 1,
            "task_timeout_seconds": 300
        }
    }
