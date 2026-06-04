use std::sync::Arc;
use std::sync::atomic::Ordering::Relaxed;
use tokio::sync::RwLock;
use uuid::Uuid;
use chrono::{Utc, DateTime};
use std::collections::HashMap;

use common::models::{EdgeNode, NodeRegistration, NodeStatus, Heartbeat};
use common::error::{CdnResult, CdnError};
use common::db::Database;
use common::redis::RedisClient;
use common::utils::generate_id;

#[derive(Clone)]
pub struct NodeRegistry {
    db: Database,
    redis: RedisClient,
    nodes: Arc<RwLock<HashMap<Uuid, EdgeNode>>>,
    max_heartbeat_failures: u32,
}

impl NodeRegistry {
    pub fn new(db: Database, redis: RedisClient, max_heartbeat_failures: u32) -> Self {
        NodeRegistry {
            db,
            redis,
            nodes: Arc::new(RwLock::new(HashMap::new())),
            max_heartbeat_failures,
        }
    }

    pub async fn register_node(&self, registration: NodeRegistration) -> CdnResult<EdgeNode> {
        let node_id = generate_id();
        let now = Utc::now();

        use std::sync::atomic::AtomicU64;
        let node = EdgeNode {
            id: node_id,
            ip_address: registration.ip,
            region: registration.region.clone(),
            datacenter: registration.datacenter.clone(),
            bandwidth_capacity: registration.bandwidth_capacity,
            bandwidth_usage: 0.0,
            storage_capacity: registration.storage_capacity,
            current_load: 0.0,
            status: NodeStatus::Online,
            weight: 100,
            latitude: registration.latitude.unwrap_or(0.0),
            longitude: registration.longitude.unwrap_or(0.0),
            registered_at: now,
            last_heartbeat_ts: AtomicU64::new(0),
            role: registration.role.clone(),
            parent_node_id: registration.parent_node_id,
        };
        self.db.create_edge_node(&node).await?;
        self.redis.store_node_status(&node, 3600).await?;

        let mut nodes = self.nodes.write().await;
        nodes.insert(node_id, node.clone());

        tracing::info!("Node registered: {} ({})", node.id, node.ip_address);
        Ok(node)
    }

    pub async fn deregister_node(&self, node_id: Uuid) -> CdnResult<()> {
        let mut nodes = self.nodes.write().await;
        
        if nodes.remove(&node_id).is_some() {
            self.db.delete_edge_node(node_id).await?;
            self.redis.delete_key(&format!("node:status:{}", node_id)).await?;
            tracing::info!("Node unregistered: {}", node_id);
        }

        Ok(())
    }

    pub async fn process_heartbeat(&self, heartbeat: Heartbeat) -> CdnResult<()> {
        let nodes = self.nodes.read().await;
        
        if let Some(node) = nodes.get(&heartbeat.node_id) {
            let ts = heartbeat.timestamp.timestamp_millis() as u64;
            node.last_heartbeat_ts.store(ts, Relaxed);
            self.db.update_node_heartbeat(heartbeat.node_id, &heartbeat).await?;
            self.redis.store_heartbeat(&heartbeat, 30).await?;
        } else {
            return Err(CdnError::NodeNotFound(heartbeat.node_id.to_string()));
        }

        Ok(())
    }

    pub async fn get_node(&self, node_id: Uuid) -> CdnResult<Option<EdgeNode>> {
        let nodes = self.nodes.read().await;
        Ok(nodes.get(&node_id).cloned())
    }

    pub async fn list_nodes(&self) -> CdnResult<Vec<EdgeNode>> {
        let nodes = self.nodes.read().await;
        Ok(nodes.values().cloned().collect())
    }

    pub async fn get_online_nodes(&self) -> CdnResult<Vec<EdgeNode>> {
        let nodes = self.nodes.read().await;
        Ok(nodes
            .values()
            .filter(|n| n.status == NodeStatus::Online)
            .cloned()
            .collect())
    }

    pub async fn get_nodes_by_region(&self, region: &str) -> CdnResult<Vec<EdgeNode>> {
        let nodes = self.nodes.read().await;
        Ok(nodes
            .values()
            .filter(|n| n.status == NodeStatus::Online && n.region == region)
            .cloned()
            .collect())
    }

    pub async fn load_from_database(&self) -> CdnResult<()> {
        let db_nodes = self.db.get_all_edge_nodes().await?;
        let mut nodes = self.nodes.write().await;

        for node in db_nodes {
            nodes.insert(node.id, node);
        }

        tracing::info!("Loaded {} nodes from database", nodes.len());
        Ok(())
    }

    pub async fn update_node_weight(&self, node_id: Uuid, weight: u32) -> CdnResult<()> {
        let mut nodes = self.nodes.write().await;
        
        if let Some(node) = nodes.get_mut(&node_id) {
            node.weight = weight;
        }

        Ok(())
    }

    pub async fn set_node_maintenance(&self, node_id: Uuid, maintenance: bool) -> CdnResult<()> {
        let mut nodes = self.nodes.write().await;
        
        if let Some(node) = nodes.get_mut(&node_id) {
            let status = if maintenance {
                NodeStatus::Maintenance
            } else {
                NodeStatus::Online
            };
            node.status = status.clone();
            self.db.update_edge_node_status(node_id, &status).await?;
        }

        Ok(())
    }

    pub async fn set_node_status(&self, node_id: Uuid, status: NodeStatus) -> CdnResult<()> {
        let mut nodes = self.nodes.write().await;
        
        if let Some(node) = nodes.get_mut(&node_id) {
            node.status = status.clone();
            self.db.update_edge_node_status(node_id, &status).await?;
        }

        Ok(())
    }
}
