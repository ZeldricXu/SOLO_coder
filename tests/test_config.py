import pytest


class TestConfigSources:
    def test_memory_source(self):
        from platform_engineer.config.sources import MemorySource

        data = {"app": {"name": "test", "version": "1.0"}}
        source = MemorySource(data)
        loaded = source.load()
        assert loaded["app"]["name"] == "test"
        assert loaded["app"]["version"] == "1.0"

    def test_environment_source(self, monkeypatch):
        from platform_engineer.config.sources import EnvironmentSource

        monkeypatch.setenv("TEST_APP_NAME", "myapp")
        monkeypatch.setenv("TEST_APP_PORT", "8080")

        source = EnvironmentSource(prefix="TEST_")
        loaded = source.load()

        assert "app" in loaded
        assert loaded["app"]["name"] == "myapp"
        assert loaded["app"]["port"] == "8080"


class TestConfigManager:
    def test_add_and_load_sources(self, config_manager):
        snapshot = config_manager.get_snapshot()
        assert snapshot.get("test.key") == "value"

    def test_get_nested_config(self, config_manager):
        from platform_engineer.config.sources import MemorySource
        from platform_engineer.config import ConfigManager

        manager = ConfigManager()
        manager.add_source(MemorySource({"db": {"host": "localhost", "port": 5432}}), priority=10)
        manager.load_all()

        snapshot = manager.get_snapshot()
        assert snapshot.get("db.host") == "localhost"
        assert snapshot.get("db.port") == 5432

    def test_source_priority(self):
        from platform_engineer.config.sources import MemorySource
        from platform_engineer.config import ConfigManager

        manager = ConfigManager()
        manager.add_source(MemorySource({"app": {"name": "low"}}), priority=10)
        manager.add_source(MemorySource({"app": {"name": "high"}}), priority=20)
        manager.load_all()

        snapshot = manager.get_snapshot()
        assert snapshot.get("app.name") == "high"
