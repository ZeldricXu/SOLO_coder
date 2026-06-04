use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use shared::{AuctionStatus, OrderStatus, TransactionType, UserRole};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiResponse<T> {
    pub success: bool,
    pub data: Option<T>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Auction {
    pub id: Uuid,
    pub seller_id: Uuid,
    pub category_id: Option<Uuid>,
    pub title: String,
    pub description: String,
    pub starting_price: Decimal,
    pub reserve_price: Decimal,
    pub current_price: Decimal,
    pub price_decrement: Decimal,
    pub decrement_interval_seconds: i32,
    pub duration_seconds: i32,
    pub status: AuctionStatus,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub winner_id: Option<Uuid>,
    pub final_price: Option<Decimal>,
    pub view_count: i32,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuctionListItem {
    pub id: Uuid,
    pub title: String,
    pub current_price: Decimal,
    pub starting_price: Decimal,
    pub reserve_price: Decimal,
    pub status: AuctionStatus,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub category_id: Option<Uuid>,
    pub category_name: Option<String>,
    pub primary_image: Option<String>,
    pub view_count: i32,
    pub time_left_seconds: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuctionDetail {
    #[serde(flatten)]
    pub auction: Auction,
    pub media: Vec<AuctionMedia>,
    pub category_name: Option<String>,
    pub seller_name: String,
    pub time_left_seconds: Option<i64>,
    pub is_watching: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuctionMedia {
    pub id: Uuid,
    pub auction_id: Uuid,
    pub media_type: String,
    pub file_path: String,
    pub file_size: i64,
    pub mime_type: Option<String>,
    pub sort_order: i32,
    pub is_primary: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateAuctionRequest {
    pub category_id: Option<Uuid>,
    pub title: String,
    pub description: String,
    pub starting_price: Decimal,
    pub reserve_price: Decimal,
    pub price_decrement: Decimal,
    pub decrement_interval_seconds: i32,
    pub duration_seconds: i32,
    pub schedule_time: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateBidRequest {
    pub auction_id: Uuid,
    pub max_price: Decimal,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BidResult {
    pub success: bool,
    pub auction_id: Uuid,
    pub price: Option<Decimal>,
    pub message: String,
    pub winner: Option<Uuid>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserProfile {
    pub id: Uuid,
    pub username: String,
    pub email: String,
    pub role: UserRole,
    pub balance: Decimal,
    pub frozen_balance: Decimal,
    pub is_verified: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoginRequest {
    pub email: String,
    pub password: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RegisterRequest {
    pub username: String,
    pub email: String,
    pub password: String,
    pub role: UserRole,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoginResponse {
    pub token: String,
    pub user: UserProfile,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AccountTransaction {
    pub id: Uuid,
    pub user_id: Uuid,
    pub transaction_type: TransactionType,
    pub amount: Decimal,
    pub balance_before: Decimal,
    pub balance_after: Decimal,
    pub reference_id: Option<Uuid>,
    pub description: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Order {
    pub id: Uuid,
    pub auction_id: Uuid,
    pub buyer_id: Uuid,
    pub seller_id: Uuid,
    pub final_price: Decimal,
    pub status: OrderStatus,
    pub tracking_number: Option<String>,
    pub tracking_company: Option<String>,
    pub shipping_address: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Notification {
    pub id: Uuid,
    pub user_id: Uuid,
    pub notification_type: String,
    pub title: String,
    pub content: String,
    pub data: Option<serde_json::Value>,
    pub read: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PriceHistoryPoint {
    pub timestamp: DateTime<Utc>,
    pub price: Decimal,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PriceUpdate {
    pub auction_id: Uuid,
    pub current_price: Decimal,
    pub timestamp: DateTime<Utc>,
    pub status: AuctionStatus,
    pub price_history: Vec<PriceHistoryPoint>,
    pub price_forecast: Vec<PriceHistoryPoint>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HotRankingItem {
    pub auction_id: Uuid,
    pub title: String,
    pub current_price: Decimal,
    pub primary_image: Option<String>,
    pub score: i64,
    pub rank: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HotRankings {
    pub most_viewed: Vec<HotRankingItem>,
    pub most_bidded: Vec<HotRankingItem>,
}
