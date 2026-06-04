use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use shared::AuctionStatus;
use sqlx::{FromRow, Row};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
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
    pub risk_score: Option<Decimal>,
    pub review_note: Option<String>,
    pub reviewed_by: Option<Uuid>,
    pub reviewed_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
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

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
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

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
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

impl<'r> FromRow<'r, sqlx::postgres::PgRow> for AuctionDetail {
    fn from_row(row: &'r sqlx::postgres::PgRow) -> Result<Self, sqlx::Error> {
        Ok(AuctionDetail {
            auction: Auction::from_row(row)?,
            media: Vec::new(),
            category_name: row.try_get("category_name")?,
            seller_name: row.try_get("seller_name")?,
            time_left_seconds: row.try_get("time_left_seconds")?,
            is_watching: false,
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReviewAuctionRequest {
    pub approved: bool,
    pub note: Option<String>,
}
