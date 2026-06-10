use actix_web::{web, HttpResponse, Responder};
use uuid::Uuid;

use crate::models::{ExportRequest, StatsQuery};
use crate::services::StatsService;
use crate::utils::{ApiResponse, AppResult};

pub async fn stats_page() -> impl Responder {
    HttpResponse::Ok().body("Statistics dashboard page")
}

pub async fn coverage_stats_api(
    stats_service: web::Data<StatsService>,
    query: web::Query<StatsQuery>,
    org_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let stats = stats_service
        .get_review_stats(query.into_inner(), org_id.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(stats)))
}

pub async fn heatmap_api(
    stats_service: web::Data<StatsService>,
    query: web::Query<StatsQuery>,
    org_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let heatmap_data = stats_service
        .get_heatmap_data(query.into_inner(), org_id.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(heatmap_data)))
}

pub async fn personal_stats_api(
    stats_service: web::Data<StatsService>,
    query: web::Query<StatsQuery>,
    user_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let stats = stats_service
        .get_personal_stats(user_id.into_inner(), query.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(stats)))
}

pub async fn team_ranking_api(
    stats_service: web::Data<StatsService>,
    query: web::Query<StatsQuery>,
    org_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let ranking = stats_service
        .get_team_ranking(query.into_inner(), org_id.into_inner(), 10)
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(ranking)))
}

pub async fn export_report_api(
    stats_service: web::Data<StatsService>,
    req: web::Json<ExportRequest>,
    org_id: web::ReqData<Uuid>,
) -> AppResult<impl Responder> {
    let download_url = stats_service
        .export_stats_report(req.into_inner(), org_id.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(serde_json::json!({
        "download_url": download_url
    }))))
}
