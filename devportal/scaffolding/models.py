"""Database models for the scaffolding module."""
from __future__ import annotations

from typing import Optional

from sqlalchemy import JSON, Boolean, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from devportal.core.database import Base
from devportal.core.models import CoreEntity, RunInstance, generate_id


class Template(CoreEntity):
    """Represents a project template."""

    __tablename__ = "scaffold_templates"

    name: Mapped[str] = mapped_column(String(255), nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=True)
    category: Mapped[str] = mapped_column(String(100), nullable=False)  # web, service, library, cli
    language: Mapped[str] = mapped_column(String(50), nullable=False)  # python, go, java, etc.
    version: Mapped[str] = mapped_column(String(50), default="1.0.0")
    author: Mapped[str] = mapped_column(String(255), nullable=True)
    icon: Mapped[Optional[str]] = mapped_column(String(1024), nullable=True)
    tags: Mapped[dict] = mapped_column(JSON, nullable=False, default=list)
    variables: Mapped[dict] = mapped_column(JSON, nullable=False, default=list)
    files: Mapped[dict] = mapped_column(JSON, nullable=False, default=list)
    post_generation_commands: Mapped[dict] = mapped_column(JSON, nullable=False, default=list)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    is_public: Mapped[bool] = mapped_column(Boolean, default=True)
    owner_id: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    documentation_url: Mapped[Optional[str]] = mapped_column(String(2048), nullable=True)
    repository_url: Mapped[Optional[str]] = mapped_column(String(2048), nullable=True)

    versions: Mapped[list["TemplateVersion"]] = relationship(
        "TemplateVersion", back_populates="template", cascade="all, delete-orphan"
    )
    parameters: Mapped[list["TemplateParameter"]] = relationship(
        "TemplateParameter", back_populates="template", cascade="all, delete-orphan"
    )
    jobs: Mapped[list["ScaffoldJob"]] = relationship(
        "ScaffoldJob", back_populates="template", cascade="all, delete-orphan"
    )

    __table_args__ = (
        UniqueConstraint("name", "version", name="uix_template_name_version"),
    )


class TemplateVersion(CoreEntity):
    """Represents a version of a template."""

    __tablename__ = "scaffold_template_versions"

    template_id: Mapped[str] = mapped_column(String(50), ForeignKey("scaffold_templates.id"), nullable=False)
    version: Mapped[str] = mapped_column(String(50), nullable=False)
    changelog: Mapped[str] = mapped_column(Text, nullable=True)
    files: Mapped[dict] = mapped_column(JSON, nullable=False, default=list)
    variables: Mapped[dict] = mapped_column(JSON, nullable=False, default=list)
    released_at: Mapped[DateTime] = mapped_column(DateTime, nullable=False)

    template: Mapped[Template] = relationship("Template", back_populates="versions")

    __table_args__ = (
        UniqueConstraint("template_id", "version", name="uix_template_version"),
    )


class TemplateParameter(CoreEntity):
    """Represents a parameter for a template."""

    __tablename__ = "scaffold_template_parameters"

    template_id: Mapped[str] = mapped_column(String(50), ForeignKey("scaffold_templates.id"), nullable=False)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=True)
    param_type: Mapped[str] = mapped_column(String(50), nullable=False)  # string, int, bool, choice, list
    required: Mapped[bool] = mapped_column(Boolean, default=True)
    default_value: Mapped[Optional[dict]] = mapped_column(JSON, nullable=True)
    choices: Mapped[Optional[dict]] = mapped_column(JSON, nullable=True)
    validation: Mapped[Optional[dict]] = mapped_column(JSON, nullable=True)
    category: Mapped[str] = mapped_column(String(100), default="general")
    order: Mapped[int] = mapped_column(Integer, default=0)

    template: Mapped[Template] = relationship("Template", back_populates="parameters")

    __table_args__ = (
        UniqueConstraint("template_id", "name", name="uix_template_parameter_name"),
    )


class ScaffoldJob(RunInstance):
    """Represents a scaffold generation job."""

    __tablename__ = "scaffold_jobs"

    template_id: Mapped[str] = mapped_column(String(50), ForeignKey("scaffold_templates.id"), nullable=False)
    template_version: Mapped[str] = mapped_column(String(50), nullable=False)
    project_name: Mapped[str] = mapped_column(String(255), nullable=False)
    project_description: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    output_path: Mapped[str] = mapped_column(String(2048), nullable=False)
    parameters: Mapped[dict] = mapped_column(JSON, nullable=False, default=dict)
    generated_files: Mapped[dict] = mapped_column(JSON, nullable=False, default=list)
    error_details: Mapped[Optional[dict]] = mapped_column(JSON, nullable=True)
    created_by: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    archive_url: Mapped[Optional[str]] = mapped_column(String(2048), nullable=True)

    template: Mapped[Template] = relationship("Template", back_populates="jobs")


class GeneratedProject(CoreEntity):
    """Represents a generated project."""

    __tablename__ = "scaffold_generated_projects"

    template_id: Mapped[str] = mapped_column(String(50), ForeignKey("scaffold_templates.id"), nullable=False)
    template_version: Mapped[str] = mapped_column(String(50), nullable=False)
    project_name: Mapped[str] = mapped_column(String(255), nullable=False)
    job_id: Mapped[str] = mapped_column(String(50), nullable=False)
    output_path: Mapped[str] = mapped_column(String(2048), nullable=False)
    parameters: Mapped[dict] = mapped_column(JSON, nullable=False, default=dict)
    repository_url: Mapped[Optional[str]] = mapped_column(String(2048), nullable=True)
    deployed_url: Mapped[Optional[str]] = mapped_column(String(2048), nullable=True)
    created_by: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)

    template: Mapped[Template] = relationship("Template")
