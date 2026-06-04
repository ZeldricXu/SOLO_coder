use std::sync::Arc;
use tokio::sync::RwLock;
use std::collections::HashMap;
use serde_json::Value;

use common::error::{CdnResult};
use common::models::{ConfigDeployment, DeploymentStatus};
use common::db::Database;
use common::redis::RedisClient;
use common::utils::generate_id;

use crate::diff::IncrementalPusher;

pub struct ConfigDeployer {
    db: Database,
    redis: RedisClient,
    active_deployments: Arc<RwLock<HashMap<uuid::Uuid, ConfigDeployment>>>,
    incremental_pusher: IncrementalPusher,
}

impl ConfigDeployer {
    pub fn new(db: Database, redis: RedisClient) -> Self {
        let incremental_pusher = IncrementalPusher::new(db.clone(), redis.clone());
        ConfigDeployer {
            db,
            redis,
            active_deployments: Arc::new(RwLock::new(HashMap::new())),
            incremental_pusher,
        }
    }

    pub async fn start_deployment(
        &self,
        config_version_id: uuid::Uuid,
        target_nodes: Vec<uuid::Uuid>,
        percentage: u32,
    ) -> CdnResult<ConfigDeployment> {
        let deployment = ConfigDeployment {
            id: generate_id(),
            config_version_id,
            target_nodes: target_nodes.clone(),
            canary_percent: percentage,
            percentage,
            status: DeploymentStatus::InProgress,
            success_count: 0,
            failure_count: 0,
            started_at: Some(chrono::Utc::now()),
            completed_at: None,
            error_message: None,
            created_at: chrono::Utc::now(),
        };

        self.db.create_config_deployment(&deployment).await?;

        let config_data = self.get_config_version_data(config_version_id).await?;
        let config_type = self.get_config_type(config_version_id).await?;

        let push_result = self.incremental_pusher.push_incremental(
            &config_type,
            &config_data,
            &target_nodes,
        ).await;

        if push_result.is_err() {
            self.incremental_pusher.push_full(
                &config_type,
                &config_data,
                &target_nodes,
            ).await?;
        }

        let mut deployments = self.active_deployments.write().await;
        deployments.insert(deployment.id, deployment.clone());

        Ok(deployment)
    }

    async fn get_config_version_data(&self, config_version_id: uuid::Uuid) -> CdnResult<Value> {
        let row: (Value,) = sqlx::query_as(
            r#"SELECT data FROM config_versions WHERE id = $1"#,
        )
        .bind(config_version_id)
        .fetch_one(self.db.pool())
        .await?;

        Ok(row.0)
    }

    async fn get_config_type(&self, config_version_id: uuid::Uuid) -> CdnResult<String> {
        let row: (String,) = sqlx::query_as(
            r#"SELECT config_type FROM config_versions WHERE id = $1"#,
        )
        .bind(config_version_id)
        .fetch_one(self.db.pool())
        .await?;

        Ok(row.0)
    }

    pub async fn mark_success(&self, deployment_id: uuid::Uuid) -> CdnResult<()> {
        let mut deployments = self.active_deployments.write().await;
        if let Some(deployment) = deployments.get_mut(&deployment_id) {
            deployment.success_count += 1;
        }
        Ok(())
    }

    pub async fn mark_failure(&self, deployment_id: uuid::Uuid) -> CdnResult<()> {
        let mut deployments = self.active_deployments.write().await;
        if let Some(deployment) = deployments.get_mut(&deployment_id) {
            deployment.failure_count += 1;
        }
        Ok(())
    }

    pub async fn complete_deployment(&self, deployment_id: uuid::Uuid) -> CdnResult<()> {
        let mut deployments = self.active_deployments.write().await;
        if let Some(deployment) = deployments.get_mut(&deployment_id) {
            deployment.status = DeploymentStatus::Completed;
        }
        Ok(())
    }

    pub async fn rollback_deployment(&self, deployment_id: uuid::Uuid) -> CdnResult<()> {
        let mut deployments = self.active_deployments.write().await;
        if let Some(deployment) = deployments.get_mut(&deployment_id) {
            deployment.status = DeploymentStatus::RolledBack;
        }
        Ok(())
    }

    pub async fn get_deployment(&self, deployment_id: uuid::Uuid) -> Option<ConfigDeployment> {
        let deployments = self.active_deployments.read().await;
        deployments.get(&deployment_id).cloned()
    }

    pub async fn get_active_deployments(&self) -> Vec<ConfigDeployment> {
        let deployments = self.active_deployments.read().await;
        deployments.values()
            .filter(|d| d.status == DeploymentStatus::InProgress)
            .cloned()
            .collect()
    }

    pub async fn select_canary_nodes(
        &self,
        all_nodes: &[uuid::Uuid],
        percentage: u32,
    ) -> Vec<uuid::Uuid> {
        let count = (all_nodes.len() as f64 * percentage as f64 / 100.0).ceil() as usize;
        let count = count.max(1).min(all_nodes.len());
        
        all_nodes.iter().take(count).cloned().collect()
    }
}

impl Clone for ConfigDeployer {
    fn clone(&self) -> Self {
        ConfigDeployer {
            db: self.db.clone(),
            redis: self.redis.clone(),
            active_deployments: self.active_deployments.clone(),
            incremental_pusher: self.incremental_pusher.clone(),
        }
    }
}
