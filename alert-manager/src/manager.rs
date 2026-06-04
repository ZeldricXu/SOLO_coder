use anyhow::Result;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, info, warn};

use common::alert::{Alert, Incident, IncidentStatus, InhibitRule, LabelMatcher, Silence};

use crate::channels::NotificationChannel;
use crate::dedup::AlertDeduplicator;
use crate::root_cause::{RootCauseAnalyzer, RootCauseConfig};
use crate::storage::AlertStorage;

pub struct AlertManager {
    storage: Arc<AlertStorage>,
    deduplicator: AlertDeduplicator,
    channels: Arc<RwLock<Vec<Arc<NotificationChannel>>>>,
    inhibit_rules: Arc<RwLock<Vec<InhibitRule>>>,
    silences: Arc<RwLock<Vec<Silence>>>,
    incidents: Arc<RwLock<HashMap<String, Incident>>>,
    root_cause_analyzer: Arc<RootCauseAnalyzer>,
}

impl AlertManager {
    pub fn new(storage: Arc<AlertStorage>) -> Self {
        Self {
            storage,
            deduplicator: AlertDeduplicator::new(),
            channels: Arc::new(RwLock::new(Vec::new())),
            inhibit_rules: Arc::new(RwLock::new(Vec::new())),
            silences: Arc::new(RwLock::new(Vec::new())),
            incidents: Arc::new(RwLock::new(HashMap::new())),
            root_cause_analyzer: Arc::new(RootCauseAnalyzer::new(RootCauseConfig::default())),
        }
    }

    pub fn with_root_cause(mut self, config: RootCauseConfig) -> Self {
        self.root_cause_analyzer = Arc::new(RootCauseAnalyzer::new(config));
        self
    }

    pub async fn add_channel(&self, channel: NotificationChannel) {
        let mut channels = self.channels.write().await;
        channels.push(Arc::new(channel));
    }

    pub async fn add_inhibit_rule(&self, rule: InhibitRule) {
        let mut rules = self.inhibit_rules.write().await;
        rules.push(rule);
    }

    pub async fn add_silence(&self, silence: Silence) {
        let mut silences = self.silences.write().await;
        silences.push(silence);
    }

    pub async fn process_alert(&self, mut alert: Alert) -> Result<()> {
        info!("Processing alert: {} ({})", alert.name, alert.severity.as_str());

        if self.is_silenced(&alert).await {
            debug!("Alert is silenced, skipping");
            return Ok(());
        }

        if self.is_inhibited(&alert).await {
            debug!("Alert is inhibited, skipping");
            return Ok(());
        }

        if self.deduplicator.check_and_record(&alert).await {
            debug!("Alert is duplicate, skipping");
            return Ok(());
        }

        if self.root_cause_analyzer.is_enabled() {
            match self.root_cause_analyzer.analyze(&alert).await {
                Ok(Some(analysis)) => {
                    info!(
                        "Root cause analysis complete for {}, confidence={:.2}",
                        alert.name, analysis.confidence
                    );
                    alert = alert
                        .with_annotation("root_cause_summary".to_string(), analysis.related_logs_summary)
                        .with_annotation(
                            "possible_causes".to_string(),
                            analysis.possible_causes.join("; "),
                        )
                        .with_annotation(
                            "recommended_actions".to_string(),
                            analysis.recommended_actions.join("; "),
                        )
                        .with_annotation(
                            "root_cause_confidence".to_string(),
                            format!("{:.2}", analysis.confidence),
                        );
                }
                Ok(None) => {
                    debug!("No root cause analysis available");
                }
                Err(e) => {
                    warn!("Root cause analysis failed: {}", e);
                }
            }
        }

        self.storage.save_alert(&alert).await?;
        self.update_incident(alert.clone()).await?;
        self.send_notifications(alert).await?;

        Ok(())
    }

    async fn is_silenced(&self, alert: &Alert) -> bool {
        let silences = self.silences.read().await;
        let now = chrono::Utc::now();

        for silence in silences.iter() {
            if now >= silence.starts_at && now <= silence.ends_at {
                if self.match_labels(&alert.labels.to_btree(), &silence.matchers) {
                    return true;
                }
            }
        }
        false
    }

    async fn is_inhibited(&self, alert: &Alert) -> bool {
        let rules = self.inhibit_rules.read().await;
        let incidents = self.incidents.read().await;

        for rule in rules.iter() {
            if !rule.enabled {
                continue;
            }

            if self.match_labels(&alert.labels.to_btree(), &rule.target_match) {
                for (_, incident) in incidents.iter() {
                    if incident.status == IncidentStatus::Open {
                        for source_alert in &incident.alerts {
                            if self.match_labels(&source_alert.labels.to_btree(), &rule.source_match) {
                                if self.check_equal_labels(alert, source_alert, &rule.equal) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        false
    }

    fn match_labels(&self, labels: &std::collections::BTreeMap<String, String>, matchers: &[LabelMatcher]) -> bool {
        for matcher in matchers {
            let value = labels.get(&matcher.name);
            let matches = match (value, matcher.is_regex) {
                (Some(v), false) => v == &matcher.value,
                (Some(v), true) => {
                    if let Ok(re) = regex::Regex::new(&matcher.value) {
                        re.is_match(v)
                    } else {
                        false
                    }
                }
                (None, _) => false,
            };
            if !matches {
                return false;
            }
        }
        true
    }

    fn check_equal_labels(&self, a: &Alert, b: &Alert, equal: &[String]) -> bool {
        for label_name in equal {
            if a.labels.get(label_name) != b.labels.get(label_name) {
                return false;
            }
        }
        true
    }

    async fn update_incident(&self, alert: Alert) -> Result<()> {
        let mut incidents = self.incidents.write().await;

        let key = format!("{}-{}", alert.name, alert.severity.as_str());

        if let Some(incident) = incidents.get_mut(&key) {
            incident.add_alert(alert);
        } else {
            let title = format!("{} - {}", alert.name, alert.severity.as_str());
            let incident = Incident::new(title, alert.severity.clone(), alert);
            incidents.insert(key, incident);
        }

        Ok(())
    }

    async fn send_notifications(&self, alert: Alert) -> Result<()> {
        let channels = self.channels.read().await;

        for channel in channels.iter() {
            if channel.enabled {
                if let Err(e) = channel.send(&alert).await {
                    warn!("Failed to send to channel {}: {}", channel.name, e);
                }
            }
        }

        Ok(())
    }

    pub async fn get_incidents(&self) -> Vec<Incident> {
        let incidents = self.incidents.read().await;
        incidents.values().cloned().collect()
    }

    pub async fn resolve_incident(&self, incident_id: &str) -> Result<()> {
        let mut incidents = self.incidents.write().await;

        if let Some(incident) = incidents.get_mut(incident_id) {
            incident.status = IncidentStatus::Resolved;
        }

        Ok(())
    }

    pub async fn cleanup_expired_silences(&self) {
        let mut silences = self.silences.write().await;
        let now = chrono::Utc::now();
        silences.retain(|s| s.ends_at > now);
    }
}
