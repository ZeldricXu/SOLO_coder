use std::sync::Arc;
use async_trait::async_trait;
use serde_json::Value;
use tokio::sync::Mutex;
use std::collections::HashMap;

use crate::common::error::AppResult;
use crate::ports::mod::{NotificationPort, CloudSyncPort};

pub struct InMemoryNotification {
    alerts: Arc<Mutex<Vec<Value>>>,
    commands: Arc<Mutex<HashMap<String, Vec<Value>>>>,
}

impl InMemoryNotification {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            alerts: Arc::new(Mutex::new(Vec::new())),
            commands: Arc::new(Mutex::new(HashMap::new())),
        })
    }

    pub async fn get_alerts(&self) -> Vec<Value> {
        self.alerts.lock().await.clone()
    }

    pub async fn get_commands(&self, device_id: &str) -> Vec<Value> {
        self.commands.lock().await.get(device_id).cloned().unwrap_or_default()
    }
}

#[async_trait]
impl NotificationPort for InMemoryNotification {
    async fn send_alert(&self, level: &str, title: &str, message: &str) -> AppResult<()> {
        let alert = serde_json::json!({
            "level": level,
            "title": title,
            "message": message,
            "timestamp": chrono::Utc::now().to_rfc3339(),
        });
        self.alerts.lock().await.push(alert);
        Ok(())
    }

    async fn send_device_command(&self, device_id: &str, command: Value) -> AppResult<()> {
        let mut commands = self.commands.lock().await;
        commands.entry(device_id.to_string()).or_default().push(command);
        Ok(())
    }
}

pub struct InMemoryCloudSync {
    online: std::sync::atomic::AtomicBool,
    uploaded_data: Arc<Mutex<Vec<Value>>>,
}

impl InMemoryCloudSync {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            online: std::sync::atomic::AtomicBool::new(true),
            uploaded_data: Arc::new(Mutex::new(Vec::new())),
        })
    }

    pub fn set_online(&self, online: bool) {
        self.online.store(online, std::sync::atomic::Ordering::SeqCst);
    }

    pub async fn get_uploaded_data(&self) -> Vec<Value> {
        self.uploaded_data.lock().await.clone()
    }
}

#[async_trait]
impl CloudSyncPort for InMemoryCloudSync {
    async fn upload_data(&self, data: Value) -> AppResult<()> {
        if !self.is_online() {
            return Err(crate::common::error::AppError::ServiceUnavailable("云端连接不可用".into()));
        }
        self.uploaded_data.lock().await.push(data);
        Ok(())
    }

    async fn download_config(&self) -> AppResult<Value> {
        if !self.is_online() {
            return Err(crate::common::error::AppError::ServiceUnavailable("云端连接不可用".into()));
        }
        Ok(serde_json::json!({
            "version": "1.0.0",
            "modules": {
                "device_shadow": { "enabled": true },
                "rule_engine": { "enabled": true },
                "inference_scheduler": { "enabled": true },
                "device_lifecycle": { "enabled": true },
                "data_aggregation": { "enabled": true },
                "offline_cache": { "enabled": true },
                "ota_upgrade": { "enabled": true },
                "protocol_adapter": { "enabled": true },
            }
        }))
    }

    fn is_online(&self) -> bool {
        self.online.load(std::sync::atomic::Ordering::SeqCst)
    }

    async fn check_connectivity(&self) -> AppResult<()> {
        if self.is_online() {
            Ok(())
        } else {
            Err(crate::common::error::AppError::ServiceUnavailable("网络连接不可用".into()))
        }
    }
}
