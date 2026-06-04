use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
pub use shared::OrderStatus;
use sqlx::postgres::PgTypeInfo;
use sqlx::{Decode, FromRow, Postgres, Row, Type};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShippingAddress {
    pub recipient_name: String,
    pub phone: String,
    pub address_line1: String,
    pub address_line2: Option<String>,
    pub city: String,
    pub state: String,
    pub postal_code: String,
    pub country: String,
}

impl Type<Postgres> for ShippingAddress {
    fn type_info() -> PgTypeInfo {
        PgTypeInfo::with_name("jsonb")
    }
}

impl<'r> Decode<'r, Postgres> for ShippingAddress {
    fn decode(value: sqlx::postgres::PgValueRef<'r>) -> Result<Self, sqlx::error::BoxDynError> {
        let json_value: serde_json::Value = Decode::decode(value)?;
        serde_json::from_value(json_value).map_err(Into::into)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
pub struct Order {
    pub id: Uuid,
    pub auction_id: Uuid,
    pub buyer_id: Uuid,
    pub seller_id: Uuid,
    pub amount: Decimal,
    pub status: OrderStatus,
    pub shipping_address: Option<ShippingAddress>,
    pub tracking_number: Option<String>,
    pub tracking_company: Option<String>,
    pub payment_gateway: Option<String>,
    pub payment_transaction_id: Option<String>,
    pub paid_at: Option<DateTime<Utc>>,
    pub shipped_at: Option<DateTime<Utc>>,
    pub delivered_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateOrderRequest {
    pub auction_id: Uuid,
    pub shipping_address: ShippingAddress,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateShippingRequest {
    pub tracking_number: String,
    pub tracking_company: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PaymentCallbackRequest {
    pub order_id: Uuid,
    pub transaction_id: String,
    pub amount: Decimal,
    pub success: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OrderDetail {
    #[serde(flatten)]
    pub order: Order,
    pub auction_title: String,
    pub buyer_name: String,
    pub seller_name: String,
    pub auction_thumbnail: Option<String>,
}

impl<'r> FromRow<'r, sqlx::postgres::PgRow> for OrderDetail {
    fn from_row(row: &'r sqlx::postgres::PgRow) -> Result<Self, sqlx::Error> {
        Ok(OrderDetail {
            order: Order::from_row(row)?,
            auction_title: row.try_get("auction_title")?,
            buyer_name: row.try_get("buyer_name")?,
            seller_name: row.try_get("seller_name")?,
            auction_thumbnail: row.try_get("auction_thumbnail")?,
        })
    }
}
