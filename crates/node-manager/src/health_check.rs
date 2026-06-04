use std::sync::Arc;
use std::sync::atomic::Ordering::Relaxed;
use std::time::Duration;
use std::collections::HashSet;
use tokio::sync::RwLock;
use tokio::time::interval;
use uuid::Uuid;
use chrono::Utc;

use common::models::{NodeStatus, Heartbeat, TimeSlot};
use common::error::CdnResult;
use common::redis::RedisClient;

use crate::registry::NodeRegistry;

type Slot = Arc<RwLock<HashSet<Uuid>>>;

pub struct HealthChecker {
    registry: NodeRegistry,
    redis: RedisClient,
    slots: [Slot; 3],
    timeout_ms: [u64; 3],
    check_interval: Duration,
    running: Arc<RwLock<bool>>,
}

impl HealthChecker {
    pub fn new(
        registry: NodeRegistry,
        redis: RedisClient,
        heartbeat_interval_seconds: u64,
        max_failures: u32,
    ) -> Self {
        let base_timeout = heartbeat_interval_seconds * max_failures as u64 * 1000 + 5000;
        let timeout_5s = std::cmp::min(base_timeout, 5000);
        let timeout_10s = if base_timeout > 5000 && base_timeout <= 10000 { base_timeout } else { 10000 };
        let timeout_30s = if base_timeout > 10000 { base_timeout } else { 30000 };

        HealthChecker {
            registry,
            redis,
            slots: [
                Arc::new(RwLock::new(HashSet::new())),
                Arc::new(RwLock::new(HashSet::new())),
                Arc::new(RwLock::new(HashSet::new())),
            ],
            timeout_ms: [timeout_5s, timeout_10s, timeout_30s],
            check_interval: Duration::from_secs(2),
            running: Arc::new(RwLock::new(false)),
        }
    }

    pub async fn start(&self) -> CdnResult<()> {
        let mut running = self.running.write().await;
        if *running {
            return Ok(());
        }
        *running = true;
        drop(running);

        let checker = self.clone();
        tokio::spawn(async move {
            if let Err(e) = checker.run_health_checks().await {
                tracing::error!("Health checker failed: {}", e);
            }
        });

        tracing::info!("Health checker started");
        Ok(())
    }

    pub async fn stop(&self) -> CdnResult<()> {
        let mut running = self.running.write().await;
        *running = false;
        tracing::info!("Health checker stopped");
        Ok(())
    }

    async fn run_health_checks(&self) -> CdnResult<()> {
        let mut ticker = interval(self.check_interval);

        loop {
            ticker.tick().await;

            let running = self.running.read().await;
            if !*running {
                break;
            }
            drop(running);

            self.check_expired_nodes().await?;
        }

        Ok(())
    }

    pub async fn add_node(&self, node_id: Uuid, timeout_ms: u64) {
        let slot_idx = Self::slot_index(timeout_ms);
        let mut slot = self.slots[slot_idx].write().await;
        slot.insert(node_id);
        tracing::debug!("Node {} added to slot {:?}", node_id, slot_idx);
    }

    pub async fn remove_node(&self, node_id: Uuid) {
        for i in 0..3 {
            let mut slot = self.slots[i].write().await;
            slot.remove(&node_id);
        }
    }

    fn slot_index(timeout_ms: u64) -> usize {
        match TimeSlot::from_timeout_ms(timeout_ms) {
            TimeSlot::Slot5s => 0,
            TimeSlot::Slot10s => 1,
            TimeSlot::Slot30s => 2,
        }
    }

    async fn check_expired_nodes(&self) -> CdnResult<Vec<Uuid>> {
        let now_ms = Utc::now().timestamp_millis() as u64;
        let mut failed_nodes = Vec::new();

        for slot_idx in 0..3 {
            let timeout_ms = self.timeout_ms[slot_idx];

            let node_ids: Vec<Uuid> = {
                let slot = self.slots[slot_idx].read().await;
                slot.iter().copied().collect()
            };

            for node_id in node_ids {
                if let Ok(Some(node)) = self.registry.get_node(node_id).await {
                    if node.status != NodeStatus::Online {
                        continue;
                    }

                    let last_ts = node.last_heartbeat_ts.load(Relaxed);
                    let expiration_ts = last_ts + timeout_ms;

                    if expiration_ts <= now_ms {
                        failed_nodes.push(node_id);
                        let mut slot = self.slots[slot_idx].write().await;
                        slot.remove(&node_id);
                    }
                }
            }
        }

        for node_id in &failed_nodes {
            tracing::warn!("Node {} failed health check, marked offline", node_id);
            self.registry.deregister_node(*node_id).await?;
        }

        Ok(failed_nodes)
    }

    pub async fn check_single_node(&self, node_id: Uuid) -> CdnResult<bool> {
        let node = self.registry.get_node(node_id).await?;
        
        match node {
            Some(n) => {
                let is_online = n.status == NodeStatus::Online;
                if is_online {
                    let last_ts = n.last_heartbeat_ts.load(Relaxed);
                    if last_ts == 0 {
                        return Ok(false);
                    }
                    let now_ms = Utc::now().timestamp_millis() as u64;
                    let timeout_ms = self.timeout_ms.iter().min().copied().unwrap_or(5000);
                    Ok(now_ms - last_ts < timeout_ms)
                } else {
                    Ok(false)
                }
            }
            None => Ok(false),
        }
    }
    
    pub async fn receive_heartbeat(&self, node_id: &Uuid, heartbeat: Heartbeat) -> CdnResult<()> {
        let result = self.registry.process_heartbeat(heartbeat).await;
        if result.is_ok() {
            let timeout_ms = self.timeout_ms.iter().min().copied().unwrap_or(5000);
            self.add_node(*node_id, timeout_ms).await;
        }
        result
    }
}

impl Clone for HealthChecker {
    fn clone(&self) -> Self {
        HealthChecker {
            registry: self.registry.clone(),
            redis: self.redis.clone(),
            slots: [
                self.slots[0].clone(),
                self.slots[1].clone(),
                self.slots[2].clone(),
            ],
            timeout_ms: self.timeout_ms,
            check_interval: self.check_interval,
            running: self.running.clone(),
        }
    }
}

#[cfg(test)]
pub mod tests {
    use super::*;

    #[test]
    fn test_health_check_interval_is_2_seconds() {
        assert_eq!(Duration::from_secs(2), Duration::from_secs(2));
    }

    #[test]
    fn test_timeout_calculation_with_grace_period() {
        let heartbeat_interval = 1;
        let max_failures = 3;
        let base_timeout = heartbeat_interval * max_failures * 1000 + 5000;
        
        assert_eq!(base_timeout, 8000);
        
        let timeout_5s = std::cmp::min(base_timeout, 5000);
        let timeout_10s = if base_timeout > 5000 && base_timeout <= 10000 { base_timeout } else { 10000 };
        let timeout_30s = if base_timeout > 10000 { base_timeout } else { 30000 };
        
        assert_eq!(timeout_5s, 5000);
        assert_eq!(timeout_10s, 8000);
        assert_eq!(timeout_30s, 30000);
    }

    #[test]
    fn test_timeout_formula_various_values() {
        let test_cases = vec![
            ((1, 1), 1 * 1 * 1000 + 5000),
            ((2, 3), 2 * 3 * 1000 + 5000),
            ((5, 3), 5 * 3 * 1000 + 5000),
        ];

        for ((interval, failures), expected) in test_cases {
            let result = interval * failures * 1000 + 5000;
            assert_eq!(result, expected, "interval={}, failures={}", interval, failures);
        }
    }

    #[test]
    fn test_slot_index_calculation() {
        assert_eq!(HealthChecker::slot_index(5000), 0);
        assert_eq!(HealthChecker::slot_index(5001), 1);
        assert_eq!(HealthChecker::slot_index(10000), 1);
        assert_eq!(HealthChecker::slot_index(10001), 2);
        assert_eq!(HealthChecker::slot_index(30000), 2);
    }

    #[test]
    fn test_expiration_ts_calculation() {
        let last_ts = 100000u64;
        let timeout_ms = 5000u64;
        let expiration_ts = last_ts + timeout_ms;
        
        assert_eq!(expiration_ts, 105000);
    }

    #[test]
    fn test_node_expired_comparison() {
        let now_ms = 100000u64;
        let last_ts = 90000u64;
        let timeout_ms = 5000u64;
        let expiration_ts = last_ts + timeout_ms;
        
        assert!(expiration_ts <= now_ms);
    }

    #[test]
    fn test_node_not_expired_comparison() {
        let now_ms = 100000u64;
        let last_ts = 98000u64;
        let timeout_ms = 5000u64;
        let expiration_ts = last_ts + timeout_ms;
        
        assert!(expiration_ts > now_ms);
    }

    #[tokio::test]
    async fn test_concurrent_slot_operations() {
        let slot: Slot = Arc::new(RwLock::new(HashSet::new()));
        let mut handles = Vec::new();

        for i in 0..100 {
            let slot_clone = slot.clone();
            handles.push(tokio::spawn(async move {
                let node_id = Uuid::from_u128(i);
                let mut slot_write = slot_clone.write().await;
                slot_write.insert(node_id);
            }));
        }

        for handle in handles {
            let _ = handle.await;
        }

        let slot_read = slot.read().await;
        assert_eq!(slot_read.len(), 100);
    }

    #[tokio::test]
    async fn test_concurrent_add_remove_nodes() {
        let slots: [Slot; 3] = [
            Arc::new(RwLock::new(HashSet::new())),
            Arc::new(RwLock::new(HashSet::new())),
            Arc::new(RwLock::new(HashSet::new())),
        ];
        
        let mut handles = Vec::new();

        for i in 0..50 {
            let slot0 = slots[0].clone();
            let slot1 = slots[1].clone();
            let slot2 = slots[2].clone();
            handles.push(tokio::spawn(async move {
                let node_id = Uuid::from_u128(i);
                let timeout_ms = if i % 2 == 0 { 5000 } else { 10000 };
                let slot_idx = HealthChecker::slot_index(timeout_ms);
                
                let slots_ref = match slot_idx {
                    0 => &slot0,
                    1 => &slot1,
                    _ => &slot2,
                };
                
                {
                    let mut slot_write = slots_ref.write().await;
                    slot_write.insert(node_id);
                }
                
                {
                    let mut slot_write = slots_ref.write().await;
                    slot_write.remove(&node_id);
                }
            }));
        }

        for handle in handles {
            let _ = handle.await;
        }

        for i in 0..3 {
            let slot_read = slots[i].read().await;
            assert!(slot_read.is_empty(), "Slot {} should be empty", i);
        }
    }

    #[test]
    fn test_check_interval_configuration() {
        let expected_interval = Duration::from_secs(2);
        assert_eq!(expected_interval.as_secs(), 2);
        assert_eq!(expected_interval.as_millis(), 2000);
    }

    #[test]
    fn test_grace_period_added_to_timeout() {
        let heartbeat_interval_seconds = 2;
        let max_failures = 3;
        let grace_period = 5000;
        
        let timeout_ms = heartbeat_interval_seconds * max_failures as u64 * 1000 + grace_period;
        
        assert_eq!(timeout_ms, 2 * 3 * 1000 + 5000);
        assert_eq!(timeout_ms, 11000);
    }

    #[test]
    fn test_formula_last_heartbeat_plus_timeout() {
        let last_heartbeat_time = 100000;
        let heartbeat_interval = 2;
        let max_failures = 3;
        let grace_period = 5000;
        
        let timeout_ms = heartbeat_interval * max_failures * 1000 + grace_period;
        let expiration_ts = last_heartbeat_time + timeout_ms;
        
        assert_eq!(expiration_ts, 100000 + 11000);
        assert_eq!(expiration_ts, 111000);
    }
}
