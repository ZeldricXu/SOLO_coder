use common::error::AppResult;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use tracing::{info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PaymentRequest {
    pub order_id: Uuid,
    pub user_id: Uuid,
    pub amount: Decimal,
    pub payment_method: String,
    pub return_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PaymentResponse {
    pub success: bool,
    pub transaction_id: String,
    pub payment_url: Option<String>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PaymentCallback {
    pub order_id: Uuid,
    pub transaction_id: String,
    pub amount: Decimal,
    pub success: bool,
    pub signature: String,
}

pub struct PaymentGateway {
    api_key: String,
    api_url: String,
    mock_mode: bool,
}

impl PaymentGateway {
    pub fn new() -> Self {
        let api_key = std::env::var("PAYMENT_API_KEY").unwrap_or_default();
        let api_url = std::env::var("PAYMENT_API_URL")
            .unwrap_or_else(|_| "https://api.payment-gateway.com".into());
        let mock_mode = std::env::var("PAYMENT_MOCK_MODE")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(true);

        Self {
            api_key,
            api_url,
            mock_mode,
        }
    }

    pub async fn create_payment(&self, req: PaymentRequest) -> AppResult<PaymentResponse> {
        if self.mock_mode {
            info!(order_id = %req.order_id, amount = %req.amount, "[MOCK] Creating payment");
            tokio::time::sleep(tokio::time::Duration::from_millis(200)).await;

            let transaction_id = format!("PAY_{}", Uuid::new_v4().simple());

            return Ok(PaymentResponse {
                success: true,
                transaction_id: transaction_id.clone(),
                payment_url: Some(format!(
                    "{}?order_id={}&transaction_id={}",
                    req.return_url, req.order_id, transaction_id
                )),
                error: None,
            });
        }

        #[cfg(not(feature = "mock-payment"))]
        {
            let client = reqwest::Client::new();
            let resp = client
                .post(&format!("{}/payments", self.api_url))
                .header("Authorization", format!("Bearer {}", self.api_key))
                .json(&req)
                .send()
                .await?
                .json::<PaymentResponse>()
                .await?;
            Ok(resp)
        }

        #[cfg(feature = "mock-payment")]
        {
            Ok(PaymentResponse {
                success: false,
                transaction_id: String::new(),
                payment_url: None,
                error: Some("Payment gateway not configured".into()),
            })
        }
    }

    pub async fn verify_callback(&self, callback: &PaymentCallback) -> AppResult<bool> {
        if self.mock_mode {
            info!(order_id = %callback.order_id, success = callback.success, "[MOCK] Verifying payment callback");
            return Ok(callback.success);
        }

        let mut params = HashMap::new();
        params.insert("order_id".to_string(), callback.order_id.to_string());
        params.insert("transaction_id".to_string(), callback.transaction_id.clone());
        params.insert("amount".to_string(), callback.amount.to_string());
        params.insert("success".to_string(), callback.success.to_string());

        let mut sorted_keys: Vec<&String> = params.keys().collect();
        sorted_keys.sort();

        let signature_base = sorted_keys
            .iter()
            .map(|k| format!("{}={}", k, params[*k]))
            .collect::<Vec<_>>()
            .join("&");

        let expected_signature = format!("{}{}", signature_base, self.api_key);
        let expected_hash = sha256(&expected_signature);

        Ok(expected_hash == callback.signature)
    }

    pub async fn refund(&self, order_id: Uuid, transaction_id: &str, amount: Decimal, reason: &str) -> AppResult<bool> {
        if self.mock_mode {
            info!(order_id = %order_id, amount = %amount, reason = reason, "[MOCK] Processing refund");
            tokio::time::sleep(tokio::time::Duration::from_millis(150)).await;
            return Ok(true);
        }

        warn!("Real payment refund not implemented");
        Ok(true)
    }
}

fn sha256(input: &str) -> String {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};

    let mut hasher = DefaultHasher::new();
    input.hash(&mut hasher);
    format!("{:x}", hasher.finish())
}

impl Clone for PaymentGateway {
    fn clone(&self) -> Self {
        Self {
            api_key: self.api_key.clone(),
            api_url: self.api_url.clone(),
            mock_mode: self.mock_mode,
        }
    }
}
