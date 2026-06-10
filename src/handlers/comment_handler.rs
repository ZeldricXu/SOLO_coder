use actix_web::{web, HttpResponse, Responder};
use uuid::Uuid;

use crate::models::{ResolveCommentRequest, UpdateCommentRequest};
use crate::services::CommentService;
use crate::utils::{ApiResponse, AppResult};

pub async fn update_comment_api(
    comment_service: web::Data<CommentService>,
    id: web::Path<Uuid>,
    req: web::Json<UpdateCommentRequest>,
    user_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let comment = comment_service
        .update_comment(id.into_inner(), user_id.into_inner(), &req)
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(comment)))
}

pub async fn delete_comment_api(
    comment_service: web::Data<CommentService>,
    id: web::Path<Uuid>,
    user_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    comment_service
        .delete_comment(id.into_inner(), user_id.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::<()>::success_no_data()))
}

pub async fn resolve_comment_api(
    comment_service: web::Data<CommentService>,
    id: web::Path<Uuid>,
    req: web::Json<ResolveCommentRequest>,
    user_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let comment = comment_service
        .resolve_comment(id.into_inner(), user_id.into_inner(), req.resolved)
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(comment)))
}
