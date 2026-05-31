import pytest
import json
from src.modules import MemoryStorageBackend, StorageManager, StorageBackendType


@pytest.fixture
def storage_manager():
    backend = MemoryStorageBackend()
    return StorageManager(backend=backend)


@pytest.mark.asyncio
async def test_save_and_load_data(storage_manager):
    test_data = {"key": "value", "number": 42}
    key = "test/data.json"

    saved_key = await storage_manager.save_data(key, test_data)
    assert saved_key == key

    loaded = await storage_manager.load_data(key)
    assert loaded == test_data


@pytest.mark.asyncio
async def test_save_and_load_bytes(storage_manager):
    test_bytes = b"binary data"
    key = "test/binary.bin"

    await storage_manager.save_data(key, test_bytes, serialize=False)
    loaded = await storage_manager.load_data(key, deserialize=False)
    assert loaded == test_bytes


@pytest.mark.asyncio
async def test_exists_and_delete(storage_manager):
    key = "test/exists.txt"
    await storage_manager.save_data(key, "test")

    assert await storage_manager.exists(key)
    assert await storage_manager.delete_data(key)
    assert not await storage_manager.exists(key)


@pytest.mark.asyncio
async def test_list_objects(storage_manager):
    await storage_manager.save_data("test/1.txt", "data1")
    await storage_manager.save_data("test/2.txt", "data2")
    await storage_manager.save_data("other/3.txt", "data3")

    objects = await storage_manager.list_data("test/")
    assert len(objects) == 2


@pytest.mark.asyncio
async def test_compression(storage_manager):
    test_data = {"key": "value" * 100}
    key = "test/compressed"

    await storage_manager.save_data(key, test_data, compress=True)
    loaded = await storage_manager.load_data(key, decompress=True)
    assert loaded == test_data
