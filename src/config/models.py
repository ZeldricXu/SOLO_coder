from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

from src.common.models import generate_id, utc_now


class ConfigSourceType(str, Enum):
    ENVIRONMENT = "environment"
    FILE = "file"
    DATABASE = "database"
    REDIS = "redis"
    VAULT = "vault"
    CONSUL = "consul"
    ETCD = "etcd"
    HTTP = "http"
    MEMORY = "memory"


class ConfigValueType(str, Enum):
    STRING = "string"
    INTEGER = "integer"
    FLOAT = "float"
    BOOLEAN = "boolean"
    LIST = "list"
    DICT = "dict"
    JSON = "json"


class ConfigEntry(BaseModel):
    config_id: str = Field(default_factory=lambda: generate_id("cfg"))
    key: str
    value: Any
    value_type: ConfigValueType = ConfigValueType.STRING
    namespace: str = "default"
    version: int = 1
    description: str = ""
    tags: List[str] = Field(default_factory=list)
    source: ConfigSourceType = ConfigSourceType.MEMORY
    encrypted: bool = False
    created_by: Optional[str] = None
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)
    expires_at: Optional[datetime] = None


class ConfigChangeEvent(BaseModel):
    event_id: str = Field(default_factory=lambda: generate_id("evt"))
    key: str
    old_value: Any
    new_value: Any
    namespace: str
    source: ConfigSourceType
    changed_by: Optional[str] = None
    changed_at: datetime = Field(default_factory=utc_now)


class ConfigValidationRule(BaseModel):
    rule_id: str = Field(default_factory=lambda: generate_id("rule"))
    key_pattern: str
    required: bool = False
    value_type: Optional[ConfigValueType] = None
    allowed_values: Optional[List[Any]] = None
    min_value: Optional[float] = None
    max_value: Optional[float] = None
    regex_pattern: Optional[str] = None


class ConfigDiff(BaseModel):
    namespace: str
    added: Dict[str, Any] = Field(default_factory=dict)
    removed: Dict[str, Any] = Field(default_factory=dict)
    changed: Dict[str, Dict[str, Any]] = Field(default_factory=dict)


class ConfigSnapshot(BaseModel):
    snapshot_id: str = Field(default_factory=lambda: generate_id("snap"))
    namespace: str
    data: Dict[str, Any]
    created_at: datetime = Field(default_factory=utc_now)
