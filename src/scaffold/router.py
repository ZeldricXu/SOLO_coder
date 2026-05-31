from __future__ import annotations

import tempfile
from typing import Any, Dict, List

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from src.common.models import APIResponse
from src.scaffold.generator import ProjectGenerator
from src.scaffold.models import ProjectConfig

router = APIRouter(prefix="/scaffold", tags=["Scaffold"])

_generator: ProjectGenerator | None = None


def get_generator() -> ProjectGenerator:
    global _generator
    if _generator is None:
        _generator = ProjectGenerator()
    return _generator


class GenerateProjectRequest(BaseModel):
    template_id: str
    name: str
    description: str = ""
    author: str = ""
    email: str = ""
    variables: Dict[str, Any] = {}


@router.get("/templates")
async def list_templates(
    generator: ProjectGenerator = Depends(get_generator),
) -> APIResponse:
    templates = generator.template_repo.list()
    return APIResponse(data=[
        {
            "id": t.template_id,
            "name": t.name,
            "description": t.description,
            "project_type": t.project_type.value,
            "variables": [v.model_dump() for v in t.variables],
        }
        for t in templates
    ])


@router.get("/templates/{template_id}")
async def get_template(
    template_id: str,
    generator: ProjectGenerator = Depends(get_generator),
) -> APIResponse:
    template = generator.template_repo.get(template_id)
    if not template:
        raise HTTPException(status_code=404, detail="Template not found")
    return APIResponse(data=template.model_dump())


@router.post("/generate", status_code=201)
async def generate_project(
    request: GenerateProjectRequest,
    generator: ProjectGenerator = Depends(get_generator),
) -> APIResponse:
    with tempfile.TemporaryDirectory() as tmpdir:
        config = ProjectConfig(
            name=request.name,
            description=request.description,
            template_id=request.template_id,
            author=request.author,
            email=request.email,
            variables=request.variables,
            output_dir=tmpdir,
            overwrite=True,
        )
        result = generator.generate(config)
        return APIResponse(code=201, data=result.model_dump())
