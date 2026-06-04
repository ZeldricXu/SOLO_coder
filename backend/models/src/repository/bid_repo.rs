use chrono::Utc;
use common::error::AppResult;
use rust_decimal::Decimal;
use sqlx::PgPool;
use uuid::Uuid;

use crate::{Bid, BidHistoryItem};

pub struct BidRepository;

impl BidRepository {
    pub async fn create(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        auction_id: Uuid,
        user_id: Uuid,
        max_price: Decimal,
        bid_price: Decimal,
        is_winning: bool,
        frozen_amount: Decimal,
    ) -> AppResult<Bid> {
        let bid = sqlx::query_as::<_, Bid>(
            "
            INSERT INTO bids (id, auction_id, user_id, max_price, bid_price, is_winning, frozen_amount)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            RETURNING id, auction_id, user_id, max_price, bid_price, is_winning, frozen_amount, created_at
            "
        )
        .bind(id)
        .bind(auction_id)
        .bind(user_id)
        .bind(max_price)
        .bind(bid_price)
        .bind(is_winning)
        .bind(frozen_amount)
        .fetch_one(&mut **pool)
        .await?;
        Ok(bid)
    }

    pub async fn create_non_winning(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        auction_id: Uuid,
        user_id: Uuid,
        max_price: Decimal,
        bid_price: Decimal,
        frozen_amount: Decimal,
    ) -> AppResult<()> {
        sqlx::query(
            "
            INSERT INTO bids (id, auction_id, user_id, max_price, bid_price, is_winning, frozen_amount)
            VALUES ($1, $2, $3, $4, $5, false, $6)
            "
        )
        .bind(id)
        .bind(auction_id)
        .bind(user_id)
        .bind(max_price)
        .bind(bid_price)
        .bind(frozen_amount)
        .execute(&mut **pool)
        .await?;
        Ok(())
    }

    pub async fn find_by_id(pool: &PgPool, id: Uuid) -> AppResult<Option<Bid>> {
        let bid = sqlx::query_as::<_, Bid>(
            "
            SELECT id, auction_id, user_id, max_price, bid_price, is_winning, frozen_amount, created_at
            FROM bids WHERE id = $1
            "
        )
        .bind(id)
        .fetch_optional(pool)
        .await?;
        Ok(bid)
    }

    pub async fn find_history(
        pool: &PgPool,
        user_id: Uuid,
        limit: i64,
        offset: i64,
    ) -> AppResult<Vec<BidHistoryItem>> {
        let history = sqlx::query_as::<_, BidHistoryItem>(
            "
            SELECT b.id, b.auction_id, a.title, b.bid_price, b.is_winning, b.created_at
            FROM bids b
            JOIN auctions a ON b.auction_id = a.id
            WHERE b.user_id = $1
            ORDER BY b.created_at DESC
            LIMIT $2 OFFSET $3
            "
        )
        .bind(user_id)
        .bind(limit)
        .bind(offset)
        .fetch_all(pool)
        .await?;
        Ok(history)
    }
}
