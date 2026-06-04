from fastapi import APIRouter, Depends, HTTPException, status, Query
from typing import List, Dict, Any, Tuple

from recommendation_engine.api.dependencies import (
    get_cf_svc,
    verify_api_key,
)
from recommendation_engine.collaborative_filter import CollaborativeFilter

router = APIRouter(prefix="/api/v1/cf", tags=["collaborative-filter"], dependencies=[Depends(verify_api_key)])


@router.get("/recommend/{user_id}")
async def get_cf_recommendations(
    user_id: str,
    top_k: int = Query(100, ge=1, le=500),
    service: CollaborativeFilter = Depends(get_cf_svc),
):
    try:
        results = await service.recommend(user_id, top_k)
        return {
            "user_id": user_id,
            "results": [
                {"content_id": cid, "score": float(score)}
                for cid, score in results
            ]
        }
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get CF recommendations: {str(e)}",
        )


@router.post("/train", status_code=status.HTTP_202_ACCEPTED)
async def train_als_model(
    interactions: List[Tuple[str, str, float]],
    service: CollaborativeFilter = Depends(get_cf_svc),
):
    try:
        result = await service.train(interactions)
        return {"status": "accepted", "result": result}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to train ALS model: {str(e)}",
        )


@router.post("/reload", status_code=status.HTTP_200_OK)
async def reload_model(
    service: CollaborativeFilter = Depends(get_cf_svc),
):
    try:
        success = await service.reload_model()
        return {"status": "success" if success else "failed", "reloaded": success}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to reload model: {str(e)}",
        )


@router.get("/predict/{user_id}/{content_id}")
async def predict_score(
    user_id: str,
    content_id: str,
    service: CollaborativeFilter = Depends(get_cf_svc),
):
    try:
        score = await service.predict(user_id, content_id)
        return {"user_id": user_id, "content_id": content_id, "score": score}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to predict score: {str(e)}",
        )


@router.get("/stats")
async def get_cf_stats(
    service: CollaborativeFilter = Depends(get_cf_svc),
):
    try:
        stats = service.get_stats()
        return stats
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get stats: {str(e)}",
        )
