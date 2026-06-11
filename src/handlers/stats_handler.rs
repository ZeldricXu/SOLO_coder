use actix_session::Session;
use actix_web::{web, HttpResponse, Responder};
use uuid::Uuid;

use crate::handlers::auth_handler::SESSION_ID_KEY;
use crate::models::{ExportRequest, OrgStatsQuery, StatsQuery};
use crate::services::{AuthService, OrgStatsService, StatsService};
use crate::utils::{ApiResponse, AppError, AppResult};

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

async fn get_current_user_for_stats(
    session: &Session,
    auth_service: &AuthService,
) -> AppResult<crate::models::AuthUser> {
    let session_id = session
        .get::<String>(SESSION_ID_KEY)?
        .ok_or_else(|| AppError::Authentication("请先登录".to_string()))?;

    auth_service.get_current_user(&session_id).await
}

pub async fn org_stats_page(
    session: Session,
    auth_service: web::Data<AuthService>,
) -> AppResult<impl Responder> {
    let user = get_current_user_for_stats(&session, &auth_service).await?;

    let content = maud::html! {
        div style="padding: 24px;" {
            h1 style="font-size: 24px; font-weight: 600; margin-bottom: 16px;" { "组织级统计" }
            p style="color: #666;" { "欢迎使用组织级统计分析页面" }
            div style="margin-top: 24px; display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px;" {
                div style="background: #f0f9ff; padding: 20px; border-radius: 8px;" {
                    div style="font-size: 14px; color: #0369a1; margin-bottom: 8px;" { "概览数据" }
                    div style="font-size: 24px; font-weight: 600; color: #075985;" { "加载中..." }
                }
                div style="background: #f0fdf4; padding: 20px; border-radius: 8px;" {
                    div style="font-size: 14px; color: #15803d; margin-bottom: 8px;" { "健康度排行" }
                    div style="font-size: 24px; font-weight: 600; color: #166534;" { "加载中..." }
                }
                div style="background: #fef3c7; padding: 20px; border-radius: 8px;" {
                    div style="font-size: 14px; color: #b45309; margin-bottom: 8px;" { "贡献者排行" }
                    div style="font-size: 24px; font-weight: 600; color: #92400e;" { "加载中..." }
                }
                div style="background: #fce7f3; padding: 20px; border-radius: 8px;" {
                    div style="font-size: 14px; color: #be185d; margin-bottom: 8px;" { "问题趋势" }
                    div style="font-size: 24px; font-weight: 600; color: #9d174d;" { "加载中..." }
                }
            }
        }
    };

    let html = maud::html! {
        (maud::DOCTYPE)
        html {
            head {
                meta charset="utf-8";
                meta name="viewport" content="width=device-width, initial-scale=1.0";
                title { "组织级统计 - 代码审查平台" }
            }
            body {
                (content)
                div style="position: fixed; bottom: 20px; right: 20px;" {
                    span style="font-size: 12px; color: #999;" { "当前用户: " (user.username) }
                }
            }
        }
    };

    Ok(HttpResponse::Ok().content_type("text/html; charset=utf-8").body(html.into_string()))
}

pub async fn org_overview_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    org_stats_service: web::Data<OrgStatsService>,
    organization_id: web::Path<Uuid>,
    query: web::Query<OrgStatsQuery>,
) -> AppResult<impl Responder> {
    let user = get_current_user_for_stats(&session, &auth_service).await?;
    let org_id = organization_id.into_inner();

    let overview = org_stats_service
        .get_org_overview(user.id, org_id, &query.into_inner())
        .await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success(overview)))
}

pub async fn org_repo_health_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    org_stats_service: web::Data<OrgStatsService>,
    organization_id: web::Path<Uuid>,
    query: web::Query<OrgStatsQuery>,
) -> AppResult<impl Responder> {
    let user = get_current_user_for_stats(&session, &auth_service).await?;
    let org_id = organization_id.into_inner();

    let ranking = org_stats_service
        .get_repo_health_ranking(user.id, org_id, &query.into_inner())
        .await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success(ranking)))
}

pub async fn org_contributor_ranking_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    org_stats_service: web::Data<OrgStatsService>,
    organization_id: web::Path<Uuid>,
    query: web::Query<OrgStatsQuery>,
) -> AppResult<impl Responder> {
    let user = get_current_user_for_stats(&session, &auth_service).await?;
    let org_id = organization_id.into_inner();
    let query_inner = query.into_inner();

    let ranking = org_stats_service
        .get_contributor_ranking(user.id, org_id, &query_inner, query_inner.team_id, None)
        .await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success(ranking)))
}

pub async fn org_issue_trend_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    org_stats_service: web::Data<OrgStatsService>,
    organization_id: web::Path<Uuid>,
    query: web::Query<OrgStatsQuery>,
) -> AppResult<impl Responder> {
    let user = get_current_user_for_stats(&session, &auth_service).await?;
    let org_id = organization_id.into_inner();

    let trend = org_stats_service
        .get_issue_type_trend(user.id, org_id, &query.into_inner())
        .await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success(trend)))
}

pub async fn org_refresh_mv_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    org_stats_service: web::Data<OrgStatsService>,
    organization_id: web::Path<Uuid>,
) -> AppResult<impl Responder> {
    let user = get_current_user_for_stats(&session, &auth_service).await?;
    let org_id = organization_id.into_inner();

    org_stats_service
        .refresh_materialized_views(user.id, org_id)
        .await?;

    Ok(HttpResponse::Ok().json(ApiResponse::<()>::success_no_data()))
}
