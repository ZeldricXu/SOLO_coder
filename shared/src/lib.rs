use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[cfg(feature = "backend")]
use sqlx::Type;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "backend", derive(Type))]
#[cfg_attr(feature = "backend", sqlx(type_name = "auction_status"))]
pub enum AuctionStatus {
    PendingReview,
    ReviewRejected,
    Scheduled,
    Active,
    Sold,
    Expired,
    Cancelled,
    Failed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "backend", derive(Type))]
#[cfg_attr(feature = "backend", sqlx(type_name = "user_role"))]
pub enum UserRole {
    Buyer,
    Seller,
    Admin,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "backend", derive(Type))]
#[cfg_attr(feature = "backend", sqlx(type_name = "order_status"))]
pub enum OrderStatus {
    Created,
    Paid,
    Shipped,
    Delivered,
    Completed,
    Cancelled,
    Refunded,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "backend", derive(Type))]
#[cfg_attr(feature = "backend", sqlx(type_name = "transaction_type"))]
pub enum TransactionType {
    Deposit,
    Withdraw,
    Freeze,
    Unfreeze,
    Payment,
    Refund,
    Settlement,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "backend", derive(Type))]
#[cfg_attr(feature = "backend", sqlx(type_name = "notification_type"))]
pub enum NotificationType {
    AuctionStarted,
    AuctionEnded,
    Outbid,
    WonAuction,
    LostAuction,
    PaymentReceived,
    RefundProcessed,
    OrderShipped,
    OrderDelivered,
    RiskAlert,
    ReviewResult,
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
pub struct BidRequest {
    pub auction_id: Uuid,
    pub user_id: Uuid,
    pub max_price: Decimal,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BidResponse {
    pub success: bool,
    pub auction_id: Uuid,
    pub price: Option<Decimal>,
    pub message: String,
    pub winner: Option<Uuid>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Category {
    pub id: Uuid,
    pub name: String,
    pub slug: String,
    pub parent_id: Option<Uuid>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiResponse<T> {
    pub success: bool,
    pub data: Option<T>,
    pub error: Option<String>,
}

impl<T> ApiResponse<T> {
    pub fn ok(data: T) -> Self {
        Self {
            success: true,
            data: Some(data),
            error: None,
        }
    }

    pub fn err(msg: impl Into<String>) -> Self {
        Self {
            success: false,
            data: None,
            error: Some(msg.into()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_api_response_ok() {
        let resp = ApiResponse::ok(42);
        assert!(resp.success);
        assert_eq!(resp.data, Some(42));
        assert!(resp.error.is_none());
    }

    #[test]
    fn test_api_response_err() {
        let resp: ApiResponse<i32> = ApiResponse::err("something went wrong");
        assert!(!resp.success);
        assert!(resp.data.is_none());
        assert_eq!(resp.error, Some("something went wrong".to_string()));
    }

    #[test]
    fn test_auction_status_serialization() {
        let status = AuctionStatus::Active;
        let json = serde_json::to_string(&status).unwrap();
        let deserialized: AuctionStatus = serde_json::from_str(&json).unwrap();
        assert_eq!(status, deserialized);
    }

    #[test]
    fn test_order_status_serialization() {
        let status = OrderStatus::Paid;
        let json = serde_json::to_string(&status).unwrap();
        let deserialized: OrderStatus = serde_json::from_str(&json).unwrap();
        assert_eq!(status, deserialized);
    }

    #[test]
    fn test_user_role_serialization() {
        let role = UserRole::Seller;
        let json = serde_json::to_string(&role).unwrap();
        let deserialized: UserRole = serde_json::from_str(&json).unwrap();
        assert_eq!(role, deserialized);
    }

    #[test]
    fn test_price_update_serialization() {
        let update = PriceUpdate {
            auction_id: Uuid::new_v4(),
            current_price: Decimal::ONE_HUNDRED,
            timestamp: chrono::Utc::now(),
            status: AuctionStatus::Active,
            price_history: vec![],
            price_forecast: vec![],
        };
        let json = serde_json::to_string(&update).unwrap();
        let deserialized: PriceUpdate = serde_json::from_str(&json).unwrap();
        assert_eq!(update.auction_id, deserialized.auction_id);
        assert_eq!(update.status, deserialized.status);
    }

    #[test]
    fn test_bid_response_serialization() {
        let resp = BidResponse {
            success: true,
            auction_id: Uuid::new_v4(),
            price: Some(rust_decimal_macros::dec!(50)),
            message: "You won!".to_string(),
            winner: Some(Uuid::new_v4()),
        };
        let json = serde_json::to_string(&resp).unwrap();
        let deserialized: BidResponse = serde_json::from_str(&json).unwrap();
        assert_eq!(resp.success, deserialized.success);
        assert_eq!(resp.price, deserialized.price);
    }
}
