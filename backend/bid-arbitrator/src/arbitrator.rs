use chrono::Utc;
use common::{error::AppResult, DistributedLock};
use models::{AuctionRepository, BidRepository, UserRepository, UpdateStatusFields, AuctionEvent, BidResult};
use mongodb::Collection;
use redis::aio::MultiplexedConnection;
use rust_decimal::Decimal;
use mongodb::bson;
use std::str::FromStr;
use shared::{AuctionStatus, BidResponse, TransactionType};
use sqlx::PgPool;
use std::sync::Arc;
use tracing::{error, info, warn};
use uuid::Uuid;

pub struct BidArbitrator {
    pg_pool: PgPool,
    redis: MultiplexedConnection,
    lock_manager: Arc<DistributedLock>,
    mongo_events: Collection<AuctionEvent>,
    price_complete_callback: Option<Arc<dyn Fn(Uuid, Uuid, Decimal) -> AppResult<()> + Send + Sync>>,
    auction_fail_callback: Option<Arc<dyn Fn(Uuid) -> AppResult<()> + Send + Sync>>,
    on_bid_callback: Option<Arc<dyn Fn(Uuid) -> AppResult<()> + Send + Sync>>,
    unfreeze_callback: Option<Arc<dyn Fn(Uuid, Uuid, Decimal, Option<Uuid>, String) -> AppResult<()> + Send + Sync>>,
    freeze_callback: Option<Arc<dyn Fn(Uuid, Uuid, Decimal, Option<Uuid>, String) -> AppResult<()> + Send + Sync>>,
}

impl BidArbitrator {
    pub fn new(
        pg_pool: PgPool,
        redis: MultiplexedConnection,
        lock_manager: DistributedLock,
        mongo_events: Collection<AuctionEvent>,
    ) -> Self {
        Self {
            pg_pool,
            redis,
            lock_manager: Arc::new(lock_manager),
            mongo_events,
            price_complete_callback: None,
            auction_fail_callback: None,
            on_bid_callback: None,
            unfreeze_callback: None,
            freeze_callback: None,
        }
    }

    pub fn with_price_complete<F>(mut self, callback: F) -> Self
    where
        F: Fn(Uuid, Uuid, Decimal) -> AppResult<()> + Send + Sync + 'static,
    {
        self.price_complete_callback = Some(Arc::new(callback));
        self
    }

    pub fn with_auction_fail<F>(mut self, callback: F) -> Self
    where
        F: Fn(Uuid) -> AppResult<()> + Send + Sync + 'static,
    {
        self.auction_fail_callback = Some(Arc::new(callback));
        self
    }

    pub fn with_on_bid<F>(mut self, callback: F) -> Self
    where
        F: Fn(Uuid) -> AppResult<()> + Send + Sync + 'static,
    {
        self.on_bid_callback = Some(Arc::new(callback));
        self
    }

    pub fn with_unfreeze<F>(mut self, callback: F) -> Self
    where
        F: Fn(Uuid, Uuid, Decimal, Option<Uuid>, String) -> AppResult<()> + Send + Sync + 'static,
    {
        self.unfreeze_callback = Some(Arc::new(callback));
        self
    }

    pub fn with_freeze<F>(mut self, callback: F) -> Self
    where
        F: Fn(Uuid, Uuid, Decimal, Option<Uuid>, String) -> AppResult<()> + Send + Sync + 'static,
    {
        self.freeze_callback = Some(Arc::new(callback));
        self
    }

    pub async fn process_bid(&self, auction_id: Uuid, user_id: Uuid, max_price: Decimal) -> AppResult<BidResult> {
        info!(
            auction_id = %auction_id,
            user_id = %user_id,
            max_price = %max_price,
            "Processing bid"
        );

        let lock_key = format!("bid:{}", auction_id);
        let lock = self.lock_manager.lock(&lock_key)
            .map_err(|e| {
                warn!(auction_id = %auction_id, error = %e, "Failed to acquire bid lock, concurrent bid detected");
                common::error::AppError::Bid("拍卖太火爆了，请稍后重试".into())
            })?;

        let result = self.process_bid_locked(auction_id, user_id, max_price).await;

        if let Some(ref on_bid_cb) = self.on_bid_callback {
            if result.is_ok() {
                if let Err(e) = on_bid_cb(auction_id) {
                    error!(auction_id = %auction_id, error = %e, "On bid callback failed");
                }
            }
        }

        self.lock_manager.unlock(&lock);
        result
    }

    async fn process_bid_locked(&self, auction_id: Uuid, user_id: Uuid, max_price: Decimal) -> AppResult<BidResult> {
        let mut tx = self.pg_pool.begin().await?;

        let auction = AuctionRepository::find_by_id_for_update(&mut tx, auction_id)
            .await?
            .ok_or_else(|| common::error::AppError::NotFound("拍卖不存在".into()))?;

        if auction.status != AuctionStatus::Active {
            return Ok(BidResult {
                success: false,
                bid_id: None,
                price: None,
                is_winner: false,
                message: "拍卖已结束".into(),
            });
        }

        if auction.seller_id == user_id {
            return Ok(BidResult {
                success: false,
                bid_id: None,
                price: None,
                is_winner: false,
                message: "不能竞拍自己的商品".into(),
            });
        }

        let current_price = self.get_current_price_from_cache_or_db(auction_id).await?.unwrap_or(auction.current_price);

        if max_price < current_price {
            return Ok(BidResult {
                success: false,
                bid_id: None,
                price: None,
                is_winner: false,
                message: format!("当前价格已降至 ¥{:.2}，您的出价低于当前价格", current_price),
            });
        }

        if current_price < auction.reserve_price {
            let mut tx = self.pg_pool.begin().await?;

            let deposit_amount = current_price * rust_decimal_macros::dec!(0.1);

            if let Some(ref freeze_cb) = self.freeze_callback {
                freeze_cb(user_id, auction_id, deposit_amount, None, "拍卖保证金冻结".to_string())?;
            } else {
                self.freeze_balance(&mut tx, user_id, deposit_amount).await?;
            }

            let bid_id = Uuid::new_v4();
            BidRepository::create_non_winning(&mut tx, bid_id, auction_id, user_id, max_price, current_price, deposit_amount).await?;

            AuctionRepository::update_status_in_tx(&mut tx, auction_id, AuctionStatus::Active, AuctionStatus::Failed, None).await?;

            tx.commit().await?;

            if let Some(ref unfreeze_cb) = self.unfreeze_callback {
                if let Err(e) = unfreeze_cb(user_id, auction_id, deposit_amount, None, "流拍保证金解冻".to_string()) {
                    error!(auction_id = %auction_id, error = %e, "Unfreeze callback failed");
                }
            }

            if let Some(ref fail_cb) = self.auction_fail_callback {
                if let Err(e) = fail_cb(auction_id) {
                    error!(auction_id = %auction_id, error = %e, "Auction fail callback failed");
                }
            }

            self.clear_auction_cache(auction_id).await?;
            self.record_auction_event(auction_id, Some(user_id), current_price, "reserve_not_met").await?;

            warn!(
                auction_id = %auction_id,
                user_id = %user_id,
                reserve_price = %auction.reserve_price,
                bid_price = %current_price,
                "Bid failed due to reserve price not met"
            );

            return Ok(BidResult {
                success: false,
                bid_id: None,
                price: None,
                is_winner: false,
                message: format!("未达到卖家保留价 ¥{:.2}，拍卖流拍，保证金已退还", auction.reserve_price),
            });
        }

        let final_price = current_price;
        let deposit_amount = final_price * rust_decimal_macros::dec!(0.1);

        if let Some(ref freeze_cb) = self.freeze_callback {
            freeze_cb(user_id, auction_id, deposit_amount, None, "拍卖保证金冻结".to_string())?;
        } else {
            self.freeze_balance(&mut tx, user_id, deposit_amount).await?;
        }

        let bid_id = Uuid::new_v4();
        BidRepository::create(&mut tx, bid_id, auction_id, user_id, max_price, final_price, true, deposit_amount).await?;

        let now = Utc::now();
        AuctionRepository::update_status_in_tx(
            &mut tx,
            auction_id,
            AuctionStatus::Active,
            AuctionStatus::Sold,
            Some(UpdateStatusFields {
                winner_id: Some(user_id),
                final_price: Some(final_price),
                end_time: Some(now),
            }),
        ).await?;

        tx.commit().await?;

        if let Some(ref cb) = self.price_complete_callback {
            if let Err(e) = cb(auction_id, user_id, final_price) {
                error!(auction_id = %auction_id, error = %e, "Price complete callback failed");
            }
        }

        self.clear_auction_cache(auction_id).await?;
        self.record_auction_event(auction_id, Some(user_id), final_price, "bid_won").await?;

        info!(
            auction_id = %auction_id,
            bid_id = %bid_id,
            winner_id = %user_id,
            price = %final_price,
            "Bid processed successfully"
        );

        Ok(BidResult {
            success: true,
            bid_id: Some(bid_id),
            price: Some(final_price),
            is_winner: true,
            message: "恭喜！您已成功拍得此商品".into(),
        })
    }

    async fn get_current_price_from_cache_or_db(&self, auction_id: Uuid) -> AppResult<Option<Decimal>> {
        let key = format!("auction:{}:price", auction_id);
        let mut con = self.redis.clone();

        let cache_price: Option<f64> = redis::cmd("GET").arg(&key).query_async(&mut con).await?;

        if let Some(p) = cache_price {
            return Ok(Some(Decimal::from_f64_retain(p).unwrap_or_default()));
        }

        let auction = AuctionRepository::find_by_id(&self.pg_pool, auction_id).await?;
        Ok(auction.map(|a| a.current_price))
    }

    async fn freeze_balance(
        &self,
        tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        user_id: Uuid,
        amount: Decimal,
    ) -> AppResult<()> {
        let frozen = UserRepository::freeze_balance(tx, user_id, amount).await?;
        if !frozen {
            return Err(common::error::AppError::Account("余额不足，请先充值".into()));
        }
        Ok(())
    }

    async fn clear_auction_cache(&self, auction_id: Uuid) -> AppResult<()> {
        let mut con = self.redis.clone();
        redis::pipe()
            .atomic()
            .del(format!("auction:{}:price", auction_id))
            .del(format!("auction:{}:status", auction_id))
            .query_async::<_, ()>(&mut con)
            .await?;
        Ok(())
    }

    async fn record_auction_event(
        &self,
        auction_id: Uuid,
        user_id: Option<Uuid>,
        price: Decimal,
        event_type: &str,
    ) -> AppResult<()> {
        let event = AuctionEvent {
            id: None,
            event_id: Uuid::new_v4(),
            auction_id,
            event_type: event_type.to_string(),
            user_id,
            price: Some(price),
            metadata: Some(bson::doc! {"source": "bid_arbitrator"}),
            timestamp: Utc::now(),
        };

        self.mongo_events.insert_one(event, None).await?;
        Ok(())
    }

    pub async fn get_bid_history(&self, user_id: Uuid, limit: i64, offset: i64) -> AppResult<Vec<models::BidHistoryItem>> {
        let history = BidRepository::find_history(&self.pg_pool, user_id, limit, offset).await?;
        Ok(history)
    }

    pub async fn refund_failed_bid(&self, bid_id: Uuid) -> AppResult<()> {
        let bid = BidRepository::find_by_id(&self.pg_pool, bid_id)
            .await?
            .ok_or_else(|| common::error::AppError::NotFound("出价记录不存在".into()))?;

        if bid.is_winning {
            return Err(common::error::AppError::Bid("得标的出价无法退款".into()));
        }

        let mut tx = self.pg_pool.begin().await?;

        UserRepository::unfreeze_balance(&mut tx, bid.user_id, bid.frozen_amount).await?;

        tx.commit().await?;

        info!(bid_id = %bid_id, user_id = %bid.user_id, amount = %bid.frozen_amount, "Bid refunded");
        Ok(())
    }
}

impl Clone for BidArbitrator {
    fn clone(&self) -> Self {
        Self {
            pg_pool: self.pg_pool.clone(),
            redis: self.redis.clone(),
            lock_manager: self.lock_manager.clone(),
            mongo_events: self.mongo_events.clone(),
            price_complete_callback: self.price_complete_callback.clone(),
            auction_fail_callback: self.auction_fail_callback.clone(),
            on_bid_callback: self.on_bid_callback.clone(),
            unfreeze_callback: self.unfreeze_callback.clone(),
            freeze_callback: self.freeze_callback.clone(),
        }
    }
}
