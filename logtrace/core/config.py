import os
import yaml
from pathlib import Path
from typing import Dict, List, Any


class ConfigManager:
    def __init__(self, config_dir: str = None):
        if config_dir is None:
            config_dir = Path(__file__).parent.parent / 'config' / 'default'
        self.config_dir = Path(config_dir)
        self.config = self._load_config()

    def _load_config(self) -> Dict[str, Any]:
        config_file = self.config_dir / 'config.yaml'
        if config_file.exists():
            with open(config_file, 'r', encoding='utf-8') as f:
                return yaml.safe_load(f)
        return {}

    def get(self, key: str, default: Any = None) -> Any:
        keys = key.split('.')
        value = self.config
        for k in keys:
            if isinstance(value, dict) and k in value:
                value = value[k]
            else:
                return default
        return value

    def get_elasticsearch_config(self) -> Dict[str, Any]:
        return self.get('elasticsearch', {
            'host': 'localhost',
            'port': 9200,
            'index_prefix': 'logtrace'
        })

    def get_api_config(self) -> Dict[str, Any]:
        return self.get('api', {
            'host': '0.0.0.0',
            'port': 5000,
            'debug': False
        })

    def get_nodes(self) -> List[Dict[str, Any]]:
        return self.get('nodes', [])

    def get_exception_rules(self) -> List[Dict[str, Any]]:
        return self.get('exception_rules', [])

    def get_alert_config(self) -> Dict[str, Any]:
        return self.get('alert', {'channels': []})

    def get_retention_days(self) -> int:
        return self.get('retention.days', 30)
