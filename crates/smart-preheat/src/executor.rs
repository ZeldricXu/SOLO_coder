use std::sync::Arc;
use tokio::sync::RwLock;

use common::db::Database;
use common::redis::RedisClient;
use common::models::{PreheatPlanStatus, NodeStatus};
use cache_engine::CacheEngine;
use node_manager::registry::NodeRegistry;

use crate::planner::{PreheatPlanner, PreheatPlan};
use crate::bandwidth::BandwidthThrottler;

const DEFAULT_CACHE_NAME: &str = "default";
const ESTIMATED_CONTENT_SIZE: u64 = 1024 * 1024;

pub struct PreheatExecutor {
    planner: Arc<PreheatPlanner>,
    bandwidth_throttler: Arc<BandwidthThrottler>,
    cache_engine: CacheEngine,
    node_registry: NodeRegistry,
    db: Database,
    redis: RedisClient,
    running_plans: Arc<RwLock<Vec<PreheatPlan>>>,
}

impl PreheatExecutor {
    pub fn new(
        planner: PreheatPlanner,
        bandwidth_throttler: BandwidthThrottler,
        cache_engine: CacheEngine,
        node_registry: NodeRegistry,
        db: Database,
        redis: RedisClient,
    ) -> Self {
        PreheatExecutor {
            planner: Arc::new(planner),
            bandwidth_throttler: Arc::new(bandwidth_throttler),
            cache_engine,
            node_registry,
            db,
            redis,
            running_plans: Arc::new(RwLock::new(Vec::new())),
        }
    }

    pub async fn execute_plan(&self, plan: PreheatPlan) -> Result<PreheatPlan, String> {
        let estimated_bandwidth = plan.bandwidth_limit_bps;

        if !self.bandwidth_throttler.acquire(estimated_bandwidth) {
            return Err(format!(
                "bandwidth limit exceeded: requested {} bps, available {} bps",
                estimated_bandwidth,
                self.bandwidth_throttler.available_bandwidth()
            ));
        }

        let mut running = self.running_plans.write().await;
        let mut executing_plan = plan.clone();
        executing_plan.status = PreheatPlanStatus::Executing;
        running.push(executing_plan.clone());

        let throttler = self.bandwidth_throttler.clone();
        let running_plans = self.running_plans.clone();
        let db = self.db.clone();
        let redis = self.redis.clone();

        let plan_id = plan.id;
        let plan_urls = plan.content_urls.clone();
        let plan_regions = plan.target_regions.clone();

        let cache_engine = self.cache_engine.clone();
        let node_registry = self.node_registry.clone();

        tokio::spawn(async move {
            let result = push_content_to_nodes(
                &plan_urls,
                &plan_regions,
                &cache_engine,
                &node_registry,
                &db,
                &redis,
            ).await;

            throttler.release(estimated_bandwidth);

            let mut running = running_plans.write().await;
            if let Some(stored) = running.iter_mut().find(|p| p.id == plan_id) {
                stored.status = if result.is_ok() {
                    PreheatPlanStatus::Completed
                } else {
                    PreheatPlanStatus::Failed
                };
            }
        });

        Ok(executing_plan)
    }

    pub async fn generate_and_execute(&self) -> Result<PreheatPlan, String> {
        let plan = self.planner.generate_plan().await;
        if plan.content_urls.is_empty() {
            return Ok(plan);
        }
        self.execute_plan(plan).await
    }

    pub async fn get_running_plans(&self) -> Vec<PreheatPlan> {
        let running = self.running_plans.read().await;
        running.clone()
    }
}

async fn push_content_to_nodes(
    urls: &[String],
    regions: &[String],
    cache_engine: &CacheEngine,
    node_registry: &NodeRegistry,
    _db: &Database,
    redis: &RedisClient,
) -> Result<(), String> {
    let content_size = if urls.is_empty() {
        ESTIMATED_CONTENT_SIZE
    } else {
        ESTIMATED_CONTENT_SIZE * urls.len() as u64
    };

    let can_push = match cache_engine.ensure_space_for_push(DEFAULT_CACHE_NAME, content_size).await {
        Ok(can) => can,
        Err(e) => {
            tracing::warn!("failed to check cache space: {}", e);
            false
        }
    };

    if !can_push {
        for region in regions {
            if let Ok(nodes) = node_registry.get_nodes_by_region(region).await {
                for node in nodes {
                    if let Err(e) = node_registry.set_node_status(node.id, NodeStatus::StorageFull).await {
                        tracing::warn!("failed to set node {} status to StorageFull: {}", node.id, e);
                    } else {
                        tracing::warn!("node {} marked as StorageFull, skipping preheat", node.id);
                    }
                }
            }
        }
        return Err("insufficient storage space, nodes marked as StorageFull".to_string());
    }

    for url in urls {
        for region in regions {
            let key = format!("preheat:{}", url);
            let data = serde_json::json!({
                "url": url,
                "region": region,
                "status": "pending",
            });

            if let Err(e) = redis.set_config_value(&key, &data, None).await {
                tracing::warn!("failed to publish preheat task for {} in {}: {}", url, region, e);
            }
        }
    }

    Ok(())
}

impl Clone for PreheatExecutor {
    fn clone(&self) -> Self {
        PreheatExecutor {
            planner: self.planner.clone(),
            bandwidth_throttler: self.bandwidth_throttler.clone(),
            cache_engine: self.cache_engine.clone(),
            node_registry: self.node_registry.clone(),
            db: self.db.clone(),
            redis: self.redis.clone(),
            running_plans: self.running_plans.clone(),
        }
    }
}
