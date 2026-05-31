"""Default configuration helper."""
from __future__ import annotations

from .settings import Settings


def get_default_settings() -> Settings:
    """Get default settings with minimal required configuration."""
    return Settings.model_validate(
        {
            "storage": {
                "hot": {"path": "./data/hot", "max_size_gb": 100},
                "cold": {"path": "./data/cold", "max_size_gb": 500},
                "archive": {"path": "./data/archive", "max_size_gb": 2000},
            },
            "messaging": {
                "kafka": {
                    "bootstrap_servers": "localhost:9092",
                    "topics": {
                        "storage_events": "storage.events",
                        "lifecycle_events": "lifecycle.events",
                    },
                }
            },
        }
    )
