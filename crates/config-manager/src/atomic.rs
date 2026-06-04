use arc_swap::ArcSwap;
use chrono::{DateTime, Utc};
use common::config::{
    validate_config, CacheRule, ConfigSnapshot, ConfigValidationError, DomainConfig,
    NodeRuntimeConfig, SchedulingStrategy,
};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

pub struct AtomicConfigManager {
    current: ArcSwap<ConfigSnapshot>,
    validation_enabled: bool,
    version_counter: AtomicU64,
}

impl AtomicConfigManager {
    pub fn new(initial: NodeRuntimeConfig) -> Self {
        let snapshot = ConfigSnapshot {
            config: Arc::new(initial),
            version: 1,
            loaded_at: Utc::now(),
        };

        AtomicConfigManager {
            current: ArcSwap::from(Arc::new(snapshot)),
            validation_enabled: true,
            version_counter: AtomicU64::new(2),
        }
    }

    pub fn with_validation(initial: NodeRuntimeConfig, validation_enabled: bool) -> Self {
        let snapshot = ConfigSnapshot {
            config: Arc::new(initial),
            version: 1,
            loaded_at: Utc::now(),
        };

        AtomicConfigManager {
            current: ArcSwap::from(Arc::new(snapshot)),
            validation_enabled,
            version_counter: AtomicU64::new(2),
        }
    }

    pub fn update_config(&self, new_config: NodeRuntimeConfig) -> Result<(), Vec<ConfigValidationError>> {
        if self.validation_enabled {
            validate_config(&new_config)?;
        }

        let new_version = self.version_counter.fetch_add(1, Ordering::SeqCst);
        let snapshot = ConfigSnapshot {
            config: Arc::new(new_config),
            version: new_version,
            loaded_at: Utc::now(),
        };

        self.current.store(Arc::new(snapshot));

        Ok(())
    }

    pub fn get_snapshot(&self) -> Arc<ConfigSnapshot> {
        self.current.load_full()
    }

    pub fn get_config(&self) -> Arc<NodeRuntimeConfig> {
        Arc::clone(&self.current.load().config)
    }

    pub fn get_version(&self) -> u64 {
        self.current.load().version
    }

    pub fn get_loaded_at(&self) -> DateTime<Utc> {
        self.current.load().loaded_at
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::net::SocketAddr;
    use std::thread;

    fn create_valid_config(node_id: &str) -> NodeRuntimeConfig {
        let mut weights = HashMap::new();
        weights.insert("origin1".to_string(), 100);
        weights.insert("origin2".to_string(), 50);

        NodeRuntimeConfig {
            node_id: node_id.to_string(),
            listen_addr: "127.0.0.1:8080".parse::<SocketAddr>().unwrap(),
            cache_max_size_bytes: 1024 * 1024 * 100,
            cache_rules: vec![CacheRule {
                path_pattern: "/static/*".to_string(),
                ttl_seconds: 3600,
                cache_key_template: None,
            }],
            scheduling: SchedulingStrategy {
                strategy_type: "weighted_round_robin".to_string(),
                weights,
                health_check_enabled: true,
            },
            domains: vec![DomainConfig {
                domain_name: "example.com".to_string(),
                origin_server: "origin.example.com".to_string(),
                origin_port: 80,
                https_enabled: false,
                cache_rules: vec![],
            }],
            health_check_interval_seconds: 30,
            log_level: "info".to_string(),
        }
    }

    #[test]
    fn test_create_manager() {
        let config = create_valid_config("node-1");
        let manager = AtomicConfigManager::new(config);
        assert_eq!(manager.get_version(), 1);
        assert_eq!(manager.get_config().node_id, "node-1");
    }

    #[test]
    fn test_update_config_valid() {
        let config1 = create_valid_config("node-1");
        let manager = AtomicConfigManager::new(config1);
        assert_eq!(manager.get_version(), 1);

        let config2 = create_valid_config("node-2");
        manager.update_config(config2).unwrap();
        assert_eq!(manager.get_version(), 2);
        assert_eq!(manager.get_config().node_id, "node-2");
    }

    #[test]
    fn test_update_config_invalid_cache_size() {
        let config1 = create_valid_config("node-1");
        let manager = AtomicConfigManager::new(config1);

        let mut invalid_config = create_valid_config("node-bad");
        invalid_config.cache_max_size_bytes = 0;

        let result = manager.update_config(invalid_config);
        assert!(result.is_err());
        let errors = result.unwrap_err();
        assert!(errors.contains(&ConfigValidationError::CacheMaxSizeMustBePositive));
        assert_eq!(manager.get_version(), 1);
        assert_eq!(manager.get_config().node_id, "node-1");
    }

    #[test]
    fn test_update_config_invalid_ttl() {
        let config1 = create_valid_config("node-1");
        let manager = AtomicConfigManager::new(config1);

        let mut invalid_config = create_valid_config("node-bad");
        invalid_config.cache_rules[0].ttl_seconds = 0;

        let result = manager.update_config(invalid_config);
        assert!(result.is_err());
        let errors = result.unwrap_err();
        assert!(errors.contains(&ConfigValidationError::CacheTtlMustBePositive { rule_index: 0 }));
        assert_eq!(manager.get_version(), 1);
    }

    #[test]
    fn test_update_config_invalid_path_pattern() {
        let config1 = create_valid_config("node-1");
        let manager = AtomicConfigManager::new(config1);

        let mut invalid_config = create_valid_config("node-bad");
        invalid_config.cache_rules[0].path_pattern = String::new();

        let result = manager.update_config(invalid_config);
        assert!(result.is_err());
        assert_eq!(manager.get_version(), 1);
    }

    #[test]
    fn test_update_config_invalid_origin_server() {
        let config1 = create_valid_config("node-1");
        let manager = AtomicConfigManager::new(config1);

        let mut invalid_config = create_valid_config("node-bad");
        invalid_config.domains[0].origin_server = String::new();

        let result = manager.update_config(invalid_config);
        assert!(result.is_err());
        assert_eq!(manager.get_version(), 1);
    }

    #[test]
    fn test_update_config_invalid_weight() {
        let config1 = create_valid_config("node-1");
        let manager = AtomicConfigManager::new(config1);

        let mut invalid_config = create_valid_config("node-bad");
        invalid_config.scheduling.weights.insert("bad-origin".to_string(), 0);

        let result = manager.update_config(invalid_config);
        assert!(result.is_err());
        assert_eq!(manager.get_version(), 1);
    }

    #[test]
    fn test_snapshot_consistency() {
        let config1 = create_valid_config("v1");
        let manager = AtomicConfigManager::new(config1);

        let snapshot1 = manager.get_snapshot();
        assert_eq!(snapshot1.version, 1);
        assert_eq!(snapshot1.config.node_id, "v1");

        let config2 = create_valid_config("v2");
        manager.update_config(config2).unwrap();

        let snapshot2 = manager.get_snapshot();
        assert_eq!(snapshot2.version, 2);
        assert_eq!(snapshot2.config.node_id, "v2");

        assert_eq!(snapshot1.version, 1);
        assert_eq!(snapshot1.config.node_id, "v1");
    }

    #[test]
    fn test_concurrent_reads_and_writes() {
        let config = create_valid_config("initial");
        let manager = Arc::new(AtomicConfigManager::new(config));
        let manager_clone = Arc::clone(&manager);

        let num_readers = 10;
        let num_writes = 100;

        let mut handles = vec![];

        for _ in 0..num_readers {
            let m = Arc::clone(&manager);
            let handle = thread::spawn(move || {
                let mut previous_version: Option<u64> = None;
                for _ in 0..num_writes {
                    let snapshot = m.get_snapshot();
                    let version = snapshot.version;
                    let config = Arc::clone(&snapshot.config);

                    assert!(!config.node_id.is_empty());
                    assert!(config.cache_max_size_bytes > 0);

                    if let Some(prev) = previous_version {
                        assert!(version >= prev);
                    }
                    previous_version = Some(version);

                    thread::yield_now();
                }
                previous_version
            });
            handles.push(handle);
        }

        let writer_handle = thread::spawn(move || {
            for i in 0..num_writes {
                let new_config = create_valid_config(&format!("node-{}", i));
                manager_clone.update_config(new_config).unwrap();
                thread::yield_now();
            }
        });

        for handle in handles {
            let last_version = handle.join().unwrap();
            assert!(last_version.unwrap_or(0) >= 1);
        }

        writer_handle.join().unwrap();
        assert_eq!(manager.get_version(), 1 + num_writes as u64);
    }

    #[test]
    fn test_concurrent_writers() {
        let config = create_valid_config("initial");
        let manager = Arc::new(AtomicConfigManager::new(config));
        let num_writers = 5;
        let writes_per_writer = 20;

        let mut handles = vec![];

        for writer_id in 0..num_writers {
            let m = Arc::clone(&manager);
            let handle = thread::spawn(move || {
                for i in 0..writes_per_writer {
                    let new_config = create_valid_config(&format!("writer-{}-{}", writer_id, i));
                    m.update_config(new_config).unwrap();
                    thread::yield_now();
                }
            });
            handles.push(handle);
        }

        for handle in handles {
            handle.join().unwrap();
        }

        let expected_version = 1 + (num_writers * writes_per_writer) as u64;
        assert_eq!(manager.get_version(), expected_version);
    }

    #[test]
    fn test_validation_disabled() {
        let config = create_valid_config("node-1");
        let manager = AtomicConfigManager::with_validation(config, false);

        let mut invalid_config = create_valid_config("bad-node");
        invalid_config.cache_max_size_bytes = 0;

        let result = manager.update_config(invalid_config);
        assert!(result.is_ok());
        assert_eq!(manager.get_version(), 2);
    }
}
