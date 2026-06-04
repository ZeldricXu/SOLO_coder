from fastapi import APIRouter, Depends, HTTPException, status, Query, Body
from typing import Optional, List, Dict, Any

from recommendation_engine.models.schemas import ModelInferenceRequest, ModelInferenceResponse
from recommendation_engine.api.dependencies import (
    get_model_gateway_svc,
    verify_api_key,
)
from recommendation_engine.model_serving_gateway import ModelServingGateway

router = APIRouter(prefix="/api/v1/model-serving", tags=["model-serving"], dependencies=[Depends(verify_api_key)])


@router.post("/infer", response_model=ModelInferenceResponse)
async def infer(
    request: ModelInferenceRequest,
    gateway: ModelServingGateway = Depends(get_model_gateway_svc),
):
    try:
        response = await gateway.infer(request)
        if not response:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="Inference failed",
            )
        return response
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Inference error: {str(e)}",
        )


@router.post("/models", status_code=status.HTTP_201_CREATED)
async def register_model(
    model_data: Dict[str, Any],
    gateway: ModelServingGateway = Depends(get_model_gateway_svc),
):
    try:
        success = await gateway.register_model(
            model_name=model_data["model_name"],
            model_version=model_data["model_version"],
            backend=model_data["backend"],
            model_path=model_data["model_path"],
            metadata=model_data.get("metadata"),
            set_default=model_data.get("set_default", True),
        )
        if not success:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="Failed to register model",
            )
        return {
            "status": "registered",
            "model_name": model_data["model_name"],
            "model_version": model_data["model_version"],
        }
    except HTTPException:
        raise
    except KeyError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Missing required field: {str(e)}",
        )
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to register model: {str(e)}",
        )


@router.post("/models/{model_name}/load", status_code=status.HTTP_200_OK)
async def load_model(
    model_name: str,
    model_version: Optional[str] = Query(None),
    gateway: ModelServingGateway = Depends(get_model_gateway_svc),
):
    try:
        success = await gateway.load_model(model_name, model_version)
        return {"status": "success" if success else "failed", "loaded": success}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to load model: {str(e)}",
        )


@router.post("/models/{model_name}/unload", status_code=status.HTTP_200_OK)
async def unload_model(
    model_name: str,
    model_version: Optional[str] = Query(None),
    gateway: ModelServingGateway = Depends(get_model_gateway_svc),
):
    try:
        success = await gateway.unload_model(model_name, model_version)
        return {"status": "success" if success else "failed", "unloaded": success}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to unload model: {str(e)}",
        )


@router.put("/models/{model_name}/default-version", status_code=status.HTTP_200_OK)
async def set_default_version(
    model_name: str,
    version_data: Dict[str, str] = Body(...),
    gateway: ModelServingGateway = Depends(get_model_gateway_svc),
):
    try:
        model_version = version_data.get("model_version")
        if not model_version:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="model_version is required",
            )
        success = await gateway.set_default_version(model_name, model_version)
        return {"status": "success" if success else "failed", "updated": success}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to set default version: {str(e)}",
        )


@router.get("/models")
async def list_models(
    gateway: ModelServingGateway = Depends(get_model_gateway_svc),
):
    try:
        models = gateway.list_models()
        return {"models": models}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to list models: {str(e)}",
        )


@router.get("/models/{model_name}")
async def get_model_info(
    model_name: str,
    model_version: Optional[str] = Query(None),
    gateway: ModelServingGateway = Depends(get_model_gateway_svc),
):
    try:
        info = gateway.get_model_info(model_name, model_version)
        if not info:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Model {model_name} not found",
            )
        return info
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get model info: {str(e)}",
        )


@router.get("/stats")
async def get_model_serving_stats(
    gateway: ModelServingGateway = Depends(get_model_gateway_svc),
):
    try:
        stats = gateway.get_stats()
        return stats
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get stats: {str(e)}",
        )
