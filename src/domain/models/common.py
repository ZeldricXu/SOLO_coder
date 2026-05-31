"""
通用领域模型
"""

from __future__ import annotations

import enum
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from uuid import uuid4


class NotificationPriority(str, enum.Enum):
    LOW = "low"
    NORMAL = "normal"
    HIGH = "high"
    URGENT = "urgent"


@dataclass
class ServiceMetadata:
    id: str = field(default_factory=lambda: str(uuid4()))
    name: str = ""
    type: str = ""
    description: str = ""
    version: str = ""
    language: str = ""
    owner: str = ""
    repository: str = ""
    endpoints: List[Dict[str, Any]] = field(default_factory=list)
    dependencies: List[str] = field(default_factory=list)
    tags: List[str] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)


@dataclass
class DocumentMetadata:
    id: str = field(default_factory=lambda: str(uuid4()))
    title: str = ""
    source: str = ""
    type: str = ""
    url: str = ""
    content_hash: str = ""
    permissions: List[str] = field(default_factory=list)
    tags: List[str] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)


@dataclass
class ScaffoldConfig:
    project_name: str
    project_type: str
    language: str
    author: str
    template: str
    parameters: Dict[str, Any] = field(default_factory=dict)
    output_dir: str = ""
