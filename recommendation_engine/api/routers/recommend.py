from fastapi import APIRouter, Depends, HTTPException, status
from typing import Any

from recommendation_engine.models.schemas import RecommendRequest, RecommendResponse
from recommendation_engine.api.dependencies import (
    get_rank_pipeline_svc,
    get_abtest_router_svc,
    get_feedback_collector_svc,
    verify_api_key,
)
from recommendation_engine.realtime_rank_pipeline import RealtimeRankPipeline
from recommendation_engine.ab_test_router import ABTestRouter
from recommendation_engine.feedback_collector import FeedbackCollector

router = APIRouter(prefix="/api/v1/recommend", tags=["recommend"], dependencies=[Depends(verify_api_key)])


@router.post("", response_model=RecommendResponse, status_code=status.HTTP_200_OK)
async def get_recommendations(
    request: RecommendRequest,
    rank_pipeline: RealtimeRankPipeline = Depends(get_rank_pipeline_svc),
    abtest_router: ABTestRouter = Depends(get_abtest_router_svc),
    feedback_collector: FeedbackCollector = Depends(get_feedback_collector_svc),
):
    try:
        experiment_config = await abtest_router.get_experiment_config(request.user_id)

        response = await rank_pipeline.recommend(request, experiment_config)

        for i, item in enumerate(response.results):
            await feedback_collector.collect_raw(
                {
                    "user_id": request.user_id,
                    "content_id": item.content_id,
                    "event_type": "expose",
                    "request_id": request.request_id,
                    "scene": request.scene,
                    "position": i,
                    "value": 1.0,
                }
            )

        return response
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to generate recommendations: {str(e)}",
        )


@router.post("/explain", response_model=Any, status_code=status.HTTP_200_OK)
async def explain_recommendation(
    request: RecommendRequest,
    rank_pipeline: RealtimeRankPipeline = Depends(get_rank_pipeline_svc),
    abtest_router: ABTestRouter = Depends(get_abtest_router_svc),
):
    try:
        experiment_config = await abtest_router.get_experiment_config(request.user_id)
        explanation = await rank_pipeline.explain_recommendation(request, experiment_config)
        return {"request_id": request.request_id, "explanation": explanation}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to explain recommendation: {str(e)}",
        )
