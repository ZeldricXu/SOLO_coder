use serde::{Deserialize, Serialize};
use std::sync::Arc;
use dashmap::DashMap;
use anyhow::Result;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CatalogConfig {
    pub search_weight_name: f32,
    pub search_weight_description: f32,
    pub search_weight_tags: f32,
    pub max_page_size: usize,
    pub default_page_size: usize,
    pub enable_dependency_cycle_detection: bool,
    pub cache_ttl_seconds: u64,
    pub max_cached_items: usize,
}

impl Default for CatalogConfig {
    fn default() -> Self {
        Self {
            search_weight_name: 2.0,
            search_weight_description: 1.0,
            search_weight_tags: 1.5,
            max_page_size: 100,
            default_page_size: 20,
            enable_dependency_cycle_detection: true,
            cache_ttl_seconds: 300,
            max_cached_items: 1000,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigUpdate {
    pub key: String,
    pub value: serde_json::Value,
    pub updated_by: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigHistory {
    pub id: Uuid,
    pub key: String,
    pub old_value: serde_json::Value,
    pub new_value: serde_json::Value,
    pub updated_by: String,
    pub updated_at: chrono::DateTime<chrono::Utc>,
}

pub struct HotConfigManager {
    config: Arc<DashMap<String, serde_json::Value>>,
    history: Arc<DashMap<Uuid, ConfigHistory>>,
    listeners: Arc<DashMap<Uuid, Box<dyn Fn(&ConfigUpdate) + Send + Sync>>>,
}

impl HotConfigManager {
    pub fn new() -> Self {
        let config = Arc::new(DashMap::new());
        let default = CatalogConfig::default();
        config.insert("search_weight_name".to_string(), serde_json::to_value(default.search_weight_name).unwrap());
        config.insert("search_weight_description".to_string(), serde_json::to_value(default.search_weight_description).unwrap());
        config.insert("search_weight_tags".to_string(), serde_json::to_value(default.search_weight_tags).unwrap());
        config.insert("max_page_size".to_string(), serde_json::to_value(default.max_page_size).unwrap());
        config.insert("default_page_size".to_string(), serde_json::to_value(default.default_page_size).unwrap());
        config.insert("enable_dependency_cycle_detection".to_string(), serde_json::to_value(default.enable_dependency_cycle_detection).unwrap());
        config.insert("cache_ttl_seconds".to_string(), serde_json::to_value(default.cache_ttl_seconds).unwrap());
        config.insert("max_cached_items".to_string(), serde_json::to_value(default.max_cached_items).unwrap());
        Self {
            config,
            history: Arc::new(DashMap::new()),
            listeners: Arc::new(DashMap::new()),
        }
    }

    pub fn get<T: for<'de> Deserialize<'de>>(&self, key: &str) -> Option<T> {
        self.config.get(key).and_then(|v| serde_json::from_value(v.value().clone()).ok())
    }

    pub fn get_config(&self) -> CatalogConfig {
        CatalogConfig {
            search_weight_name: self.get("search_weight_name").unwrap_or(2.0),
            search_weight_description: self.get("search_weight_description").unwrap_or(1.0),
            search_weight_tags: self.get("search_weight_tags").unwrap_or(1.5),
            max_page_size: self.get("max_page_size").unwrap_or(100),
            default_page_size: self.get("default_page_size").unwrap_or(20),
            enable_dependency_cycle_detection: self.get("enable_dependency_cycle_detection").unwrap_or(true),
            cache_ttl_seconds: self.get("cache_ttl_seconds").unwrap_or(300),
            max_cached_items: self.get("max_cached_items").unwrap_or(1000),
        }
    }

    pub fn update(&self, update: ConfigUpdate) -> Result<()> {
        let old_value = self.config.get(&update.key)
            .map(|v| v.value().clone())
            .unwrap_or(serde_json::Value::Null);

        self.config.insert(update.key.clone(), update.value.clone());

        let history = ConfigHistory {
            id: Uuid::new_v4(),
            key: update.key.clone(),
            old_value,
            new_value: update.value.clone(),
            updated_by: update.updated_by.clone(),
            updated_at: chrono::Utc::now(),
        };
        self.history.insert(history.id, history);

        for listener in self.listeners.iter() {
            listener.value()(&update);
        }

        Ok(())
    }

    pub fn batch_update(&self, updates: Vec<ConfigUpdate>, updated_by: String) -> Result<()> {
        for update in updates {
            let update = ConfigUpdate {
                updated_by: updated_by.clone(),
                ..update
            };
            self.update(update)?;
        }
        Ok(())
    }

    pub fn add_listener<F>(&self, listener: F) -> Uuid
    where
        F: Fn(&ConfigUpdate) + Send + Sync + 'static,
    {
        let id = Uuid::new_v4();
        self.listeners.insert(id, Box::new(listener));
        id
    }

    pub fn remove_listener(&self, id: Uuid) {
        self.listeners.remove(&id);
    }

    pub fn get_history(&self, limit: usize) -> Vec<ConfigHistory> {
        let mut items: Vec<ConfigHistory> = self.history.iter().map(|v| v.value().clone()).collect();
        items.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));
        items.truncate(limit);
        items
    }

    pub fn reload_from_json(&self, json: &str) -> Result<()> {
        let new_config: serde_json::Map<String, serde_json::Value> = serde_json::from_str(json)?;
        for (key, value) in new_config {
            self.update(ConfigUpdate {
                key,
                value,
                updated_by: "system_reload".to_string(),
            })?;
        }
        Ok(())
    }

    pub fn export_to_json(&self) -> Result<String> {
        let mut map = serde_json::Map::new();
        for item in self.config.iter() {
            map.insert(item.key().clone(), item.value().clone());
        }
        Ok(serde_json::to_string_pretty(&map)?)
    }
}

impl Default for HotConfigManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_hot_config_update() {
        let manager = HotConfigManager::new();
        assert_eq!(manager.get::<usize>("max_page_size"), Some(100));
        
        manager.update(ConfigUpdate {
            key: "max_page_size".to_string(),
            value: serde_json::json!(200),
            updated_by: "test_user".to_string(),
        }).unwrap();
        
        assert_eq!(manager.get::<usize>("max_page_size"), Some(200));
    }

    #[test]
    fn test_config_listener() {
        let manager = HotConfigManager::new();
        let flag = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let flag_clone = flag.clone();
        
        manager.add_listener(move |_update| {
            flag_clone.store(true, std::sync::atomic::Ordering::SeqCst);
        });
        
        manager.update(ConfigUpdate {
            key: "search_weight_name".to_string(),
            value: serde_json::json!(3.0),
            updated_by: "test".to_string(),
        }).unwrap();
        
        assert!(flag.load(std::sync::atomic::Ordering::SeqCst));
    }
}
