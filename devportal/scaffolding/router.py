"""Router for the scaffolding module."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from devportal.core.database import get_db
from devportal.core.dependencies import PermissionChecker, get_current_user
from devportal.core.models import User
from devportal.core.schemas import APIResponse, PaginatedResponse
from devportal.scaffolding.schemas import (
    GeneratedProjectResponse,
    InteractiveAnswer,
    InteractiveSessionCreate,
    InteractiveSessionResponse,
    PaginatedGeneratedProjects,
    PaginatedScaffoldJobs,
    PaginatedTemplates,
    ScaffoldJobResponse,
    ScaffoldRequest,
    ScaffoldResponse,
    TemplateCreate,
    TemplateParameterCreate,
    TemplateParameterResponse,
    TemplateResponse,
    TemplateSearchRequest,
    TemplateUpdate,
    TemplateVersionCreate,
    TemplateVersionResponse,
)
from devportal.scaffolding.services import (
    InteractiveSessionManager,
    ProjectGenerator,
    ScaffoldJobService,
    TemplateService,
)

router = APIRouter(prefix="/scaffolding", tags=["scaffolding"])


@router.get("/templates", response_model=APIResponse[PaginatedTemplates])
async def list_templates(
    skip: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=100),
    query: str | None = Query(None),
    category: str | None = Query(None),
    language: str | None = Query(None),
    tags: list[str] | None = Query(None),
    enabled_only: bool = Query(True),
    public_only: bool = Query(True),
    db: AsyncSession = Depends(get_db),
    user: User | None = Depends(get_current_user),
):
    """List templates with filtering."""
    service = TemplateService(db)
    search_request = TemplateSearchRequest(
        query=query,
        category=category,
        language=language,
        tags=tags,
        enabled_only=enabled_only,
        public_only=public_only,
    )
    templates, total = await service.list_templates(skip, limit, search_request, user)
    return APIResponse(
        code=200,
        data=PaginatedResponse(
            items=[
                TemplateResponse(
                    **t.__dict__,
                    usage_count=len(t.jobs),
                    parameters=[TemplateParameterResponse(**p.__dict__) for p in t.parameters],
                )
                for t in templates
            ],
            total=total,
            skip=skip,
            limit=limit,
        ),
    )


@router.get("/templates/{template_id}", response_model=APIResponse[TemplateResponse])
async def get_template(
    template_id: str,
    db: AsyncSession = Depends(get_db),
    user: User | None = Depends(get_current_user),
):
    """Get a template by ID."""
    service = TemplateService(db)
    template = await service.get_template(template_id, user)
    return APIResponse(
        code=200,
        data=TemplateResponse(
            **template.__dict__,
            usage_count=len(template.jobs),
            parameters=[TemplateParameterResponse(**p.__dict__) for p in template.parameters],
        ),
    )


@router.post("/templates", response_model=APIResponse[TemplateResponse], status_code=201)
async def create_template(
    template_data: TemplateCreate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["scaffolding:manage"])),
):
    """Create a new template."""
    service = TemplateService(db)
    template = await service.create_template(template_data, user)
    return APIResponse(
        code=201,
        data=TemplateResponse(
            **template.__dict__,
            usage_count=len(template.jobs),
            parameters=[TemplateParameterResponse(**p.__dict__) for p in template.parameters],
        ),
    )


@router.put("/templates/{template_id}", response_model=APIResponse[TemplateResponse])
async def update_template(
    template_id: str,
    template_data: TemplateUpdate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["scaffolding:manage"])),
):
    """Update a template."""
    service = TemplateService(db)
    template = await service.update_template(template_id, template_data, user)
    return APIResponse(
        code=200,
        data=TemplateResponse(
            **template.__dict__,
            usage_count=len(template.jobs),
            parameters=[TemplateParameterResponse(**p.__dict__) for p in template.parameters],
        ),
    )


@router.delete("/templates/{template_id}", response_model=APIResponse[dict])
async def delete_template(
    template_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["scaffolding:manage"])),
):
    """Delete a template."""
    service = TemplateService(db)
    await service.delete_template(template_id, user)
    return APIResponse(code=200, data={"message": "Template deleted successfully"})


@router.post("/templates/{template_id}/parameters", response_model=APIResponse[TemplateParameterResponse], status_code=201)
async def add_parameter(
    template_id: str,
    param_data: TemplateParameterCreate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["scaffolding:manage"])),
):
    """Add a parameter to a template."""
    service = TemplateService(db)
    parameter = await service.add_parameter(template_id, param_data, user)
    return APIResponse(code=201, data=TemplateParameterResponse(**parameter.__dict__))


@router.delete("/templates/{template_id}/parameters/{param_id}", response_model=APIResponse[dict])
async def remove_parameter(
    template_id: str,
    param_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["scaffolding:manage"])),
):
    """Remove a parameter from a template."""
    service = TemplateService(db)
    await service.remove_parameter(template_id, param_id, user)
    return APIResponse(code=200, data={"message": "Parameter removed successfully"})


@router.post("/templates/{template_id}/versions", response_model=APIResponse[TemplateVersionResponse], status_code=201)
async def create_version(
    template_id: str,
    version_data: TemplateVersionCreate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["scaffolding:manage"])),
):
    """Create a new version of a template."""
    version_data.template_id = template_id
    service = TemplateService(db)
    version = await service.create_version(version_data, user)
    return APIResponse(code=201, data=TemplateVersionResponse(**version.__dict__))


@router.get("/templates/{template_id}/versions", response_model=APIResponse[list[TemplateVersionResponse]])
async def list_template_versions(
    template_id: str,
    db: AsyncSession = Depends(get_db),
    user: User | None = Depends(get_current_user),
):
    """List all versions of a template."""
    from sqlalchemy import select
    from devportal.scaffolding.models import TemplateVersion

    template_service = TemplateService(db)
    await template_service.get_template(template_id, user)

    stmt = select(TemplateVersion).where(TemplateVersion.template_id == template_id).order_by(TemplateVersion.released_at.desc())
    result = await db.execute(stmt)
    versions = result.scalars().all()

    return APIResponse(
        code=200,
        data=[TemplateVersionResponse(**v.__dict__) for v in versions],
    )


@router.post("/generate", response_model=APIResponse[ScaffoldResponse], status_code=201)
async def generate_project(
    request: ScaffoldRequest,
    db: AsyncSession = Depends(get_db),
    user: User | None = Depends(get_current_user),
):
    """Generate a project from a template."""
    generator = ProjectGenerator(db)
    result = await generator.generate_project(request, user)
    return APIResponse(code=201, data=result)


@router.get("/jobs", response_model=APIResponse[PaginatedScaffoldJobs])
async def list_jobs(
    skip: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=100),
    template_id: str | None = Query(None),
    db: AsyncSession = Depends(get_db),
    user: User | None = Depends(get_current_user),
):
    """List scaffold jobs."""
    service = ScaffoldJobService(db)
    jobs, total = await service.list_jobs(skip, limit, template_id, user)
    return APIResponse(
        code=200,
        data=PaginatedResponse(
            items=[
                ScaffoldJobResponse(
                    **j.__dict__,
                    template_name=j.template.name if j.template else None,
                )
                for j in jobs
            ],
            total=total,
            skip=skip,
            limit=limit,
        ),
    )


@router.get("/jobs/{job_id}", response_model=APIResponse[ScaffoldJobResponse])
async def get_job(
    job_id: str,
    db: AsyncSession = Depends(get_db),
    user: User | None = Depends(get_current_user),
):
    """Get a scaffold job by ID."""
    service = ScaffoldJobService(db)
    job = await service.get_job(job_id, user)
    return APIResponse(
        code=200,
        data=ScaffoldJobResponse(
            **job.__dict__,
            template_name=job.template.name if job.template else None,
        ),
    )


@router.get("/projects", response_model=APIResponse[PaginatedGeneratedProjects])
async def list_generated_projects(
    skip: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=100),
    template_id: str | None = Query(None),
    db: AsyncSession = Depends(get_db),
    user: User | None = Depends(get_current_user),
):
    """List generated projects."""
    service = ScaffoldJobService(db)
    projects, total = await service.list_generated_projects(skip, limit, template_id, user)
    return APIResponse(
        code=200,
        data=PaginatedResponse(
            items=[
                GeneratedProjectResponse(
                    **p.__dict__,
                    template_name=p.template.name if p.template else None,
                )
                for p in projects
            ],
            total=total,
            skip=skip,
            limit=limit,
        ),
    )


@router.post("/interactive/start", response_model=APIResponse[InteractiveSessionResponse], status_code=201)
async def start_interactive_session(
    session_data: InteractiveSessionCreate,
    db: AsyncSession = Depends(get_db),
    user: User | None = Depends(get_current_user),
):
    """Start an interactive Q&A session for template parameter collection."""
    manager = InteractiveSessionManager(db)
    result = await manager.create_session(session_data, user)
    return APIResponse(code=201, data=result)


@router.post("/interactive/answer", response_model=APIResponse[InteractiveSessionResponse])
async def answer_interactive_question(
    answer: InteractiveAnswer,
    db: AsyncSession = Depends(get_db),
    user: User | None = Depends(get_current_user),
):
    """Answer a question in an interactive session."""
    manager = InteractiveSessionManager(db)
    result = await manager.answer_question(answer, user)
    return APIResponse(code=200, data=result)


@router.get("/interactive/{session_id}", response_model=APIResponse[InteractiveSessionResponse])
async def get_interactive_session(
    session_id: str,
    db: AsyncSession = Depends(get_db),
    user: User | None = Depends(get_current_user),
):
    """Get the current state of an interactive session."""
    manager = InteractiveSessionManager(db)
    result = await manager.get_session(session_id)
    return APIResponse(code=200, data=result)


@router.post("/interactive/{session_id}/complete", response_model=APIResponse[dict])
async def complete_interactive_session(
    session_id: str,
    db: AsyncSession = Depends(get_db),
    user: User | None = Depends(get_current_user),
):
    """Complete an interactive session and return collected parameters."""
    manager = InteractiveSessionManager(db)
    result = await manager.complete_session(session_id)
    return APIResponse(code=200, data=result)
