use actix_web::{web, HttpResponse, Responder};
use uuid::Uuid;

use crate::models::{ActOnSuggestionRequest, TriggerAiScanRequest};
use crate::services::AiReviewService;
use crate::utils::{ApiResponse, AppResult};

pub async fn ai_review_api(
    ai_review_service: web::Data<AiReviewService>,
    mr_id: web::Path<Uuid>,
) -> AppResult<impl Responder> {
    let mr_id = mr_id.into_inner();
    let review = match ai_review_service.get_latest_review(mr_id).await? {
        Some(review) => review,
        None => {
            return Ok(HttpResponse::NotFound().json(ApiResponse::<()>::error(
                404,
                &format!("No AI review found for merge request {}", mr_id),
            )));
        }
    };

    let review_with_suggestions = ai_review_service.get_review(review.id).await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(review_with_suggestions)))
}

pub async fn trigger_scan_api(
    ai_review_service: web::Data<AiReviewService>,
    mr_id: web::Path<Uuid>,
    req: web::Json<TriggerAiScanRequest>,
) -> AppResult<impl Responder> {
    let review_id = ai_review_service
        .trigger_scan(mr_id.into_inner(), &req.into_inner())
        .await?;
    Ok(HttpResponse::Accepted().json(ApiResponse::success(serde_json::json!({
        "review_id": review_id,
        "status": "running"
    }))))
}

pub async fn act_on_suggestion_api(
    ai_review_service: web::Data<AiReviewService>,
    id: web::Path<Uuid>,
    req: web::Json<ActOnSuggestionRequest>,
    user_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let suggestion = ai_review_service
        .act_on_suggestion(id.into_inner(), user_id.into_inner(), &req.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(suggestion)))
}
