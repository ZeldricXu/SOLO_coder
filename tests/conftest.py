import pytest
import os
import tempfile
from pathlib import Path
from typing import AsyncGenerator, Generator

os.environ["APP_ENV"] = "test"
os.environ["DATABASE_URL"] = "sqlite+aiosqlite:///./test.db"
os.environ["STORAGE_BACKEND"] = "memory"


@pytest.fixture
async def temp_dir() -> AsyncGenerator[Path, None]:
    with tempfile.TemporaryDirectory() as tmpdir:
        yield Path(tmpdir)


@pytest.fixture
def config_manager():
    from src.modules import ConfigManager
    return ConfigManager()


@pytest.fixture
def logger():
    from src.modules import get_logger
    return get_logger("test")


@pytest.fixture
def in_memory_storage():
    from src.modules import MemoryStorageBackend, StorageManager
    backend = MemoryStorageBackend()
    return StorageManager(backend=backend)


@pytest.fixture
def in_memory_event_store():
    from src.modules import InMemoryEventStore, EventStore
    backend = InMemoryEventStore()
    return EventStore(backend=backend)
