use actix_web::{web, HttpResponse, Responder};
use common::error::AppResult;
use models::{BidHistoryItem, BidResult, CreateBidRequest};
use shared::ApiResponse;
use uuid::Uuid;

use crate::BidArbitrator;

pub struct BidService {
    arbitrator: BidArbitrator,
}

impl BidService {
    pub fn new(arbitrator: BidArbitrator) -> Self {
        Self { arbitrator }
    }

    pub async fn place_bid(&self, user_id: Uuid, req: CreateBidRequest) -> AppResult<BidResult> {
        self.arbitrator
            .process_bid(req.auction_id, user_id, req.max_price)
            .await
    }

    pub async fn get_my_bids(&self, user_id: Uuid, page: i64, per_page: i64) -> AppResult<Vec<BidHistoryItem>> {
        let offset = (page - 1) * per_page;
        self.arbitrator
            .get_bid_history(user_id, per_page, offset)
            .await
    }

    pub async fn refund_bid(&self, bid_id: Uuid) -> AppResult<()> {
        self.arbitrator.refund_failed_bid(bid_id).await
    }
}

impl Clone for BidService {
    fn clone(&self) -> Self {
        Self {
            arbitrator: self.arbitrator.clone(),
        }
    }
}

pub async fn place_bid_handler(
    service: web::Data<BidService>,
    user_id: web::ReqData<Uuid>,
    req: web::Json<CreateBidRequest>,
) -> impl Responder {
    match service.place_bid(user_id.into_inner(), req.into_inner()).await {
        Ok(result) => HttpResponse::Ok().json(ApiResponse::ok(result)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn get_my_bids_handler(
    service: web::Data<BidService>,
    user_id: web::ReqData<Uuid>,
    query: web::Query<super::service_impl::BidQuery>,
) -> impl Responder {
    let page = query.page.unwrap_or(1);
    let per_page = std::cmp::min(query.per_page.unwrap_or(20), 100);

    match service.get_my_bids(user_id.into_inner(), page, per_page).await {
        Ok(bids) => HttpResponse::Ok().json(ApiResponse::ok(bids)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub mod service_impl {
    use serde::Deserialize;

    #[derive(Debug, Deserialize)]
    pub struct BidQuery {
        pub page: Option<i64>,
        pub per_page: Option<i64>,
    }
}
