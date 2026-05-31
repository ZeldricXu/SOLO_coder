from fastapi import APIRouter, Depends, Header, HTTPException
from typing import Optional, List
from src.core import ApiResponse, get_trace_id
from src.modules.model_registry import (
    ModelRegisterRequest,
    VersionCreateRequest,
    StageTransitionRequest,
    ModelStage,
)
from src.di import DIContainer, get_container

router = APIRouter(prefix="/api/v1/model-registry", tags=["Model Registry"])


@router.post("/models", response_model=ApiResponse)
async def register_model(
    request: ModelRegisterRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.model_registry.register_model(request, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.get("/models", response_model=ApiResponse)
async def list_models(
    framework: Optional[str] = None,
    tags: Optional[List[str]] = None,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.model_registry.list_models(framework, tags, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/models/{model_id}", response_model=ApiResponse)
async def get_model(
    model_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.model_registry.get_model(model_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/models/name/{name}", response_model=ApiResponse)
async def get_model_by_name(
    name: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.model_registry.get_model_by_name(name, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/versions", response_model=ApiResponse)
async def create_version(
    request: VersionCreateRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.model_registry.create_version(request, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.get("/versions/{version_id}", response_model=ApiResponse)
async def get_version(
    version_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.model_registry.get_version(version_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/models/{model_id}/versions", response_model=ApiResponse)
async def get_model_versions(
    model_id: str,
    stage: Optional[ModelStage] = None,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.model_registry.get_model_versions(model_id, stage, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/versions/transition", response_model=ApiResponse)
async def transition_stage(
    request: StageTransitionRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.model_registry.transition_stage(request, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/versions/{version_id}/transitions", response_model=ApiResponse)
async def get_version_transitions(
    version_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.model_registry.get_version_transitions(version_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/models/{model_id}/summary", response_model=ApiResponse)
async def get_model_summary(
    model_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.model_registry.get_model_summary(model_id, trace_id or get_trace_id())
    return ApiResponse.success(result)
