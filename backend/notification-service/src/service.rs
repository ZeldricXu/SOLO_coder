use actix_web::{web, HttpResponse, Responder};
use common::error::AppResult;
use models::{Notification, PushSubscriptionRequest};
use shared::ApiResponse;
use uuid::Uuid;

use crate::{NotificationService, WebPushPayload, WebPushService};

#[derive(Debug, serde::Deserialize)]
pub struct NotificationListQuery {
    pub page: Option<i64>,
    pub per_page: Option<i64>,
    pub unread_only: Option<bool>,
}

pub struct NotificationWebService {
    notification_service: NotificationService,
    web_push_service: WebPushService,
}

impl NotificationWebService {
    pub fn new(notification_service: NotificationService, web_push_service: WebPushService) -> Self {
        Self {
            notification_service,
            web_push_service,
        }
    }

    pub async fn get_notifications(
        &self,
        user_id: Uuid,
        unread_only: bool,
        page: i64,
        per_page: i64,
    ) -> AppResult<Vec<Notification>> {
        let offset = (page - 1) * per_page;
        self.notification_service
            .get_user_notifications(user_id, unread_only, per_page, offset)
            .await
    }

    pub async fn get_unread_count(&self, user_id: Uuid) -> AppResult<i64> {
        self.notification_service.get_unread_count(user_id).await
    }

    pub async fn mark_as_read(&self, user_id: Uuid, notification_id: Uuid) -> AppResult<()> {
        self.notification_service
            .mark_as_read(user_id, notification_id)
            .await
    }

    pub async fn mark_all_as_read(&self, user_id: Uuid) -> AppResult<u64> {
        self.notification_service.mark_all_as_read(user_id).await
    }

    pub async fn subscribe_push(&self, user_id: Uuid, req: PushSubscriptionRequest) -> AppResult<Uuid> {
        self.web_push_service.subscribe(user_id, req).await
    }

    pub async fn unsubscribe_push(&self, user_id: Uuid, endpoint: String) -> AppResult<u64> {
        self.web_push_service.unsubscribe(user_id, &endpoint).await
    }

    pub fn get_vapid_public_key(&self) -> &str {
        self.web_push_service.public_key()
    }
}

impl Clone for NotificationWebService {
    fn clone(&self) -> Self {
        Self {
            notification_service: self.notification_service.clone(),
            web_push_service: self.web_push_service.clone(),
        }
    }
}

pub async fn list_notifications_handler(
    service: web::Data<NotificationWebService>,
    user_id: web::ReqData<Uuid>,
    query: web::Query<NotificationListQuery>,
) -> impl Responder {
    let page = query.page.unwrap_or(1);
    let per_page = std::cmp::min(query.per_page.unwrap_or(20), 100);
    let unread_only = query.unread_only.unwrap_or(false);

    match service
        .get_notifications(user_id.into_inner(), unread_only, page, per_page)
        .await
    {
        Ok(notifications) => HttpResponse::Ok().json(ApiResponse::ok(notifications)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn get_unread_count_handler(
    service: web::Data<NotificationWebService>,
    user_id: web::ReqData<Uuid>,
) -> impl Responder {
    match service.get_unread_count(user_id.into_inner()).await {
        Ok(count) => HttpResponse::Ok().json(ApiResponse::ok(serde_json::json!({ "unread_count": count }))),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn mark_as_read_handler(
    service: web::Data<NotificationWebService>,
    user_id: web::ReqData<Uuid>,
    path: web::Path<Uuid>,
) -> impl Responder {
    match service.mark_as_read(user_id.into_inner(), path.into_inner()).await {
        Ok(_) => HttpResponse::Ok().json(ApiResponse::ok(serde_json::json!({ "success": true }))),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn mark_all_as_read_handler(
    service: web::Data<NotificationWebService>,
    user_id: web::ReqData<Uuid>,
) -> impl Responder {
    match service.mark_all_as_read(user_id.into_inner()).await {
        Ok(count) => HttpResponse::Ok().json(ApiResponse::ok(serde_json::json!({ "updated": count }))),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn subscribe_push_handler(
    service: web::Data<NotificationWebService>,
    user_id: web::ReqData<Uuid>,
    req: web::Json<PushSubscriptionRequest>,
) -> impl Responder {
    match service.subscribe_push(user_id.into_inner(), req.into_inner()).await {
        Ok(sub_id) => HttpResponse::Ok().json(ApiResponse::ok(serde_json::json!({ "subscription_id": sub_id }))),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn unsubscribe_push_handler(
    service: web::Data<NotificationWebService>,
    user_id: web::ReqData<Uuid>,
    req: web::Json<serde_json::Value>,
) -> impl Responder {
    let endpoint = req
        .get("endpoint")
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_string();

    match service.unsubscribe_push(user_id.into_inner(), endpoint).await {
        Ok(count) => HttpResponse::Ok().json(ApiResponse::ok(serde_json::json!({ "deleted": count }))),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn get_vapid_key_handler(service: web::Data<NotificationWebService>) -> impl Responder {
    HttpResponse::Ok().json(ApiResponse::ok(serde_json::json!({
        "public_key": service.get_vapid_public_key()
    })))
}
