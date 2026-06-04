use std::collections::{VecDeque, HashMap};
use std::hash::Hash;
use std::sync::atomic::{AtomicU64, Ordering};

use common::error::{CdnResult, CdnError};
use common::models::CacheEvictionPolicy;

pub trait EvictionPolicy<K: Clone + Eq + Hash + Sync + Send>: Send + Sync {
    fn name(&self) -> &str;
    fn on_access(&mut self, key: &K);
    fn on_insert(&mut self, key: K);
    fn evict(&mut self) -> Option<K>;
    fn remove(&mut self, key: &K);
    fn clear(&mut self);
}

pub struct LRUPolicy<K: Clone + Eq + Hash + Sync + Send> {
    order: VecDeque<K>,
}

impl<K: Clone + Eq + Hash + Sync + Send> LRUPolicy<K> {
    pub fn new() -> Self {
        LRUPolicy {
            order: VecDeque::new(),
        }
    }
}

impl<K: Clone + Eq + Hash + Sync + Send> EvictionPolicy<K> for LRUPolicy<K> {
    fn name(&self) -> &str {
        "LRU"
    }

    fn on_access(&mut self, key: &K) {
        if let Some(pos) = self.order.iter().position(|k| k == key) {
            let k = self.order.remove(pos).unwrap();
            self.order.push_front(k);
        }
    }

    fn on_insert(&mut self, key: K) {
        self.order.push_front(key);
    }

    fn evict(&mut self) -> Option<K> {
        self.order.pop_back()
    }

    fn remove(&mut self, key: &K) {
        if let Some(pos) = self.order.iter().position(|k| k == key) {
            self.order.remove(pos);
        }
    }

    fn clear(&mut self) {
        self.order.clear();
    }
}

impl<K: Clone + Eq + Hash + Sync + Send> Default for LRUPolicy<K> {
    fn default() -> Self {
        Self::new()
    }
}

pub struct LFUPolicy<K: Clone + Eq + Hash + Sync + Send> {
    frequencies: HashMap<K, u64>,
    counter: AtomicU64,
}

impl<K: Clone + Eq + Hash + Sync + Send> LFUPolicy<K> {
    pub fn new() -> Self {
        LFUPolicy {
            frequencies: HashMap::new(),
            counter: AtomicU64::new(0),
        }
    }
}

impl<K: Clone + Eq + Hash + Sync + Send> EvictionPolicy<K> for LFUPolicy<K> {
    fn name(&self) -> &str {
        "LFU"
    }

    fn on_access(&mut self, key: &K) {
        if let Some(freq) = self.frequencies.get_mut(key) {
            *freq += 1;
        }
    }

    fn on_insert(&mut self, key: K) {
        self.frequencies.insert(key, 1);
    }

    fn evict(&mut self) -> Option<K> {
        let mut min_freq = u64::MAX;
        let mut min_key = None;

        for (key, &freq) in &self.frequencies {
            if freq < min_freq {
                min_freq = freq;
                min_key = Some(key.clone());
            }
        }

        if let Some(ref key) = min_key {
            self.frequencies.remove(key);
        }

        min_key
    }

    fn remove(&mut self, key: &K) {
        self.frequencies.remove(key);
    }

    fn clear(&mut self) {
        self.frequencies.clear();
    }
}

impl<K: Clone + Eq + Hash + Sync + Send> Default for LFUPolicy<K> {
    fn default() -> Self {
        Self::new()
    }
}

pub struct FIFOPolicy<K: Clone + Eq + Hash + Sync + Send> {
    order: VecDeque<K>,
}

impl<K: Clone + Eq + Hash + Sync + Send> FIFOPolicy<K> {
    pub fn new() -> Self {
        FIFOPolicy {
            order: VecDeque::new(),
        }
    }
}

impl<K: Clone + Eq + Hash + Sync + Send> EvictionPolicy<K> for FIFOPolicy<K> {
    fn name(&self) -> &str {
        "FIFO"
    }

    fn on_access(&mut self, _key: &K) {}

    fn on_insert(&mut self, key: K) {
        self.order.push_back(key);
    }

    fn evict(&mut self) -> Option<K> {
        self.order.pop_front()
    }

    fn remove(&mut self, key: &K) {
        if let Some(pos) = self.order.iter().position(|k| k == key) {
            self.order.remove(pos);
        }
    }

    fn clear(&mut self) {
        self.order.clear();
    }
}

impl<K: Clone + Eq + Hash + Sync + Send> Default for FIFOPolicy<K> {
    fn default() -> Self {
        Self::new()
    }
}

pub struct TwoQueuePolicy<K: Clone + Eq + Hash + Sync + Send> {
    am: VecDeque<K>,
    a1: VecDeque<K>,
    max_a1_size: usize,
}

impl<K: Clone + Eq + Hash + Sync + Send> TwoQueuePolicy<K> {
    pub fn new() -> Self {
        TwoQueuePolicy {
            am: VecDeque::new(),
            a1: VecDeque::new(),
            max_a1_size: 100,
        }
    }

    pub fn with_max_a1_size(max_a1_size: usize) -> Self {
        TwoQueuePolicy {
            am: VecDeque::new(),
            a1: VecDeque::new(),
            max_a1_size,
        }
    }
}

impl<K: Clone + Eq + Hash + Sync + Send> EvictionPolicy<K> for TwoQueuePolicy<K> {
    fn name(&self) -> &str {
        "TwoQueue"
    }

    fn on_access(&mut self, key: &K) {
        if let Some(pos) = self.am.iter().position(|k| k == key) {
            let k = self.am.remove(pos).unwrap();
            self.am.push_front(k);
            return;
        }

        if let Some(pos) = self.a1.iter().position(|k| k == key) {
            let k = self.a1.remove(pos).unwrap();
            self.am.push_front(k);
        }
    }

    fn on_insert(&mut self, key: K) {
        self.a1.push_back(key);
        
        if self.a1.len() > self.max_a1_size {
            self.a1.pop_front();
        }
    }

    fn evict(&mut self) -> Option<K> {
        if !self.a1.is_empty() {
            self.a1.pop_front()
        } else {
            self.am.pop_back()
        }
    }

    fn remove(&mut self, key: &K) {
        if let Some(pos) = self.am.iter().position(|k| k == key) {
            self.am.remove(pos);
            return;
        }
        if let Some(pos) = self.a1.iter().position(|k| k == key) {
            self.a1.remove(pos);
        }
    }

    fn clear(&mut self) {
        self.am.clear();
        self.a1.clear();
    }
}

impl<K: Clone + Eq + Hash + Sync + Send> Default for TwoQueuePolicy<K> {
    fn default() -> Self {
        Self::new()
    }
}

pub fn create_policy<K: Clone + Eq + Hash + Sync + Send + 'static>(policy: CacheEvictionPolicy) -> Box<dyn EvictionPolicy<K>> {
    match policy {
        CacheEvictionPolicy::LRU => Box::new(LRUPolicy::new()),
        CacheEvictionPolicy::LFU => Box::new(LFUPolicy::new()),
        CacheEvictionPolicy::FIFO => Box::new(FIFOPolicy::new()),
        CacheEvictionPolicy::TwoQueue => Box::new(TwoQueuePolicy::new()),
    }
}
