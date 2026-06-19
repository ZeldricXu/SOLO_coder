use crate::{AlertEvent, AlertSeverity, LogLevel, LogRecord, WindowStats};
use crate::config::{
    ConfigHandle, PatternRuleConfig, ThresholdRuleConfig, TrendRuleConfig,
};
use chrono::{DateTime, Duration, Utc};
use parking_lot::RwLock;
use regex::RegexSet;
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use tracing::{debug, info};

#[derive(Clone)]
enum RuleKind {
    Threshold(ThresholdRule),
    Trend(TrendRule),
    Pattern(PatternRule),
}

#[derive(Clone)]
struct ThresholdRule {
    cfg: ThresholdRuleConfig,
    window_counts: HashMap<String, VecDeque<(DateTime<Utc>, u64)>>,
}

impl ThresholdRule {
    fn new(cfg: ThresholdRuleConfig) -> Self {
        Self {
            cfg,
            window_counts: HashMap::new(),
        }
    }
}

#[derive(Clone)]
struct TrendRule {
    cfg: TrendRuleConfig,
    history: HashMap<String, VecDeque<(DateTime<Utc>, f64)>>,
    consecutive: HashMap<String, u32>,
}

impl TrendRule {
    fn new(cfg: TrendRuleConfig) -> Self {
        Self {
            cfg,
            history: HashMap::new(),
            consecutive: HashMap::new(),
        }
    }
}

#[derive(Clone)]
struct PatternRule {
    cfg: PatternRuleConfig,
    regex_set: RegexSet,
    recent_hits: HashMap<String, VecDeque<DateTime<Utc>>>,
}

impl PatternRule {
    fn new(cfg: PatternRuleConfig) -> Result<Self, regex::Error> {
        let patterns: Vec<String> = cfg
            .patterns
            .iter()
            .map(|p| {
                if cfg.case_sensitive {
                    p.clone()
                } else {
                    format!("(?i){}", p)
                }
            })
            .collect();
        let regex_set = RegexSet::new(patterns)?;
        Ok(Self {
            cfg,
            regex_set,
            recent_hits: HashMap::new(),
        })
    }
}

struct RuleEngineInner {
    rules: HashMap<String, RuleKind>,
    fired_events: VecDeque<AlertEvent>,
    fire_history: HashMap<String, DateTime<Utc>>,
    last_raw_samples: HashMap<String, VecDeque<String>>,
}

pub struct RuleEngine {
    config: ConfigHandle,
    inner: Arc<RwLock<RuleEngineInner>>,
}

impl RuleEngine {
    pub fn new(config: ConfigHandle) -> Self {
        Self {
            config,
            inner: Arc::new(RwLock::new(RuleEngineInner {
                rules: HashMap::new(),
                fired_events: VecDeque::new(),
                fire_history: HashMap::new(),
                last_raw_samples: HashMap::new(),
            })),
        }
    }

    pub async fn reload_rules(&self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let cfg = self.config.read().await;
        let mut inner = self.inner.write();
        inner.rules.clear();

        for rule_cfg in &cfg.rules.threshold_rules {
            let rule = ThresholdRule::new(rule_cfg.clone());
            info!("Loaded threshold rule: {} ({})", rule_cfg.name, rule_cfg.id);
            inner.rules.insert(rule_cfg.id.clone(), RuleKind::Threshold(rule));
        }

        for rule_cfg in &cfg.rules.trend_rules {
            let rule = TrendRule::new(rule_cfg.clone());
            info!("Loaded trend rule: {} ({})", rule_cfg.name, rule_cfg.id);
            inner.rules.insert(rule_cfg.id.clone(), RuleKind::Trend(rule));
        }

        for rule_cfg in &cfg.rules.pattern_rules {
            match PatternRule::new(rule_cfg.clone()) {
                Ok(rule) => {
                    info!("Loaded pattern rule: {} ({})", rule_cfg.name, rule_cfg.id);
                    inner.rules.insert(rule_cfg.id.clone(), RuleKind::Pattern(rule));
                }
                Err(e) => {
                    panic!("Failed to compile pattern rule {}: {}", rule_cfg.id, e);
                }
            }
        }

        info!("Rule engine loaded {} rules total", inner.rules.len());
        Ok(())
    }

    pub fn ingest_record(&self, record: &LogRecord, now: DateTime<Utc>) {
        let mut inner = self.inner.write();
        let sample_key = format!("{}:{}", record.service, record.level);
        let samples = inner
            .last_raw_samples
            .entry(sample_key)
            .or_insert_with(|| VecDeque::with_capacity(20));
        samples.push_back(record.raw.clone());
        if samples.len() > 20 {
            samples.pop_front();
        }

        let rule_ids: Vec<String> = inner.rules.keys().cloned().collect();
        for rule_id in rule_ids {
            let rule = inner.rules.get(&rule_id).unwrap().clone();
            match rule {
                RuleKind::Threshold(rule) => {
                    Self::check_threshold(&mut inner, rule, record, now);
                }
                RuleKind::Pattern(rule) => {
                    Self::check_pattern(&mut inner, rule, record, now);
                }
                RuleKind::Trend(_) => {}
            }
        }
    }

    fn check_threshold(
        inner: &mut RuleEngineInner,
        mut rule: ThresholdRule,
        record: &LogRecord,
        now: DateTime<Utc>,
    ) {
        let service_matches = rule
            .cfg
            .service
            .as_deref()
            .map(|s| s == record.service.as_str())
            .unwrap_or(true);
        if !service_matches {
            return;
        }
        if let Some(expected_level) = &rule.cfg.level {
            let rec_level_str = record.level.to_string();
            if expected_level.to_uppercase() != rec_level_str.to_string().to_uppercase() {
                return;
            }
        }

        let svc_key = record.service.clone();
        let q = rule
            .window_counts
            .entry(svc_key.clone())
            .or_insert_with(VecDeque::new);
        q.push_back((now, 1));

        let cutoff = now - Duration::minutes(rule.cfg.window_minutes as i64);
        while let Some(f) = q.front() {
            if f.0 < cutoff {
                q.pop_front();
            } else {
                break;
            }
        }
        let total: u64 = q.iter().map(|(_, c)| *c).sum();
        inner
            .rules
            .insert(rule.cfg.id.clone(), RuleKind::Threshold(rule));

        if total >= rule.cfg.threshold_count {
            let rule_id = rule.cfg.id.clone();
            let last_fire = inner.fire_history.get(&rule_id).copied();
            let dedup_window = Duration::seconds(10);
            if last_fire.map(|t| now - t < dedup_window).unwrap_or(false) {
                return;
            }
            inner.fire_history.insert(rule_id.clone(), now);
            let severity = match rule.cfg.severity.as_str() {
                s if s.eq_ignore_ascii_case("CRITICAL") => AlertSeverity::Critical,
                s if s.eq_ignore_ascii_case("WARNING") | s.eq_ignore_ascii_case("WARN") => {
                    AlertSeverity::Warning
                }
                _ => AlertSeverity::Info,
            };
            let samples = inner
                .last_raw_samples
                .get(&format!("{}:{}", svc_key, record.level))
                .map(|q| q.iter().cloned().take(5).collect())
                .unwrap_or_default();
            let evt = AlertEvent {
                id: uuid::Uuid::new_v4(),
                timestamp: now,
                rule_id: rule_id,
                rule_name: rule.cfg.name.clone(),
                severity,
                message: format!(
                    "Threshold exceeded: service={}, {}={} in {}min window (threshold={})",
                    svc_key,
                    rule.cfg
                        .level
                        .as_deref()
                        .unwrap_or("logs"),
                    total,
                    rule.cfg.window_minutes,
                    rule.cfg.threshold_count
                ),
                service: svc_key,
                window_stats: None,
                raw_logs: samples,
                occurrences: total,
                duration_secs: rule.cfg.window_minutes * 60,
            };
            debug!("Threshold rule fired: {}", evt.rule_name);
            inner.fired_events.push_back(evt);
        }
    }

    fn check_pattern(
        inner: &mut RuleEngineInner,
        rule: PatternRule,
        record: &LogRecord,
        now: DateTime<Utc>,
    ) {
        let service_matches = rule
            .cfg
            .service
            .as_deref()
            .map(|s| s == record.service.as_str())
            .unwrap_or(true);
        if !service_matches {
            inner
                .rules
                .insert(rule.cfg.id.clone(), RuleKind::Pattern(rule));
            return;
        }

        if !rule.regex_set.is_match(&record.raw) && !rule.regex_set.is_match(&record.message) {
            inner
                .rules
                .insert(rule.cfg.id.clone(), RuleKind::Pattern(rule));
            return;
        }

        let matched_idx = rule
            .regex_set
            .matches(&record.raw)
            .into_iter()
            .chain(rule.regex_set.matches(&record.message).into_iter())
            .next();
        let _ = matched_idx;

        let rule_id = rule.cfg.id.clone();
        let svc_key = record.service.clone();
        let last_fire = inner.fire_history.get(&rule_id).copied();
        let dedup_window = Duration::seconds(10);

        inner
            .rules
            .insert(rule.cfg.id.clone(), RuleKind::Pattern(rule));

        if last_fire.map(|t| now - t < dedup_window).unwrap_or(false) {
            return;
        }
        inner.fire_history.insert(rule_id.clone(), now);

        let cfg = inner.rules.get(&rule_id).and_then(|r| match r {
            RuleKind::Pattern(p) => Some(p.cfg.clone()),
            _ => None,
        });
        if cfg.is_none() {
            return;
        }
        let cfg = cfg.unwrap();

        let severity = match cfg.severity.as_str() {
            s if s.eq_ignore_ascii_case("CRITICAL") => AlertSeverity::Critical,
            s if s.eq_ignore_ascii_case("WARNING") | s.eq_ignore_ascii_case("WARN") => {
                AlertSeverity::Warning
            }
            _ => AlertSeverity::Info,
        };
        let samples = inner
            .last_raw_samples
            .get(&format!("{}:{}", svc_key, record.level))
            .map(|q| q.iter().cloned().take(5).collect())
            .unwrap_or_else(|| vec![record.raw.clone()]);
        let evt = AlertEvent {
            id: uuid::Uuid::new_v4(),
            timestamp: now,
            rule_id,
            rule_name: cfg.name.clone(),
            severity,
            message: format!(
                "Pattern matched in service={}: patterns={:?}, raw_sample=\"{}\"",
                svc_key,
                cfg.patterns,
                samples.first().cloned().unwrap_or_default()
            ),
            service: svc_key,
            window_stats: None,
            raw_logs: samples,
            occurrences: 1,
            duration_secs: 0,
        };
        debug!("Pattern rule fired: {}", evt.rule_name);
        inner.fired_events.push_back(evt);
    }

    pub fn ingest_stats(&self, stats: &[WindowStats], now: DateTime<Utc>) {
        let mut inner = self.inner.write();
        let rule_ids: Vec<String> = inner.rules.keys().cloned().collect();
        for rule_id in rule_ids {
            let rule = inner.rules.get(&rule_id).unwrap().clone();
            if let RuleKind::Trend(trend) = rule {
                Self::check_trend(&mut inner, trend, stats, now);
            }
        }
    }

    fn check_trend(
        inner: &mut RuleEngineInner,
        mut rule: TrendRule,
        stats: &[WindowStats],
        now: DateTime<Utc>,
    ) {
        let quantile_extract = |ws: &WindowStats| -> f64 {
            match rule.cfg.quantile.as_str() {
                "P50" | "p50" | "MEDIAN" => ws.p50_spend,
                "P95" | "p95" => ws.p95_spend,
                "P99" | "p99" => ws.p99_spend,
                "MAX" | "max" => ws.max_spend,
                "AVG" | "avg" | "MEAN" => ws.avg_spend,
                _ => ws.p99_spend,
            }
        };

        let latest_per_service: HashMap<String, f64> = stats
            .iter()
            .filter(|ws| ws.key.level == LogLevel::Info || ws.key.level == LogLevel::Error || true)
            .map(|ws| {
                let service_matches = rule
                    .cfg
                    .service
                    .as_deref()
                    .map(|s| s == ws.key.service.as_str())
                    .unwrap_or(true);
                (ws.key.service.clone(), quantile_extract(ws), service_matches)
            })
            .filter(|(_, v, m)| *m && *v > 0.0)
            .map(|(k, v, _)| (k, v))
            .collect();

        let consecutive_required = rule.cfg.consecutive_windows;
        let growth_threshold = rule.cfg.growth_percent / 100.0;

        for (svc, current_value) in latest_per_service {
            let h = rule.history.entry(svc.clone()).or_insert_with(VecDeque::new);
            h.push_back((now, current_value));
            if h.len() > 20 {
                h.pop_front();
            }
            if h.len() >= 2 {
                let prev = h.iter().rev().nth(1).unwrap().1;
                let growth = if prev > 0.0 {
                    (current_value - prev) / prev
                } else {
                    0.0
                };
                let consec_entry = rule.consecutive.entry(svc.clone()).or_insert(0);
                if growth >= growth_threshold {
                    *consec_entry += 1;
                } else {
                    *consec_entry = 0;
                }
                if *consec_entry >= consecutive_required {
                    let rule_id = rule.cfg.id.clone();
                    let last_fire = inner.fire_history.get(&rule_id).copied();
                    let dedup_window = Duration::seconds(10);
                    if last_fire.map(|t| now - t >= dedup_window).unwrap_or(true) {
                        inner.fire_history.insert(rule_id.clone(), now);
                        let severity = match rule.cfg.severity.as_str() {
                            s if s.eq_ignore_ascii_case("CRITICAL") => AlertSeverity::Critical,
                            s if s.eq_ignore_ascii_case("WARNING") | s.eq_ignore_ascii_case("WARN") => {
                                AlertSeverity::Warning
                            }
                            _ => AlertSeverity::Info,
                        };
                        let samples = inner
                            .last_raw_samples
                            .get(&format!("{}:INFO", svc))
                            .map(|q| q.iter().cloned().take(3).collect())
                            .unwrap_or_default();
                        let evt = AlertEvent {
                            id: uuid::Uuid::new_v4(),
                            timestamp: now,
                            rule_id: rule_id.clone(),
                            rule_name: rule.cfg.name.clone(),
                            severity,
                            message: format!(
                                "Trend alert: service={}, {} value={:.2}ms, growth={:.1}% for {} consecutive windows (threshold={:.1}%)",
                                svc,
                                rule.cfg.quantile,
                                current_value,
                                growth * 100.0,
                                consec_entry,
                                rule.cfg.growth_percent
                            ),
                            service: svc.clone(),
                            window_stats: None,
                            raw_logs: samples,
                            occurrences: *consec_entry as u64,
                            duration_secs: *consec_entry as u64 * 10,
                        };
                        debug!("Trend rule fired: {}", evt.rule_name);
                        inner.fired_events.push_back(evt);
                        *consec_entry = 0;
                    }
                }
            }
        }

        inner
            .rules
            .insert(rule.cfg.id.clone(), RuleKind::Trend(rule));
    }

    pub fn drain_events(&self) -> Vec<AlertEvent> {
        let mut inner = self.inner.write();
        inner.fired_events.drain(..).collect()
    }

    pub fn triggered_count(&self) -> HashMap<String, u64> {
        let inner = self.inner.read();
        let mut result: HashMap<String, u64> = HashMap::new();
        for (rule_id, kind) in &inner.rules {
            let count = match kind {
                RuleKind::Threshold(t) => t.window_counts.values().map(|q| q.len() as u64).sum(),
                RuleKind::Trend(t) => t.consecutive.values().map(|v| *v as u64).sum(),
                RuleKind::Pattern(_) => 0,
            };
            result.insert(rule_id.clone(), count);
        }
        result
    }
}
