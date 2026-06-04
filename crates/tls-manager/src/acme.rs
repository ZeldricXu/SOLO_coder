use std::sync::Arc;
use tokio::sync::RwLock;

use common::error::{CdnResult, CdnError};
use common::config::TlsConfig;

pub struct AcmeClient {
    config: TlsConfig,
    account_key: Arc<RwLock<Option<String>>>,
}

impl AcmeClient {
    pub fn new(config: TlsConfig) -> Self {
        AcmeClient {
            config,
            account_key: Arc::new(RwLock::new(None)),
        }
    }

    pub async fn create_account(&self, email: &str) -> CdnResult<()> {
        tracing::info!("Creating ACME account for {}", email);
        Ok(())
    }

    pub async fn order_certificate(&self, domain: &str) -> CdnResult<()> {
        tracing::info!("Ordering certificate for {}", domain);
        Ok(())
    }

    pub async fn complete_challenge(&self, token: &str) -> CdnResult<()> {
        tracing::info!("Completing ACME challenge: {}", token);
        Ok(())
    }

    pub async fn finalize_order(&self, order_url: &str) -> CdnResult<String> {
        tracing::info!("Finalizing ACME order: {}", order_url);
        Ok("certificate_pem".to_string())
    }

    pub async fn revoke_certificate(&self, cert_pem: &str) -> CdnResult<()> {
        tracing::info!("Revoking certificate");
        Ok(())
    }
}

impl Clone for AcmeClient {
    fn clone(&self) -> Self {
        AcmeClient {
            config: self.config.clone(),
            account_key: self.account_key.clone(),
        }
    }
}
