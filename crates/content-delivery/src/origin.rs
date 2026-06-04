use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

use common::error::{CdnResult, CdnError};
use common::models::DomainConfig;

pub struct OriginManager {
    origins: Arc<RwLock<HashMap<String, OriginServer>>>,
}

#[derive(Clone)]
pub struct OriginServer {
    pub domain: String,
    pub origin_url: String,
    pub host_header: Option<String>,
    pub timeout_ms: u64,
    pub max_connections: u32,
}

impl OriginManager {
    pub fn new() -> Self {
        OriginManager {
            origins: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn add_origin(&self, config: &DomainConfig) -> CdnResult<()> {
        let origin = OriginServer {
            domain: config.domain.clone(),
            origin_url: config.origin_server.clone(),
            host_header: Some(config.origin_server.clone()),
            timeout_ms: 30000,
            max_connections: 100,
        };

        let mut origins = self.origins.write().await;
        origins.insert(config.domain.clone(), origin);
        
        Ok(())
    }

    pub async fn remove_origin(&self, domain: &str) -> bool {
        let mut origins = self.origins.write().await;
        origins.remove(domain).is_some()
    }

    pub async fn get_origin(&self, domain: &str) -> Option<OriginServer> {
        let origins = self.origins.read().await;
        origins.get(domain).cloned()
    }

    pub async fn build_url(&self, domain: &str, path: &str) -> CdnResult<String> {
        let origins = self.origins.read().await;
        let origin = origins.get(domain)
            .ok_or_else(|| CdnError::InternalError(format!("Origin not found for domain: {}", domain)))?;

        Ok(format!("{}{}", origin.origin_url, path))
    }
}

impl Default for OriginManager {
    fn default() -> Self {
        Self::new()
    }
}

impl Clone for OriginManager {
    fn clone(&self) -> Self {
        OriginManager {
            origins: self.origins.clone(),
        }
    }
}
