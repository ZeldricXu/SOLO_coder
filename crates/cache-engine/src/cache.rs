use std::collections::HashMap;
use std::hash::Hash;
use std::sync::Arc;
use tokio::sync::RwLock;
use chrono::{DateTime, Utc};

use common::error::{CdnResult, CdnError};
use common::models::CachedContent;

use crate::policies::EvictionPolicy;

#[derive(Clone)]
pub struct CacheEntry {
    pub content: Vec<u8>,
    pub content_type: String,
    pub size_bytes: u64,
    pub created_at: DateTime<Utc>,
    pub expires_at: DateTime<Utc>,
    pub access_count: u64,
    pub last_accessed: DateTime<Utc>,
}

pub struct Cache<K: Clone + Eq + Hash + Send + Sync> {
    entries: HashMap<K, CacheEntry>,
    eviction_policy: Box<dyn EvictionPolicy<K>>,
    max_size_bytes: u64,
    current_size_bytes: u64,
    hit_count: u64,
    miss_count: u64,
}

impl<K: Clone + Eq + Hash + Send + Sync> Cache<K> {
    pub fn new(eviction_policy: Box<dyn EvictionPolicy<K>>, max_size_bytes: u64) -> Self {
        Cache {
            entries: HashMap::new(),
            eviction_policy,
            max_size_bytes,
            current_size_bytes: 0,
            hit_count: 0,
            miss_count: 0,
        }
    }

    pub fn get(&mut self, key: &K) -> Option<&CacheEntry> {
        if let Some(entry) = self.entries.get(key) {
            if Utc::now() > entry.expires_at {
                self.evict_entry(key);
                self.miss_count += 1;
                return None;
            }

            self.eviction_policy.on_access(key);
            self.hit_count += 1;
            
            let entry = self.entries.get_mut(key).unwrap();
            entry.access_count += 1;
            entry.last_accessed = Utc::now();
            
            Some(entry)
        } else {
            self.miss_count += 1;
            None
        }
    }

    pub fn insert(&mut self, key: K, content: Vec<u8>, content_type: String, ttl_seconds: u64) -> CdnResult<()> {
        let size_bytes = content.len() as u64;
        
        if size_bytes > self.max_size_bytes {
            return Err(CdnError::CacheError("Content too large for cache".to_string()));
        }

        while self.current_size_bytes + size_bytes > self.max_size_bytes {
            if let Some(evicted_key) = self.eviction_policy.evict() {
                self.evict_entry(&evicted_key);
            } else {
                return Err(CdnError::CacheError("Cache full and cannot evict".to_string()));
            }
        }

        let now = Utc::now();
        let expires_at = now + chrono::Duration::seconds(ttl_seconds as i64);

        let entry = CacheEntry {
            content,
            content_type,
            size_bytes,
            created_at: now,
            expires_at,
            access_count: 1,
            last_accessed: now,
        };

        self.entries.insert(key.clone(), entry);
        self.eviction_policy.on_insert(key);
        self.current_size_bytes += size_bytes;

        Ok(())
    }

    pub fn remove(&mut self, key: &K) -> bool {
        if self.entries.contains_key(key) {
            self.evict_entry(key);
            true
        } else {
            false
        }
    }

    pub fn contains(&self, key: &K) -> bool {
        self.entries.contains_key(key)
    }

    fn evict_entry(&mut self, key: &K) {
        if let Some(entry) = self.entries.remove(key) {
            self.current_size_bytes -= entry.size_bytes;
            self.eviction_policy.remove(key);
        }
    }

    pub fn clear(&mut self) {
        self.entries.clear();
        self.eviction_policy.clear();
        self.current_size_bytes = 0;
    }

    pub fn hit_rate(&self) -> f64 {
        let total = self.hit_count + self.miss_count;
        if total == 0 {
            0.0
        } else {
            (self.hit_count as f64) / (total as f64)
        }
    }

    pub fn size_bytes(&self) -> u64 {
        self.current_size_bytes
    }

    pub fn max_size_bytes(&self) -> u64 {
        self.max_size_bytes
    }

    pub fn entry_count(&self) -> usize {
        self.entries.len()
    }

    pub fn hits(&self) -> u64 {
        self.hit_count
    }

    pub fn misses(&self) -> u64 {
        self.miss_count
    }

    pub fn cleanup_expired(&mut self) -> usize {
        let now = Utc::now();
        let expired_keys: Vec<K> = self.entries
            .iter()
            .filter(|(_, entry)| now > entry.expires_at)
            .map(|(k, _)| k.clone())
            .collect();

        let count = expired_keys.len();
        for key in expired_keys {
            self.evict_entry(&key);
        }

        count
    }

    pub fn keys(&self) -> impl Iterator<Item = &K> {
        self.entries.keys()
    }

    pub fn available_bytes(&self) -> u64 {
        self.max_size_bytes.saturating_sub(self.current_size_bytes)
    }

    pub fn usage_ratio(&self) -> f64 {
        if self.max_size_bytes == 0 {
            1.0
        } else {
            (self.current_size_bytes as f64) / (self.max_size_bytes as f64)
        }
    }

    pub fn evict_to_target(&mut self, target_bytes: u64) -> usize {
        let mut evicted_count = 0;
        
        while self.current_size_bytes > target_bytes {
            if let Some(evicted_key) = self.eviction_policy.evict() {
                self.evict_entry(&evicted_key);
                evicted_count += 1;
            } else {
                break;
            }
        }

        evicted_count
    }

    pub fn ensure_space(&mut self, required_bytes: u64) -> bool {
        if self.available_bytes() >= required_bytes {
            return true;
        }

        let needed_bytes = required_bytes.saturating_sub(self.available_bytes());
        let target_bytes = self.current_size_bytes.saturating_sub(needed_bytes);
        
        self.evict_to_target(target_bytes);
        
        self.available_bytes() >= required_bytes
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policies::LRUPolicy;

    #[test]
    fn test_available_bytes() {
        let policy = Box::new(LRUPolicy::<String>::new());
        let mut cache = Cache::new(policy, 1000);
        
        assert_eq!(cache.available_bytes(), 1000);
        
        let content = vec![0u8; 500];
        cache.insert("key1".to_string(), content, "text/plain".to_string(), 60).unwrap();
        
        assert_eq!(cache.available_bytes(), 500);
    }

    #[test]
    fn test_usage_ratio() {
        let policy = Box::new(LRUPolicy::<String>::new());
        let mut cache = Cache::new(policy, 1000);
        
        assert!((cache.usage_ratio() - 0.0).abs() < 0.001);
        
        let content = vec![0u8; 500];
        cache.insert("key1".to_string(), content, "text/plain".to_string(), 60).unwrap();
        
        assert!((cache.usage_ratio() - 0.5).abs() < 0.001);
    }

    #[test]
    fn test_evict_to_target() {
        let policy = Box::new(LRUPolicy::<String>::new());
        let mut cache = Cache::new(policy, 1000);
        
        for i in 0..5 {
            let content = vec![0u8; 200];
            cache.insert(format!("key{}", i), content, "text/plain".to_string(), 60).unwrap();
        }
        
        assert_eq!(cache.entry_count(), 5);
        assert_eq!(cache.size_bytes(), 1000);
        
        let evicted = cache.evict_to_target(500);
        
        assert_eq!(evicted, 3);
        assert_eq!(cache.entry_count(), 2);
        assert!(cache.size_bytes() <= 500);
    }

    #[test]
    fn test_ensure_space_with_available_space() {
        let policy = Box::new(LRUPolicy::<String>::new());
        let mut cache = Cache::new(policy, 1000);
        
        let content = vec![0u8; 500];
        cache.insert("key1".to_string(), content, "text/plain".to_string(), 60).unwrap();
        
        assert!(cache.ensure_space(300));
        assert_eq!(cache.entry_count(), 1);
    }

    #[test]
    fn test_ensure_space_with_eviction() {
        let policy = Box::new(LRUPolicy::<String>::new());
        let mut cache = Cache::new(policy, 1000);
        
        for i in 0..5 {
            let content = vec![0u8; 200];
            cache.insert(format!("key{}", i), content, "text/plain".to_string(), 60).unwrap();
        }
        
        assert_eq!(cache.entry_count(), 5);
        assert!(cache.ensure_space(300));
        assert!(cache.available_bytes() >= 300);
        assert!(cache.entry_count() < 5);
    }

    #[test]
    fn test_ensure_space_impossible() {
        let policy = Box::new(LRUPolicy::<String>::new());
        let mut cache = Cache::new(policy, 1000);
        
        assert!(!cache.ensure_space(1500));
    }
}
