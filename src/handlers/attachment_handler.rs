use actix_session::Session;
use actix_web::{web, HttpResponse, Responder};
use base64::{engine::general_purpose, Engine as _};
use serde::Deserialize;
use uuid::Uuid;

use crate::handlers::auth_handler::SESSION_ID_KEY;
use crate::services::{AttachmentService, AuthService};
use crate::utils::{ApiResponse, AppError, AppResult};

#[derive(Debug, Deserialize)]
pub struct UploadAttachmentRequest {
    pub data: String,
    pub file_name: String,
    pub content_type: String,
}

async fn get_current_user(
    session: &Session,
    auth_service: &AuthService,
) -> AppResult<crate::models::AuthUser> {
    let session_id = session
        .get::<String>(SESSION_ID_KEY)?
        .ok_or_else(|| AppError::Authentication("请先登录".to_string()))?;

    auth_service.get_current_user(&session_id).await
}

pub async fn upload_attachment_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    attachment_service: web::Data<AttachmentService>,
    path: web::Path<(Uuid, String, Uuid)>,
    body: web::Json<UploadAttachmentRequest>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    let (organization_id, attachment_type, target_id) = path.into_inner();

    let file_bytes = general_purpose::STANDARD
        .decode(&body.data)
        .map_err(|e| AppError::Validation(format!("Invalid base64 data: {}", e)))?;

    let response = attachment_service
        .upload_attachment(
            user.id,
            organization_id,
            &attachment_type,
            target_id,
            &body.file_name,
            &body.content_type,
            &file_bytes,
        )
        .await?;

    Ok(HttpResponse::Created().json(ApiResponse::success(response)))
}

pub async fn list_attachments_api(
    attachment_service: web::Data<AttachmentService>,
    path: web::Path<(String, Uuid)>,
) -> AppResult<impl Responder> {
    let (attachment_type, target_id) = path.into_inner();

    let attachments = attachment_service
        .get_attachments_for_target(&attachment_type, target_id)
        .await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success(attachments)))
}

pub async fn delete_attachment_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    attachment_service: web::Data<AttachmentService>,
    id: web::Path<Uuid>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;

    attachment_service
        .delete_attachment(user.id, id.into_inner())
        .await?;

    Ok(HttpResponse::Ok().json(ApiResponse::<()>::success_no_data()))
}
