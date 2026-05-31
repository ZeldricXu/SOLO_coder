use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;
use std::sync::{Arc, Mutex};
use chrono::{DateTime, Utc, Duration};
use lru::LruCache;
use std::num::NonZeroUsize;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum DnsRecordType {
    A,
    AAAA,
    CNAME,
    MX,
    TXT,
    SRV,
    NS,
    PTR,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DnsRecord {
    pub name: String,
    pub record_type: DnsRecordType,
    pub value: String,
    pub ttl: u32,
    pub priority: Option<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DnsResponse {
    pub records: Vec<DnsRecord>,
    pub source: String,
    pub resolved_at: DateTime<Utc>,
    pub rtt_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpstreamDns {
    pub id: String,
    pub name: String,
    pub address: String,
    pub port: u16,
    pub protocol: String,
    pub enabled: bool,
    pub priority: u32,
    pub timeout_ms: u64,
    pub health_check_enabled: bool,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpstreamHealth {
    pub upstream_id: String,
    pub is_healthy: bool,
    pub last_check: DateTime<Utc>,
    pub failure_count: u32,
    pub success_count: u32,
    pub average_rtt_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum ResolutionStrategy {
    Failover,
    RoundRobin,
    Fastest,
    LatencyWeighted,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheConfig {
    pub enabled: bool,
    pub max_size: usize,
    pub default_ttl_override: Option<u32>,
    pub min_ttl: u32,
    pub max_ttl: u32,
}

#[derive(Debug, Clone)]
struct CacheEntry {
    response: DnsResponse,
    expires_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DnsProxyConfig {
    pub id: String,
    pub name: String,
    pub upstreams: Vec<UpstreamDns>,
    pub strategy: ResolutionStrategy,
    pub cache_config: CacheConfig,
    pub retry_attempts: u32,
    pub timeout_ms: u64,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone)]
pub struct DnsProxy {
    configs: Arc<Mutex<HashMap<String, DnsProxyConfig>>>,
    health_status: Arc<Mutex<HashMap<String, UpstreamHealth>>>,
    cache: Arc<Mutex<LruCache<String, CacheEntry>>>,
}

impl DnsProxy {
    pub fn new() -> Self {
        let cache_size = NonZeroUsize::new(10000).unwrap();
        Self {
            configs: Arc::new(Mutex::new(HashMap::new())),
            health_status: Arc::new(Mutex::new(HashMap::new())),
            cache: Arc::new(Mutex::new(LruCache::new(cache_size))),
        }
    }

    pub fn create_config(
        &self,
        name: &str,
        strategy: ResolutionStrategy,
        cache_config: CacheConfig,
    ) -> DnsProxyConfig {
        let id = Uuid::new_v4().to_string();
        let now = Utc::now();

        let config = DnsProxyConfig {
            id: id.clone(),
            name: name.to_string(),
            upstreams: vec![],
            strategy,
            cache_config,
            retry_attempts: 3,
            timeout_ms: 5000,
            created_at: now,
            updated_at: now,
        };

        let mut configs = self.configs.lock().unwrap();
        configs.insert(id, config.clone());
        config
    }

    pub fn add_upstream(&self, config_id: &str, upstream: UpstreamDns) -> Option<DnsProxyConfig> {
        let mut configs = self.configs.lock().unwrap();
        let config = configs.get_mut(config_id)?;

        let health = UpstreamHealth {
            upstream_id: upstream.id.clone(),
            is_healthy: true,
            last_check: Utc::now(),
            failure_count: 0,
            success_count: 0,
            average_rtt_ms: 0,
        };

        let mut health_status = self.health_status.lock().unwrap();
        health_status.insert(upstream.id.clone(), health);

        config.upstreams.push(upstream);
        config.updated_at = Utc::now();
        Some(config.clone())
    }

    pub fn remove_upstream(&self, config_id: &str, upstream_id: &str) -> Option<DnsProxyConfig> {
        let mut configs = self.configs.lock().unwrap();
        let config = configs.get_mut(config_id)?;

        config.upstreams.retain(|u| u.id != upstream_id);
        config.updated_at = Utc::now();

        let mut health_status = self.health_status.lock().unwrap();
        health_status.remove(upstream_id);

        Some(config.clone())
    }

    pub fn get_config(&self, config_id: &str) -> Option<DnsProxyConfig> {
        let configs = self.configs.lock().unwrap();
        configs.get(config_id).cloned()
    }

    pub fn list_configs(&self) -> Vec<DnsProxyConfig> {
        let configs = self.configs.lock().unwrap();
        configs.values().cloned().collect()
    }

    pub fn delete_config(&self, config_id: &str) -> bool {
        let mut configs = self.configs.lock().unwrap();
        configs.remove(config_id).is_some()
    }

    pub async fn resolve(
        &self,
        config_id: &str,
        domain: &str,
        record_type: DnsRecordType,
    ) -> Result<DnsResponse, String> {
        let configs = self.configs.lock().unwrap();
        let config = configs.get(config_id).ok_or_else(|| "Config not found".to_string())?;

        let cache_key = format!("{}:{:?}", domain, record_type);

        if config.cache_config.enabled {
            let mut cache = self.cache.lock().unwrap();
            if let Some(entry) = cache.get(&cache_key) {
                if entry.expires_at > Utc::now() {
                    return Ok(entry.response.clone());
                }
            }
        }

        let healthy_upstreams = self.get_healthy_upstreams(config);
        if healthy_upstreams.is_empty() {
            return Err("No healthy DNS upstreams available".to_string());
        }

        let ordered_upstreams = self.order_upstreams(&healthy_upstreams, &config.strategy);

        let mut last_error = None;
        for upstream in ordered_upstreams {
            match self.resolve_with_upstream(domain, record_type.clone(), &upstream).await {
                Ok(mut response) => {
                    self.record_success(&upstream.id, response.rtt_ms);

                    let effective_ttl = self.calculate_effective_ttl(&response, &config.cache_config);
                    let expires_at = Utc::now() + Duration::seconds(effective_ttl as i64);

                    for record in &mut response.records {
                        record.ttl = effective_ttl;
                    }

                    if config.cache_config.enabled {
                        let mut cache = self.cache.lock().unwrap();
                        cache.put(cache_key, CacheEntry {
                            response: response.clone(),
                            expires_at,
                        });
                    }

                    return Ok(response);
                }
                Err(e) => {
                    self.record_failure(&upstream.id);
                    last_error = Some(e);
                }
            }
        }

        Err(last_error.unwrap_or_else(|| "All upstreams failed".to_string()))
    }

    async fn resolve_with_upstream(
        &self,
        domain: &str,
        record_type: DnsRecordType,
        upstream: &UpstreamDns,
    ) -> Result<DnsResponse, String> {
        let start = std::time::Instant::now();

        tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;

        let rtt_ms = start.elapsed().as_millis() as u64;

        let records = match record_type {
            DnsRecordType::A => vec![
                DnsRecord {
                    name: domain.to_string(),
                    record_type: DnsRecordType::A,
                    value: format!("192.168.{}.{}", rand::random::<u8>(), rand::random::<u8>()),
                    ttl: 300,
                    priority: None,
                }
            ],
            DnsRecordType::AAAA => vec![
                DnsRecord {
                    name: domain.to_string(),
                    record_type: DnsRecordType::AAAA,
                    value: "2001:db8::1".to_string(),
                    ttl: 300,
                    priority: None,
                }
            ],
            DnsRecordType::CNAME => vec![
                DnsRecord {
                    name: domain.to_string(),
                    record_type: DnsRecordType::CNAME,
                    value: format!("www.{}", domain),
                    ttl: 300,
                    priority: None,
                }
            ],
            _ => vec![],
        };

        Ok(DnsResponse {
            records,
            source: upstream.address.clone(),
            resolved_at: Utc::now(),
            rtt_ms,
        })
    }

    fn get_healthy_upstreams(&self, config: &DnsProxyConfig) -> Vec<UpstreamDns> {
        let health_status = self.health_status.lock().unwrap();
        
        config.upstreams.iter()
            .filter(|u| u.enabled)
            .filter(|u| {
                if !u.health_check_enabled {
                    return true;
                }
                health_status.get(&u.id)
                    .map(|h| h.is_healthy)
                    .unwrap_or(true)
            })
            .cloned()
            .collect()
    }

    fn order_upstreams(&self, upstreams: &[UpstreamDns], strategy: &ResolutionStrategy) -> Vec<UpstreamDns> {
        let mut upstreams = upstreams.to_vec();
        
        match strategy {
            ResolutionStrategy::Failover => {
                upstreams.sort_by_key(|u| u.priority);
            }
            ResolutionStrategy::RoundRobin => {
                use rand::seq::SliceRandom;
                let mut rng = rand::thread_rng();
                upstreams.shuffle(&mut rng);
            }
            ResolutionStrategy::Fastest | ResolutionStrategy::LatencyWeighted => {
                let health_status = self.health_status.lock().unwrap();
                upstreams.sort_by_key(|u| {
                    health_status.get(&u.id)
                        .map(|h| h.average_rtt_ms)
                        .unwrap_or(u64::MAX)
                });
            }
        }
        
        upstreams
    }

    fn record_success(&self, upstream_id: &str, rtt_ms: u64) {
        let mut health_status = self.health_status.lock().unwrap();
        let health = health_status.entry(upstream_id.to_string()).or_insert(UpstreamHealth {
            upstream_id: upstream_id.to_string(),
            is_healthy: true,
            last_check: Utc::now(),
            failure_count: 0,
            success_count: 0,
            average_rtt_ms: rtt_ms,
        });

        health.success_count += 1;
        health.failure_count = health.failure_count.saturating_sub(1);
        health.last_check = Utc::now();
        health.is_healthy = true;
        health.average_rtt_ms = (health.average_rtt_ms + rtt_ms) / 2;
    }

    fn record_failure(&self, upstream_id: &str) {
        let mut health_status = self.health_status.lock().unwrap();
        let health = health_status.entry(upstream_id.to_string()).or_insert(UpstreamHealth {
            upstream_id: upstream_id.to_string(),
            is_healthy: true,
            last_check: Utc::now(),
            failure_count: 0,
            success_count: 0,
            average_rtt_ms: 0,
        });

        health.failure_count += 1;
        health.last_check = Utc::now();
        
        if health.failure_count >= 5 {
            health.is_healthy = false;
        }
    }

    fn calculate_effective_ttl(&self, response: &DnsResponse, cache_config: &CacheConfig) -> u32 {
        let min_ttl = response.records.iter()
            .map(|r| r.ttl)
            .min()
            .unwrap_or(cache_config.min_ttl);

        let mut ttl = cache_config.default_ttl_override.unwrap_or(min_ttl);
        ttl = ttl.max(cache_config.min_ttl);
        ttl = ttl.min(cache_config.max_ttl);
        ttl
    }

    pub fn clear_cache(&self) {
        let cache_size = NonZeroUsize::new(10000).unwrap();
        let mut cache = self.cache.lock().unwrap();
        *cache = LruCache::new(cache_size);
    }

    pub fn get_cache_stats(&self) -> (usize, usize) {
        let cache = self.cache.lock().unwrap();
        (cache.len(), cache.cap().get())
    }

    pub fn get_upstream_health(&self, upstream_id: &str) -> Option<UpstreamHealth> {
        let health_status = self.health_status.lock().unwrap();
        health_status.get(upstream_id).cloned()
    }

    pub fn list_upstream_health(&self) -> Vec<UpstreamHealth> {
        let health_status = self.health_status.lock().unwrap();
        health_status.values().cloned().collect()
    }
}

impl Default for DnsProxy {
    fn default() -> Self {
        Self::new()
    }
}

pub fn create_upstream(name: &str, address: &str, port: u16, priority: u32) -> UpstreamDns {
    UpstreamDns {
        id: Uuid::new_v4().to_string(),
        name: name.to_string(),
        address: address.to_string(),
        port,
        protocol: "udp".to_string(),
        enabled: true,
        priority,
        timeout_ms: 5000,
        health_check_enabled: true,
        labels: HashMap::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_config() {
        let proxy = DnsProxy::new();
        let cache_config = CacheConfig {
            enabled: true,
            max_size: 1000,
            default_ttl_override: None,
            min_ttl: 60,
            max_ttl: 86400,
        };
        
        let config = proxy.create_config("test-config", ResolutionStrategy::Failover, cache_config);
        
        assert_eq!(config.name, "test-config");
        assert_eq!(config.strategy, ResolutionStrategy::Failover);
        assert!(config.cache_config.enabled);
    }

    #[test]
    fn test_add_upstream() {
        let proxy = DnsProxy::new();
        let cache_config = CacheConfig {
            enabled: true,
            max_size: 1000,
            default_ttl_override: None,
            min_ttl: 60,
            max_ttl: 86400,
        };
        
        let config = proxy.create_config("test-config", ResolutionStrategy::Failover, cache_config);
        let upstream = create_upstream("cloudflare", "1.1.1.1", 53, 1);
        
        let updated = proxy.add_upstream(&config.id, upstream);
        assert!(updated.is_some());
        assert_eq!(updated.unwrap().upstreams.len(), 1);
    }

    #[tokio::test]
    async fn test_resolve() {
        let proxy = DnsProxy::new();
        let cache_config = CacheConfig {
            enabled: true,
            max_size: 1000,
            default_ttl_override: None,
            min_ttl: 60,
            max_ttl: 86400,
        };
        
        let config = proxy.create_config("test-config", ResolutionStrategy::Failover, cache_config);
        let upstream = create_upstream("cloudflare", "1.1.1.1", 53, 1);
        proxy.add_upstream(&config.id, upstream);
        
        let result = proxy.resolve(&config.id, "example.com", DnsRecordType::A).await;
        assert!(result.is_ok());
        
        let response = result.unwrap();
        assert!(!response.records.is_empty());
        assert_eq!(response.records[0].record_type, DnsRecordType::A);
    }

    #[test]
    fn test_cache_stats() {
        let proxy = DnsProxy::new();
        let (used, capacity) = proxy.get_cache_stats();
        
        assert_eq!(used, 0);
        assert_eq!(capacity, 10000);
    }

    #[test]
    fn test_clear_cache() {
        let proxy = DnsProxy::new();
        proxy.clear_cache();
        
        let (used, _) = proxy.get_cache_stats();
        assert_eq!(used, 0);
    }
}
