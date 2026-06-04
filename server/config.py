from __future__ import annotations

from enum import Enum
from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Environment(str, Enum):
    DEVELOPMENT = "development"
    STAGING = "staging"
    PRODUCTION = "production"


class DungeonConfig(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_prefix="DUNGEON_",
        extra="ignore",
    )

    environment: Environment = Environment.DEVELOPMENT

    host: str = "0.0.0.0"
    port: int = 8765

    map_width: int = 60
    map_height: int = 40
    max_floors: int = 10
    monster_strength_mult: float = 1.0

    log_level: str = "DEBUG"

    db_path: str = "data/dungeon.db"
    data_dir: str = "data"

    max_party_size: int = 4
    max_concurrent_dungeons: int = 100
    rate_limit_per_second: int = 10

    graceful_shutdown_timeout: float = 30.0
    turn_settlement_timeout: float = 5.0

    @property
    def is_dev(self) -> bool:
        return self.environment == Environment.DEVELOPMENT

    @property
    def is_prod(self) -> bool:
        return self.environment == Environment.PRODUCTION


class DevelopmentConfig(DungeonConfig):
    map_width: int = 40
    map_height: int = 20
    max_floors: int = 3
    monster_strength_mult: float = 0.5
    log_level: str = "DEBUG"


class StagingConfig(DungeonConfig):
    map_width: int = 50
    map_height: int = 30
    max_floors: int = 7
    monster_strength_mult: float = 0.8
    log_level: str = "INFO"


class ProductionConfig(DungeonConfig):
    map_width: int = 60
    map_height: int = 40
    max_floors: int = 10
    monster_strength_mult: float = 1.0
    log_level: str = "INFO"


_CONFIG_MAP: dict[Environment, type[DungeonConfig]] = {
    Environment.DEVELOPMENT: DevelopmentConfig,
    Environment.STAGING: StagingConfig,
    Environment.PRODUCTION: ProductionConfig,
}


@lru_cache
def get_config() -> DungeonConfig:
    raw_env = DevelopmentConfig().environment
    env = Environment(raw_env)
    cls = _CONFIG_MAP.get(env, DungeonConfig)
    return cls()
