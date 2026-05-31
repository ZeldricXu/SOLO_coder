import pytest
from src.modules import ConfigManager, get_config_manager, AppConfig


def test_config_singleton():
    cm1 = get_config_manager()
    cm2 = get_config_manager()
    assert cm1 is cm2


def test_config_defaults():
    cm = ConfigManager()
    assert cm.app_config.app_name == "cloud-native-engine"
    assert cm.app_config.app_env == "test"


def test_config_get():
    cm = ConfigManager()
    assert cm.get("app_name") == "cloud-native-engine"
    assert cm.get("nonexistent", "default") == "default"


def test_config_validate():
    cm = ConfigManager()
    errors = cm.validate()
    assert isinstance(errors, list)


def test_config_diff():
    cm1 = ConfigManager()
    cm2 = ConfigManager()
    diff = cm1.diff(cm2)
    assert isinstance(diff, dict)


def test_config_mask_sensitive():
    cm = ConfigManager()
    data = {
        "password": "secret123",
        "api_key": "key123",
        "normal": "value",
        "nested": {
            "secret": "hidden",
            "public": "visible"
        }
    }
    masked = cm._mask_sensitive_data(data)
    assert masked["password"] == "***MASKED***"
    assert masked["api_key"] == "***MASKED***"
    assert masked["normal"] == "value"
    assert masked["nested"]["secret"] == "***MASKED***"
    assert masked["nested"]["public"] == "visible"


def test_feature_flags():
    cm = ConfigManager()
    assert isinstance(cm.is_feature_enabled("fault_injection"), bool)
    assert isinstance(cm.is_feature_enabled("nonexistent"), bool)
