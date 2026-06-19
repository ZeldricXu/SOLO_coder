use crate::{AlertEvent, AlertSeverity};
use crate::config::{
    AlertChannelsConfig, ConfigHandle, DingTalkChannelConfig, EmailChannelConfig,
    FeishuChannelConfig, PagerDutyChannelConfig,
};
use chrono::{DateTime, Duration, Utc};
use hmac::{Hmac, Mac};
use parking_lot::RwLock;
use sha2::Sha256;
use std::collections::HashMap;
use std::sync::Arc;
use tracing::{debug, error, info, warn};

type HmacSha256 = Hmac<Sha256>;

#[async_trait::async_trait]
trait AlertChannel: Send + Sync {
    fn name(&self) -> &str;
    async fn send(&self, event: &AlertEvent) -> Result<(), Box<dyn std::error::Error + Send + Sync>>;
}

struct DingTalkChannel {
    cfg: DingTalkChannelConfig,
    client: reqwest::Client,
}

impl DingTalkChannel {
    fn new(cfg: DingTalkChannelConfig) -> Self {
        Self {
            cfg,
            client: reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(10))
                .build()
                .unwrap_or_default(),
        }
    }

    fn sign(&self, timestamp: i64) -> (String, String) {
        let secret = self.cfg.secret.clone().unwrap_or_default();
        if secret.is_empty() {
            return ("0".to_string(), String::new());
        }
        let string_to_sign = format!("{}\n{}", timestamp, secret);
        let mut mac = HmacSha256::new_from_slice(secret.as_bytes()).unwrap();
        mac.update(string_to_sign.as_bytes());
        let sig = mac.finalize().into_bytes();
        use base64::{engine::general_purpose::STANDARD, Engine as _};
        let encoded = STANDARD.encode(sig);
        let sign_enc = urlencoding::encode(&encoded).to_string();
        (timestamp.to_string(), sign_enc)
    }
}

#[async_trait::async_trait]
impl AlertChannel for DingTalkChannel {
    fn name(&self) -> &str {
        &self.cfg.name
    }

    async fn send(
        &self,
        event: &AlertEvent,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let now = Utc::now().timestamp_millis();
        let (ts, sign) = self.sign(now);
        let url = if sign.is_empty() {
            self.cfg.webhook_url.clone()
        } else {
            format!(
                "{}&timestamp={}&sign={}",
                self.cfg.webhook_url, ts, sign
            )
        };

        let color = match event.severity {
            AlertSeverity::Critical => "#FF0000",
            AlertSeverity::Warning => "#FFA500",
            AlertSeverity::Info => "#0000FF",
        };
        let title = format!("[{}] {}", event.severity, event.rule_name);
        let raw_preview = event
            .raw_logs
            .first()
            .map(|r| r.chars().take(500).collect::<String>())
            .unwrap_or_default();
        let body_text = format!(
            "**Time**: {}\n**Service**: {}\n**Rule**: {} (`{}`)\n**Severity**: {}\n**Occurrences**: {} ({}s)\n\n**Message**:\n{}\n\n**Sample log**:\n```\n{}\n```",
            event.timestamp.format("%Y-%m-%d %H:%M:%S UTC"),
            event.service,
            event.rule_name,
            event.rule_id,
            event.severity,
            event.occurrences,
            event.duration_secs,
            event.message,
            raw_preview,
        );

        let payload = serde_json::json!({
            "msgtype": "markdown",
            "markdown": {
                "title": title,
                "text": format!("<font color=\"{}\">## {}</font>\n\n{}", color, title, body_text),
            },
            "at": {
                "isAtAll": event.severity == AlertSeverity::Critical,
            }
        });

        let resp = self.client.post(&url).json(&payload).send().await?;
        let status = resp.status();
        if !status.is_success() {
            let txt = resp.text().await.unwrap_or_default();
            warn!("DingTalk {} returned {}: {}", self.name(), status, txt);
        }
        Ok(())
    }
}

struct FeishuChannel {
    cfg: FeishuChannelConfig,
    client: reqwest::Client,
}

impl FeishuChannel {
    fn new(cfg: FeishuChannelConfig) -> Self {
        Self {
            cfg,
            client: reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(10))
                .build()
                .unwrap_or_default(),
        }
    }
}

#[async_trait::async_trait]
impl AlertChannel for FeishuChannel {
    fn name(&self) -> &str {
        &self.cfg.name
    }

    async fn send(
        &self,
        event: &AlertEvent,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let color = match event.severity {
            AlertSeverity::Critical => "red",
            AlertSeverity::Warning => "orange",
            AlertSeverity::Info => "blue",
        };
        let title = format!("[{}] {} - {}", event.severity, event.rule_name, event.service);
        let raw_preview = event
            .raw_logs
            .first()
            .map(|r| r.chars().take(400).collect::<String>())
            .unwrap_or_default();

        let elements = vec![
            serde_json::json!({
                "tag": "div",
                "fields": [
                    {"is_short": true, "text": {"tag": "lark_md", "content": format!("**时间**\n{}", event.timestamp.format("%Y-%m-%d %H:%M:%S UTC"))}},
                    {"is_short": true, "text": {"tag": "lark_md", "content": format!("**服务**\n{}", event.service)}},
                    {"is_short": true, "text": {"tag": "lark_md", "content": format!("**等级**\n{}", event.severity)}},
                    {"is_short": true, "text": {"tag": "lark_md", "content": format!("**次数**\n{} ({}s)", event.occurrences, event.duration_secs)}},
                ]
            }),
            serde_json::json!({
                "tag": "markdown",
                "content": format!("**规则**: {} (`{}`)", event.rule_name, event.rule_id),
            }),
            serde_json::json!({
                "tag": "markdown",
                "content": format!("**描述**: {}", event.message),
            }),
            serde_json::json!({
                "tag": "markdown",
                "content": format!("**日志样例**:\n```\n{}\n```", raw_preview),
            }),
        ];

        let payload = serde_json::json!({
            "msg_type": "interactive",
            "card": {
                "config": {"wide_screen_mode": true},
                "header": {
                    "title": {"tag": "plain_text", "content": title},
                    "template": color,
                },
                "elements": elements,
            }
        });

        let resp = self
            .client
            .post(&self.cfg.webhook_url)
            .json(&payload)
            .send()
            .await?;
        let status = resp.status();
        if !status.is_success() {
            let txt = resp.text().await.unwrap_or_default();
            warn!("Feishu {} returned {}: {}", self.name(), status, txt);
        }
        Ok(())
    }
}

struct EmailChannel {
    cfg: EmailChannelConfig,
    client: reqwest::Client,
}

impl EmailChannel {
    fn new(cfg: EmailChannelConfig) -> Self {
        Self {
            cfg,
            client: reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(10))
                .build()
                .unwrap_or_default(),
        }
    }
}

#[async_trait::async_trait]
impl AlertChannel for EmailChannel {
    fn name(&self) -> &str {
        &self.cfg.name
    }

    async fn send(
        &self,
        event: &AlertEvent,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let subject = format!(
            "[logforge][{}] {} - {}",
            event.severity, event.rule_name, event.service
        );
        let raw_preview = event
            .raw_logs
            .first()
            .map(|r| r.chars().take(500).collect::<String>())
            .unwrap_or_default();
        let body = format!(
            "Time: {}\n\
             Service: {}\n\
             Rule: {} ({})\n\
             Severity: {}\n\
             Occurrences: {} ({}s)\n\
             \n\
             Message:\n{}\n\
             \n\
             Sample log:\n{}\n\
             \n\
             SMTP: {}:{}, from={}, to={:?}\n",
            event.timestamp.format("%Y-%m-%d %H:%M:%S UTC"),
            event.service,
            event.rule_name,
            event.rule_id,
            event.severity,
            event.occurrences,
            event.duration_secs,
            event.message,
            raw_preview,
            self.cfg.smtp_host,
            self.cfg.smtp_port,
            self.cfg.from,
            self.cfg.to,
        );

        debug!(
            "Email alert via {} (stub send, subject=\"{}\"): would send to {:?}",
            self.name(),
            subject,
            self.cfg.to
        );
        let _ = (subject, body, &self.client);
        Ok(())
    }
}

struct PagerDutyChannel {
    cfg: PagerDutyChannelConfig,
    client: reqwest::Client,
}

impl PagerDutyChannel {
    fn new(cfg: PagerDutyChannelConfig) -> Self {
        Self {
            cfg,
            client: reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(10))
                .build()
                .unwrap_or_default(),
        }
    }
}

#[async_trait::async_trait]
impl AlertChannel for PagerDutyChannel {
    fn name(&self) -> &str {
        &self.cfg.name
    }

    async fn send(
        &self,
        event: &AlertEvent,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let severity_pd = match event.severity {
            AlertSeverity::Critical => "critical",
            AlertSeverity::Warning => "warning",
            AlertSeverity::Info => "info",
        };
        let dedup_key = format!(
            "logforge-{}-{}-{}",
            event.rule_id,
            event.service,
            event.timestamp.format("%Y%m%d%H%M")
        );
        let payload = serde_json::json!({
            "routing_key": self.cfg.routing_key,
            "event_action": "trigger",
            "dedup_key": dedup_key,
            "payload": {
                "summary": format!("[{}] {} ({})", event.severity, event.rule_name, event.service),
                "source": format!("logforge-{}", event.service),
                "severity": severity_pd,
                "timestamp": event.timestamp.to_rfc3339(),
                "component": event.service.clone(),
                "group": "logforge",
                "class": event.rule_id.clone(),
                "custom_details": {
                    "rule_id": event.rule_id,
                    "rule_name": event.rule_name,
                    "service": event.service,
                    "occurrences": event.occurrences,
                    "duration_secs": event.duration_secs,
                    "message": event.message,
                    "raw_log_sample": event.raw_logs.first().cloned().unwrap_or_default(),
                }
            }
        });

        let resp = self
            .client
            .post(&self.cfg.api_url)
            .header("Content-Type", "application/json")
            .json(&payload)
            .send()
            .await?;
        let status = resp.status();
        if !status.is_success() {
            let txt = resp.text().await.unwrap_or_default();
            warn!("PagerDuty {} returned {}: {}", self.name(), status, txt);
        }
        Ok(())
    }
}

struct DedupEntry {
    first_time: DateTime<Utc>,
    last_time: DateTime<Utc>,
    occurrences: u64,
    notified: bool,
    escalated: bool,
}

struct AlerterInner {
    channels: HashMap<String, Arc<dyn AlertChannel>>,
    dedup_window_secs: u64,
    escalation_minutes: u64,
    escalation_api_url: Option<String>,
    dedup: HashMap<(String, String), DedupEntry>,
    pending: VecDeque<(AlertEvent, Vec<String>)>,
    rule_channels: HashMap<String, Vec<String>>,
    client: reqwest::Client,
}

pub struct Alerter {
    config: ConfigHandle,
    inner: Arc<RwLock<AlerterInner>>,
}

use std::collections::VecDeque;

impl Alerter {
    pub fn new(config: ConfigHandle) -> Self {
        Self {
            config,
            inner: Arc::new(RwLock::new(AlerterInner {
                channels: HashMap::new(),
                dedup_window_secs: 10,
                escalation_minutes: 5,
                escalation_api_url: None,
                dedup: HashMap::new(),
                pending: VecDeque::new(),
                rule_channels: HashMap::new(),
                client: reqwest::Client::builder()
                    .timeout(std::time::Duration::from_secs(10))
                    .build()
                    .unwrap_or_default(),
            })),
        }
    }

    pub async fn reload_from_config(&self) {
        let cfg = self.config.read().await;
        self.reload(&cfg.sink.alert_channels, &cfg.rules).await;
    }

    pub async fn reload(
        &self,
        alert_cfg: &AlertChannelsConfig,
        rules_cfg: &crate::config::RulesConfig,
    ) {
        let mut inner = self.inner.write();
        inner.channels.clear();
        inner.rule_channels.clear();
        inner.dedup_window_secs = alert_cfg.dedup_window_secs;
        inner.escalation_minutes = alert_cfg.escalation_minutes;
        inner.escalation_api_url = alert_cfg.escalation_api_url.clone();

        for dt_cfg in &alert_cfg.dingtalk {
            let ch = DingTalkChannel::new(dt_cfg.clone());
            info!("Loaded alert channel: dingtalk/{}", ch.name());
            inner
                .channels
                .insert(dt_cfg.name.clone(), Arc::new(ch) as Arc<dyn AlertChannel>);
        }
        for fs_cfg in &alert_cfg.feishu {
            let ch = FeishuChannel::new(fs_cfg.clone());
            info!("Loaded alert channel: feishu/{}", ch.name());
            inner
                .channels
                .insert(fs_cfg.name.clone(), Arc::new(ch) as Arc<dyn AlertChannel>);
        }
        for em_cfg in &alert_cfg.email {
            let ch = EmailChannel::new(em_cfg.clone());
            info!("Loaded alert channel: email/{}", ch.name());
            inner
                .channels
                .insert(em_cfg.name.clone(), Arc::new(ch) as Arc<dyn AlertChannel>);
        }
        for pd_cfg in &alert_cfg.pagerduty {
            let ch = PagerDutyChannel::new(pd_cfg.clone());
            info!("Loaded alert channel: pagerduty/{}", ch.name());
            inner
                .channels
                .insert(pd_cfg.name.clone(), Arc::new(ch) as Arc<dyn AlertChannel>);
        }

        for r in &rules_cfg.threshold_rules {
            inner
                .rule_channels
                .insert(r.id.clone(), r.alert_channels.clone());
        }
        for r in &rules_cfg.trend_rules {
            inner
                .rule_channels
                .insert(r.id.clone(), r.alert_channels.clone());
        }
        for r in &rules_cfg.pattern_rules {
            inner
                .rule_channels
                .insert(r.id.clone(), r.alert_channels.clone());
        }

        info!("Alerter loaded {} channels", inner.channels.len());
    }

    pub fn enqueue(&self, event: AlertEvent, now: DateTime<Utc>) {
        let mut inner = self.inner.write();
        let key = (event.rule_id.clone(), event.service.clone());
        let channels = inner
            .rule_channels
            .get(&event.rule_id)
            .cloned()
            .unwrap_or_default();

        if channels.is_empty() {
            return;
        }

        let entry = inner.dedup.entry(key.clone()).or_insert(DedupEntry {
            first_time: now,
            last_time: now,
            occurrences: 0,
            notified: false,
            escalated: false,
        });
        entry.last_time = now;
        entry.occurrences += event.occurrences.max(1);

        let dedup_cutoff = Duration::seconds(inner.dedup_window_secs as i64);
        let since_last = now - entry.last_time;
        let should_send = !entry.notified || since_last >= dedup_cutoff;

        if should_send {
            let mut ev = event.clone();
            ev.occurrences = entry.occurrences;
            ev.duration_secs = (now - entry.first_time).num_seconds() as u64;
            entry.notified = true;
            entry.first_time = now;
            entry.occurrences = 0;
            inner.pending.push_back((ev, channels));
        }
    }

    pub async fn flush(&self, now: DateTime<Utc>) {
        let items: Vec<(AlertEvent, Vec<String>)>;
        let escalation_candidates: Vec<(String, String, AlertEvent, Vec<String>)>;
        {
            let mut inner = self.inner.write();
            items = inner.pending.drain(..).collect();

            let mut escalate = Vec::new();
            let esc_cutoff = Duration::minutes(inner.escalation_minutes as i64);
            let keys: Vec<_> = inner.dedup.keys().cloned().collect();
            for k in keys {
                if let Some(entry) = inner.dedup.get_mut(&k) {
                    if entry.notified
                        && !entry.escalated
                        && now - entry.last_time >= esc_cutoff
                    {
                        entry.escalated = true;
                        if let Some(url) = inner.escalation_api_url.clone() {
                            let ev = AlertEvent {
                                id: uuid::Uuid::new_v4(),
                                timestamp: now,
                                rule_id: k.0.clone(),
                                rule_name: format!("[UNACKED] {}", k.0),
                                severity: AlertSeverity::Critical,
                                message: format!(
                                    "Alert not acknowledged in {} minutes - escalating: rule={}, service={}",
                                    inner.escalation_minutes, k.0, k.1
                                ),
                                service: k.1.clone(),
                                window_stats: None,
                                raw_logs: Vec::new(),
                                occurrences: entry.occurrences,
                                duration_secs: (now - entry.first_time).num_seconds() as u64,
                            };
                            escalate.push((url, k.0.clone(), ev, Vec::new()));
                        }
                    }
                }
            }
            escalation_candidates = escalate;
        }

        for (event, channels) in items {
            self.dispatch(event, channels).await;
        }

        for (url, _rule_id, ev, _channels) in escalation_candidates {
            let client = self.inner.read().client.clone();
            tokio::spawn(async move {
                let payload = serde_json::json!({
                    "alert_id": ev.id.to_string(),
                    "severity": "critical",
                    "message": ev.message,
                    "rule_id": ev.rule_id,
                    "service": ev.service,
                    "timestamp": ev.timestamp.to_rfc3339(),
                });
                match client.post(&url).json(&payload).send().await {
                    Ok(r) => {
                        debug!("Escalation API called: {}", r.status());
                    }
                    Err(e) => {
                        error!("Escalation API call failed: {}", e);
                    }
                }
            });
        }
    }

    async fn dispatch(&self, event: AlertEvent, channels: Vec<String>) {
        let inner = self.inner.read();
        for ch_name in channels {
            if let Some(ch) = inner.channels.get(&ch_name) {
                let ch = ch.clone();
                let ev = event.clone();
                tokio::spawn(async move {
                    if let Err(e) = ch.send(&ev).await {
                        warn!(
                            "Alert channel {} failed to send {}: {}",
                            ch.name(),
                            ev.rule_name,
                            e
                        );
                    } else {
                        info!(
                            "Alert sent via {}: {} (service={})",
                            ch.name(),
                            ev.rule_name,
                            ev.service
                        );
                    }
                });
            } else {
                warn!("Alert channel not found: {}", ch_name);
            }
        }
    }

    pub fn stats(&self) -> (usize, usize) {
        let inner = self.inner.read();
        (inner.channels.len(), inner.dedup.len())
    }
}
