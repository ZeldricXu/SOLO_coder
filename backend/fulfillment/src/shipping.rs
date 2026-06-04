use common::error::AppResult;
use serde::{Deserialize, Serialize};
use tracing::info;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrackingInfo {
    pub tracking_number: String,
    pub tracking_company: String,
    pub status: String,
    pub events: Vec<TrackingEvent>,
    pub estimated_delivery: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrackingEvent {
    pub timestamp: String,
    pub location: String,
    pub description: String,
    pub status: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShippingQuoteRequest {
    pub from_zip: String,
    pub to_zip: String,
    pub weight_kg: f64,
    pub length_cm: Option<f64>,
    pub width_cm: Option<f64>,
    pub height_cm: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShippingQuote {
    pub carrier: String,
    pub service: String,
    pub price: rust_decimal::Decimal,
    pub estimated_days: i32,
}

pub struct ShippingApi {
    api_key: String,
    mock_mode: bool,
}

impl ShippingApi {
    pub fn new() -> Self {
        let api_key = std::env::var("SHIPPING_API_KEY").unwrap_or_default();
        let mock_mode = std::env::var("SHIPPING_MOCK_MODE")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(true);

        Self { api_key, mock_mode }
    }

    pub async fn get_tracking_info(
        &self,
        tracking_number: &str,
        company: &str,
    ) -> AppResult<TrackingInfo> {
        if self.mock_mode {
            info!(tracking_number = tracking_number, company = company, "[MOCK] Getting tracking info");
            tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;

            return Ok(TrackingInfo {
                tracking_number: tracking_number.to_string(),
                tracking_company: company.to_string(),
                status: "in_transit".into(),
                events: vec![
                    TrackingEvent {
                        timestamp: chrono::Utc::now().to_rfc3339(),
                        location: "上海市".into(),
                        description: "包裹已从上海仓库发出".into(),
                        status: "shipped".into(),
                    },
                    TrackingEvent {
                        timestamp: chrono::Utc::now().to_rfc3339(),
                        location: "杭州市".into(),
                        description: "包裹已到达杭州转运中心".into(),
                        status: "in_transit".into(),
                    },
                ],
                estimated_delivery: Some((chrono::Utc::now() + chrono::Duration::days(3)).to_rfc3339()),
            });
        }

        Ok(TrackingInfo {
            tracking_number: tracking_number.to_string(),
            tracking_company: company.to_string(),
            status: "unknown".into(),
            events: Vec::new(),
            estimated_delivery: None,
        })
    }

    pub async fn get_shipping_quotes(
        &self,
        _req: ShippingQuoteRequest,
    ) -> AppResult<Vec<ShippingQuote>> {
        use rust_decimal_macros::dec;

        if self.mock_mode {
            tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;

            return Ok(vec![
                ShippingQuote {
                    carrier: "顺丰速运".into(),
                    service: "标准快递".into(),
                    price: dec!(18.00),
                    estimated_days: 2,
                },
                ShippingQuote {
                    carrier: "京东物流".into(),
                    service: "次日达".into(),
                    price: dec!(25.00),
                    estimated_days: 1,
                },
                ShippingQuote {
                    carrier: "圆通速递".into(),
                    service: "经济快递".into(),
                    price: dec!(12.00),
                    estimated_days: 4,
                },
            ]);
        }

        Ok(Vec::new())
    }

    pub fn supported_carriers(&self) -> Vec<&'static str> {
        vec![
            "顺丰速运",
            "京东物流",
            "圆通速递",
            "中通快递",
            "韵达快递",
            "申通快递",
            "邮政EMS",
        ]
    }
}

impl Clone for ShippingApi {
    fn clone(&self) -> Self {
        Self {
            api_key: self.api_key.clone(),
            mock_mode: self.mock_mode,
        }
    }
}
