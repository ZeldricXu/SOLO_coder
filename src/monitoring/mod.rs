use crate::error::PlatformError;
use crate::types::{Alert, AlertRule, AlertSeverity, StatsSnapshot};
use crate::audit_log::AuditLogChain;
use crate::utils::{current_timestamp, generate_uuid, hash_string};
use async_trait::async_trait;
use parking_lot::RwLock;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::{info, warn, error};

#[async_trait]
pub trait NotificationChannel: Send + Sync {
    async fn send(&self, alert: &Alert) -> Result<(), PlatformError>;
    fn name(&self) -> &str;
}

pub struct ConsoleChannel;

#[async_trait]
impl NotificationChannel for ConsoleChannel {
    async fn send(&self, alert: &Alert) -> Result<(), PlatformError> {
        println!("[ALERT] [{}] {}: {}", alert.severity as u32, alert.timestamp, alert.message);
        info!(alert_id = %alert.alert_id, severity = ?alert.severity, "Alert sent to console");
        Ok(())
    }
    
    fn name(&self) -> &str {
        "console"
    }
}

pub struct WebhookChannel {
    pub url: String,
}

#[async_trait]
impl NotificationChannel for WebhookChannel {
    async fn send(&self, alert: &Alert) -> Result<(), PlatformError> {
        info!(alert_id = %alert.alert_id, webhook_url = %self.url, "Sending alert to webhook");
        Ok(())
    }
    
    fn name(&self) -> &str {
        "webhook"
    }
}

pub struct EmailChannel {
    pub recipients: Vec<String>,
}

#[async_trait]
impl NotificationChannel for EmailChannel {
    async fn send(&self, alert: &Alert) -> Result<(), PlatformError> {
        for recipient in &self.recipients {
            info!(alert_id = %alert.alert_id, recipient = %recipient, "Sending alert email");
        }
        Ok(())
    }
    
    fn name(&self) -> &str {
        "email"
    }
}

struct MonitoringState {
    rules: HashMap<String, AlertRule>,
    active_alerts: HashMap<String, Alert>,
    snapshots: Vec<StatsSnapshot>,
    notification_channels: HashMap<String, Arc<dyn NotificationChannel>>,
    current_metrics: HashMap<String, f64>,
    sequence_counter: u64,
}

pub struct MonitoringService {
    state: Arc<RwLock<MonitoringState>>,
    audit_log: Arc<AuditLogChain>,
    alert_tx: Option<mpsc::UnboundedSender<Alert>>,
}

impl MonitoringService {
    pub fn new(audit_log: Arc<AuditLogChain>) -> Self {
        let mut notification_channels: HashMap<String, Arc<dyn NotificationChannel>> = HashMap::new();
        notification_channels.insert("console".to_string(), Arc::new(ConsoleChannel));
        
        MonitoringService {
            state: Arc::new(RwLock::new(MonitoringState {
                rules: HashMap::new(),
                active_alerts: HashMap::new(),
                snapshots: Vec::new(),
                notification_channels,
                current_metrics: HashMap::new(),
                sequence_counter: 0,
            })),
            audit_log,
            alert_tx: None,
        }
    }

    pub async fn start(&self) -> Result<(), PlatformError> {
        let (tx, mut rx) = mpsc::unbounded_channel::<Alert>();
        
        {
            let mut state = self.state.write();
            state.notification_channels.insert("console".to_string(), Arc::new(ConsoleChannel));
        }
        
        let state_clone = self.state.clone();
        let audit_log_clone = self.audit_log.clone();
        
        tokio::spawn(async move {
            while let Some(alert) = rx.recv().await {
                let state = state_clone.read();
                let rule = state.rules.get(&alert.rule_id);
                
                if let Some(rule) = rule {
                    for channel_name in &rule.notification_channels {
                        if let Some(channel) = state.notification_channels.get(channel_name) {
                            if let Err(e) = channel.send(&alert).await {
                                error!(error = %e, "Failed to send notification");
                            }
                        }
                    }
                }
                
                if let Err(e) = audit_log_clone.append(
                    "system",
                    "alert_created",
                    &alert.alert_id,
                    serde_json::json!({
                        "severity": format!("{:?}", alert.severity),
                        "message": alert.message,
                        "timestamp": alert.timestamp.to_rfc3339(),
                    })
                ).await {
                    error!(error = %e, "Failed to log alert to audit chain");
                }
            }
        });
        
        Ok(())
    }

    pub fn register_channel(&self, name: &str, channel: Arc<dyn NotificationChannel>) {
        let mut state = self.state.write();
        state.notification_channels.insert(name.to_string(), channel);
    }

    pub fn add_rule(&self, rule: AlertRule) -> Result<(), PlatformError> {
        if rule.name.is_empty() {
            return Err(PlatformError::Validation("Rule name cannot be empty".to_string()));
        }
        
        let mut state = self.state.write();
        state.rules.insert(rule.rule_id.clone(), rule);
        
        Ok(())
    }

    pub fn remove_rule(&self, rule_id: &str) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        if state.rules.remove(rule_id).is_none() {
            return Err(PlatformError::NotFound(format!("Rule {} not found", rule_id)));
        }
        Ok(())
    }

    pub fn update_metric(&self, metric_name: &str, value: f64) -> Result<(), PlatformError> {
        if metric_name.is_empty() {
            return Err(PlatformError::Validation("Metric name cannot be empty".to_string()));
        }
        
        let mut state = self.state.write();
        state.current_metrics.insert(metric_name.to_string(), value);
        
        let rules: Vec<AlertRule> = state.rules.values().cloned().collect();
        let active_alerts: HashMap<String, Alert> = state.active_alerts.clone();
        
        for rule in rules {
            if rule.name == metric_name && rule.enabled {
                let threshold_exceeded = rule.evaluate(value);
                let has_active_alert = active_alerts.values().any(|a| a.rule_id == rule.rule_id && !a.resolved);
                
                if threshold_exceeded && !has_active_alert {
                    let alert = Alert::new(&rule.rule_id, rule.severity, 
                        &format!("Metric {} exceeded threshold: {} > {}", metric_name, value, rule.threshold));
                    state.active_alerts.insert(alert.alert_id.clone(), alert.clone());
                    
                    warn!(
                        rule_id = %rule.rule_id,
                        metric = %metric_name,
                        value = value,
                        threshold = rule.threshold,
                        "Alert triggered"
                    );
                } else if !threshold_exceeded && has_active_alert {
                    let alerts_to_resolve: Vec<String> = active_alerts
                        .values()
                        .filter(|a| a.rule_id == rule.rule_id && !a.resolved)
                        .map(|a| a.alert_id.clone())
                        .collect();
                    
                    for alert_id in alerts_to_resolve {
                        if let Some(alert) = state.active_alerts.get_mut(&alert_id) {
                            alert.resolve();
                            info!(alert_id = %alert_id, "Alert resolved automatically");
                        }
                    }
                }
            }
        }
        
        Ok(())
    }

    pub fn evaluate_all_rules(&self) -> Vec<Alert> {
        let state = self.state.read();
        let mut triggered = Vec::new();
        
        for (metric_name, &value) in &state.current_metrics {
            for rule in state.rules.values() {
                if rule.name == *metric_name && rule.enabled && rule.evaluate(value) {
                    let alert = Alert::new(&rule.rule_id, rule.severity,
                        &format!("Metric {} exceeded threshold", metric_name));
                    triggered.push(alert);
                }
            }
        }
        
        triggered
    }

    pub fn record_snapshot(&self, snapshot: StatsSnapshot) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        state.snapshots.push(snapshot.clone());
        
        if state.snapshots.len() > 1000 {
            state.snapshots.remove(0);
        }
        
        state.sequence_counter += 1;
        
        info!(
            snapshot_id = %snapshot.snapshot_id,
            throughput = snapshot.metrics.throughput,
            latency_p99 = snapshot.metrics.latency_p99,
            error_rate = snapshot.metrics.error_rate,
            "Stats snapshot recorded"
        );
        
        Ok(())
    }

    pub fn get_latest_snapshot(&self) -> Option<StatsSnapshot> {
        let state = self.state.read();
        state.snapshots.last().cloned()
    }

    pub fn get_active_alerts(&self) -> Vec<Alert> {
        let state = self.state.read();
        state.active_alerts
            .values()
            .filter(|a| !a.resolved)
            .cloned()
            .collect()
    }

    pub fn resolve_alert(&self, alert_id: &str) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        if let Some(alert) = state.active_alerts.get_mut(alert_id) {
            alert.resolve();
            info!(alert_id = %alert_id, "Alert resolved manually");
            Ok(())
        } else {
            Err(PlatformError::NotFound(format!("Alert {} not found", alert_id)))
        }
    }

    pub fn get_rules(&self) -> Vec<AlertRule> {
        let state = self.state.read();
        state.rules.values().cloned().collect()
    }

    pub fn get_metrics(&self) -> HashMap<String, f64> {
        let state = self.state.read();
        state.current_metrics.clone()
    }

    pub fn increment_counter(&self, metric_name: &str, amount: f64) {
        let mut state = self.state.write();
        let current = state.current_metrics.get(metric_name).copied().unwrap_or(0.0);
        state.current_metrics.insert(metric_name.to_string(), current + amount);
    }

    pub fn record_latency(&self, operation: &str, duration_ms: u64) {
        let mut state = self.state.write();
        let latency_key = format!("latency_{}", operation);
        state.current_metrics.insert(latency_key, duration_ms as f64);
        state.sequence_counter += 1;
    }

    pub fn record_error(&self, operation: &str) {
        let mut state = self.state.write();
        let error_key = format!("errors_{}", operation);
        let current = state.current_metrics.get(&error_key).copied().unwrap_or(0.0);
        state.current_metrics.insert(error_key, current + 1.0);
        state.sequence_counter += 1;
    }

    pub fn get_sequence(&self) -> u64 {
        let state = self.state.read();
        state.sequence_counter
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{AlertCondition, AlertSeverity};

    #[test]
    fn test_rule_evaluation() {
        let rule = AlertRule::new(
            "test_metric",
            AlertCondition::GreaterThan,
            100.0,
            AlertSeverity::Warning,
        );
        
        assert!(rule.evaluate(150.0));
        assert!(!rule.evaluate(50.0));
    }

    #[test]
    fn test_add_and_remove_rule() {
        let audit_log = Arc::new(AuditLogChain::new());
        let service = MonitoringService::new(audit_log);
        
        let rule = AlertRule::new(
            "test_metric",
            AlertCondition::GreaterThan,
            100.0,
            AlertSeverity::Warning,
        );
        
        let rule_id = rule.rule_id.clone();
        assert!(service.add_rule(rule).is_ok());
        assert!(service.remove_rule(&rule_id).is_ok());
    }
}
