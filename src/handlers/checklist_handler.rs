use actix_web::{web, HttpResponse, Responder};
use uuid::Uuid;

use crate::models::{
    CheckItemRequest, ChecklistScope, CreateChecklistTemplateRequest,
    UpdateChecklistTemplateRequest,
};
use crate::services::{ChecklistPermissionService, ChecklistService, PermissionRepository};
use crate::utils::{ApiResponse, AppResult, PaginatedResponse, PaginationQuery};

pub async fn checklists_page() -> impl Responder {
    HttpResponse::Ok().body("Checklist templates page")
}

pub async fn checklist_detail_page(id: web::Path<Uuid>) -> impl Responder {
    HttpResponse::Ok().body(format!("Checklist template detail page: {}", id))
}

pub async fn checklists_api(
    checklist_service: web::Data<ChecklistService<PermissionRepository>>,
    query: web::Query<PaginationQuery>,
    scope: web::Query<std::collections::HashMap<String, String>>,
) -> AppResult<impl Responder> {
    let query = query.into_inner().sanitize();
    let scope_str = scope.get("scope").cloned();
    let scope_id = scope
        .get("scope_id")
        .and_then(|s| Uuid::parse_str(s).ok());

    let scope_enum = scope_str.as_deref().and_then(ChecklistScope::from_str);

    let (templates, total) = checklist_service
        .list_templates(scope_enum, scope_id, query.page, query.per_page)
        .await?;

    let response = PaginatedResponse::new(templates, total, query.page, query.per_page);
    Ok(HttpResponse::Ok().json(ApiResponse::success(response)))
}

pub async fn checklist_api(
    checklist_service: web::Data<ChecklistService<PermissionRepository>>,
    id: web::Path<Uuid>,
) -> AppResult<impl Responder> {
    let template = checklist_service.get_template(id.into_inner()).await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(template)))
}

pub async fn create_checklist_api(
    checklist_service: web::Data<ChecklistService<PermissionRepository>>,
    req: web::Json<CreateChecklistTemplateRequest>,
    user_id: web::ReqData<Uuid>,
    org_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let template = checklist_service
        .create_template(
            user_id.into_inner(),
            org_id.into_inner(),
            &req.into_inner(),
        )
        .await?;
    Ok(HttpResponse::Created().json(ApiResponse::success(template)))
}

pub async fn update_checklist_api(
    checklist_service: web::Data<ChecklistService<PermissionRepository>>,
    id: web::Path<Uuid>,
    req: web::Json<UpdateChecklistTemplateRequest>,
    user_id: web::ReqData<Uuid>,
    org_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let template = checklist_service
        .update_template(
            id.into_inner(),
            user_id.into_inner(),
            org_id.into_inner(),
            &req.into_inner(),
        )
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(template)))
}

pub async fn delete_checklist_api(
    checklist_service: web::Data<ChecklistService<PermissionRepository>>,
    id: web::Path<Uuid>,
    user_id: web::ReqData<Uuid>,
    org_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    checklist_service
        .delete_template(id.into_inner(), user_id.into_inner(), org_id.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::<()>::success_no_data()))
}

pub async fn check_item_api(
    checklist_service: web::Data<ChecklistService<PermissionRepository>>,
    path: web::Path<(Uuid, Uuid)>,
    req: web::Json<CheckItemRequest>,
    user_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let (_mr_id, item_id) = path.into_inner();
    checklist_service
        .check_item(item_id, user_id.into_inner(), &req.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::<()>::success_no_data()))
}
