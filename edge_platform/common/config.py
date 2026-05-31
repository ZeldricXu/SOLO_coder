import os
from typing import Dict, Any
import yaml


class Config:
    _instance = None
    _config: Dict[str, Any] = {}

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._load_config()
        return cls._instance

    def _load_config(self) -> None:
        default_config = {
            "scheduler": {
                "max_retry_attempts": 3,
                "retry_delay_seconds": 1,
                "task_timeout_seconds": 300
            },
            "ota": {
                "delta_algorithm": "bsdiff",
                "max_batch_size": 100,
                "auto_rollback_enabled": True,
                "rollback_threshold": 0.3
            },
            "device_shadow": {
                "sync_interval_seconds": 30,
                "max_shadow_size": 8192
            },
            "rule_engine": {
                "max_rules": 1000,
                "execution_timeout_seconds": 10
            },
            "storage": {
                "provider": "local",
                "local": {
                    "base_path": "./data/storage"
                },
                "s3": {
                    "endpoint_url": "",
                    "access_key": "",
                    "secret_key": "",
                    "bucket": ""
                }
            },
            "notification": {
                "channels": ["email", "webhook", "sms"],
                "default_channel": "email"
            },
            "protocol": {
                "drivers": ["modbus", "mqtt", "opcua"],
                "standard_format": "json"
            },
            "monitoring": {
                "collection_interval_seconds": 15,
                "retention_days": 30
            },
            "inference": {
                "model_path": "./models",
                "max_concurrent_tasks": 4
            }
        }

        config_path = os.environ.get("EDGE_PLATFORM_CONFIG", "config.yaml")
        if os.path.exists(config_path):
            try:
                with open(config_path, "r", encoding="utf-8") as f:
                    user_config = yaml.safe_load(f) or {}
                    self._config = self._deep_merge(default_config, user_config)
            except Exception as e:
                print(f"Warning: Failed to load config file: {e}")
                self._config = default_config
        else:
            self._config = default_config

    def _deep_merge(self, base: Dict, override: Dict) -> Dict:
        result = base.copy()
        for key, value in override.items():
            if key in result and isinstance(result[key], dict) and isinstance(value, dict):
                result[key] = self._deep_merge(result[key], value)
            else:
                result[key] = value
        return result

    def get(self, key: str, default: Any = None) -> Any:
        keys = key.split(".")
        value = self._config
        for k in keys:
            if isinstance(value, dict) and k in value:
                value = value[k]
            else:
                return default
        return value

    def set(self, key: str, value: Any) -> None:
        keys = key.split(".")
        config = self._config
        for k in keys[:-1]:
            if k not in config:
                config[k] = {}
            config = config[k]
        config[keys[-1]] = value


config = Config()
