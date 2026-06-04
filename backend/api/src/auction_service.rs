use chrono::{Duration, Utc};
use common::error::{AppError, AppResult};
use models::{
    Auction, AuctionDetail, AuctionListItem, AuctionMedia, AuctionMediaRepository,
    AuctionRepository, CategoryRepository, CreateAuctionRequest, ReviewAuctionRequest,
    SellerRepository,
};
use rust_decimal::Decimal;
use serde_json::json;
use shared::{AuctionStatus, UserRole};
use sqlx::PgPool;
use std::path::Path;
use tracing::{info, warn};
use uuid::Uuid;

use crate::auction_handlers::AuctionSortBy;

pub struct AuctionService {
    pg_pool: PgPool,
    storage_path: String,
}

impl AuctionService {
    pub fn new(pg_pool: PgPool, storage_path: String) -> Self {
        Self { pg_pool, storage_path }
    }

    pub async fn create_auction(
        &self,
        seller_id: Uuid,
        req: CreateAuctionRequest,
    ) -> AppResult<Auction> {
        if req.starting_price <= req.reserve_price {
            return Err(AppError::Validation("起拍价必须高于保留价".into()));
        }
        if req.price_decrement <= Decimal::ZERO {
            return Err(AppError::Validation("降价幅度必须大于0".into()));
        }
        if req.duration_seconds < 60 {
            return Err(AppError::Validation("拍卖时长至少60秒".into()));
        }
        if req.decrement_interval_seconds < 1 {
            return Err(AppError::Validation("降价间隔至少1秒".into()));
        }

        let mut tx = self.pg_pool.begin().await?;

        let start_time = req.schedule_time.unwrap_or_else(Utc::now);
        let end_time = start_time + Duration::seconds(req.duration_seconds as i64);

        let status = if req.schedule_time.is_some() && req.schedule_time > Some(Utc::now()) {
            AuctionStatus::Scheduled
        } else {
            AuctionStatus::PendingReview
        };

        let auction_id = Uuid::new_v4();
        let auction = AuctionRepository::create(
            &mut tx,
            auction_id,
            seller_id,
            req.category_id,
            req.title,
            req.description,
            req.starting_price,
            req.reserve_price,
            req.starting_price,
            req.price_decrement,
            req.decrement_interval_seconds,
            req.duration_seconds,
            status,
            start_time,
            end_time,
        )
        .await?;

        tx.commit().await?;

        info!(
            auction_id = %auction_id,
            seller_id = %seller_id,
            title = %auction.title,
            "Auction created successfully"
        );

        Ok(auction)
    }

    pub async fn add_media(
        &self,
        auction_id: Uuid,
        seller_id: Uuid,
        file_name: &str,
        file_path: &str,
        file_size: i64,
        mime_type: Option<String>,
        media_type: &str,
        sort_order: i32,
        is_primary: bool,
    ) -> AppResult<AuctionMedia> {
        let (db_seller_id, _status) = AuctionRepository::find_seller_id(&self.pg_pool, auction_id)
            .await?
            .ok_or_else(|| AppError::NotFound("拍卖不存在".into()))?;

        if db_seller_id != seller_id {
            return Err(AppError::Authorization("无权修改此拍卖".into()));
        }

        if is_primary {
            AuctionMediaRepository::clear_primary(&self.pg_pool, auction_id).await?;
        }

        let media_id = Uuid::new_v4();
        let media = AuctionMediaRepository::create(
            &self.pg_pool,
            media_id,
            auction_id,
            media_type,
            file_path,
            file_size,
            mime_type,
            sort_order,
            is_primary,
        )
        .await?;

        info!(auction_id = %auction_id, media_id = %media_id, "Media added to auction");
        Ok(media)
    }

    pub async fn list_auctions(
        &self,
        category_id: Option<Uuid>,
        status: Option<AuctionStatus>,
        min_price: Option<Decimal>,
        max_price: Option<Decimal>,
        max_time_left: Option<i64>,
        sort_by: Option<AuctionSortBy>,
        page: i64,
        per_page: i64,
    ) -> AppResult<Vec<AuctionListItem>> {
        let offset = (page - 1) * per_page;
        let status_filter = status.unwrap_or(AuctionStatus::Active);

        let order_clause = match sort_by.unwrap_or(AuctionSortBy::EndingSoon) {
            AuctionSortBy::PriceAsc => "ORDER BY a.current_price ASC",
            AuctionSortBy::EndingSoon => "ORDER BY time_left_seconds ASC NULLS LAST",
            AuctionSortBy::MostViewed => "ORDER BY a.view_count DESC",
        };

        let auctions = AuctionRepository::list_with_filters(
            &self.pg_pool,
            status_filter,
            category_id,
            min_price,
            max_price,
            max_time_left,
            &order_clause,
            per_page,
            offset,
        )
        .await?;

        Ok(auctions)
    }

    pub async fn get_auction_detail(
        &self,
        auction_id: Uuid,
        user_id: Option<Uuid>,
    ) -> AppResult<AuctionDetail> {
        let mut tx = self.pg_pool.begin().await?;

        AuctionRepository::increment_view_count(&mut tx, auction_id).await?;

        let auction = AuctionRepository::find_by_id_for_update(&mut tx, auction_id)
            .await?
            .ok_or_else(|| AppError::NotFound("拍卖不存在".into()))?;

        let media = AuctionMediaRepository::find_by_auction(&mut tx, auction_id).await?;

        let category_name = CategoryRepository::find_name_by_auction(&mut tx, auction_id).await?;

        let seller_name = SellerRepository::find_name_by_auction(&mut tx, auction_id).await?;

        let time_left_seconds = if auction.status == AuctionStatus::Active {
            auction
                .end_time
                .map(|et| (et - Utc::now()).num_seconds())
                .filter(|&s| s > 0)
        } else {
            None
        };

        tx.commit().await?;

        Ok(AuctionDetail {
            auction,
            media,
            category_name,
            seller_name,
            time_left_seconds,
            is_watching: false,
        })
    }

    pub async fn get_my_auctions(
        &self,
        seller_id: Uuid,
        status: Option<AuctionStatus>,
        page: i64,
        per_page: i64,
    ) -> AppResult<Vec<AuctionListItem>> {
        let offset = (page - 1) * per_page;

        let auctions = AuctionRepository::list_by_seller(
            &self.pg_pool,
            seller_id,
            status,
            per_page,
            offset,
        )
        .await?;

        Ok(auctions)
    }

    pub async fn review_auction(
        &self,
        auction_id: Uuid,
        reviewer_id: Uuid,
        req: ReviewAuctionRequest,
    ) -> AppResult<Auction> {
        let mut tx = self.pg_pool.begin().await?;

        let _auction = AuctionRepository::find_by_id_and_status_for_update(
            &mut tx,
            auction_id,
            AuctionStatus::PendingReview,
        )
        .await?
        .ok_or_else(|| AppError::NotFound("拍卖不存在或无需审核".into()))?;

        let new_status = if req.approved {
            AuctionStatus::Active
        } else {
            AuctionStatus::ReviewRejected
        };

        let now = Utc::now();
        let updated =
            AuctionRepository::update_review(&mut tx, auction_id, new_status, req.note, reviewer_id, now, req.approved, now)
                .await?;

        tx.commit().await?;

        info!(
            auction_id = %auction_id,
            approved = req.approved,
            "Auction review completed"
        );

        Ok(updated)
    }

    pub async fn start_scheduled_auctions(&self) -> AppResult<Vec<Uuid>> {
        let now = Utc::now();

        let auctions = AuctionRepository::find_scheduled_ready(&self.pg_pool, now).await?;

        let mut started_ids = Vec::new();
        for (id, duration) in auctions {
            let end_time = now + Duration::seconds(duration as i64);
            AuctionRepository::activate_scheduled(&self.pg_pool, id, end_time).await?;
            started_ids.push(id);
            info!(auction_id = %id, "Scheduled auction started");
        }

        Ok(started_ids)
    }

    pub async fn delete_media(&self, media_id: Uuid, seller_id: Uuid) -> AppResult<()> {
        let auction_seller = AuctionMediaRepository::find_seller_by_media_id(&self.pg_pool, media_id).await?;

        match auction_seller {
            Some(s_id) if s_id == seller_id => {
                AuctionMediaRepository::delete(&self.pg_pool, media_id).await?;
                Ok(())
            }
            _ => Err(AppError::Authorization("无权删除此媒体".into())),
        }
    }

    pub fn storage_path(&self) -> &Path {
        Path::new(&self.storage_path)
    }
}

impl Clone for AuctionService {
    fn clone(&self) -> Self {
        Self {
            pg_pool: self.pg_pool.clone(),
            storage_path: self.storage_path.clone(),
        }
    }
}
