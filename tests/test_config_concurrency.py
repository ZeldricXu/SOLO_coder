import asyncio
import pytest
import threading
import time

from platform_engineer.config import ConfigManager, Configuration
from platform_engineer.config.sources import MemorySource


class TestConfigManagerConcurrency:
    @pytest.mark.asyncio
    async def test_concurrent_load(self):
        manager = ConfigManager()
        source = MemorySource({"key": "value"})
        manager.add_source(source)

        results = []

        async def load_config(index):
            config = await manager.load()
            results.append((index, config.get_version()))
            return config

        tasks = [load_config(i) for i in range(10)]
        await asyncio.gather(*tasks)

        assert len(results) == 10
        versions = [r[1] for r in results]
        assert all(v >= 1 for v in versions)

    @pytest.mark.asyncio
    async def test_concurrent_reads_during_write(self):
        manager = ConfigManager()
        source = MemorySource({"initial": "value"})
        manager.add_source(source)
        await manager.load()

        concurrent_reads = []
        concurrent_writes = []

        async def read_config():
            for _ in range(100):
                config = manager.get_config()
                if config:
                    _ = config.get("initial")
                _ = manager.get("initial", "default")

        async def write_config():
            for _ in range(10):
                await manager.load()

        read_tasks = [read_config() for _ in range(5)]
        write_tasks = [write_config() for _ in range(2)]

        await asyncio.gather(*read_tasks, *write_tasks)

    @pytest.mark.asyncio
    async def test_concurrent_listener_modification(self):
        manager = ConfigManager()
        source = MemorySource({"test": "data"})
        manager.add_source(source)

        listener_calls = []

        async def listener1(old, new):
            listener_calls.append(("listener1", new.get_version()))

        async def listener2(old, new):
            listener_calls.append(("listener2", new.get_version()))

        manager.add_listener(listener1)
        manager.add_listener(listener2)

        await manager.load()
        await manager.load()

        assert len(listener_calls) == 2
        listener_names = [call[0] for call in listener_calls]
        assert "listener1" in listener_names
        assert "listener2" in listener_names

        manager.remove_listener(listener1)
        await manager.load()

        remaining_listeners = [call[0] for call in listener_calls[2:]]
        assert "listener1" not in remaining_listeners
        assert "listener2" in remaining_listeners

    @pytest.mark.asyncio
    async def test_concurrent_config_store_operations(self):
        manager = ConfigManager()
        errors = []

        async def create_configs():
            for i in range(20):
                try:
                    config = manager.create_config(
                        namespace=f"ns_{i % 5}",
                        parameters={"value": i},
                    )
                    manager.update_config(config.config_id, {"updated": True})
                except Exception as e:
                    errors.append(e)

        tasks = [create_configs() for _ in range(3)]
        await asyncio.gather(*tasks)

        assert len(errors) == 0
        configs = manager.list_configs()
        assert len(configs) == 60

    def test_thread_safe_read_operations(self):
        manager = ConfigManager()

        def worker():
            for _ in range(100):
                manager.get("key", "default")
                manager.get_config()

        threads = [threading.Thread(target=worker) for _ in range(5)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

    @pytest.mark.asyncio
    async def test_global_config_manager_thread_safe(self):
        from platform_engineer.config.manager import (
            _global_config_manager,
            get_config_manager,
            set_config_manager,
        )

        if _global_config_manager is not None:
            original = _global_config_manager
            set_config_manager(None)
        else:
            original = None

        managers = []

        def worker():
            mgr = get_config_manager()
            managers.append(id(mgr))

        threads = [threading.Thread(target=worker) for _ in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(set(managers)) == 1

        if original is not None:
            set_config_manager(original)
        else:
            set_config_manager(None)
