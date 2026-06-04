use chrono::{DateTime, Utc};
use mongodb::bson::{self, oid::ObjectId};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use shared::TransactionType;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AccountTransaction {
    #[serde(rename = "_id", skip_serializing_if = "Option::is_none")]
    pub id: Option<ObjectId>,
    pub transaction_id: Uuid,
    pub user_id: Uuid,
    pub type_: TransactionType,
    pub amount: Decimal,
    pub balance_before: Decimal,
    pub balance_after: Decimal,
    pub frozen_before: Decimal,
    pub frozen_after: Decimal,
    pub reference_id: Option<Uuid>,
    pub reference_type: Option<String>,
    pub description: String,
    pub metadata: Option<bson::Document>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuctionEvent {
    #[serde(rename = "_id", skip_serializing_if = "Option::is_none")]
    pub id: Option<ObjectId>,
    pub event_id: Uuid,
    pub auction_id: Uuid,
    pub event_type: String,
    pub user_id: Option<Uuid>,
    pub price: Option<Decimal>,
    pub metadata: Option<bson::Document>,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditLogEntry {
    #[serde(rename = "_id", skip_serializing_if = "Option::is_none")]
    pub id: Option<ObjectId>,
    pub log_id: Uuid,
    pub user_id: Option<Uuid>,
    pub action: String,
    pub resource: String,
    pub resource_id: Option<Uuid>,
    pub ip_address: Option<String>,
    pub user_agent: Option<String>,
    pub metadata: Option<bson::Document>,
    pub created_at: DateTime<Utc>,
}
