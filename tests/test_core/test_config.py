import pytest
from streamsql.core.config import ConfigManager, AppConfig


def test_config_manager_singleton():
    manager1 = ConfigManager()
    manager2 = ConfigManager()
    assert manager1 is manager2


def test_load_config():
    config = ConfigManager.load("config/default.yml")
    assert isinstance(config, AppConfig)
    assert config.server is not None
    assert config.database is not None
    assert config.redis is not None


def test_config_validation():
    config = ConfigManager.load("config/default.yml")
    assert config.server.host == "0.0.0.0"
    assert config.server.port == 8000
    assert config.database.url.startswith("sqlite")
    assert config.redis.url.startswith("redis")


def test_get_config():
    config1 = ConfigManager.load()
    config2 = ConfigManager.load()
    assert config1 is config2


def test_modules_config():
    config = ConfigManager.load()
    assert config.modules.metadata_crawler.sample_size == 100
    assert config.modules.metadata_crawler.timeout == 30
    assert config.modules.vector_index.default_dimension == 1536
    assert config.modules.lifecycle_manager.hot_threshold_days == 7
