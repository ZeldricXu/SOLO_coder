"""Pydantic schemas for the scaffolding module."""
from __future__ import annotations

from datetime import datetime
from typing import Any, Optional

from pydantic import BaseModel, Field, field_validator

from devportal.core.schemas import Entity, PaginatedResponse


class TemplateParameterBase(BaseModel):
    name: str = Field(..., min_length=1, max_length=255)
    description: Optional[str] = None
    param_type: str = Field(..., pattern="^(string|int|bool|choice|list)$")
    required: bool = True
    default_value: Optional[Any] = None
    choices: Optional[list[dict[str, Any]]] = None
    validation: Optional[dict[str, Any]] = None
    category: str = "general"
    order: int = 0

    @field_validator("choices")
    @classmethod
    def validate_choices(cls, v: Optional[list[dict[str, Any]]]) -> Optional[list[dict[str, Any]]]:
        if v is not None:
            for choice in v:
                if "label" not in choice or "value" not in choice:
                    raise ValueError("Each choice must have 'label' and 'value'")
        return v


class TemplateParameterCreate(TemplateParameterBase):
    pass


class TemplateParameterResponse(Entity, TemplateParameterBase):
    template_id: str

    class Config:
        from_attributes = True


class TemplateFile(BaseModel):
    path: str = Field(..., min_length=1, max_length=1024)
    content: str = Field(..., min_length=1)
    is_template: bool = True
    executable: bool = False


class TemplateVariable(BaseModel):
    name: str = Field(..., min_length=1, max_length=255)
    description: Optional[str] = None
    default_value: Optional[Any] = None


class PostGenerationCommand(BaseModel):
    command: str = Field(..., min_length=1)
    description: Optional[str] = None
    working_directory: Optional[str] = None
    required: bool = True


class TemplateBase(BaseModel):
    name: str = Field(..., min_length=1, max_length=255)
    description: Optional[str] = None
    category: str = Field(..., pattern="^(web|service|library|cli)$")
    language: str = Field(..., min_length=1, max_length=50)
    version: str = Field("1.0.0", min_length=1, max_length=50)
    author: Optional[str] = None
    icon: Optional[str] = None
    tags: list[str] = Field(default_factory=list)
    variables: list[TemplateVariable] = Field(default_factory=list)
    files: list[TemplateFile] = Field(default_factory=list)
    post_generation_commands: list[PostGenerationCommand] = Field(default_factory=list)
    enabled: bool = True
    is_public: bool = True
    documentation_url: Optional[str] = None
    repository_url: Optional[str] = None

    @field_validator("tags")
    @classmethod
    def validate_tags(cls, v: list[str]) -> list[str]:
        return [tag.strip() for tag in v if tag.strip()]


class TemplateCreate(TemplateBase):
    parameters: list[TemplateParameterCreate] = Field(default_factory=list)


class TemplateUpdate(BaseModel):
    name: Optional[str] = Field(None, min_length=1, max_length=255)
    description: Optional[str] = None
    category: Optional[str] = Field(None, pattern="^(web|service|library|cli)$")
    language: Optional[str] = Field(None, min_length=1, max_length=50)
    version: Optional[str] = Field(None, min_length=1, max_length=50)
    author: Optional[str] = None
    icon: Optional[str] = None
    tags: Optional[list[str]] = None
    variables: Optional[list[TemplateVariable]] = None
    files: Optional[list[TemplateFile]] = None
    post_generation_commands: Optional[list[PostGenerationCommand]] = None
    enabled: Optional[bool] = None
    is_public: Optional[bool] = None
    documentation_url: Optional[str] = None
    repository_url: Optional[str] = None


class TemplateResponse(Entity, TemplateBase):
    owner_id: Optional[str] = None
    usage_count: int = 0
    parameters: list[TemplateParameterResponse] = Field(default_factory=list)

    class Config:
        from_attributes = True


class TemplateVersionBase(BaseModel):
    version: str = Field(..., min_length=1, max_length=50)
    changelog: Optional[str] = None
    files: list[TemplateFile] = Field(default_factory=list)
    variables: list[TemplateVariable] = Field(default_factory=list)


class TemplateVersionCreate(TemplateVersionBase):
    template_id: str = Field(..., min_length=1, max_length=50)


class TemplateVersionResponse(Entity, TemplateVersionBase):
    template_id: str
    released_at: datetime

    class Config:
        from_attributes = True


class ScaffoldRequest(BaseModel):
    template_id: str = Field(..., min_length=1, max_length=50)
    template_version: Optional[str] = None
    project_name: str = Field(..., min_length=1, max_length=255)
    project_description: Optional[str] = None
    output_path: Optional[str] = None
    parameters: dict[str, Any] = Field(default_factory=dict)
    run_post_commands: bool = True
    create_archive: bool = False

    @field_validator("project_name")
    @classmethod
    def validate_project_name(cls, v: str) -> str:
        if not v.replace("-", "_").replace(" ", "_").isalnum():
            raise ValueError("Project name must be alphanumeric with hyphens or underscores")
        return v.strip()


class ScaffoldResponse(BaseModel):
    job_id: str
    status: str
    message: str
    project_name: str
    output_path: Optional[str] = None


class ScaffoldJobResponse(Entity):
    template_id: str
    template_version: str
    template_name: Optional[str] = None
    project_name: str
    project_description: Optional[str] = None
    output_path: str
    phase: str
    progress: float
    started_at: datetime
    completed_at: Optional[datetime] = None
    error_detail: Optional[str] = None
    parameters: dict[str, Any] = Field(default_factory=dict)
    generated_files: list[str] = Field(default_factory=list)
    error_details: Optional[dict[str, Any]] = None
    created_by: Optional[str] = None
    archive_url: Optional[str] = None

    class Config:
        from_attributes = True


class GeneratedProjectResponse(Entity):
    template_id: str
    template_version: str
    template_name: Optional[str] = None
    project_name: str
    job_id: str
    output_path: str
    parameters: dict[str, Any] = Field(default_factory=dict)
    repository_url: Optional[str] = None
    deployed_url: Optional[str] = None
    created_by: Optional[str] = None

    class Config:
        from_attributes = True


class InteractivePrompt(BaseModel):
    parameter_name: str
    prompt: str
    param_type: str
    required: bool
    default_value: Optional[Any] = None
    choices: Optional[list[dict[str, Any]]] = None
    validation: Optional[dict[str, Any]] = None


class InteractiveSessionCreate(BaseModel):
    template_id: str = Field(..., min_length=1, max_length=50)
    template_version: Optional[str] = None


class InteractiveSessionResponse(BaseModel):
    session_id: str
    template_id: str
    template_name: str
    current_prompt: Optional[InteractivePrompt] = None
    collected_parameters: dict[str, Any] = Field(default_factory=dict)
    remaining_prompts: int = 0
    total_prompts: int = 0
    is_complete: bool = False


class InteractiveAnswer(BaseModel):
    session_id: str
    parameter_name: str
    value: Any

    @field_validator("value")
    @classmethod
    def validate_value(cls, v: Any) -> Any:
        if v is None:
            raise ValueError("Value cannot be None")
        return v


class TemplateSearchRequest(BaseModel):
    query: Optional[str] = None
    category: Optional[str] = None
    language: Optional[str] = None
    tags: Optional[list[str]] = None
    enabled_only: bool = True
    public_only: bool = True


# Paginated responses
class PaginatedTemplates(PaginatedResponse[TemplateResponse]):
    pass


class PaginatedTemplateVersions(PaginatedResponse[TemplateVersionResponse]):
    pass


class PaginatedScaffoldJobs(PaginatedResponse[ScaffoldJobResponse]):
    pass


class PaginatedGeneratedProjects(PaginatedResponse[GeneratedProjectResponse]):
    pass


class PaginatedParameters(PaginatedResponse[TemplateParameterResponse]):
    pass
