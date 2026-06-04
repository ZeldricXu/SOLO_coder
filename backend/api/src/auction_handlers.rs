use actix_web::{web, HttpResponse, Responder};
use models::{CreateAuctionRequest, ReviewAuctionRequest};
use rust_decimal::Decimal;
use shared::{ApiResponse, AuctionStatus, UserRole};
use uuid::Uuid;

use crate::auction_service::AuctionService;
use crate::hot_ranking::HotRankingService;

#[derive(Debug, serde::Deserialize)]
pub struct AuctionListQuery {
    pub category_id: Option<Uuid>,
    pub status: Option<AuctionStatus>,
    pub page: Option<i64>,
    pub per_page: Option<i64>,
    pub min_price: Option<Decimal>,
    pub max_price: Option<Decimal>,
    pub max_time_left: Option<i64>,
    pub sort_by: Option<AuctionSortBy>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum AuctionSortBy {
    PriceAsc,
    EndingSoon,
    MostViewed,
}

#[derive(Debug, serde::Deserialize)]
pub struct SseCategoryQuery {
    pub category_ids: Option<String>,
}

pub async fn create_auction_handler(
    service: web::Data<AuctionService>,
    user_id: web::ReqData<Uuid>,
    req: web::Json<CreateAuctionRequest>,
) -> impl Responder {
    match service.create_auction(user_id.into_inner(), req.into_inner()).await {
        Ok(auction) => HttpResponse::Ok().json(ApiResponse::ok(auction)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn list_auctions_handler(
    service: web::Data<AuctionService>,
    query: web::Query<AuctionListQuery>,
) -> impl Responder {
    let page = query.page.unwrap_or(1);
    let per_page = std::cmp::min(query.per_page.unwrap_or(20), 100);

    match service
        .list_auctions(
            query.category_id,
            query.status,
            query.min_price,
            query.max_price,
            query.max_time_left,
            query.sort_by,
            page,
            per_page,
        )
        .await
    {
        Ok(auctions) => HttpResponse::Ok().json(ApiResponse::ok(auctions)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn get_auction_detail_handler(
    service: web::Data<AuctionService>,
    hot_ranking: web::Data<HotRankingService>,
    path: web::Path<Uuid>,
    user_id: Option<web::ReqData<Uuid>>,
) -> impl Responder {
    let auction_id = path.into_inner();
    let uid = user_id.map(|u| u.into_inner());

    hot_ranking.record_view(auction_id).await.ok();

    match service.get_auction_detail(auction_id, uid).await {
        Ok(detail) => HttpResponse::Ok().json(ApiResponse::ok(detail)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn get_hot_rankings_handler(
    hot_ranking: web::Data<HotRankingService>,
) -> impl Responder {
    match hot_ranking.get_hot_rankings(10).await {
        Ok(rankings) => HttpResponse::Ok().json(ApiResponse::ok(rankings)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn get_my_auctions_handler(
    service: web::Data<AuctionService>,
    user_id: web::ReqData<Uuid>,
    query: web::Query<AuctionListQuery>,
) -> impl Responder {
    let page = query.page.unwrap_or(1);
    let per_page = std::cmp::min(query.per_page.unwrap_or(20), 100);

    match service
        .get_my_auctions(user_id.into_inner(), query.status, page, per_page)
        .await
    {
        Ok(auctions) => HttpResponse::Ok().json(ApiResponse::ok(auctions)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn review_auction_handler(
    service: web::Data<AuctionService>,
    user_id: web::ReqData<Uuid>,
    _role: web::ReqData<UserRole>,
    path: web::Path<Uuid>,
    req: web::Json<ReviewAuctionRequest>,
) -> impl Responder {
    match service
        .review_auction(path.into_inner(), user_id.into_inner(), req.into_inner())
        .await
    {
        Ok(auction) => HttpResponse::Ok().json(ApiResponse::ok(auction)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn sse_all_prices_handler(
    sse_handler: web::Data<auction_engine::SseHandler>,
) -> impl Responder {
    sse_handler.stream_all_prices().await
}

pub async fn sse_auction_price_handler(
    sse_handler: web::Data<auction_engine::SseHandler>,
    path: web::Path<Uuid>,
) -> impl Responder {
    sse_handler.stream_auction_price(path.into_inner()).await
}

pub async fn sse_category_prices_handler(
    sse_handler: web::Data<auction_engine::SseHandler>,
    pg_pool: web::Data<sqlx::PgPool>,
    query: web::Query<SseCategoryQuery>,
) -> impl Responder {
    let category_ids = query
        .category_ids
        .as_ref()
        .map(|s| {
            s.split(',')
                .filter_map(|id| Uuid::parse_str(id.trim()).ok())
                .collect()
        })
        .unwrap_or_default();

    sse_handler
        .stream_category_prices(category_ids, pg_pool.get_ref().clone())
        .await
}
