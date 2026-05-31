from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

from pydantic import BaseModel, Field

from src.common.models import generate_id, utc_now


class ProjectType(str, Enum):
    FASTAPI = "fastapi"
    DJANGO = "django"
    FLASK = "flask"
    CLI = "cli"
    LIBRARY = "library"
    FULL_STACK = "full_stack"


class DatabaseType(str, Enum):
    POSTGRESQL = "postgresql"
    MYSQL = "mysql"
    SQLITE = "sqlite"
    MONGODB = "mongodb"
    NONE = "none"


class TemplateVariable(BaseModel):
    name: str
    description: str = ""
    type: str = "string"
    default: Optional[Any] = None
    required: bool = False
    choices: Optional[List[Any]] = None
    validator: Optional[str] = None


class TemplateFile(BaseModel):
    source_path: str
    target_path: str
    template: bool = True
    executable: bool = False


class TemplateDefinition(BaseModel):
    template_id: str = Field(default_factory=lambda: generate_id("tpl"))
    name: str
    description: str = ""
    version: str = "1.0.0"
    project_type: ProjectType
    variables: List[TemplateVariable] = Field(default_factory=list)
    files: List[TemplateFile] = Field(default_factory=list)
    dependencies: List[str] = Field(default_factory=list)
    dev_dependencies: List[str] = Field(default_factory=list)
    pre_hooks: List[str] = Field(default_factory=list)
    post_hooks: List[str] = Field(default_factory=list)
    created_at: datetime = Field(default_factory=utc_now)


class ProjectConfig(BaseModel):
    config_id: str = Field(default_factory=lambda: generate_id("prj"))
    name: str
    description: str = ""
    template_id: str
    author: str = ""
    email: str = ""
    version: str = "0.1.0"
    variables: Dict[str, Any] = Field(default_factory=dict)
    output_dir: str
    overwrite: bool = False
    created_at: datetime = Field(default_factory=utc_now)


class GenerationResult(BaseModel):
    success: bool
    project_name: str
    output_dir: str
    files_generated: int
    files_skipped: int
    errors: List[str] = Field(default_factory=list)
    warnings: List[str] = Field(default_factory=list)
    duration_ms: float = 0.0
