use actix_web::{web, HttpResponse, Responder};
use uuid::Uuid;

use crate::models::{AddTeamMemberRequest, CreateTeamRequest, UpdateRoleRequest};
use crate::services::UserService;
use crate::utils::{ApiResponse, AppResult};

pub async fn organization_page() -> impl Responder {
    HttpResponse::Ok().body("Organization management page")
}

pub async fn teams_page() -> impl Responder {
    HttpResponse::Ok().body("Teams list page")
}

pub async fn team_members_page(id: web::Path<Uuid>) -> impl Responder {
    HttpResponse::Ok().body(format!("Team members page: {}", id))
}

pub async fn create_team_api(
    user_service: web::Data<UserService>,
    req: web::Json<CreateTeamRequest>,
    org_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let team = user_service
        .create_team(org_id.into_inner(), &req.into_inner())
        .await?;
    Ok(HttpResponse::Created().json(ApiResponse::success(team)))
}

pub async fn add_member_api(
    user_service: web::Data<UserService>,
    team_id: web::Path<Uuid>,
    req: web::Json<AddTeamMemberRequest>,
) -> AppResult<impl Responder> {
    let member = user_service
        .add_team_member(team_id.into_inner(), req.user_id, &req.role)
        .await?;
    Ok(HttpResponse::Created().json(ApiResponse::success(member)))
}

pub async fn update_role_api(
    user_service: web::Data<UserService>,
    path: web::Path<(Uuid, Uuid)>,
    req: web::Json<UpdateRoleRequest>,
) -> AppResult<impl Responder> {
    let (team_id, user_id) = path.into_inner();
    let member = user_service
        .update_member_role(team_id, user_id, &req.role)
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(member)))
}

pub async fn remove_member_api(
    user_service: web::Data<UserService>,
    path: web::Path<(Uuid, Uuid)>,
) -> AppResult<impl Responder> {
    let (team_id, user_id) = path.into_inner();
    user_service.remove_team_member(team_id, user_id).await?;
    Ok(HttpResponse::Ok().json(ApiResponse::<()>::success_no_data()))
}
