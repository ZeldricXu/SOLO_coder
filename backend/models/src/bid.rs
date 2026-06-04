use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
pub struct Bid {
    pub id: Uuid,
    pub auction_id: Uuid,
    pub user_id: Uuid,
    pub max_price: Decimal,
    pub bid_price: Decimal,
    pub is_winning: bool,
    pub frozen_amount: Decimal,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateBidRequest {
    pub auction_id: Uuid,
    pub max_price: Decimal,
}

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
pub struct BidHistoryItem {
    pub id: Uuid,
    pub auction_id: Uuid,
    pub auction_title: String,
    pub bid_price: Decimal,
    pub is_winning: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BidResult {
    pub success: bool,
    pub bid_id: Option<Uuid>,
    pub price: Option<Decimal>,
    pub is_winner: bool,
    pub message: String,
}
