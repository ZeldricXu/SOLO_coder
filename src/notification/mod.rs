use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;
use std::sync::{Arc, Mutex};
use chrono::{DateTime, Utc, Duration};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, PartialOrd, Copy)]
pub enum NotificationPriority {
    Critical = 1,
    High = 2,
    Medium = 3,
    Low = 4,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum NotificationChannel {
    Email,
    Slack,
    Webhook,
    PagerDuty,
    SMS,
    Push,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum NotificationStatus {
    Pending,
    Sent,
    Failed,
    Suppressed,
    Inhibited,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Notification {
    pub id: String,
    pub title: String,
    pub message: String,
    pub priority: NotificationPriority,
    pub channels: Vec<NotificationChannel>,
    pub recipients: Vec<String>,
    pub status: NotificationStatus,
    pub created_at: DateTime<Utc>,
    pub sent_at: Option<DateTime<Utc>>,
    pub failed_at: Option<DateTime<Utc>>,
    pub failure_reason: Option<String>,
    pub retry_count: u32,
    pub labels: HashMap<String, String>,
    pub deduplication_key: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SuppressionRule {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub match_labels: HashMap<String, String>,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub reason: String,
    pub created_by: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InhibitionRule {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub source_labels: HashMap<String, String>,
    pub target_labels: HashMap<String, String>,
    pub equal: Vec<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChannelConfig {
    pub channel: NotificationChannel,
    pub enabled: bool,
    pub min_priority: NotificationPriority,
    pub rate_limit_per_minute: u32,
    pub config: HashMap<String, String>,
}

#[derive(Debug, Clone)]
struct RateLimiter {
    timestamps: Vec<DateTime<Utc>>,
    limit: u32,
}

impl RateLimiter {
    fn new(limit: u32) -> Self {
        Self {
            timestamps: Vec::new(),
            limit,
        }
    }

    fn check_and_update(&mut self) -> bool {
        let now = Utc::now();
        let one_minute_ago = now - Duration::minutes(1);
        
        self.timestamps.retain(|&t| t > one_minute_ago);
        
        if self.timestamps.len() < self.limit as usize {
            self.timestamps.push(now);
            true
        } else {
            false
        }
    }
}

#[derive(Debug, Clone)]
pub struct NotificationManager {
    notifications: Arc<Mutex<Vec<Notification>>>,
    suppression_rules: Arc<Mutex<HashMap<String, SuppressionRule>>>,
    inhibition_rules: Arc<Mutex<HashMap<String, InhibitionRule>>>,
    channel_configs: Arc<Mutex<HashMap<NotificationChannel, ChannelConfig>>>,
    recent_notifications: Arc<Mutex<HashMap<String, DateTime<Utc>>>>,
    rate_limiters: Arc<Mutex<HashMap<NotificationChannel, RateLimiter>>>,
}

impl NotificationManager {
    pub fn new() -> Self {
        let mut rate_limiters = HashMap::new();
        rate_limiters.insert(NotificationChannel::Email, RateLimiter::new(100));
        rate_limiters.insert(NotificationChannel::Slack, RateLimiter::new(100));
        rate_limiters.insert(NotificationChannel::Webhook, RateLimiter::new(1000));
        rate_limiters.insert(NotificationChannel::PagerDuty, RateLimiter::new(10));
        rate_limiters.insert(NotificationChannel::SMS, RateLimiter::new(50));
        rate_limiters.insert(NotificationChannel::Push, RateLimiter::new(500));

        Self {
            notifications: Arc::new(Mutex::new(Vec::new())),
            suppression_rules: Arc::new(Mutex::new(HashMap::new())),
            inhibition_rules: Arc::new(Mutex::new(HashMap::new())),
            channel_configs: Arc::new(Mutex::new(HashMap::new())),
            recent_notifications: Arc::new(Mutex::new(HashMap::new())),
            rate_limiters: Arc::new(Mutex::new(rate_limiters)),
        }
    }

    pub fn configure_channel(&self, config: ChannelConfig) {
        let mut configs = self.channel_configs.lock().unwrap();
        configs.insert(config.channel.clone(), config);
    }

    pub fn create_notification(
        &self,
        title: &str,
        message: &str,
        priority: NotificationPriority,
        channels: Vec<NotificationChannel>,
        recipients: Vec<String>,
        labels: HashMap<String, String>,
    ) -> Notification {
        let id = Uuid::new_v4().to_string();
        let deduplication_key = self.generate_deduplication_key(title, &labels);

        let notification = Notification {
            id,
            title: title.to_string(),
            message: message.to_string(),
            priority,
            channels,
            recipients,
            status: NotificationStatus::Pending,
            created_at: Utc::now(),
            sent_at: None,
            failed_at: None,
            failure_reason: None,
            retry_count: 0,
            labels,
            deduplication_key,
        };

        let mut notifications = self.notifications.lock().unwrap();
        notifications.push(notification.clone());
        notification
    }

    fn generate_deduplication_key(&self, title: &str, labels: &HashMap<String, String>) -> String {
        let mut label_parts: Vec<String> = labels.iter()
            .map(|(k, v)| format!("{}:{}", k, v))
            .collect();
        label_parts.sort();
        format!("{}|{}", title, label_parts.join(","))
    }

    pub fn send_notification(&self, notification_id: &str) -> Result<Notification, String> {
        let notification_clone = {
            let notifications = self.notifications.lock().unwrap();
            let notification = notifications.iter()
                .find(|n| n.id == notification_id)
                .ok_or_else(|| "Notification not found".to_string())?;
            
            if notification.status != NotificationStatus::Pending {
                return Err("Notification already processed".to_string());
            }
            notification.clone()
        };

        if self.check_suppression(&notification_clone) {
            let mut notifications = self.notifications.lock().unwrap();
            let notification = notifications.iter_mut()
                .find(|n| n.id == notification_id)
                .ok_or_else(|| "Notification not found".to_string())?;
            notification.status = NotificationStatus::Suppressed;
            return Ok(notification.clone());
        }

        if self.check_inhibition(&notification_clone) {
            let mut notifications = self.notifications.lock().unwrap();
            let notification = notifications.iter_mut()
                .find(|n| n.id == notification_id)
                .ok_or_else(|| "Notification not found".to_string())?;
            notification.status = NotificationStatus::Inhibited;
            return Ok(notification.clone());
        }

        if self.check_duplicate(&notification_clone) {
            let mut notifications = self.notifications.lock().unwrap();
            let notification = notifications.iter_mut()
                .find(|n| n.id == notification_id)
                .ok_or_else(|| "Notification not found".to_string())?;
            notification.status = NotificationStatus::Suppressed;
            return Ok(notification.clone());
        }

        let mut all_sent = true;
        for channel in &notification_clone.channels {
            if !self.check_rate_limit(channel) {
                all_sent = false;
                continue;
            }

            if !self.check_channel_priority(channel, notification_clone.priority) {
                continue;
            }

            all_sent = all_sent && self.send_via_channel(&notification_clone, channel);
        }

        let mut notifications = self.notifications.lock().unwrap();
        let notification = notifications.iter_mut()
            .find(|n| n.id == notification_id)
            .ok_or_else(|| "Notification not found".to_string())?;

        if all_sent {
            notification.status = NotificationStatus::Sent;
            notification.sent_at = Some(Utc::now());
        } else {
            notification.status = NotificationStatus::Failed;
            notification.failed_at = Some(Utc::now());
            notification.failure_reason = Some("Some channels failed to send".to_string());
        }

        Ok(notification.clone())
    }

    fn check_suppression(&self, notification: &Notification) -> bool {
        let rules = self.suppression_rules.lock().unwrap();
        let now = Utc::now();

        for rule in rules.values() {
            if !rule.enabled {
                continue;
            }

            if let Some(start) = rule.start_time {
                if now < start {
                    continue;
                }
            }

            if let Some(end) = rule.end_time {
                if now > end {
                    continue;
                }
            }

            let mut matches = true;
            for (key, value) in &rule.match_labels {
                if notification.labels.get(key) != Some(value) {
                    matches = false;
                    break;
                }
            }

            if matches {
                return true;
            }
        }

        false
    }

    fn check_inhibition(&self, notification: &Notification) -> bool {
        let rules = self.inhibition_rules.lock().unwrap();
        let notifications = self.notifications.lock().unwrap();
        let five_minutes_ago = Utc::now() - Duration::minutes(5);

        for rule in rules.values() {
            if !rule.enabled {
                continue;
            }

            let mut source_matches = false;
            for n in notifications.iter() {
                if n.status != NotificationStatus::Sent && n.status != NotificationStatus::Pending {
                    continue;
                }
                if n.created_at < five_minutes_ago {
                    continue;
                }

                let mut matches = true;
                for (key, value) in &rule.source_labels {
                    if n.labels.get(key) != Some(value) {
                        matches = false;
                        break;
                    }
                }
                if matches {
                    source_matches = true;
                    break;
                }
            }

            if !source_matches {
                continue;
            }

            let mut target_matches = true;
            for (key, value) in &rule.target_labels {
                if notification.labels.get(key) != Some(value) {
                    target_matches = false;
                    break;
                }
            }

            if target_matches {
                return true;
            }
        }

        false
    }

    fn check_duplicate(&self, notification: &Notification) -> bool {
        let mut recent = self.recent_notifications.lock().unwrap();
        let five_minutes_ago = Utc::now() - Duration::minutes(5);

        if let Some(&last_sent) = recent.get(&notification.deduplication_key) {
            if last_sent > five_minutes_ago {
                return true;
            }
        }

        recent.insert(notification.deduplication_key.clone(), Utc::now());
        false
    }

    fn check_rate_limit(&self, channel: &NotificationChannel) -> bool {
        let mut rate_limiters = self.rate_limiters.lock().unwrap();
        let limiter = rate_limiters.entry(channel.clone())
            .or_insert_with(|| RateLimiter::new(100));
        limiter.check_and_update()
    }

    fn check_channel_priority(&self, channel: &NotificationChannel, priority: NotificationPriority) -> bool {
        let configs = self.channel_configs.lock().unwrap();
        match configs.get(channel) {
            Some(config) => config.enabled && priority as i32 <= config.min_priority as i32,
            None => true,
        }
    }

    fn send_via_channel(&self, _notification: &Notification, _channel: &NotificationChannel) -> bool {
        true
    }

    pub fn create_suppression_rule(
        &self,
        name: &str,
        match_labels: HashMap<String, String>,
        duration_minutes: Option<i64>,
        reason: &str,
        created_by: &str,
    ) -> SuppressionRule {
        let id = Uuid::new_v4().to_string();
        let now = Utc::now();

        let (start_time, end_time) = match duration_minutes {
            Some(d) => (Some(now), Some(now + Duration::minutes(d))),
            None => (None, None),
        };

        let rule = SuppressionRule {
            id: id.clone(),
            name: name.to_string(),
            enabled: true,
            match_labels,
            start_time,
            end_time,
            reason: reason.to_string(),
            created_by: created_by.to_string(),
            created_at: now,
        };

        let mut rules = self.suppression_rules.lock().unwrap();
        rules.insert(id, rule.clone());
        rule
    }

    pub fn create_inhibition_rule(
        &self,
        name: &str,
        source_labels: HashMap<String, String>,
        target_labels: HashMap<String, String>,
        equal: Vec<String>,
    ) -> InhibitionRule {
        let id = Uuid::new_v4().to_string();

        let rule = InhibitionRule {
            id: id.clone(),
            name: name.to_string(),
            enabled: true,
            source_labels,
            target_labels,
            equal,
            created_at: Utc::now(),
        };

        let mut rules = self.inhibition_rules.lock().unwrap();
        rules.insert(id, rule.clone());
        rule
    }

    pub fn get_notification(&self, notification_id: &str) -> Option<Notification> {
        let notifications = self.notifications.lock().unwrap();
        notifications.iter().find(|n| n.id == notification_id).cloned()
    }

    pub fn list_notifications(&self, limit: usize) -> Vec<Notification> {
        let notifications = self.notifications.lock().unwrap();
        notifications.iter()
            .rev()
            .take(limit)
            .cloned()
            .collect()
    }

    pub fn list_notifications_by_priority(&self, priority: NotificationPriority) -> Vec<Notification> {
        let notifications = self.notifications.lock().unwrap();
        notifications.iter()
            .filter(|n| n.priority == priority)
            .cloned()
            .collect()
    }

    pub fn get_suppression_rules(&self) -> Vec<SuppressionRule> {
        let rules = self.suppression_rules.lock().unwrap();
        rules.values().cloned().collect()
    }

    pub fn get_inhibition_rules(&self) -> Vec<InhibitionRule> {
        let rules = self.inhibition_rules.lock().unwrap();
        rules.values().cloned().collect()
    }

    pub fn disable_suppression_rule(&self, rule_id: &str) -> bool {
        let mut rules = self.suppression_rules.lock().unwrap();
        if let Some(rule) = rules.get_mut(rule_id) {
            rule.enabled = false;
            true
        } else {
            false
        }
    }

    pub fn disable_inhibition_rule(&self, rule_id: &str) -> bool {
        let mut rules = self.inhibition_rules.lock().unwrap();
        if let Some(rule) = rules.get_mut(rule_id) {
            rule.enabled = false;
            true
        } else {
            false
        }
    }

    pub fn retry_notification(&self, notification_id: &str) -> Result<Notification, String> {
        let mut notifications = self.notifications.lock().unwrap();
        let notification = notifications.iter_mut()
            .find(|n| n.id == notification_id)
            .ok_or_else(|| "Notification not found".to_string())?;

        if notification.status != NotificationStatus::Failed {
            return Err("Only failed notifications can be retried".to_string());
        }

        notification.status = NotificationStatus::Pending;
        notification.retry_count += 1;
        notification.failed_at = None;
        notification.failure_reason = None;

        Ok(notification.clone())
    }
}

impl Default for NotificationManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_notification() {
        let manager = NotificationManager::new();
        let mut labels = HashMap::new();
        labels.insert("service".to_string(), "api".to_string());
        labels.insert("severity".to_string(), "critical".to_string());

        let notification = manager.create_notification(
            "Test Alert",
            "Something went wrong",
            NotificationPriority::Critical,
            vec![NotificationChannel::Email, NotificationChannel::Slack],
            vec!["admin@example.com".to_string()],
            labels,
        );

        assert_eq!(notification.title, "Test Alert");
        assert_eq!(notification.priority, NotificationPriority::Critical);
        assert_eq!(notification.channels.len(), 2);
        assert_eq!(notification.status, NotificationStatus::Pending);
    }

    #[test]
    fn test_send_notification() {
        let manager = NotificationManager::new();
        let notification = manager.create_notification(
            "Test",
            "Message",
            NotificationPriority::High,
            vec![NotificationChannel::Email],
            vec!["test@example.com".to_string()],
            HashMap::new(),
        );

        let result = manager.send_notification(&notification.id);
        assert!(result.is_ok());
        
        let sent = result.unwrap();
        assert_eq!(sent.status, NotificationStatus::Sent);
        assert!(sent.sent_at.is_some());
    }

    #[test]
    fn test_suppression_rule() {
        let manager = NotificationManager::new();
        let mut match_labels = HashMap::new();
        match_labels.insert("service".to_string(), "api".to_string());

        let rule = manager.create_suppression_rule(
            "API Maintenance",
            match_labels,
            Some(60),
            "Scheduled maintenance",
            "admin",
        );

        assert!(rule.enabled);
        assert!(rule.start_time.is_some());
        assert!(rule.end_time.is_some());
        assert_eq!(rule.reason, "Scheduled maintenance");
    }

    #[test]
    fn test_inhibition_rule() {
        let manager = NotificationManager::new();
        let mut source_labels = HashMap::new();
        source_labels.insert("alertname".to_string(), "DatabaseDown".to_string());
        
        let mut target_labels = HashMap::new();
        target_labels.insert("alertname".to_string(), "ApiError".to_string());

        let rule = manager.create_inhibition_rule(
            "DB Inhibits API",
            source_labels,
            target_labels,
            vec!["cluster".to_string()],
        );

        assert!(rule.enabled);
        assert_eq!(rule.name, "DB Inhibits API");
    }

    #[test]
    fn test_list_notifications() {
        let manager = NotificationManager::new();
        
        for i in 0..5 {
            manager.create_notification(
                &format!("Notification {}", i),
                "Message",
                NotificationPriority::Medium,
                vec![NotificationChannel::Email],
                vec!["test@example.com".to_string()],
                HashMap::new(),
            );
        }

        let notifications = manager.list_notifications(10);
        assert_eq!(notifications.len(), 5);
    }

    #[test]
    fn test_priority_ordering() {
        assert!(NotificationPriority::Critical < NotificationPriority::High);
        assert!(NotificationPriority::High < NotificationPriority::Medium);
        assert!(NotificationPriority::Medium < NotificationPriority::Low);
    }
}
