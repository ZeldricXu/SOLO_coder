use chrono::Utc;
use common::error::{AppError, AppResult};
use models::{Order, OrderDetail, OrderRepository, OrderStatus, OrderUpdateFields, ShippingAddress};
use shared::AuctionStatus;
use sqlx::PgPool;
use tracing::{info, warn};
use uuid::Uuid;

pub struct OrderService {
    pg_pool: PgPool,
}

impl OrderService {
    pub fn new(pg_pool: PgPool) -> Self {
        Self { pg_pool }
    }

    pub async fn create_order_from_auction(
        &self,
        auction_id: Uuid,
        buyer_id: Uuid,
        shipping_address: ShippingAddress,
    ) -> AppResult<Order> {
        let mut tx = self.pg_pool.begin().await?;

        let auction_info = OrderRepository::find_auction_info_for_order(&mut tx, auction_id)
            .await?
            .ok_or_else(|| AppError::NotFound("拍卖不存在".into()))?;

        if auction_info.status != AuctionStatus::Sold {
            return Err(AppError::Auction("拍卖状态不正确".into()));
        }

        if OrderRepository::exists_by_auction(&mut tx, auction_id).await? {
            return Err(AppError::Auction("该拍卖已有订单".into()));
        }

        let final_price = auction_info.final_price.ok_or_else(|| {
            AppError::Auction("拍卖价格信息缺失".into())
        })?;

        let order_id = Uuid::new_v4();
        let order = OrderRepository::create(
            &mut tx,
            order_id,
            auction_id,
            buyer_id,
            auction_info.seller_id,
            final_price,
            &shipping_address,
        )
        .await?;

        tx.commit().await?;

        info!(order_id = %order_id, auction_id = %auction_id, "Order created from auction");
        Ok(order)
    }

    pub async fn get_order(&self, order_id: Uuid, user_id: Uuid) -> AppResult<OrderDetail> {
        let order = OrderRepository::find_detail_by_id_and_user(&self.pg_pool, order_id, user_id)
            .await?
            .ok_or_else(|| AppError::NotFound("订单不存在".into()))?;

        Ok(order)
    }

    pub async fn get_my_orders(
        &self,
        user_id: Uuid,
        status: Option<OrderStatus>,
        limit: i64,
        offset: i64,
    ) -> AppResult<Vec<OrderDetail>> {
        let orders = OrderRepository::list_by_user(&self.pg_pool, user_id, status, limit, offset).await?;
        Ok(orders)
    }

    pub async fn mark_paid(
        &self,
        order_id: Uuid,
        buyer_id: Uuid,
        payment_gateway: &str,
        transaction_id: &str,
    ) -> AppResult<Order> {
        let mut tx = self.pg_pool.begin().await?;

        let _order = OrderRepository::find_order_for_update(&mut tx, order_id, buyer_id, OrderStatus::Created)
            .await?
            .ok_or_else(|| AppError::NotFound("订单不存在或状态不正确".into()))?;

        let now = Utc::now();
        let updated = OrderRepository::update_status(
            &mut tx,
            order_id,
            OrderStatus::Paid,
            OrderUpdateFields {
                tracking_number: None,
                tracking_company: None,
                payment_gateway: Some(payment_gateway.to_string()),
                payment_transaction_id: Some(transaction_id.to_string()),
                paid_at: Some(now),
                shipped_at: None,
                delivered_at: None,
                completed_at: None,
            },
        )
        .await?;

        tx.commit().await?;

        info!(order_id = %order_id, "Order marked as paid");
        Ok(updated)
    }

    pub async fn mark_shipped(
        &self,
        order_id: Uuid,
        seller_id: Uuid,
        tracking_number: &str,
        tracking_company: &str,
    ) -> AppResult<Order> {
        let mut tx = self.pg_pool.begin().await?;

        OrderRepository::find_seller_order_for_update(&mut tx, order_id, seller_id, OrderStatus::Paid)
            .await?
            .ok_or_else(|| AppError::NotFound("订单不存在或状态不正确".into()))?;

        let now = Utc::now();
        let updated = OrderRepository::update_status(
            &mut tx,
            order_id,
            OrderStatus::Shipped,
            OrderUpdateFields {
                tracking_number: Some(tracking_number.to_string()),
                tracking_company: Some(tracking_company.to_string()),
                payment_gateway: None,
                payment_transaction_id: None,
                paid_at: None,
                shipped_at: Some(now),
                delivered_at: None,
                completed_at: None,
            },
        )
        .await?;

        tx.commit().await?;

        info!(order_id = %order_id, "Order marked as shipped");
        Ok(updated)
    }

    pub async fn mark_delivered(
        &self,
        order_id: Uuid,
        buyer_id: Uuid,
    ) -> AppResult<Order> {
        let mut tx = self.pg_pool.begin().await?;

        let _order = OrderRepository::find_order_for_update(&mut tx, order_id, buyer_id, OrderStatus::Shipped)
            .await?
            .ok_or_else(|| AppError::NotFound("订单不存在或状态不正确".into()))?;

        let now = Utc::now();
        let updated = OrderRepository::update_status(
            &mut tx,
            order_id,
            OrderStatus::Delivered,
            OrderUpdateFields {
                tracking_number: None,
                tracking_company: None,
                payment_gateway: None,
                payment_transaction_id: None,
                paid_at: None,
                shipped_at: None,
                delivered_at: Some(now),
                completed_at: None,
            },
        )
        .await?;

        tx.commit().await?;

        info!(order_id = %order_id, "Order marked as delivered");
        Ok(updated)
    }

    pub async fn confirm_receipt(
        &self,
        order_id: Uuid,
        buyer_id: Uuid,
    ) -> AppResult<Order> {
        let mut tx = self.pg_pool.begin().await?;

        let order = OrderRepository::find_order_for_update(&mut tx, order_id, buyer_id, OrderStatus::Delivered)
            .await?;
        let _order = match order {
            Some(o) => o,
            None => OrderRepository::find_order_for_update(&mut tx, order_id, buyer_id, OrderStatus::Shipped)
                .await?
                .ok_or_else(|| AppError::NotFound("订单不存在或状态不正确".into()))?,
        };

        let now = Utc::now();
        let updated = OrderRepository::update_status(
            &mut tx,
            order_id,
            OrderStatus::Completed,
            OrderUpdateFields {
                tracking_number: None,
                tracking_company: None,
                payment_gateway: None,
                payment_transaction_id: None,
                paid_at: None,
                shipped_at: None,
                delivered_at: None,
                completed_at: Some(now),
            },
        )
        .await?;

        tx.commit().await?;

        info!(order_id = %order_id, "Order completed, settlement pending");
        Ok(updated)
    }
}

impl Clone for OrderService {
    fn clone(&self) -> Self {
        Self {
            pg_pool: self.pg_pool.clone(),
        }
    }
}
