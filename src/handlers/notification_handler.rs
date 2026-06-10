use actix_web::{web, HttpResponse, Responder};
use uuid::Uuid;

use crate::models::{NotificationQuery, UpdateNotificationSettingsRequest};
use crate::services::NotificationService;
use crate::utils::{ApiResponse, AppResult};

pub async fn notifications_page() -> impl Responder {
    HttpResponse::Ok().body("Notifications center page")
}

pub async fn notifications_settings_page() -> impl Responder {
    HttpResponse::Ok().body("Notifications settings page")
}

pub async fn notifications_api(
    notification_service: web::Data<NotificationService>,
    query: web::Query<NotificationQuery>,
    user_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let notifications = notification_service
        .get_notifications(user_id.into_inner(), query.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(notifications)))
}

pub async fn mark_read_api(
    notification_service: web::Data<NotificationService>,
    id: web::Path<Uuid>,
    user_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let notification = notification_service
        .mark_read(id.into_inner(), user_id.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(notification)))
}

pub async fn mark_all_read_api(
    notification_service: web::Data<NotificationService>,
    user_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let count = notification_service
        .mark_all_read(user_id.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(serde_json::json!({
        "marked_count": count
    }))))
}

pub async fn settings_api(
    notification_service: web::Data<NotificationService>,
    user_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let settings = notification_service
        .get_settings(user_id.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(settings)))
}

pub async fn update_settings_api(
    notification_service: web::Data<NotificationService>,
    req: web::Json<UpdateNotificationSettingsRequest>,
    user_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let settings = notification_service
        .update_settings(user_id.into_inner(), &req.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(settings)))
}
