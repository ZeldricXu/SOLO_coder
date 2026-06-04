use chrono::{DateTime, Utc};
use common::error::AppResult;
use rust_decimal::Decimal;
use shared::AuctionStatus;
use sqlx::{PgPool, Row};
use uuid::Uuid;

use crate::{Order, OrderDetail, OrderStatus, ShippingAddress};

pub struct OrderRepository;

impl OrderRepository {
    pub async fn find_auction_info_for_order(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        auction_id: Uuid,
    ) -> AppResult<Option<AuctionOrderInfo>> {
        let row = sqlx::query(
            "
            SELECT seller_id, final_price, title, status
            FROM auctions WHERE id = $1
            "
        )
        .bind(auction_id)
        .fetch_optional(&mut **pool)
        .await?;

        match row {
            Some(r) => {
                let auction_status: AuctionStatus = r.get("status");
                let auction_final_price: Option<Decimal> = r.get("final_price");
                let auction_seller_id: Uuid = r.get("seller_id");
                Ok(Some(AuctionOrderInfo {
                    seller_id: auction_seller_id,
                    final_price: auction_final_price,
                    status: auction_status,
                }))
            }
            None => Ok(None),
        }
    }

    pub async fn exists_by_auction(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        auction_id: Uuid,
    ) -> AppResult<bool> {
        let exists: Option<Uuid> = sqlx::query_scalar(
            "SELECT id FROM orders WHERE auction_id = $1"
        )
        .bind(auction_id)
        .fetch_optional(&mut **pool)
        .await?;
        Ok(exists.is_some())
    }

    pub async fn create(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        auction_id: Uuid,
        buyer_id: Uuid,
        seller_id: Uuid,
        amount: Decimal,
        shipping_address: &ShippingAddress,
    ) -> AppResult<Order> {
        let order = sqlx::query_as::<_, Order>(
            "
            INSERT INTO orders (
                id, auction_id, buyer_id, seller_id, amount,
                status, shipping_address
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            RETURNING
                id, auction_id, buyer_id, seller_id, amount,
                status,
                shipping_address,
                tracking_number, tracking_company, payment_gateway, payment_transaction_id,
                paid_at, shipped_at, delivered_at, completed_at,
                created_at, updated_at
            "
        )
        .bind(id)
        .bind(auction_id)
        .bind(buyer_id)
        .bind(seller_id)
        .bind(amount)
        .bind(OrderStatus::Created)
        .bind(serde_json::to_value(shipping_address)?)
        .fetch_one(&mut **pool)
        .await?;
        Ok(order)
    }

    pub async fn find_detail_by_id_and_user(
        pool: &PgPool,
        id: Uuid,
        user_id: Uuid,
    ) -> AppResult<Option<OrderDetail>> {
        let order = sqlx::query_as::<_, OrderDetail>(
            "
            SELECT
                o.id, o.auction_id, o.buyer_id, o.seller_id, o.amount,
                o.status,
                o.shipping_address,
                o.tracking_number, o.tracking_company, o.payment_gateway, o.payment_transaction_id,
                o.paid_at, o.shipped_at, o.delivered_at, o.completed_at,
                o.created_at, o.updated_at,
                a.title as auction_title,
                ub.username as buyer_name,
                us.username as seller_name,
                (
                    SELECT file_path FROM auction_media
                    WHERE auction_id = o.auction_id AND is_primary = true
                    LIMIT 1
                ) as auction_thumbnail
            FROM orders o
            JOIN auctions a ON o.auction_id = a.id
            JOIN users ub ON o.buyer_id = ub.id
            JOIN users us ON o.seller_id = us.id
            WHERE o.id = $1 AND (o.buyer_id = $2 OR o.seller_id = $2)
            "
        )
        .bind(id)
        .bind(user_id)
        .fetch_optional(pool)
        .await?;
        Ok(order)
    }

    pub async fn find_order_for_update(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        user_id: Uuid,
        status: OrderStatus,
    ) -> AppResult<Option<Order>> {
        let order = sqlx::query_as::<_, Order>(
            "
            SELECT
                id, auction_id, buyer_id, seller_id, amount,
                status,
                shipping_address,
                tracking_number, tracking_company, payment_gateway, payment_transaction_id,
                paid_at, shipped_at, delivered_at, completed_at,
                created_at, updated_at
            FROM orders
            WHERE id = $1 AND buyer_id = $2 AND status = $3
            FOR UPDATE
            "
        )
        .bind(id)
        .bind(user_id)
        .bind(status)
        .fetch_optional(&mut **pool)
        .await?;
        Ok(order)
    }

    pub async fn find_seller_order_for_update(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        seller_id: Uuid,
        status: OrderStatus,
    ) -> AppResult<Option<()>> {
        let exists = sqlx::query(
            "
            SELECT id FROM orders
            WHERE id = $1 AND seller_id = $2 AND status = $3
            FOR UPDATE
            "
        )
        .bind(id)
        .bind(seller_id)
        .bind(status)
        .fetch_optional(&mut **pool)
        .await?;
        Ok(exists.map(|_| ()))
    }

    pub async fn update_status(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        status: OrderStatus,
        extra: OrderUpdateFields,
    ) -> AppResult<Order> {
        let order = sqlx::query_as::<_, Order>(
            "
            UPDATE orders
            SET status = $1, tracking_number = $2, tracking_company = $3, payment_gateway = $4,
                payment_transaction_id = $5, paid_at = $6, shipped_at = $7, delivered_at = $8, completed_at = $9
            WHERE id = $10
            RETURNING
                id, auction_id, buyer_id, seller_id, amount,
                status,
                shipping_address,
                tracking_number, tracking_company, payment_gateway, payment_transaction_id,
                paid_at, shipped_at, delivered_at, completed_at,
                created_at, updated_at
            "
        )
        .bind(status)
        .bind(extra.tracking_number)
        .bind(extra.tracking_company)
        .bind(extra.payment_gateway)
        .bind(extra.payment_transaction_id)
        .bind(extra.paid_at)
        .bind(extra.shipped_at)
        .bind(extra.delivered_at)
        .bind(extra.completed_at)
        .bind(id)
        .fetch_one(&mut **pool)
        .await?;
        Ok(order)
    }

    pub async fn list_by_user(
        pool: &PgPool,
        user_id: Uuid,
        status: Option<OrderStatus>,
        limit: i64,
        offset: i64,
    ) -> AppResult<Vec<OrderDetail>> {
        let orders = match status {
            Some(s) => {
                sqlx::query_as::<_, OrderDetail>(
                    "
                    SELECT
                        o.id, o.auction_id, o.buyer_id, o.seller_id, o.amount,
                        o.status,
                        o.shipping_address,
                        o.tracking_number, o.tracking_company, o.payment_gateway, o.payment_transaction_id,
                        o.paid_at, o.shipped_at, o.delivered_at, o.completed_at,
                        o.created_at, o.updated_at,
                        a.title as auction_title,
                        ub.username as buyer_name,
                        us.username as seller_name,
                        (
                            SELECT file_path FROM auction_media
                            WHERE auction_id = o.auction_id AND is_primary = true
                            LIMIT 1
                        ) as auction_thumbnail
                    FROM orders o
                    JOIN auctions a ON o.auction_id = a.id
                    JOIN users ub ON o.buyer_id = ub.id
                    JOIN users us ON o.seller_id = us.id
                    WHERE (o.buyer_id = $1 OR o.seller_id = $1) AND o.status = $2
                    ORDER BY o.created_at DESC
                    LIMIT $3 OFFSET $4
                    "
                )
                .bind(user_id)
                .bind(s)
                .bind(limit)
                .bind(offset)
                .fetch_all(pool)
                .await?
            }
            None => {
                sqlx::query_as::<_, OrderDetail>(
                    "
                    SELECT
                        o.id, o.auction_id, o.buyer_id, o.seller_id, o.amount,
                        o.status,
                        o.shipping_address,
                        o.tracking_number, o.tracking_company, o.payment_gateway, o.payment_transaction_id,
                        o.paid_at, o.shipped_at, o.delivered_at, o.completed_at,
                        o.created_at, o.updated_at,
                        a.title as auction_title,
                        ub.username as buyer_name,
                        us.username as seller_name,
                        (
                            SELECT file_path FROM auction_media
                            WHERE auction_id = o.auction_id AND is_primary = true
                            LIMIT 1
                        ) as auction_thumbnail
                    FROM orders o
                    JOIN auctions a ON o.auction_id = a.id
                    JOIN users ub ON o.buyer_id = ub.id
                    JOIN users us ON o.seller_id = us.id
                    WHERE o.buyer_id = $1 OR o.seller_id = $1
                    ORDER BY o.created_at DESC
                    LIMIT $2 OFFSET $3
                    "
                )
                .bind(user_id)
                .bind(limit)
                .bind(offset)
                .fetch_all(pool)
                .await?
            }
        };
        Ok(orders)
    }
}

pub struct AuctionOrderInfo {
    pub seller_id: Uuid,
    pub final_price: Option<Decimal>,
    pub status: AuctionStatus,
}

pub struct OrderUpdateFields {
    pub tracking_number: Option<String>,
    pub tracking_company: Option<String>,
    pub payment_gateway: Option<String>,
    pub payment_transaction_id: Option<String>,
    pub paid_at: Option<DateTime<Utc>>,
    pub shipped_at: Option<DateTime<Utc>>,
    pub delivered_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
}
