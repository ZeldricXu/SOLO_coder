pub mod router;
pub mod strategy;
pub mod cache;
pub mod grpc;
pub mod rollout;

pub use router::*;
pub use strategy::*;
pub use cache::*;
pub use grpc::*;
pub use rollout::*;

use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RoutingConfig {
    pub strategy: common::types::RoutingStrategy,
    pub targets: Vec<common::types::RouteTarget>,
    pub experiment_id: Option<Uuid>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RouterServiceConfig {
    pub default_strategy: common::types::RoutingStrategy,
    pub enable_dynamic_weight: bool,
    pub enable_fallback: bool,
    pub enable_health_check: bool,
    pub local_cache_capacity: usize,
    pub local_cache_ttl_secs: u64,
    pub weight_adjust_interval_secs: u64,
    pub registry_address: Option<String>,
}

impl Default for RouterServiceConfig {
    fn default() -> Self {
        Self {
            default_strategy: common::types::RoutingStrategy::Random,
            enable_dynamic_weight: true,
            enable_fallback: true,
            enable_health_check: true,
            local_cache_capacity: 10_000,
            local_cache_ttl_secs: 30,
            weight_adjust_interval_secs: 60,
            registry_address: None,
        }
    }
}

pub mod pb {
    #![allow(clippy::all)]
    #![allow(dead_code)]
    pub use super::grpc::inference::v1::*;
}
