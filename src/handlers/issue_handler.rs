use actix_web::{web, HttpResponse, Responder};
use uuid::Uuid;

use crate::models::{
    AssignIssueRequest, CreateIssueRequest, IssueQuery, UpdateIssueRequest,
    UpdateIssueStatusRequest,
};
use crate::services::IssueService;
use crate::utils::{ApiResponse, AppResult};

pub async fn issues_page() -> impl Responder {
    HttpResponse::Ok().body("Issues list page")
}

pub async fn issue_detail_page(id: web::Path<Uuid>) -> impl Responder {
    HttpResponse::Ok().body(format!("Issue detail page: {}", id))
}

pub async fn issues_api(
    issue_service: web::Data<IssueService>,
    query: web::Query<IssueQuery>,
) -> AppResult<impl Responder> {
    let result = issue_service.list_issues(query.into_inner()).await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(result)))
}

pub async fn issue_api(
    issue_service: web::Data<IssueService>,
    id: web::Path<Uuid>,
) -> AppResult<impl Responder> {
    let issue = issue_service.get_issue(id.into_inner()).await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(issue)))
}

pub async fn create_issue_api(
    issue_service: web::Data<IssueService>,
    req: web::Json<CreateIssueRequest>,
    user_id: web::ReqData<Uuid>,
    org_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let issue = issue_service
        .create_issue(
            user_id.into_inner(),
            org_id.into_inner(),
            &req.into_inner(),
        )
        .await?;
    Ok(HttpResponse::Created().json(ApiResponse::success(issue)))
}

pub async fn update_issue_api(
    issue_service: web::Data<IssueService>,
    id: web::Path<Uuid>,
    req: web::Json<UpdateIssueRequest>,
    user_id: web::ReqData<Uuid>,
    org_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let issue = issue_service
        .update_issue(
            id.into_inner(),
            user_id.into_inner(),
            org_id.into_inner(),
            &req.into_inner(),
        )
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(issue)))
}

pub async fn update_issue_status_api(
    issue_service: web::Data<IssueService>,
    id: web::Path<Uuid>,
    req: web::Json<UpdateIssueStatusRequest>,
    user_id: web::ReqData<Uuid>,
    org_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let issue = issue_service
        .update_status(
            id.into_inner(),
            user_id.into_inner(),
            org_id.into_inner(),
            &req.into_inner(),
        )
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(issue)))
}

pub async fn assign_issue_api(
    issue_service: web::Data<IssueService>,
    id: web::Path<Uuid>,
    req: web::Json<AssignIssueRequest>,
    user_id: web::ReqData<Uuid>,
    org_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let issue = issue_service
        .assign_issue(
            id.into_inner(),
            user_id.into_inner(),
            org_id.into_inner(),
            &req.into_inner(),
        )
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(issue)))
}
