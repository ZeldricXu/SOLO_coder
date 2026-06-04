from __future__ import annotations

import json
import os
from pathlib import Path


def load_server_config() -> dict:
    config_paths = [
        Path("server_config.json"),
        Path(__file__).parent / "server_config.json",
        Path.home() / ".dungeon" / "server_config.json",
    ]

    for p in config_paths:
        if p.exists():
            with open(p, "r", encoding="utf-8") as f:
                return json.load(f)

    return {
        "server_host": os.environ.get("DUNGEON_SERVER_HOST", "localhost"),
        "server_port": int(os.environ.get("DUNGEON_SERVER_PORT", "8765")),
    }


def resolve_server_address(environment: str | None = None) -> tuple[str, int]:
    config = load_server_config()

    if environment and "environments" in config and environment in config["environments"]:
        env_cfg = config["environments"][environment]
        return env_cfg.get("server_host", "localhost"), env_cfg.get("server_port", 8765)

    return config.get("server_host", "localhost"), config.get("server_port", 8765)
