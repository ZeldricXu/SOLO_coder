use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{anyhow, Result};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use tokio::sync::Semaphore;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Tenant {
    pub tenant_id: String,
    pub name: String,
    pub status: TenantStatus,
    pub tier: TenantTier,
    pub config: TenantConfig,
    pub quota: ResourceQuota,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum TenantStatus {
    Active,
    Suspended,
    Terminated,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum TenantTier {
    Free,
    Standard,
    Premium,
    Enterprise,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TenantConfig {
    pub custom_settings: serde_json::Value,
    pub feature_flags: HashMap<String, bool>,
    pub rate_limits: RateLimitConfig,
}

impl Default for TenantConfig {
    fn default() -> Self {
        Self {
            custom_settings: serde_json::Value::Object(serde_json::Map::new()),
            feature_flags: HashMap::new(),
            rate_limits: RateLimitConfig::default(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RateLimitConfig {
    pub requests_per_minute: u32,
    pub requests_per_hour: u32,
    pub requests_per_day: u32,
    pub max_concurrent_requests: u32,
}

impl Default for RateLimitConfig {
    fn default() -> Self {
        Self {
            requests_per_minute: 100,
            requests_per_hour: 1000,
            requests_per_day: 10000,
            max_concurrent_requests: 10,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceQuota {
    pub max_storage_gb: u64,
    pub max_cpu_cores: f64,
    pub max_memory_gb: f64,
    pub max_api_requests: u64,
    pub max_users: u32,
    pub max_connections: u32,
}

impl Default for ResourceQuota {
    fn default() -> Self {
        Self {
            max_storage_gb: 10,
            max_cpu_cores: 1.0,
            max_memory_gb: 2.0,
            max_api_requests: 100000,
            max_users: 5,
            max_connections: 100,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QuotaUsage {
    pub tenant_id: String,
    pub storage_used_gb: u64,
    pub cpu_used_cores: f64,
    pub memory_used_gb: f64,
    pub api_requests_count: u64,
    pub active_users: u32,
    pub active_connections: u32,
    pub current_requests: u32,
    pub last_updated: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RateLimitState {
    pub minute_requests: Vec<DateTime<Utc>>,
    pub hour_requests: Vec<DateTime<Utc>>,
    pub day_requests: Vec<DateTime<Utc>>,
}

impl Default for RateLimitState {
    fn default() -> Self {
        Self {
            minute_requests: Vec::new(),
            hour_requests: Vec::new(),
            day_requests: Vec::new(),
        }
    }
}

pub struct TenantManager {
    tenants: DashMap<String, Tenant>,
    quota_usage: DashMap<String, QuotaUsage>,
    rate_limit_states: DashMap<String, RateLimitState>,
    semaphores: DashMap<String, Arc<Semaphore>>,
    listeners: RwLock<Vec<Arc<dyn Fn(TenantEvent) -> Result<()> + Send + Sync>>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TenantEvent {
    pub event_id: String,
    pub tenant_id: String,
    pub event_type: TenantEventType,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum TenantEventType {
    Created,
    Updated,
    Suspended,
    Reactivated,
    Terminated,
    QuotaExceeded,
    RateLimitExceeded,
}

impl TenantManager {
    pub fn new() -> Self {
        Self {
            tenants: DashMap::new(),
            quota_usage: DashMap::new(),
            rate_limit_states: DashMap::new(),
            semaphores: DashMap::new(),
            listeners: RwLock::new(Vec::new()),
        }
    }

    pub fn register_listener<F>(&self, listener: F)
    where
        F: Fn(TenantEvent) -> Result<()> + Send + Sync + 'static,
    {
        self.listeners.write().push(Arc::new(listener));
    }

    fn notify_listeners(&self, event: TenantEvent) {
        let listeners = self.listeners.read();
        for listener in listeners.iter() {
            let event = event.clone();
            let listener = listener.clone();
            tokio::spawn(async move {
                if let Err(e) = listener(event) {
                    error!(error = %e, "Tenant event listener failed");
                }
            });
        }
    }

    pub fn create_tenant(&self, name: String, tier: TenantTier) -> Result<Tenant> {
        let tenant_id = format!("tnt_{}", Uuid::new_v4().simple());
        let now = Utc::now();
        
        let config = Self::default_config_for_tier(&tier);
        let quota = Self::default_quota_for_tier(&tier);
        
        let tenant = Tenant {
            tenant_id: tenant_id.clone(),
            name,
            status: TenantStatus::Active,
            tier,
            config,
            quota,
            created_at: now,
            updated_at: now,
        };
        
        self.tenants.insert(tenant_id.clone(), tenant.clone());
        
        self.quota_usage.insert(tenant_id.clone(), QuotaUsage {
            tenant_id: tenant_id.clone(),
            storage_used_gb: 0,
            cpu_used_cores: 0.0,
            memory_used_gb: 0.0,
            api_requests_count: 0,
            active_users: 0,
            active_connections: 0,
            current_requests: 0,
            last_updated: now,
        });
        
        self.semaphores.insert(
            tenant_id.clone(),
            Arc::new(Semaphore::new(tenant.quota.max_connections as usize)),
        );
        
        self.notify_listeners(TenantEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            tenant_id,
            event_type: TenantEventType::Created,
            timestamp: now,
        });
        
        info!("Created tenant: {} ({})", tenant.name, tenant.tenant_id);
        Ok(tenant)
    }

    fn default_config_for_tier(tier: &TenantTier) -> TenantConfig {
        match tier {
            TenantTier::Free => TenantConfig {
                rate_limits: RateLimitConfig {
                    requests_per_minute: 60,
                    requests_per_hour: 500,
                    requests_per_day: 5000,
                    max_concurrent_requests: 5,
                },
                ..Default::default()
            },
            TenantTier::Standard => TenantConfig {
                rate_limits: RateLimitConfig {
                    requests_per_minute: 300,
                    requests_per_hour: 3000,
                    requests_per_day: 30000,
                    max_concurrent_requests: 20,
                },
                ..Default::default()
            },
            TenantTier::Premium => TenantConfig {
                rate_limits: RateLimitConfig {
                    requests_per_minute: 1000,
                    requests_per_hour: 10000,
                    requests_per_day: 100000,
                    max_concurrent_requests: 50,
                },
                ..Default::default()
            },
            TenantTier::Enterprise => TenantConfig {
                rate_limits: RateLimitConfig {
                    requests_per_minute: 10000,
                    requests_per_hour: 100000,
                    requests_per_day: 1000000,
                    max_concurrent_requests: 200,
                },
                ..Default::default()
            },
        }
    }

    fn default_quota_for_tier(tier: &TenantTier) -> ResourceQuota {
        match tier {
            TenantTier::Free => ResourceQuota {
                max_storage_gb: 5,
                max_cpu_cores: 0.5,
                max_memory_gb: 1.0,
                max_api_requests: 50000,
                max_users: 3,
                max_connections: 50,
            },
            TenantTier::Standard => ResourceQuota {
                max_storage_gb: 50,
                max_cpu_cores: 2.0,
                max_memory_gb: 4.0,
                max_api_requests: 500000,
                max_users: 25,
                max_connections: 200,
            },
            TenantTier::Premium => ResourceQuota {
                max_storage_gb: 500,
                max_cpu_cores: 8.0,
                max_memory_gb: 16.0,
                max_api_requests: 5000000,
                max_users: 100,
                max_connections: 500,
            },
            TenantTier::Enterprise => ResourceQuota {
                max_storage_gb: 5000,
                max_cpu_cores: 32.0,
                max_memory_gb: 64.0,
                max_api_requests: u64::MAX,
                max_users: u32::MAX,
                max_connections: 2000,
            },
        }
    }

    pub fn get_tenant(&self, tenant_id: &str) -> Option<Tenant> {
        self.tenants.get(tenant_id).map(|t| t.clone())
    }

    pub fn list_tenants(&self) -> Vec<Tenant> {
        self.tenants.iter().map(|t| t.clone()).collect()
    }

    pub fn update_tenant_config(&self, tenant_id: &str, config: TenantConfig) -> Result<Tenant> {
        let mut tenant = self.tenants.get_mut(tenant_id)
            .ok_or_else(|| anyhow!("Tenant not found: {}", tenant_id))?;
        
        tenant.config = config;
        tenant.updated_at = Utc::now();
        
        let updated = tenant.clone();
        drop(tenant);
        
        self.notify_listeners(TenantEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            tenant_id: tenant_id.to_string(),
            event_type: TenantEventType::Updated,
            timestamp: Utc::now(),
        });
        
        Ok(updated)
    }

    pub fn update_tenant_quota(&self, tenant_id: &str, quota: ResourceQuota) -> Result<Tenant> {
        let mut tenant = self.tenants.get_mut(tenant_id)
            .ok_or_else(|| anyhow!("Tenant not found: {}", tenant_id))?;
        
        tenant.quota = quota;
        tenant.updated_at = Utc::now();
        
        self.semaphores.insert(
            tenant_id.to_string(),
            Arc::new(Semaphore::new(quota.max_connections as usize)),
        );
        
        let updated = tenant.clone();
        drop(tenant);
        
        self.notify_listeners(TenantEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            tenant_id: tenant_id.to_string(),
            event_type: TenantEventType::Updated,
            timestamp: Utc::now(),
        });
        
        Ok(updated)
    }

    pub fn suspend_tenant(&self, tenant_id: &str) -> Result<Tenant> {
        let mut tenant = self.tenants.get_mut(tenant_id)
            .ok_or_else(|| anyhow!("Tenant not found: {}", tenant_id))?;
        
        tenant.status = TenantStatus::Suspended;
        tenant.updated_at = Utc::now();
        let updated = tenant.clone();
        drop(tenant);
        
        self.notify_listeners(TenantEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            tenant_id: tenant_id.to_string(),
            event_type: TenantEventType::Suspended,
            timestamp: Utc::now(),
        });
        
        info!("Suspended tenant: {}", tenant_id);
        Ok(updated)
    }

    pub fn reactivate_tenant(&self, tenant_id: &str) -> Result<Tenant> {
        let mut tenant = self.tenants.get_mut(tenant_id)
            .ok_or_else(|| anyhow!("Tenant not found: {}", tenant_id))?;
        
        tenant.status = TenantStatus::Active;
        tenant.updated_at = Utc::now();
        let updated = tenant.clone();
        drop(tenant);
        
        self.notify_listeners(TenantEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            tenant_id: tenant_id.to_string(),
            event_type: TenantEventType::Reactivated,
            timestamp: Utc::now(),
        });
        
        info!("Reactivated tenant: {}", tenant_id);
        Ok(updated)
    }

    pub fn terminate_tenant(&self, tenant_id: &str) -> Result<()> {
        if self.tenants.remove(tenant_id).is_some() {
            self.quota_usage.remove(tenant_id);
            self.rate_limit_states.remove(tenant_id);
            self.semaphores.remove(tenant_id);
            
            self.notify_listeners(TenantEvent {
                event_id: format!("evt_{}", Uuid::new_v4().simple()),
                tenant_id: tenant_id.to_string(),
                event_type: TenantEventType::Terminated,
                timestamp: Utc::now(),
            });
            
            info!("Terminated tenant: {}", tenant_id);
            Ok(())
        } else {
            Err(anyhow!("Tenant not found: {}", tenant_id))
        }
    }

    pub fn check_rate_limit(&self, tenant_id: &str) -> Result<bool> {
        let tenant = self.tenants.get(tenant_id)
            .ok_or_else(|| anyhow!("Tenant not found: {}", tenant_id))?;
        
        if tenant.status != TenantStatus::Active {
            return Err(anyhow!("Tenant is not active"));
        }

        let now = Utc::now();
        let mut state = self.rate_limit_states
            .entry(tenant_id.to_string())
            .or_insert_with(RateLimitState::default);
        
        let one_minute_ago = now - chrono::Duration::minutes(1);
        let one_hour_ago = now - chrono::Duration::hours(1);
        let one_day_ago = now - chrono::Duration::days(1);
        
        state.minute_requests.retain(|t| t > &one_minute_ago);
        state.hour_requests.retain(|t| t > &one_hour_ago);
        state.day_requests.retain(|t| t > &one_day_ago);
        
        let limits = &tenant.config.rate_limits;
        
        if state.minute_requests.len() >= limits.requests_per_minute as usize
            || state.hour_requests.len() >= limits.requests_per_hour as usize
            || state.day_requests.len() >= limits.requests_per_day as usize
        {
            drop(state);
            self.notify_listeners(TenantEvent {
                event_id: format!("evt_{}", Uuid::new_v4().simple()),
                tenant_id: tenant_id.to_string(),
                event_type: TenantEventType::RateLimitExceeded,
                timestamp: now,
            });
            warn!("Rate limit exceeded for tenant: {}", tenant_id);
            return Ok(false);
        }
        
        state.minute_requests.push(now);
        state.hour_requests.push(now);
        state.day_requests.push(now);
        
        Ok(true)
    }

    pub fn check_quota(&self, tenant_id: &str) -> Result<bool> {
        let tenant = self.tenants.get(tenant_id)
            .ok_or_else(|| anyhow!("Tenant not found: {}", tenant_id))?;
        
        let usage = self.quota_usage.get(tenant_id)
            .ok_or_else(|| anyhow!("No quota usage found for tenant: {}", tenant_id))?;
        
        let exceeded = usage.storage_used_gb > tenant.quota.max_storage_gb
            || usage.cpu_used_cores > tenant.quota.max_cpu_cores
            || usage.memory_used_gb > tenant.quota.max_memory_gb
            || usage.api_requests_count > tenant.quota.max_api_requests
            || usage.active_users > tenant.quota.max_users;
        
        if exceeded {
            drop(usage);
            self.notify_listeners(TenantEvent {
                event_id: format!("evt_{}", Uuid::new_v4().simple()),
                tenant_id: tenant_id.to_string(),
                event_type: TenantEventType::QuotaExceeded,
                timestamp: Utc::now(),
            });
            warn!("Quota exceeded for tenant: {}", tenant_id);
            return Ok(false);
        }
        
        Ok(true)
    }

    pub fn update_usage(&self, tenant_id: &str, usage_update: QuotaUsageUpdate) -> Result<()> {
        let mut usage = self.quota_usage.get_mut(tenant_id)
            .ok_or_else(|| anyhow!("No quota usage found for tenant: {}", tenant_id))?;
        
        if let Some(v) = usage_update.storage_used_gb { usage.storage_used_gb = v; }
        if let Some(v) = usage_update.cpu_used_cores { usage.cpu_used_cores = v; }
        if let Some(v) = usage_update.memory_used_gb { usage.memory_used_gb = v; }
        if let Some(v) = usage_update.api_requests_count { usage.api_requests_count = v; }
        if let Some(v) = usage_update.active_users { usage.active_users = v; }
        if let Some(v) = usage_update.active_connections { usage.active_connections = v; }
        if let Some(v) = usage_update.current_requests { usage.current_requests = v; }
        
        usage.last_updated = Utc::now();
        Ok(())
    }

    pub fn increment_api_requests(&self, tenant_id: &str) -> Result<()> {
        let mut usage = self.quota_usage.get_mut(tenant_id)
            .ok_or_else(|| anyhow!("No quota usage found for tenant: {}", tenant_id))?;
        
        usage.api_requests_count = usage.api_requests_count.saturating_add(1);
        usage.last_updated = Utc::now();
        Ok(())
    }

    pub async fn acquire_connection(&self, tenant_id: &str) -> Result<tokio::sync::SemaphorePermit> {
        let semaphore = self.semaphores.get(tenant_id)
            .ok_or_else(|| anyhow!("Tenant not found: {}", tenant_id))?;
        
        let permit = semaphore.clone().acquire_owned().await
            .map_err(|e| anyhow!("Failed to acquire connection: {}", e))?;
        
        let mut usage = self.quota_usage.get_mut(tenant_id)
            .ok_or_else(|| anyhow!("No quota usage found for tenant: {}", tenant_id))?;
        usage.active_connections = usage.active_connections.saturating_add(1);
        
        Ok(permit)
    }

    pub fn get_quota_usage(&self, tenant_id: &str) -> Option<QuotaUsage> {
        self.quota_usage.get(tenant_id).map(|u| u.clone())
    }

    pub fn is_feature_enabled(&self, tenant_id: &str, feature: &str) -> bool {
        self.tenants.get(tenant_id)
            .map(|t| t.config.feature_flags.get(feature).copied().unwrap_or(false))
            .unwrap_or(false)
    }

    pub fn get_tenant_context(&self, tenant_id: &str) -> Option<TenantContext> {
        self.tenants.get(tenant_id).map(|tenant| TenantContext {
            tenant_id: tenant.tenant_id.clone(),
            tier: tenant.tier.clone(),
            config: tenant.config.clone(),
            quota: tenant.quota.clone(),
        })
    }
}

#[derive(Debug, Clone)]
pub struct TenantContext {
    pub tenant_id: String,
    pub tier: TenantTier,
    pub config: TenantConfig,
    pub quota: ResourceQuota,
}

#[derive(Debug, Clone, Default)]
pub struct QuotaUsageUpdate {
    pub storage_used_gb: Option<u64>,
    pub cpu_used_cores: Option<f64>,
    pub memory_used_gb: Option<f64>,
    pub api_requests_count: Option<u64>,
    pub active_users: Option<u32>,
    pub active_connections: Option<u32>,
    pub current_requests: Option<u32>,
}

impl Default for TenantManager {
    fn default() -> Self {
        Self::new()
    }
}
