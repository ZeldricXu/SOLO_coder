"""Services for the scaffolding module."""
from __future__ import annotations

import asyncio
import logging
import os
import re
import shutil
import subprocess
import tarfile
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

from jinja2 import Environment, FileSystemLoader, StrictUndefined, Template as JinjaTemplate
from jinja2.exceptions import TemplateError, UndefinedError
from sqlalchemy import and_, delete, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from devportal.core.config import settings
from devportal.core.exceptions import ConflictError, NotFoundError, ValidationError
from devportal.core.models import User
from devportal.core.utils import generate_id, processing_context, sanitize_filename, utc_now
from devportal.scaffolding.models import (
    GeneratedProject,
    ScaffoldJob,
    Template,
    TemplateParameter,
    TemplateVersion,
)
from devportal.scaffolding.schemas import (
    InteractiveAnswer,
    InteractivePrompt,
    InteractiveSessionCreate,
    InteractiveSessionResponse,
    ScaffoldRequest,
    ScaffoldResponse,
    TemplateCreate,
    TemplateParameterCreate,
    TemplateSearchRequest,
    TemplateUpdate,
    TemplateVersionCreate,
)

logger = logging.getLogger(__name__)


class TemplateRenderer:
    """Renders Jinja2 templates for project scaffolding."""

    def __init__(self, template_dir: str = settings.template_dir):
        self.template_dir = Path(template_dir)
        self.template_dir.mkdir(parents=True, exist_ok=True)
        self.env = Environment(
            loader=FileSystemLoader(str(self.template_dir)),
            undefined=StrictUndefined,
            keep_trailing_newline=True,
            trim_blocks=False,
            lstrip_blocks=False,
        )
        self._register_filters()

    def _register_filters(self) -> None:
        """Register custom Jinja2 filters."""
        self.env.filters["camel_case"] = self._camel_case
        self.env.filters["pascal_case"] = self._pascal_case
        self.env.filters["snake_case"] = self._snake_case
        self.env.filters["kebab_case"] = self._kebab_case
        self.env.filters["upper_first"] = self._upper_first
        self.env.filters["lower_first"] = self._lower_first
        self.env.filters["slugify"] = self._slugify

    def _camel_case(self, s: str) -> str:
        """Convert string to camelCase."""
        s = self._snake_case(s)
        parts = s.split("_")
        return parts[0] + "".join(p.capitalize() for p in parts[1:])

    def _pascal_case(self, s: str) -> str:
        """Convert string to PascalCase."""
        return "".join(p.capitalize() for p in self._snake_case(s).split("_"))

    def _snake_case(self, s: str) -> str:
        """Convert string to snake_case."""
        s = re.sub(r"[\s\-]+", "_", s)
        s = re.sub(r"(?<!^)(?=[A-Z])", "_", s)
        return s.lower()

    def _kebab_case(self, s: str) -> str:
        """Convert string to kebab-case."""
        return self._snake_case(s).replace("_", "-")

    def _upper_first(self, s: str) -> str:
        """Capitalize first letter."""
        return s[0].upper() + s[1:] if s else s

    def _lower_first(self, s: str) -> str:
        """Lowercase first letter."""
        return s[0].lower() + s[1:] if s else s

    def _slugify(self, s: str) -> str:
        """Convert string to URL-safe slug."""
        s = s.lower().strip()
        s = re.sub(r"[^\w\s-]", "", s)
        s = re.sub(r"[\s_-]+", "-", s)
        return s.strip("-")

    def render_string(self, template_str: str, context: dict[str, Any]) -> str:
        """Render a template string."""
        try:
            template = self.env.from_string(template_str)
            return template.render(**context)
        except UndefinedError as e:
            raise ValidationError(f"Template variable undefined: {e}")
        except TemplateError as e:
            raise ValidationError(f"Template error: {e}")

    def render_file(self, file_path: str, context: dict[str, Any]) -> str:
        """Render a template file from the template directory."""
        try:
            template = self.env.get_template(file_path)
            return template.render(**context)
        except UndefinedError as e:
            raise ValidationError(f"Template variable undefined: {e}")
        except TemplateError as e:
            raise ValidationError(f"Template error: {e}")


class TemplateService:
    """Service for managing templates."""

    def __init__(self, db: AsyncSession):
        self.db = db
        self.renderer = TemplateRenderer()

    async def list_templates(
        self,
        skip: int = 0,
        limit: int = 50,
        search_request: Optional[TemplateSearchRequest] = None,
        user: Optional[User] = None,
    ) -> tuple[list[Template], int]:
        """List templates with pagination and filtering."""
        stmt = select(Template).options(
            selectinload(Template.parameters),
            selectinload(Template.versions),
            selectinload(Template.jobs),
        )

        if search_request:
            if search_request.query:
                query = f"%{search_request.query}%"
                stmt = stmt.where(
                    or_(
                        Template.name.ilike(query),
                        Template.description.ilike(query),
                        Template.tags.op("->")(func.concat("$[", func.row_number().over(), "]")).ilike(query),
                    )
                )
            if search_request.category:
                stmt = stmt.where(Template.category == search_request.category)
            if search_request.language:
                stmt = stmt.where(Template.language == search_request.language)
            if search_request.tags:
                for tag in search_request.tags:
                    stmt = stmt.where(Template.tags.op("->")(func.concat("$[", func.row_number().over(), "]")) == tag)
            if search_request.enabled_only:
                stmt = stmt.where(Template.enabled == True)
            if search_request.public_only and (not user or user.role != "admin"):
                stmt = stmt.where(
                    or_(
                        Template.is_public == True,
                        Template.owner_id == user.id if user else False,
                    )
                )

        stmt = stmt.order_by(Template.updated_at.desc()).offset(skip).limit(limit)
        result = await self.db.execute(stmt)
        templates = result.scalars().all()

        count_stmt = select(func.count()).select_from(Template)
        if search_request:
            if search_request.query:
                query = f"%{search_request.query}%"
                count_stmt = count_stmt.where(
                    or_(
                        Template.name.ilike(query),
                        Template.description.ilike(query),
                    )
                )
            if search_request.category:
                count_stmt = count_stmt.where(Template.category == search_request.category)
            if search_request.language:
                count_stmt = count_stmt.where(Template.language == search_request.language)
            if search_request.enabled_only:
                count_stmt = count_stmt.where(Template.enabled == True)
            if search_request.public_only and (not user or user.role != "admin"):
                count_stmt = count_stmt.where(
                    or_(
                        Template.is_public == True,
                        Template.owner_id == user.id if user else False,
                    )
                )

        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        return list(templates), total

    async def get_template(self, template_id: str, user: Optional[User] = None) -> Template:
        """Get a template by ID."""
        stmt = (
            select(Template)
            .where(Template.id == template_id)
            .options(
                selectinload(Template.parameters),
                selectinload(Template.versions),
                selectinload(Template.jobs),
            )
        )
        result = await self.db.execute(stmt)
        template = result.scalar_one_or_none()
        if not template:
            raise NotFoundError(f"Template not found: {template_id}")

        if not template.is_public and (not user or (user.role != "admin" and template.owner_id != user.id)):
            raise NotFoundError(f"Template not found: {template_id}")

        if not template.enabled and (not user or user.role != "admin"):
            raise NotFoundError(f"Template not found: {template_id}")

        return template

    async def create_template(self, template_data: TemplateCreate, user: Optional[User] = None) -> Template:
        """Create a new template."""
        stmt = select(Template).where(
            and_(
                Template.name == template_data.name,
                Template.version == template_data.version,
            )
        )
        result = await self.db.execute(stmt)
        if result.scalar_one_or_none():
            raise ConflictError(
                f"Template already exists with name '{template_data.name}' and version '{template_data.version}'"
            )

        now = utc_now()
        template = Template(
            id=generate_id("tpl"),
            owner_id=user.id if user else None,
            created_at=now,
            updated_at=now,
            **template_data.model_dump(exclude={"parameters"}),
        )
        self.db.add(template)

        for param_data in template_data.parameters:
            parameter = TemplateParameter(
                id=generate_id("prm"),
                template_id=template.id,
                created_at=now,
                updated_at=now,
                **param_data.model_dump(),
            )
            self.db.add(parameter)

        await self.db.commit()
        await self.db.refresh(template)
        return template

    async def update_template(
        self, template_id: str, template_data: TemplateUpdate, user: Optional[User] = None
    ) -> Template:
        """Update a template."""
        template = await self.get_template(template_id, user)

        if user and user.role != "admin" and template.owner_id != user.id:
            raise ValidationError("You don't have permission to update this template")

        update_data = template_data.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(template, key, value)
        template.updated_at = utc_now()

        await self.db.commit()
        await self.db.refresh(template)
        return template

    async def delete_template(self, template_id: str, user: Optional[User] = None) -> None:
        """Delete a template."""
        template = await self.get_template(template_id, user)

        if user and user.role != "admin" and template.owner_id != user.id:
            raise ValidationError("You don't have permission to delete this template")

        await self.db.delete(template)
        await self.db.commit()

    async def add_parameter(
        self, template_id: str, param_data: TemplateParameterCreate, user: Optional[User] = None
    ) -> TemplateParameter:
        """Add a parameter to a template."""
        template = await self.get_template(template_id, user)

        if user and user.role != "admin" and template.owner_id != user.id:
            raise ValidationError("You don't have permission to modify this template")

        existing = await self.db.execute(
            select(TemplateParameter).where(
                TemplateParameter.template_id == template_id,
                TemplateParameter.name == param_data.name,
            )
        )
        if existing.scalar_one_or_none():
            raise ConflictError(f"Parameter already exists: {param_data.name}")

        now = utc_now()
        parameter = TemplateParameter(
            id=generate_id("prm"),
            template_id=template_id,
            created_at=now,
            updated_at=now,
            **param_data.model_dump(),
        )
        self.db.add(parameter)
        await self.db.commit()
        await self.db.refresh(parameter)
        return parameter

    async def remove_parameter(self, template_id: str, param_id: str, user: Optional[User] = None) -> None:
        """Remove a parameter from a template."""
        template = await self.get_template(template_id, user)

        if user and user.role != "admin" and template.owner_id != user.id:
            raise ValidationError("You don't have permission to modify this template")

        stmt = select(TemplateParameter).where(
            TemplateParameter.id == param_id,
            TemplateParameter.template_id == template_id,
        )
        result = await self.db.execute(stmt)
        parameter = result.scalar_one_or_none()
        if not parameter:
            raise NotFoundError(f"Parameter not found: {param_id}")

        await self.db.delete(parameter)
        await self.db.commit()

    async def create_version(self, version_data: TemplateVersionCreate, user: Optional[User] = None) -> TemplateVersion:
        """Create a new version of a template."""
        template = await self.get_template(version_data.template_id, user)

        if user and user.role != "admin" and template.owner_id != user.id:
            raise ValidationError("You don't have permission to version this template")

        existing = await self.db.execute(
            select(TemplateVersion).where(
                TemplateVersion.template_id == version_data.template_id,
                TemplateVersion.version == version_data.version,
            )
        )
        if existing.scalar_one_or_none():
            raise ConflictError(f"Version already exists: {version_data.version}")

        now = utc_now()
        version = TemplateVersion(
            id=generate_id("ver"),
            released_at=now,
            created_at=now,
            updated_at=now,
            **version_data.model_dump(),
        )
        self.db.add(version)
        await self.db.commit()
        await self.db.refresh(version)
        return version

    async def validate_parameters(
        self, template: Template, parameters: dict[str, Any]
    ) -> tuple[bool, list[str]]:
        """Validate template parameters."""
        errors: list[str] = []

        param_map = {p.name: p for p in template.parameters}

        for param in template.parameters:
            if param.required and param.name not in parameters:
                if param.default_value is None:
                    errors.append(f"Required parameter missing: {param.name}")
                continue

            if param.name not in parameters:
                continue

            value = parameters[param.name]

            if param.param_type == "string" and not isinstance(value, str):
                errors.append(f"Parameter {param.name} must be a string")
            elif param.param_type == "int" and not isinstance(value, int):
                errors.append(f"Parameter {param.name} must be an integer")
            elif param.param_type == "bool" and not isinstance(value, bool):
                errors.append(f"Parameter {param.name} must be a boolean")
            elif param.param_type == "choice" and param.choices:
                valid_values = [c["value"] for c in param.choices]
                if value not in valid_values:
                    errors.append(
                        f"Parameter {param.name} must be one of: {', '.join(map(str, valid_values))}"
                    )
            elif param.param_type == "list" and not isinstance(value, list):
                errors.append(f"Parameter {param.name} must be a list")

            if param.validation:
                if "min_length" in param.validation and isinstance(value, str):
                    if len(value) < param.validation["min_length"]:
                        errors.append(
                            f"Parameter {param.name} must be at least {param.validation['min_length']} characters"
                        )
                if "max_length" in param.validation and isinstance(value, str):
                    if len(value) > param.validation["max_length"]:
                        errors.append(
                            f"Parameter {param.name} must be at most {param.validation['max_length']} characters"
                        )
                if "pattern" in param.validation and isinstance(value, str):
                    if not re.match(param.validation["pattern"], value):
                        errors.append(
                            f"Parameter {param.name} does not match pattern: {param.validation['pattern']}"
                        )
                if "minimum" in param.validation and isinstance(value, (int, float)):
                    if value < param.validation["minimum"]:
                        errors.append(
                            f"Parameter {param.name} must be at least {param.validation['minimum']}"
                        )
                if "maximum" in param.validation and isinstance(value, (int, float)):
                    if value > param.validation["maximum"]:
                        errors.append(
                            f"Parameter {param.name} must be at most {param.validation['maximum']}"
                        )

        return len(errors) == 0, errors

    def _build_context(
        self, template: Template, project_name: str, parameters: dict[str, Any]
    ) -> dict[str, Any]:
        """Build the rendering context."""
        context = {
            "project_name": project_name,
            "project_name_safe": sanitize_filename(project_name),
            "template_name": template.name,
            "template_version": template.version,
            "template_category": template.category,
            "template_language": template.language,
            "generated_at": utc_now().isoformat(),
            "year": datetime.now().year,
        }

        param_map = {p.name: p for p in template.parameters}
        for param in template.parameters:
            if param.name in parameters:
                context[param.name] = parameters[param.name]
            elif param.default_value is not None:
                context[param.name] = param.default_value

        for var in template.variables:
            if var.name not in context:
                context[var.name] = var.default_value

        return context


class InteractiveSessionManager:
    """Manages interactive Q&A sessions for template parameter collection."""

    def __init__(self, db: AsyncSession):
        self.db = db
        self.template_service = TemplateService(db)
        self._sessions: dict[str, dict[str, Any]] = {}

    async def create_session(
        self, session_data: InteractiveSessionCreate, user: Optional[User] = None
    ) -> InteractiveSessionResponse:
        """Create a new interactive session."""
        template = await self.template_service.get_template(session_data.template_id, user)

        session_id = generate_id("ses")
        sorted_params = sorted(template.parameters, key=lambda p: (p.order, p.name))

        self._sessions[session_id] = {
            "template_id": template.id,
            "template_name": template.name,
            "parameters": sorted_params,
            "current_index": 0,
            "collected": {},
            "created_at": utc_now(),
        }

        return self._build_response(session_id)

    async def answer_question(
        self, answer: InteractiveAnswer, user: Optional[User] = None
    ) -> InteractiveSessionResponse:
        """Process an answer in an interactive session."""
        session = self._sessions.get(answer.session_id)
        if not session:
            raise NotFoundError(f"Session not found: {answer.session_id}")

        template = await self.template_service.get_template(session["template_id"], user)
        param_map = {p.name: p for p in template.parameters}
        param = param_map.get(answer.parameter_name)

        if not param:
            raise ValidationError(f"Unknown parameter: {answer.parameter_name}")

        current_param = session["parameters"][session["current_index"]]
        if current_param.name != answer.parameter_name:
            raise ValidationError(
                f"Expected answer for: {current_param.name}, got: {answer.parameter_name}"
            )

        valid, errors = await self.template_service.validate_parameters(
            template, {answer.parameter_name: answer.value}
        )
        if not valid:
            raise ValidationError("; ".join(errors))

        session["collected"][answer.parameter_name] = answer.value
        session["current_index"] += 1

        return self._build_response(answer.session_id)

    async def get_session(self, session_id: str) -> InteractiveSessionResponse:
        """Get the current state of an interactive session."""
        if session_id not in self._sessions:
            raise NotFoundError(f"Session not found: {session_id}")
        return self._build_response(session_id)

    async def complete_session(self, session_id: str) -> dict[str, Any]:
        """Complete an interactive session and return collected parameters."""
        session = self._sessions.get(session_id)
        if not session:
            raise NotFoundError(f"Session not found: {session_id}")

        if session["current_index"] < len(session["parameters"]):
            raise ValidationError("Session is not complete")

        result = {
            "template_id": session["template_id"],
            "template_name": session["template_name"],
            "parameters": session["collected"],
        }

        del self._sessions[session_id]
        return result

    def _build_response(self, session_id: str) -> InteractiveSessionResponse:
        """Build a session response."""
        session = self._sessions[session_id]
        total = len(session["parameters"])
        current = session["current_index"]
        is_complete = current >= total

        current_prompt: Optional[InteractivePrompt] = None
        if not is_complete:
            param = session["parameters"][current]
            current_prompt = InteractivePrompt(
                parameter_name=param.name,
                prompt=param.description or f"Enter value for {param.name}",
                param_type=param.param_type,
                required=param.required,
                default_value=param.default_value,
                choices=param.choices,
                validation=param.validation,
            )

        return InteractiveSessionResponse(
            session_id=session_id,
            template_id=session["template_id"],
            template_name=session["template_name"],
            current_prompt=current_prompt,
            collected_parameters=session["collected"],
            remaining_prompts=total - current,
            total_prompts=total,
            is_complete=is_complete,
        )


class ProjectGenerator:
    """Generates projects from templates."""

    def __init__(self, db: AsyncSession):
        self.db = db
        self.template_service = TemplateService(db)
        self.renderer = TemplateRenderer()

    async def generate_project(self, request: ScaffoldRequest, user: Optional[User] = None) -> ScaffoldResponse:
        """Generate a project from a template."""
        template = await self.template_service.get_template(request.template_id, user)

        if not template.enabled:
            raise ValidationError(f"Template {template.id} is disabled")

        valid, errors = await self.template_service.validate_parameters(template, request.parameters)
        if not valid:
            raise ValidationError("Parameter validation failed: " + "; ".join(errors))

        output_path = request.output_path or f"./generated/{sanitize_filename(request.project_name)}"
        output_path = Path(output_path).resolve()

        if output_path.exists() and any(output_path.iterdir()):
            raise ConflictError(f"Output path already exists and is not empty: {output_path}")

        now = utc_now()
        job = ScaffoldJob(
            id=generate_id("job"),
            entity_id=template.id,
            phase="pending",
            progress=0.0,
            started_at=now,
            template_id=template.id,
            template_version=request.template_version or template.version,
            project_name=request.project_name,
            project_description=request.project_description,
            output_path=str(output_path),
            parameters=request.parameters,
            created_by=user.id if user else None,
            created_at=now,
            updated_at=now,
        )
        self.db.add(job)
        await self.db.commit()
        await self.db.refresh(job)

        asyncio.create_task(self._execute_generation(job.id, request.run_post_commands, request.create_archive))

        return ScaffoldResponse(
            job_id=job.id,
            status="started",
            message=f"Project generation started for {request.project_name}",
            project_name=request.project_name,
            output_path=str(output_path),
        )

    async def _execute_generation(
        self, job_id: str, run_post_commands: bool, create_archive: bool
    ) -> None:
        """Execute project generation asynchronously."""
        try:
            async with processing_context() as ctx:
                ctx.emit_event("scaffold.started", {"job_id": job_id})

                job = await self._get_job(job_id)
                job.phase = "running"
                job.progress = 0.0
                await self.db.commit()

                template = await self.template_service.get_template(job.template_id)
                context = self.template_service._build_context(
                    template, job.project_name, job.parameters
                )

                output_path = Path(job.output_path)
                output_path.mkdir(parents=True, exist_ok=True)

                generated_files: list[str] = []
                total_files = len(template.files)

                for i, file_template in enumerate(template.files):
                    try:
                        rendered_path = self.renderer.render_string(file_template.path, context)
                        full_path = output_path / rendered_path
                        full_path.parent.mkdir(parents=True, exist_ok=True)

                        if file_template.is_template:
                            content = self.renderer.render_string(file_template.content, context)
                        else:
                            content = file_template.content

                        full_path.write_text(content, encoding="utf-8")

                        if file_template.executable:
                            os.chmod(full_path, 0o755)

                        generated_files.append(str(full_path))
                        job.progress = min((i + 1) / total_files, 0.9)
                        if i % 10 == 0:
                            await self.db.commit()

                    except Exception as e:
                        logger.warning(f"Failed to generate file {file_template.path}: {e}")
                        raise

                job.generated_files = generated_files
                job.progress = 0.95
                await self.db.commit()

                if run_post_commands and template.post_generation_commands:
                    await self._run_post_commands(job, template, output_path)

                if create_archive:
                    archive_path = await self._create_archive(job, output_path)
                    job.archive_url = f"file://{archive_path}"

                job.phase = "completed"
                job.progress = 1.0
                job.completed_at = utc_now()
                await self.db.commit()

                generated_project = GeneratedProject(
                    id=generate_id("prj"),
                    template_id=template.id,
                    template_version=template.version,
                    project_name=job.project_name,
                    job_id=job.id,
                    output_path=job.output_path,
                    parameters=job.parameters,
                    created_by=job.created_by,
                    created_at=utc_now(),
                    updated_at=utc_now(),
                )
                self.db.add(generated_project)
                await self.db.commit()

                ctx.emit_event(
                    "scaffold.completed",
                    {
                        "job_id": job_id,
                        "files_generated": len(generated_files),
                        "output_path": job.output_path,
                    },
                )

        except Exception as e:
            logger.exception(f"Scaffold job {job_id} failed")
            try:
                job = await self._get_job(job_id)
                job.phase = "failed"
                job.error_detail = str(e)
                job.error_details = {"error": str(e), "type": type(e).__name__}
                job.completed_at = utc_now()
                await self.db.commit()
            except Exception:
                logger.exception(f"Failed to update job {job_id} status")

    async def _get_job(self, job_id: str) -> ScaffoldJob:
        """Get a scaffold job by ID."""
        stmt = select(ScaffoldJob).where(ScaffoldJob.id == job_id)
        result = await self.db.execute(stmt)
        job = result.scalar_one_or_none()
        if not job:
            raise NotFoundError(f"Scaffold job not found: {job_id}")
        return job

    async def _run_post_commands(
        self, job: ScaffoldJob, template: Template, output_path: Path
    ) -> None:
        """Run post-generation commands."""
        for cmd_info in template.post_generation_commands:
            try:
                working_dir = output_path / cmd_info.working_directory if cmd_info.working_directory else output_path
                result = subprocess.run(
                    cmd_info.command,
                    shell=True,
                    cwd=str(working_dir),
                    capture_output=True,
                    text=True,
                    timeout=300,
                )
                if result.returncode != 0 and cmd_info.required:
                    raise ValidationError(
                        f"Post-generation command failed: {cmd_info.command}\n{result.stderr}"
                    )
            except subprocess.TimeoutExpired:
                if cmd_info.required:
                    raise ValidationError(f"Post-generation command timed out: {cmd_info.command}")
            except Exception as e:
                if cmd_info.required:
                    raise ValidationError(f"Post-generation command failed: {e}")

    async def _create_archive(self, job: ScaffoldJob, output_path: Path) -> Path:
        """Create a tar.gz archive of the generated project."""
        archive_name = f"{sanitize_filename(job.project_name)}.tar.gz"
        archive_path = output_path.parent / archive_name

        with tarfile.open(archive_path, "w:gz") as tar:
            tar.add(output_path, arcname=output_path.name)

        return archive_path


class ScaffoldJobService:
    """Service for managing scaffold jobs."""

    def __init__(self, db: AsyncSession):
        self.db = db

    async def list_jobs(
        self,
        skip: int = 0,
        limit: int = 50,
        template_id: Optional[str] = None,
        user: Optional[User] = None,
    ) -> tuple[list[ScaffoldJob], int]:
        """List scaffold jobs with pagination."""
        stmt = select(ScaffoldJob).options(selectinload(ScaffoldJob.template))

        if template_id:
            stmt = stmt.where(ScaffoldJob.template_id == template_id)

        if user and user.role != "admin":
            stmt = stmt.where(ScaffoldJob.created_by == user.id)

        stmt = stmt.order_by(ScaffoldJob.created_at.desc()).offset(skip).limit(limit)
        result = await self.db.execute(stmt)
        jobs = result.scalars().all()

        count_stmt = select(func.count()).select_from(ScaffoldJob)
        if template_id:
            count_stmt = count_stmt.where(ScaffoldJob.template_id == template_id)
        if user and user.role != "admin":
            count_stmt = count_stmt.where(ScaffoldJob.created_by == user.id)

        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        return list(jobs), total

    async def get_job(self, job_id: str, user: Optional[User] = None) -> ScaffoldJob:
        """Get a scaffold job by ID."""
        stmt = (
            select(ScaffoldJob)
            .where(ScaffoldJob.id == job_id)
            .options(selectinload(ScaffoldJob.template))
        )
        result = await self.db.execute(stmt)
        job = result.scalar_one_or_none()
        if not job:
            raise NotFoundError(f"Scaffold job not found: {job_id}")

        if user and user.role != "admin" and job.created_by != user.id:
            raise NotFoundError(f"Scaffold job not found: {job_id}")

        return job

    async def list_generated_projects(
        self,
        skip: int = 0,
        limit: int = 50,
        template_id: Optional[str] = None,
        user: Optional[User] = None,
    ) -> tuple[list[GeneratedProject], int]:
        """List generated projects with pagination."""
        stmt = select(GeneratedProject).options(selectinload(GeneratedProject.template))

        if template_id:
            stmt = stmt.where(GeneratedProject.template_id == template_id)

        if user and user.role != "admin":
            stmt = stmt.where(GeneratedProject.created_by == user.id)

        stmt = stmt.order_by(GeneratedProject.created_at.desc()).offset(skip).limit(limit)
        result = await self.db.execute(stmt)
        projects = result.scalars().all()

        count_stmt = select(func.count()).select_from(GeneratedProject)
        if template_id:
            count_stmt = count_stmt.where(GeneratedProject.template_id == template_id)
        if user and user.role != "admin":
            count_stmt = count_stmt.where(GeneratedProject.created_by == user.id)

        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        return list(projects), total
