use chrono::{DateTime, Duration, Utc};
use common::error::AppResult;
use models::AuctionEvent;
use mongodb::Collection;
use redis::aio::MultiplexedConnection;
use rust_decimal::{prelude::ToPrimitive, Decimal};
use serde::{Deserialize, Serialize};
use shared::{AuctionStatus, PriceHistoryPoint, PriceUpdate};
use sqlx::PgPool;
use std::sync::Arc;
use std::time::Duration as StdDuration;
use tokio::sync::broadcast;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

const ACTIVE_AUCTIONS_KEY: &str = "auction:active_ids";
const AUCTION_STATE_PREFIX: &str = "auction:state:";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RedisAuctionState {
    pub auction_id: Uuid,
    pub current_price: Decimal,
    pub start_price: Decimal,
    pub floor_price: Decimal,
    pub reserve_price: Decimal,
    pub price_decrement: Decimal,
    pub decrement_interval: i32,
    pub start_time: DateTime<Utc>,
    pub end_time: DateTime<Utc>,
    pub last_decrement: DateTime<Utc>,
    pub status: AuctionStatus,
    pub price_history: Vec<PriceHistoryPoint>,
}

impl RedisAuctionState {
    pub fn push_history(&mut self, point: PriceHistoryPoint) {
        self.price_history.push(point);
        while self.price_history.len() > 30 {
            self.price_history.remove(0);
        }
    }

    pub fn generate_forecast(&self) -> Vec<PriceHistoryPoint> {
        let mut forecast = Vec::new();
        let mut price = self.current_price;
        let mut time = self.last_decrement;

        for _ in 0..30 {
            time = time + Duration::seconds(self.decrement_interval as i64);
            if time >= self.end_time {
                break;
            }
            price = price - self.price_decrement;
            if price <= self.reserve_price {
                price = self.reserve_price;
            }
            forecast.push(PriceHistoryPoint {
                timestamp: time,
                price,
            });
            if price == self.reserve_price {
                break;
            }
        }
        forecast
    }
}

pub struct PriceEngine {
    pg_pool: PgPool,
    redis: MultiplexedConnection,
    mongo_events: Collection<AuctionEvent>,
    price_tx: broadcast::Sender<PriceUpdate>,
}

impl PriceEngine {
    pub fn new(
        pg_pool: PgPool,
        redis: MultiplexedConnection,
        mongo_events: Collection<AuctionEvent>,
    ) -> Self {
        let (price_tx, _) = broadcast::channel(10000);
        Self {
            pg_pool,
            redis,
            mongo_events,
            price_tx,
        }
    }

    pub fn get_price_sender(&self) -> broadcast::Sender<PriceUpdate> {
        self.price_tx.clone()
    }

    pub async fn load_active_auctions(&self) -> AppResult<()> {
        let auctions: Vec<models::Auction> = sqlx::query_as::<_, models::Auction>(
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
        .fetch_all(&self.pg_pool)
        .await?;

        info!("Loading {} active auctions into Redis price engine", auctions.len());

        for auction in auctions {
            self.register_auction(auction).await?;
        }

        Ok(())
    }

    pub async fn register_auction(&self, auction: models::Auction) -> AppResult<()> {
        if auction.status != AuctionStatus::Active {
            return Ok(());
        }

        let start_time = auction.start_time.unwrap_or_else(Utc::now);
        let end_time = auction.end_time.unwrap_or_else(|| {
            start_time + Duration::seconds(auction.duration_seconds as i64)
        });

        let state = RedisAuctionState {
            auction_id: auction.id,
            current_price: auction.current_price,
            start_price: auction.starting_price,
            floor_price: Decimal::ZERO,
            reserve_price: auction.reserve_price,
            price_decrement: auction.price_decrement,
            decrement_interval: auction.decrement_interval_seconds,
            start_time,
            end_time,
            last_decrement: Utc::now(),
            status: auction.status,
            price_history: vec![PriceHistoryPoint {
                timestamp: Utc::now(),
                price: auction.current_price,
            }],
        };

        self.save_state_to_redis(&state).await?;

        let mut con = self.redis.clone();
        redis::cmd("SADD")
            .arg(ACTIVE_AUCTIONS_KEY)
            .arg(auction.id.to_string())
            .query_async::<_, i32>(&mut con)
            .await?;

        info!(auction_id = %auction.id, "Auction registered with Redis price engine");
        Ok(())
    }

    pub async fn start_price_ticker(&self) {
        let engine = Arc::new(self.clone());
        let mut ticker = tokio::time::interval(StdDuration::from_secs(1));

        tokio::spawn(async move {
            loop {
                ticker.tick().await;
                engine.tick_all().await;
            }
        });
    }

    pub async fn tick_all(&self) {
        let active_ids = self.get_active_ids().await;
        if active_ids.is_empty() {
            return;
        }

        let now = Utc::now();
        let mut expired_auctions = Vec::new();
        let mut price_updates = Vec::new();
        let mut states_to_persist = Vec::new();

        for auction_id_str in &active_ids {
            let auction_id = match Uuid::parse_str(auction_id_str) {
                Ok(id) => id,
                Err(_) => continue,
            };

            let mut state = match self.load_state_from_redis(auction_id).await {
                Ok(Some(s)) => s,
                Ok(None) => continue,
                Err(e) => {
                    error!(auction_id = %auction_id, error = %e, "Failed to load auction state from Redis");
                    continue;
                }
            };

            if now >= state.end_time {
                expired_auctions.push(auction_id);
                continue;
            }

            let elapsed = (now - state.last_decrement).num_seconds();
            if elapsed >= state.decrement_interval as i64 {
                let new_price = state.current_price - state.price_decrement;

                if new_price <= state.reserve_price {
                    state.current_price = state.reserve_price;
                } else {
                    state.current_price = new_price;
                }

                state.last_decrement = now;

                state.push_history(PriceHistoryPoint {
                    timestamp: now,
                    price: state.current_price,
                });

                let price_history: Vec<PriceHistoryPoint> = state.price_history.iter().cloned().collect();
                let price_forecast = state.generate_forecast();

                let update = PriceUpdate {
                    auction_id: state.auction_id,
                    current_price: state.current_price,
                    timestamp: now,
                    status: state.status,
                    price_history,
                    price_forecast,
                };

                price_updates.push(update);
                states_to_persist.push(state);
            }
        }

        for state in &states_to_persist {
            if let Err(e) = self.save_state_to_redis(state).await {
                error!(auction_id = %state.auction_id, error = %e, "Failed to persist price state to Redis");
            }
        }

        let pg_pool = self.pg_pool.clone();
        let mongo_events = self.mongo_events.clone();
        let states_for_db: Vec<RedisAuctionState> = states_to_persist;
        tokio::spawn(async move {
            for state in states_for_db {
                if let Err(e) = persist_price_to_db(&state, &pg_pool, &mongo_events).await {
                    error!(auction_id = %state.auction_id, error = %e, "Failed to persist price update to DB");
                }
            }
        });

        for update in price_updates {
            self.price_tx.send(update).ok();
        }

        for auction_id in expired_auctions {
            self.expire_auction(auction_id).await;
        }
    }

    async fn get_active_ids(&self) -> Vec<String> {
        let mut con = self.redis.clone();
        match redis::cmd("SMEMBERS")
            .arg(ACTIVE_AUCTIONS_KEY)
            .query_async::<_, Vec<String>>(&mut con)
            .await
        {
            Ok(ids) => ids,
            Err(e) => {
                error!(error = %e, "Failed to get active auction IDs from Redis");
                Vec::new()
            }
        }
    }

    async fn save_state_to_redis(&self, state: &RedisAuctionState) -> AppResult<()> {
        let key = format!("{}{}", AUCTION_STATE_PREFIX, state.auction_id);
        let json = serde_json::to_string(state)
            .map_err(|e| common::error::AppError::Serialization(e))?;

        let mut con = self.redis.clone();
        let time_left = (state.end_time - Utc::now()).num_seconds();
        let ttl = std::cmp::max(time_left + 300, 60) as i64;

        redis::pipe()
            .atomic()
            .set(&key, &json)
            .expire(&key, ttl)
            .query_async::<_, ()>(&mut con)
            .await?;

        let price_key = format!("auction:{}:price", state.auction_id);
        let price_f64 = state.current_price.to_f64().unwrap_or(0.0);
        redis::cmd("SET")
            .arg(&price_key)
            .arg(price_f64)
            .arg("EX")
            .arg(ttl)
            .query_async::<_, ()>(&mut con)
            .await?;

        Ok(())
    }

    async fn load_state_from_redis(&self, auction_id: Uuid) -> AppResult<Option<RedisAuctionState>> {
        let key = format!("{}{}", AUCTION_STATE_PREFIX, auction_id);
        let mut con = self.redis.clone();

        let json: Option<String> = redis::cmd("GET")
            .arg(&key)
            .query_async(&mut con)
            .await?;

        match json {
            Some(data) => {
                let state: RedisAuctionState = serde_json::from_str(&data)
                    .map_err(|e| common::error::AppError::Serialization(e))?;
                Ok(Some(state))
            }
            None => Ok(None),
        }
    }

    async fn remove_state_from_redis(&self, auction_id: Uuid) -> AppResult<()> {
        let state_key = format!("{}{}", AUCTION_STATE_PREFIX, auction_id);
        let price_key = format!("auction:{}:price", auction_id);
        let status_key = format!("auction:{}:status", auction_id);

        let mut con = self.redis.clone();

        redis::pipe()
            .atomic()
            .del(&state_key)
            .del(&price_key)
            .del(&status_key)
            .srem(ACTIVE_AUCTIONS_KEY, auction_id.to_string())
            .query_async::<_, ()>(&mut con)
            .await?;

        Ok(())
    }

    pub async fn get_current_price(&self, auction_id: Uuid) -> AppResult<Option<Decimal>> {
        if let Some(state) = self.load_state_from_redis(auction_id).await? {
            return Ok(Some(state.current_price));
        }

        let key = format!("auction:{}:price", auction_id);
        let mut con = self.redis.clone();
        let price: Option<f64> = redis::cmd("GET").arg(&key).query_async(&mut con).await?;

        Ok(price.map(|f| Decimal::from_f64_retain(f).unwrap_or_default()))
    }

    pub async fn expire_auction(&self, auction_id: Uuid) {
        warn!(auction_id = %auction_id, "Auction expired without bids");

        if let Err(e) = self.remove_state_from_redis(auction_id).await {
            error!(auction_id = %auction_id, error = %e, "Failed to remove auction from Redis");
        }

        let update = PriceUpdate {
            auction_id,
            current_price: Decimal::ZERO,
            timestamp: Utc::now(),
            status: AuctionStatus::Expired,
            price_history: Vec::new(),
            price_forecast: Vec::new(),
        };
        self.price_tx.send(update).ok();

        let result = sqlx::query(
            "
            UPDATE auctions
            SET status = $1, end_time = $2
            WHERE id = $3 AND status = $4
            "
        )
        .bind(AuctionStatus::Expired)
        .bind(Utc::now())
        .bind(auction_id)
        .bind(AuctionStatus::Active)
        .execute(&self.pg_pool)
        .await;

        if let Err(e) = result {
            error!(auction_id = %auction_id, error = %e, "Failed to expire auction in DB");
        }

        let event = AuctionEvent {
            id: None,
            event_id: Uuid::new_v4(),
            auction_id,
            event_type: "auction_expired".to_string(),
            user_id: None,
            price: None,
            metadata: Some(mongodb::bson::doc! {"reason": "timeout"}),
            timestamp: Utc::now(),
        };

        self.mongo_events.insert_one(event, None).await.ok();
    }

    pub async fn complete_auction(&self, auction_id: Uuid, winner_id: Uuid, price: Decimal) -> AppResult<()> {
        info!(auction_id = %auction_id, winner_id = %winner_id, price = %price, "Auction completed");

        self.remove_state_from_redis(auction_id).await?;

        let update = PriceUpdate {
            auction_id,
            current_price: price,
            timestamp: Utc::now(),
            status: AuctionStatus::Sold,
            price_history: Vec::new(),
            price_forecast: Vec::new(),
        };
        self.price_tx.send(update).ok();

        Ok(())
    }

    pub async fn fail_auction(&self, auction_id: Uuid) -> AppResult<()> {
        warn!(auction_id = %auction_id, "Auction failed - reserve price not met");

        self.remove_state_from_redis(auction_id).await?;

        let update = PriceUpdate {
            auction_id,
            current_price: Decimal::ZERO,
            timestamp: Utc::now(),
            status: AuctionStatus::Failed,
            price_history: Vec::new(),
            price_forecast: Vec::new(),
        };
        self.price_tx.send(update).ok();

        Ok(())
    }

    pub fn active_count(&self) -> usize {
        0
    }
}

async fn persist_price_to_db(
    state: &RedisAuctionState,
    pg_pool: &PgPool,
    mongo_events: &Collection<AuctionEvent>,
) -> AppResult<()> {
    sqlx::query(
        "UPDATE auctions SET current_price = $1, updated_at = NOW() WHERE id = $2"
    )
    .bind(state.current_price)
    .bind(state.auction_id)
    .execute(pg_pool)
    .await?;

    debug!(auction_id = %state.auction_id, new_price = %state.current_price, "Price decremented in DB");

    let event = AuctionEvent {
        id: None,
        event_id: Uuid::new_v4(),
        auction_id: state.auction_id,
        event_type: "price_decrement".to_string(),
        user_id: None,
        price: Some(state.current_price),
        metadata: None,
        timestamp: Utc::now(),
    };
    mongo_events.insert_one(event, None).await.ok();

    Ok(())
}

impl Clone for PriceEngine {
    fn clone(&self) -> Self {
        Self {
            pg_pool: self.pg_pool.clone(),
            redis: self.redis.clone(),
            mongo_events: self.mongo_events.clone(),
            price_tx: self.price_tx.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use chrono::{Duration, Utc};
    use rust_decimal::Decimal;
    use rust_decimal_macros::dec;
    use shared::{AuctionStatus, PriceHistoryPoint};
    use uuid::Uuid;

    use super::RedisAuctionState;

    fn make_test_state() -> RedisAuctionState {
        RedisAuctionState {
            auction_id: Uuid::new_v4(),
            current_price: dec!(100.00),
            start_price: dec!(100.00),
            floor_price: Decimal::ZERO,
            reserve_price: dec!(50.00),
            price_decrement: dec!(5.00),
            decrement_interval: 1,
            start_time: Utc::now(),
            end_time: Utc::now() + Duration::seconds(60),
            last_decrement: Utc::now(),
            status: AuctionStatus::Active,
            price_history: vec![PriceHistoryPoint {
                timestamp: Utc::now(),
                price: dec!(100.00),
            }],
        }
    }

    #[test]
    fn test_push_history_limits_to_30() {
        let mut state = make_test_state();
        state.price_history.clear();

        for i in 1..=35 {
            state.push_history(PriceHistoryPoint {
                timestamp: Utc::now() + Duration::seconds(i),
                price: dec!(100.00) - Decimal::from(i) * dec!(1.00),
            });
        }

        assert_eq!(state.price_history.len(), 30);
        let first_price = state.price_history.first().unwrap().price;
        assert_eq!(first_price, dec!(94.00));
        let last_price = state.price_history.last().unwrap().price;
        assert_eq!(last_price, dec!(65.00));
    }

    #[test]
    fn test_generate_forecast_basic() {
        let state = make_test_state();
        let forecast = state.generate_forecast();

        assert!(!forecast.is_empty());
        assert!(forecast.len() <= 30);
        assert_eq!(forecast[0].price, dec!(95.00));
        assert_eq!(forecast[1].price, dec!(90.00));
    }

    #[test]
    fn test_generate_forecast_stops_at_reserve_price() {
        let mut state = make_test_state();
        state.current_price = dec!(55.00);
        state.reserve_price = dec!(50.00);

        let forecast = state.generate_forecast();

        assert!(!forecast.is_empty());
        let last = forecast.last().unwrap();
        assert_eq!(last.price, dec!(50.00));
    }

    #[test]
    fn test_generate_forecast_stops_at_end_time() {
        let mut state = make_test_state();
        state.end_time = state.last_decrement + Duration::seconds(3);

        let forecast = state.generate_forecast();
        assert!(forecast.len() <= 3);
    }

    #[test]
    fn test_forecast_never_goes_below_reserve() {
        let mut state = make_test_state();
        state.current_price = dec!(60.00);
        state.reserve_price = dec!(55.00);
        state.price_decrement = dec!(10.00);

        let forecast = state.generate_forecast();
        for point in &forecast {
            assert!(point.price >= state.reserve_price);
        }
    }

    #[test]
    fn test_redis_state_serialization_roundtrip() {
        let state = make_test_state();
        let json = serde_json::to_string(&state).unwrap();
        let deserialized: RedisAuctionState = serde_json::from_str(&json).unwrap();

        assert_eq!(state.auction_id, deserialized.auction_id);
        assert_eq!(state.current_price, deserialized.current_price);
        assert_eq!(state.reserve_price, deserialized.reserve_price);
        assert_eq!(state.price_decrement, deserialized.price_decrement);
        assert_eq!(state.decrement_interval, deserialized.decrement_interval);
        assert_eq!(state.status, deserialized.status);
    }

    #[test]
    fn test_price_update_serialization() {
        let update = shared::PriceUpdate {
            auction_id: Uuid::new_v4(),
            current_price: dec!(88.50),
            timestamp: Utc::now(),
            status: AuctionStatus::Active,
            price_history: vec![
                PriceHistoryPoint {
                    timestamp: Utc::now(),
                    price: dec!(100.00),
                },
                PriceHistoryPoint {
                    timestamp: Utc::now() + Duration::seconds(1),
                    price: dec!(95.00),
                },
            ],
            price_forecast: vec![PriceHistoryPoint {
                timestamp: Utc::now() + Duration::seconds(2),
                price: dec!(90.00),
            }],
        };

        let json = serde_json::to_string(&update).unwrap();
        let deserialized: shared::PriceUpdate = serde_json::from_str(&json).unwrap();

        assert_eq!(update.auction_id, deserialized.auction_id);
        assert_eq!(update.current_price, deserialized.current_price);
        assert_eq!(update.price_history.len(), deserialized.price_history.len());
        assert_eq!(update.price_forecast.len(), deserialized.price_forecast.len());
    }
}
