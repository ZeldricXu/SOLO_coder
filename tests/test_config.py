import pytest

from top.config.manager import ConfigManager, InMemoryConfigStore, ConfigSnapshot
from top.core.models import ConfigModel, utc_now


class TestInMemoryConfigStore:
    def test_save_and_get_latest(self):
        store = InMemoryConfigStore()
        
        config = ConfigModel(
            config_id="cfg_001",
            namespace="test",
            version=1,
            parameters={"key": "value"},
        )
        
        store.save(config)
        latest = store.get_latest("test")
        
        assert latest is not None
        assert latest.config_id == "cfg_001"
        assert latest.version == 1
        assert latest.parameters["key"] == "value"

    def test_get_version(self):
        store = InMemoryConfigStore()
        
        config_v1 = ConfigModel(
            config_id="cfg_001",
            namespace="test",
            version=1,
            parameters={"key": "v1"},
        )
        config_v2 = ConfigModel(
            config_id="cfg_002",
            namespace="test",
            version=2,
            parameters={"key": "v2"},
        )
        
        store.save(config_v1)
        store.save(config_v2)
        
        v1 = store.get_version("test", 1)
        v2 = store.get_version("test", 2)
        
        assert v1 is not None
        assert v1.parameters["key"] == "v1"
        assert v2 is not None
        assert v2.parameters["key"] == "v2"

    def test_history(self):
        store = InMemoryConfigStore()
        
        for i in range(5):
            config = ConfigModel(
                config_id=f"cfg_{i:03d}",
                namespace="test",
                version=i + 1,
                parameters={"version": i + 1},
            )
            store.save(config)
        
        history = store.get_history("test", limit=3)
        assert len(history) == 3
        assert history[0].version == 5
        assert history[2].version == 3


class TestConfigManager:
    def test_save_and_get(self):
        store = InMemoryConfigStore()
        manager = ConfigManager(store=store)
        
        config = ConfigModel(
            config_id="cfg_001",
            namespace="test",
            version=1,
            parameters={"key": "value"},
        )
        
        manager.save(config)
        retrieved = manager.get_latest("test")
        
        assert retrieved is not None
        assert retrieved.config_id == "cfg_001"

    def test_rollback(self):
        store = InMemoryConfigStore()
        manager = ConfigManager(store=store)
        
        v1 = ConfigModel(
            config_id="cfg_001",
            namespace="test",
            version=1,
            parameters={"value": "v1"},
        )
        v2 = ConfigModel(
            config_id="cfg_002",
            namespace="test",
            version=2,
            parameters={"value": "v2"},
        )
        
        manager.save(v1)
        manager.save(v2)
        
        rolled_back = manager.rollback("test", 1)
        
        assert rolled_back is not None
        assert rolled_back.parameters["value"] == "v1"
        assert rolled_back.version == 3

    def test_snapshot_and_restore(self):
        store = InMemoryConfigStore()
        manager = ConfigManager(store=store)
        
        config = ConfigModel(
            config_id="cfg_001",
            namespace="test",
            version=1,
            parameters={"snapshot": "test"},
        )
        manager.save(config)
        
        snapshot = manager.create_snapshot("test")
        assert isinstance(snapshot, ConfigSnapshot)
        assert snapshot.version == 1
        
        new_config = ConfigModel(
            config_id="cfg_002",
            namespace="test",
            version=2,
            parameters={"snapshot": "modified"},
        )
        manager.save(new_config)
        
        restored = manager.restore_from_snapshot(snapshot)
        assert restored is not None
        assert restored.parameters["snapshot"] == "test"

    def test_change_listener(self):
        store = InMemoryConfigStore()
        manager = ConfigManager(store=store)
        
        changes = []
        
        def listener(namespace, old_config, new_config):
            changes.append((namespace, old_config, new_config))
        
        manager.add_change_listener(listener)
        
        config = ConfigModel(
            config_id="cfg_001",
            namespace="test",
            version=1,
            parameters={"key": "value"},
        )
        manager.save(config)
        
        assert len(changes) == 1
        assert changes[0][0] == "test"
