import pytest

from app.config.manager import ConfigManager, get_config_manager


def test_config_manager_singleton():
    mgr1 = get_config_manager()
    mgr2 = get_config_manager()
    assert mgr1 is mgr2


def test_config_manager_get_set():
    mgr = get_config_manager()

    mgr.set("test", "timeout", 30)
    assert mgr.get("test", "timeout") == 30

    mgr.set("test", "retries", 3)
    config = mgr.get("test")
    assert config["timeout"] == 30
    assert config["retries"] == 3


def test_config_manager_default():
    mgr = get_config_manager()

    assert mgr.get("nonexistent", "key", default="default") == "default"


def test_config_manager_version():
    mgr = get_config_manager()
    initial = mgr.get_version("test_version")

    mgr.set("test_version", "key", "value")
    assert mgr.get_version("test_version") > initial


def test_config_manager_namespaces():
    mgr = get_config_manager()

    mgr.set("ns1", "k1", "v1")
    mgr.set("ns2", "k2", "v2")

    namespaces = mgr.get_namespaces()
    assert "ns1" in namespaces
    assert "ns2" in namespaces


def test_config_manager_load_from_dict():
    mgr = ConfigManager()

    data = {
        "production": {
            "timeout": 30,
            "retries": 3
        },
        "staging": {
            "timeout": 60
        }
    }

    mgr.load_from_dict(data)

    assert mgr.get("production", "timeout") == 30
    assert mgr.get("production", "retries") == 3
    assert mgr.get("staging", "timeout") == 60
