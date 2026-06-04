use chrono::{DateTime, Utc};
use common::error::{AppError, AppResult};
use rust_decimal::Decimal;
use shared::AuctionStatus;
use sqlx::PgPool;
use uuid::Uuid;

use crate::{Auction, AuctionListItem, AuctionMedia, CreateAuctionRequest};

pub struct AuctionRepository;

impl AuctionRepository {
    pub async fn find_by_id(pool: &PgPool, id: Uuid) -> AppResult<Option<Auction>> {
        let auction = sqlx::query_as::<_, Auction>(
            "
            SELECT id, seller_id, category_id, title, description,
                   starting_price, reserve_price, current_price,
                   price_decrement, decrement_interval_seconds, duration_seconds,
                   status,
                   start_time, end_time, winner_id, final_price,
                   view_count, risk_score, review_note, reviewed_by, reviewed_at,
                   created_at, updated_at
            FROM auctions
            WHERE id = $1
            "
        )
        .bind(id)
        .fetch_optional(pool)
        .await?;
        Ok(auction)
    }

    pub async fn find_by_id_for_update(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
    ) -> AppResult<Option<Auction>> {
        let auction = sqlx::query_as::<_, Auction>(
            "
            SELECT id, seller_id, category_id, title, description,
                   starting_price, reserve_price, current_price,
                   price_decrement, decrement_interval_seconds, duration_seconds,
                   status,
                   start_time, end_time, winner_id, final_price,
                   view_count, risk_score, review_note, reviewed_by, reviewed_at,
                   created_at, updated_at
            FROM auctions
            WHERE id = $1
            FOR UPDATE
            "
        )
        .bind(id)
        .fetch_optional(&mut **pool)
        .await?;
        Ok(auction)
    }

    pub async fn find_by_id_and_status_for_update(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        status: AuctionStatus,
    ) -> AppResult<Option<Auction>> {
        let auction = sqlx::query_as::<_, Auction>(
            "
            SELECT id, seller_id, category_id, title, description,
                   starting_price, reserve_price, current_price,
                   price_decrement, decrement_interval_seconds, duration_seconds,
                   status,
                   start_time, end_time, winner_id, final_price,
                   view_count, risk_score, review_note, reviewed_by, reviewed_at,
                   created_at, updated_at
            FROM auctions
            WHERE id = $1 AND status = $2
            FOR UPDATE
            "
        )
        .bind(id)
        .bind(status)
        .fetch_optional(&mut **pool)
        .await?;
        Ok(auction)
    }

    pub async fn find_active(pool: &PgPool) -> AppResult<Vec<Auction>> {
        let auctions = sqlx::query_as::<_, Auction>(
            "
            SELECT id, seller_id, category_id, title, description,
                   starting_price, reserve_price, current_price,
                   price_decrement, decrement_interval_seconds, duration_seconds,
                   status,
                   start_time, end_time, winner_id, final_price,
                   view_count, risk_score, review_note, reviewed_by, reviewed_at,
                   created_at, updated_at
            FROM auctions
            WHERE status = $1
            "
        )
        .bind(AuctionStatus::Active)
        .fetch_all(pool)
        .await?;
        Ok(auctions)
    }

    pub async fn create(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        seller_id: Uuid,
        category_id: Option<Uuid>,
        title: String,
        description: String,
        starting_price: Decimal,
        reserve_price: Decimal,
        current_price: Decimal,
        price_decrement: Decimal,
        decrement_interval_seconds: i32,
        duration_seconds: i32,
        status: AuctionStatus,
        start_time: DateTime<Utc>,
        end_time: DateTime<Utc>,
    ) -> AppResult<Auction> {
        let auction = sqlx::query_as::<_, Auction>(
            "
            INSERT INTO auctions (
                id, seller_id, category_id, title, description,
                starting_price, reserve_price, current_price,
                price_decrement, decrement_interval_seconds, duration_seconds,
                status, start_time, end_time
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
            RETURNING
                id, seller_id, category_id, title, description,
                starting_price, reserve_price, current_price,
                price_decrement, decrement_interval_seconds, duration_seconds,
                status,
                start_time, end_time, winner_id, final_price,
                view_count, risk_score, review_note, reviewed_by, reviewed_at,
                created_at, updated_at
            "
        )
        .bind(id)
        .bind(seller_id)
        .bind(category_id)
        .bind(title)
        .bind(description)
        .bind(starting_price)
        .bind(reserve_price)
        .bind(current_price)
        .bind(price_decrement)
        .bind(decrement_interval_seconds)
        .bind(duration_seconds)
        .bind(status)
        .bind(start_time)
        .bind(end_time)
        .fetch_one(&mut **pool)
        .await?;
        Ok(auction)
    }

    pub async fn update_status(
        pool: &PgPool,
        id: Uuid,
        from_status: AuctionStatus,
        to_status: AuctionStatus,
        extra_fields: Option<UpdateStatusFields>,
    ) -> AppResult<bool> {
        let rows = match extra_fields {
            Some(f) => {
                sqlx::query(
                    "
                    UPDATE auctions
                    SET status = $1, winner_id = $2, final_price = $3, end_time = $4
                    WHERE id = $5 AND status = $6
                    "
                )
                .bind(to_status)
                .bind(f.winner_id)
                .bind(f.final_price)
                .bind(f.end_time)
                .bind(id)
                .bind(from_status)
                .execute(pool)
                .await?
            }
            None => {
                sqlx::query(
                    "
                    UPDATE auctions SET status = $1, end_time = $2
                    WHERE id = $3 AND status = $4
                    "
                )
                .bind(to_status)
                .bind(Utc::now())
                .bind(id)
                .bind(from_status)
                .execute(pool)
                .await?
            }
        };
        Ok(rows.rows_affected() > 0)
    }

    pub async fn update_status_in_tx(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        from_status: AuctionStatus,
        to_status: AuctionStatus,
        extra_fields: Option<UpdateStatusFields>,
    ) -> AppResult<bool> {
        let rows = match extra_fields {
            Some(f) => {
                sqlx::query(
                    "
                    UPDATE auctions
                    SET status = $1, winner_id = $2, final_price = $3, end_time = $4
                    WHERE id = $5 AND status = $6
                    "
                )
                .bind(to_status)
                .bind(f.winner_id)
                .bind(f.final_price)
                .bind(f.end_time)
                .bind(id)
                .bind(from_status)
                .execute(&mut **pool)
                .await?
            }
            None => {
                sqlx::query(
                    "
                    UPDATE auctions SET status = $1, end_time = $2
                    WHERE id = $3 AND status = $4
                    "
                )
                .bind(to_status)
                .bind(Utc::now())
                .bind(id)
                .bind(from_status)
                .execute(&mut **pool)
                .await?
            }
        };
        Ok(rows.rows_affected() > 0)
    }

    pub async fn increment_view_count(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
    ) -> AppResult<()> {
        sqlx::query("UPDATE auctions SET view_count = view_count + 1 WHERE id = $1")
            .bind(id)
            .execute(&mut **pool)
            .await?;
        Ok(())
    }

    pub async fn update_review(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        status: AuctionStatus,
        review_note: Option<String>,
        reviewed_by: Uuid,
        reviewed_at: DateTime<Utc>,
        approved: bool,
        now: DateTime<Utc>,
    ) -> AppResult<Auction> {
        let updated = sqlx::query_as::<_, Auction>(
            "
            UPDATE auctions
            SET status = $1, review_note = $2, reviewed_by = $3, reviewed_at = $4,
                start_time = CASE WHEN $5 THEN COALESCE(start_time, $6) ELSE start_time END,
                end_time = CASE WHEN $5 THEN COALESCE(end_time, $6 + (duration_seconds || ' seconds')::interval) ELSE end_time END
            WHERE id = $7
            RETURNING
                id, seller_id, category_id, title, description,
                starting_price, reserve_price, current_price,
                price_decrement, decrement_interval_seconds, duration_seconds,
                status,
                start_time, end_time, winner_id, final_price,
                view_count, risk_score, review_note, reviewed_by, reviewed_at,
                created_at, updated_at
            "
        )
        .bind(status)
        .bind(review_note)
        .bind(reviewed_by)
        .bind(reviewed_at)
        .bind(approved)
        .bind(now)
        .bind(id)
        .fetch_one(&mut **pool)
        .await?;
        Ok(updated)
    }

    pub async fn find_scheduled_ready(
        pool: &PgPool,
        now: DateTime<Utc>,
    ) -> AppResult<Vec<(Uuid, i32)>> {
        let auctions = sqlx::query_as::<_, (Uuid, i32)>(
            r#"
            SELECT id, duration_seconds
            FROM auctions
            WHERE status = $1 AND start_time <= $2
            FOR UPDATE
            "#,
        )
        .bind(AuctionStatus::Scheduled)
        .bind(now)
        .fetch_all(pool)
        .await?;
        Ok(auctions)
    }

    pub async fn activate_scheduled(
        pool: &PgPool,
        id: Uuid,
        end_time: DateTime<Utc>,
    ) -> AppResult<()> {
        sqlx::query(
            "
            UPDATE auctions
            SET status = $1, end_time = $2
            WHERE id = $3
            "
        )
        .bind(AuctionStatus::Active)
        .bind(end_time)
        .bind(id)
        .execute(pool)
        .await?;
        Ok(())
    }

    pub async fn list_with_filters(
        pool: &PgPool,
        status: AuctionStatus,
        category_id: Option<Uuid>,
        min_price: Option<Decimal>,
        max_price: Option<Decimal>,
        max_time_left: Option<i64>,
        order_clause: &str,
        limit: i64,
        offset: i64,
    ) -> AppResult<Vec<AuctionListItem>> {
        let sql = format!(
            r#"
            SELECT
                a.id, a.title, a.current_price, a.starting_price, a.reserve_price,
                a.status,
                a.start_time, a.end_time,
                a.category_id, c.name as category_name,
                (
                    SELECT file_path FROM auction_media am
                    WHERE am.auction_id = a.id AND am.is_primary = true
                    LIMIT 1
                ) as primary_image,
                a.view_count,
                CASE
                    WHEN a.status = 'active' AND a.end_time IS NOT NULL
                    THEN EXTRACT(EPOCH FROM (a.end_time - NOW()))::bigint
                    ELSE NULL
                END as time_left_seconds
            FROM auctions a
            LEFT JOIN categories c ON a.category_id = c.id
            WHERE a.status = $1
              AND ($2::uuid IS NULL OR a.category_id = $2)
              AND ($3::numeric IS NULL OR a.current_price >= $3)
              AND ($4::numeric IS NULL OR a.current_price <= $4)
              AND ($5::bigint IS NULL OR (
                a.status = 'active' AND a.end_time IS NOT NULL AND
                EXTRACT(EPOCH FROM (a.end_time - NOW()))::bigint <= $5
              ))
            {order_clause}
            LIMIT $6 OFFSET $7
            "#
        );

        let auctions = sqlx::query_as::<_, AuctionListItem>(&sql)
            .bind(status)
            .bind(category_id)
            .bind(min_price)
            .bind(max_price)
            .bind(max_time_left)
            .bind(limit)
            .bind(offset)
            .fetch_all(pool)
            .await?;
        Ok(auctions)
    }

    pub async fn list_by_seller(
        pool: &PgPool,
        seller_id: Uuid,
        status: Option<AuctionStatus>,
        limit: i64,
        offset: i64,
    ) -> AppResult<Vec<AuctionListItem>> {
        let auctions = sqlx::query_as::<_, AuctionListItem>(
            "
            SELECT
                a.id, a.title, a.current_price, a.starting_price, a.reserve_price,
                a.status,
                a.start_time, a.end_time,
                a.category_id, c.name as category_name,
                (
                    SELECT file_path FROM auction_media am
                    WHERE am.auction_id = a.id AND am.is_primary = true
                    LIMIT 1
                ) as primary_image,
                a.view_count,
                CASE
                    WHEN a.status = 'active' AND a.end_time IS NOT NULL
                    THEN EXTRACT(EPOCH FROM (a.end_time - NOW()))::bigint
                    ELSE NULL
                END as time_left_seconds
            FROM auctions a
            LEFT JOIN categories c ON a.category_id = c.id
            WHERE a.seller_id = $1
              AND ($2::auction_status IS NULL OR a.status = $2)
            ORDER BY a.created_at DESC
            LIMIT $3 OFFSET $4
            "
        )
        .bind(seller_id)
        .bind(status)
        .bind(limit)
        .bind(offset)
        .fetch_all(pool)
        .await?;
        Ok(auctions)
    }

    pub async fn find_seller_id(pool: &PgPool, id: Uuid) -> AppResult<Option<(Uuid, AuctionStatus)>> {
        let result = sqlx::query_as::<_, (Uuid, AuctionStatus)>(
            "SELECT seller_id, status FROM auctions WHERE id = $1"
        )
        .bind(id)
        .fetch_optional(pool)
        .await?;
        Ok(result)
    }

    pub async fn find_category_id(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
    ) -> AppResult<Option<Option<Uuid>>> {
        let result: Option<Option<Uuid>> = sqlx::query_scalar(
            "SELECT category_id FROM auctions WHERE id = $1"
        )
        .bind(id)
        .fetch_optional(&mut **pool)
        .await?;
        Ok(result)
    }

    pub async fn update_current_price(pool: &PgPool, id: Uuid, price: Decimal) -> AppResult<()> {
        sqlx::query("UPDATE auctions SET current_price = $1, updated_at = NOW() WHERE id = $2")
            .bind(price)
            .bind(id)
            .execute(pool)
            .await?;
        Ok(())
    }
}

pub struct UpdateStatusFields {
    pub winner_id: Option<Uuid>,
    pub final_price: Option<Decimal>,
    pub end_time: Option<DateTime<Utc>>,
}

pub struct AuctionMediaRepository;

impl AuctionMediaRepository {
    pub async fn create(
        pool: &PgPool,
        id: Uuid,
        auction_id: Uuid,
        media_type: &str,
        file_path: &str,
        file_size: i64,
        mime_type: Option<String>,
        sort_order: i32,
        is_primary: bool,
    ) -> AppResult<AuctionMedia> {
        let media = sqlx::query_as::<_, AuctionMedia>(
            "
            INSERT INTO auction_media (
                id, auction_id, media_type, file_path, file_size, mime_type, sort_order, is_primary
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
            RETURNING id, auction_id, media_type, file_path, file_size, mime_type, sort_order, is_primary, created_at
            "
        )
        .bind(id)
        .bind(auction_id)
        .bind(media_type)
        .bind(file_path)
        .bind(file_size)
        .bind(mime_type)
        .bind(sort_order)
        .bind(is_primary)
        .fetch_one(pool)
        .await?;
        Ok(media)
    }

    pub async fn clear_primary(pool: &PgPool, auction_id: Uuid) -> AppResult<()> {
        sqlx::query("UPDATE auction_media SET is_primary = false WHERE auction_id = $1")
            .bind(auction_id)
            .execute(pool)
            .await?;
        Ok(())
    }

    pub async fn find_by_auction(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        auction_id: Uuid,
    ) -> AppResult<Vec<AuctionMedia>> {
        let media = sqlx::query_as::<_, AuctionMedia>(
            "
            SELECT id, auction_id, media_type, file_path, file_size, mime_type, sort_order, is_primary, created_at
            FROM auction_media
            WHERE auction_id = $1
            ORDER BY sort_order ASC, created_at ASC
            "
        )
        .bind(auction_id)
        .fetch_all(&mut **pool)
        .await?;
        Ok(media)
    }

    pub async fn find_seller_by_media_id(pool: &PgPool, media_id: Uuid) -> AppResult<Option<Uuid>> {
        let result: Option<Uuid> = sqlx::query_scalar(
            r#"
            SELECT a.seller_id
            FROM auction_media am
            JOIN auctions a ON am.auction_id = a.id
            WHERE am.id = $1
            "#,
        )
        .bind(media_id)
        .fetch_optional(pool)
        .await?;
        Ok(result)
    }

    pub async fn delete(pool: &PgPool, media_id: Uuid) -> AppResult<()> {
        sqlx::query("DELETE FROM auction_media WHERE id = $1")
            .bind(media_id)
            .execute(pool)
            .await?;
        Ok(())
    }
}

pub struct CategoryRepository;

impl CategoryRepository {
    pub async fn find_name_by_auction(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        auction_id: Uuid,
    ) -> AppResult<Option<String>> {
        let name: Option<String> = sqlx::query_scalar(
            "SELECT name FROM categories WHERE id = (SELECT category_id FROM auctions WHERE id = $1)"
        )
        .bind(auction_id)
        .fetch_optional(&mut **pool)
        .await?;
        Ok(name)
    }
}

pub struct SellerRepository;

impl SellerRepository {
    pub async fn find_name_by_auction(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        auction_id: Uuid,
    ) -> AppResult<String> {
        let name: String = sqlx::query_scalar(
            "SELECT username FROM users WHERE id = (SELECT seller_id FROM auctions WHERE id = $1)"
        )
        .bind(auction_id)
        .fetch_one(&mut **pool)
        .await?;
        Ok(name)
    }
}
