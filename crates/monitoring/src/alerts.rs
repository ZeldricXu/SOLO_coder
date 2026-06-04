use std::sync::Arc;
use std::collections::{HashMap, VecDeque};
use tokio::sync::RwLock;

use common::error::{CdnResult};
use common::models::{Alert, AlertType, AlertSeverity, NodeStatus};
use common::db::Database;
use common::utils::generate_id;

pub struct AlertManager {
    db: Database,
    active_alerts: Arc<RwLock<HashMap<uuid::Uuid, Alert>>>,
    recent_alerts: Arc<RwLock<VecDeque<Alert>>>,
    max_recent_alerts: usize,
}

impl AlertManager {
    pub fn new(db: Database) -> Self {
        AlertManager {
            db,
            active_alerts: Arc::new(RwLock::new(HashMap::new())),
            recent_alerts: Arc::new(RwLock::new(VecDeque::new())),
            max_recent_alerts: 1000,
        }
    }

    pub async fn create_alert(
        &self,
        alert_type: AlertType,
        severity: AlertSeverity,
        message: String,
        node_id: Option<uuid::Uuid>,
        metadata: HashMap<String, String>,
    ) -> CdnResult<Alert> {
        let alert = Alert {
            id: generate_id(),
            node_id,
            alert_type,
            severity: severity.clone(),
            message,
            acknowledged: false,
            resolved: false,
            metadata,
            created_at: chrono::Utc::now(),
            resolved_at: None,
        };

        self.db.create_alert(&alert).await?;

        let mut active = self.active_alerts.write().await;
        active.insert(alert.id, alert.clone());

        let mut recent = self.recent_alerts.write().await;
        recent.push_front(alert.clone());
        if recent.len() > self.max_recent_alerts {
            recent.pop_back();
        }

        match severity {
            AlertSeverity::Critical => {
                tracing::error!("Alert created: {:?}", alert);
            }
            AlertSeverity::Warning => {
                tracing::warn!("Alert created: {:?}", alert);
            }
            AlertSeverity::Info => {
                tracing::info!("Alert created: {:?}", alert);
            }
        }

        Ok(alert)
    }

    pub async fn resolve_alert(&self, alert_id: uuid::Uuid) -> CdnResult<()> {
        let mut active = self.active_alerts.write().await;
        if let Some(alert) = active.get_mut(&alert_id) {
            alert.resolved_at = Some(chrono::Utc::now());
        }
        Ok(())
    }

    pub async fn get_active_alerts(&self) -> Vec<Alert> {
        let active = self.active_alerts.read().await;
        active.values().filter(|a| a.resolved_at.is_none()).cloned().collect()
    }

    pub async fn get_recent_alerts(&self, limit: usize) -> Vec<Alert> {
        let recent = self.recent_alerts.read().await;
        recent.iter().take(limit).cloned().collect()
    }
}

impl Clone for AlertManager {
    fn clone(&self) -> Self {
        AlertManager {
            db: self.db.clone(),
            active_alerts: self.active_alerts.clone(),
            recent_alerts: self.recent_alerts.clone(),
            max_recent_alerts: self.max_recent_alerts,
        }
    }
}
