use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::RwLock;
use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};

use crate::data_quality::{Severity, QualityReport, RuleType};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum MonitoringPhase {
    RuleEvaluation,
    DataFetching,
    ReportGeneration,
    AnomalyDetection,
    NotificationSend,
    Total,
}

impl MonitoringPhase {
    pub fn as_str(&self) -> &'static str {
        match self {
            MonitoringPhase::RuleEvaluation => "rule_evaluation",
            MonitoringPhase::DataFetching => "data_fetching",
            MonitoringPhase::ReportGeneration => "report_generation",
            MonitoringPhase::AnomalyDetection => "anomaly_detection",
            MonitoringPhase::NotificationSend => "notification_send",
            MonitoringPhase::Total => "total",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TimeStats {
    pub count: u64,
    pub total_ms: u64,
    pub min_ms: u64,
    pub max_ms: u64,
    pub avg_ms: f64,
    pub p50_ms: f64,
    pub p90_ms: f64,
    pub p99_ms: f64,
}

impl Default for TimeStats {
    fn default() -> Self {
        Self {
            count: 0,
            total_ms: 0,
            min_ms: u64::MAX,
            max_ms: 0,
            avg_ms: 0.0,
            p50_ms: 0.0,
            p90_ms: 0.0,
            p99_ms: 0.0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CriticalPathSnapshot {
    pub timestamp: DateTime<Utc>,
    pub table_name: String,
    pub total_duration_ms: u64,
    pub phase_durations: HashMap<String, u64>,
    pub bottleneck_phase: String,
    pub rules_evaluated: usize,
    pub passed_rules: usize,
    pub failed_rules: usize,
    pub quality_score: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthStatus {
    pub healthy: bool,
    pub message: String,
    pub timestamp: DateTime<Utc>,
    pub critical_tables_failing: Vec<String>,
    pub active_schedules: usize,
    pub pending_tasks: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrometheusMetric {
    pub name: String,
    pub metric_type: PrometheusMetricType,
    pub value: f64,
    pub labels: HashMap<String, String>,
    pub timestamp: Option<u64>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum PrometheusMetricType {
    Counter,
    Gauge,
    Histogram,
    Summary,
}

impl PrometheusMetricType {
    pub fn as_str(&self) -> &'static str {
        match self {
            PrometheusMetricType::Counter => "counter",
            PrometheusMetricType::Gauge => "gauge",
            PrometheusMetricType::Histogram => "histogram",
            PrometheusMetricType::Summary => "summary",
        }
    }
}

#[derive(Debug, Clone)]
pub struct PhaseTimer {
    phase: MonitoringPhase,
    start: Instant,
    collector: Arc<QualityMonitor>,
}

impl PhaseTimer {
    pub fn new(phase: MonitoringPhase, collector: Arc<QualityMonitor>) -> Self {
        Self {
            phase,
            start: Instant::now(),
            collector,
        }
    }

    pub async fn finish(self) -> u64 {
        let duration_ms = self.start.elapsed().as_millis() as u64;
        self.collector.record_phase_duration(self.phase, duration_ms).await;
        duration_ms
    }
}

#[derive(Debug)]
struct MonitorState {
    phase_stats: HashMap<MonitoringPhase, TimeStats>,
    phase_latencies: HashMap<MonitoringPhase, Vec<u64>>,
    rule_latencies: HashMap<RuleType, Vec<u64>>,
    critical_path_snapshots: Vec<CriticalPathSnapshot>,
    table_latest_snapshot: HashMap<String, CriticalPathSnapshot>,
    total_validations: u64,
    failed_validations: u64,
    total_rules_evaluated: u64,
    total_failures: HashMap<Severity, u64>,
    health_status: HealthStatus,
}

impl Default for MonitorState {
    fn default() -> Self {
        Self {
            phase_stats: HashMap::new(),
            phase_latencies: HashMap::new(),
            rule_latencies: HashMap::new(),
            critical_path_snapshots: Vec::new(),
            table_latest_snapshot: HashMap::new(),
            total_validations: 0,
            failed_validations: 0,
            total_rules_evaluated: 0,
            total_failures: HashMap::new(),
            health_status: HealthStatus {
                healthy: true,
                message: "System healthy".to_string(),
                timestamp: Utc::now(),
                critical_tables_failing: Vec::new(),
                active_schedules: 0,
                pending_tasks: 0,
            },
        }
    }
}

pub struct QualityMonitor {
    state: RwLock<MonitorState>,
    metrics_prefix: String,
    enable_prometheus: bool,
    max_snapshots: usize,
}

impl QualityMonitor {
    pub fn new() -> Self {
        Self::with_prefix("streamsql_quality")
    }

    pub fn with_prefix(prefix: impl Into<String>) -> Self {
        Self {
            state: RwLock::new(MonitorState::default()),
            metrics_prefix: prefix.into(),
            enable_prometheus: true,
            max_snapshots: 1000,
        }
    }

    pub fn phase_timer(self: Arc<Self>, phase: MonitoringPhase) -> PhaseTimer {
        PhaseTimer::new(phase, self)
    }

    pub async fn record_phase_duration(&self, phase: MonitoringPhase, duration_ms: u64) {
        let mut state = self.state.write().await;

        let stats = state.phase_stats.entry(phase).or_insert_with(TimeStats::default);
        stats.count += 1;
        stats.total_ms += duration_ms;
        stats.min_ms = stats.min_ms.min(duration_ms);
        stats.max_ms = stats.max_ms.max(duration_ms);
        stats.avg_ms = stats.total_ms as f64 / stats.count as f64;

        let latencies = state.phase_latencies.entry(phase).or_insert_with(Vec::new);
        latencies.push(duration_ms);

        if latencies.len() > 10000 {
            latencies.drain(0..latencies.len() - 10000);
        }

        let mut sorted = latencies.clone();
        sorted.sort_unstable();
        if !sorted.is_empty() {
            let len = sorted.len();
            stats.p50_ms = sorted[len / 2] as f64;
            stats.p90_ms = sorted[(len * 9) / 10] as f64;
            stats.p99_ms = sorted[(len * 99) / 100] as f64;
        }
    }

    pub async fn record_rule_latency(&self, rule_type: RuleType, duration_ms: u64) {
        let mut state = self.state.write().await;
        let latencies = state.rule_latencies.entry(rule_type).or_insert_with(Vec::new);
        latencies.push(duration_ms);
        if latencies.len() > 10000 {
            latencies.drain(0..latencies.len() - 10000);
        }
    }

    pub async fn record_validation(
        &self,
        table_name: &str,
        report: &QualityReport,
        phase_durations: HashMap<MonitoringPhase, u64>,
    ) {
        let mut state = self.state.write().await;

        state.total_validations += 1;
        if report.failed_rules > 0 {
            state.failed_validations += 1;
        }
        state.total_rules_evaluated += report.total_rules as u64;

        let total_duration_ms: u64 = phase_durations.values().sum();
        let bottleneck_phase = phase_durations.iter()
            .max_by_key(|(_, v)| *v)
            .map(|(k, _)| k.as_str().to_string())
            .unwrap_or_default();

        let snapshot = CriticalPathSnapshot {
            timestamp: Utc::now(),
            table_name: table_name.to_string(),
            total_duration_ms,
            phase_durations: phase_durations.iter()
                .map(|(k, v)| (k.as_str().to_string(), *v))
                .collect(),
            bottleneck_phase,
            rules_evaluated: report.total_rules,
            passed_rules: report.passed_rules,
            failed_rules: report.failed_rules,
            quality_score: if report.total_rules > 0 {
                (report.passed_rules as f64 / report.total_rules as f64) * 100.0
            } else {
                100.0
            },
        };

        state.table_latest_snapshot.insert(table_name.to_string(), snapshot.clone());

        state.critical_path_snapshots.push(snapshot);
        if state.critical_path_snapshots.len() > self.max_snapshots {
            let drain_count = state.critical_path_snapshots.len() - self.max_snapshots;
            state.critical_path_snapshots.drain(0..drain_count);
        }

        self.update_health_status(&mut state).await;
    }

    async fn update_health_status(&self, state: &mut MonitorState) {
        let critical_failing: Vec<String> = state.table_latest_snapshot.iter()
            .filter(|(_, s)| s.quality_score < 50.0)
            .map(|(k, _)| k.clone())
            .collect();

        let is_healthy = critical_failing.is_empty() 
            && state.total_validations > 0
            && (state.total_validations == 0 || state.failed_validations * 100 < state.total_validations * 10);

        state.health_status = HealthStatus {
            healthy: is_healthy,
            message: if is_healthy {
                "All systems operational".to_string()
            } else {
                format!("Tables failing quality checks: {}", critical_failing.join(", "))
            },
            timestamp: Utc::now(),
            critical_tables_failing: critical_failing,
            active_schedules: 0,
            pending_tasks: 0,
        };
    }

    pub async fn record_failure(&self, severity: Severity, table_name: &str) {
        let mut state = self.state.write().await;
        *state.total_failures.entry(severity).or_insert(0) += 1;
        let _ = table_name;
    }

    pub async fn get_health_status(&self) -> HealthStatus {
        let state = self.state.read().await;
        state.health_status.clone()
    }

    pub async fn get_critical_path_snapshots(&self, table: Option<&str>) -> Vec<CriticalPathSnapshot> {
        let state = self.state.read().await;
        if let Some(table_name) = table {
            state.table_latest_snapshot.get(table_name)
                .cloned()
                .map(|s| vec![s])
                .unwrap_or_default()
        } else {
            state.critical_path_snapshots.clone()
        }
    }

    pub async fn get_phase_stats(&self, phase: MonitoringPhase) -> Option<TimeStats> {
        let state = self.state.read().await;
        state.phase_stats.get(&phase).cloned()
    }

    pub async fn get_all_phase_stats(&self) -> HashMap<String, TimeStats> {
        let state = self.state.read().await;
        state.phase_stats.iter()
            .map(|(k, v)| (k.as_str().to_string(), v.clone()))
            .collect()
    }

    pub async fn get_latest_snapshot(&self, table: &str) -> Option<CriticalPathSnapshot> {
        let state = self.state.read().await;
        state.table_latest_snapshot.get(table).cloned()
    }

    pub async fn get_table_summaries(&self) -> HashMap<String, TableQualitySummary> {
        let state = self.state.read().await;
        state.table_latest_snapshot.iter()
            .map(|(table, snapshot)| {
                (table.clone(), TableQualitySummary {
                    table: table.clone(),
                    latest_score: snapshot.quality_score,
                    last_validation: snapshot.timestamp,
                    total_duration_ms: snapshot.total_duration_ms,
                    rules_passed: snapshot.passed_rules,
                    rules_failed: snapshot.failed_rules,
                    bottleneck: snapshot.bottleneck_phase.clone(),
                })
            })
            .collect()
    }

    pub async fn reset_stats(&self) {
        let mut state = self.state.write().await;
        *state = MonitorState::default();
    }

    pub async fn export_prometheus_metrics(&self) -> String {
        let metrics = self.get_prometheus_metrics().await;
        Self::format_prometheus_output(&metrics)
    }

    pub async fn get_prometheus_metrics(&self) -> Vec<PrometheusMetric> {
        let mut metrics = Vec::new();
        let state = self.state.read().await;

        metrics.push(PrometheusMetric {
            name: format!("{}_validations_total", self.metrics_prefix),
            metric_type: PrometheusMetricType::Counter,
            value: state.total_validations as f64,
            labels: HashMap::new(),
            timestamp: None,
        });

        metrics.push(PrometheusMetric {
            name: format!("{}_validations_failed_total", self.metrics_prefix),
            metric_type: PrometheusMetricType::Counter,
            value: state.failed_validations as f64,
            labels: HashMap::new(),
            timestamp: None,
        });

        metrics.push(PrometheusMetric {
            name: format!("{}_rules_evaluated_total", self.metrics_prefix),
            metric_type: PrometheusMetricType::Counter,
            value: state.total_rules_evaluated as f64,
            labels: HashMap::new(),
            timestamp: None,
        });

        for (severity, count) in &state.total_failures {
            metrics.push(PrometheusMetric {
                name: format!("{}_failures_total", self.metrics_prefix),
                metric_type: PrometheusMetricType::Counter,
                value: *count as f64,
                labels: {
                    let mut labels = HashMap::new();
                    labels.insert("severity".to_string(), severity.as_str().to_lowercase());
                    labels
                },
                timestamp: None,
            });
        }

        for (phase, stats) in &state.phase_stats {
            metrics.push(PrometheusMetric {
                name: format!("{}_phase_duration_seconds_avg", self.metrics_prefix),
                metric_type: PrometheusMetricType::Gauge,
                value: stats.avg_ms / 1000.0,
                labels: {
                    let mut labels = HashMap::new();
                    labels.insert("phase".to_string(), phase.as_str().to_string());
                    labels
                },
                timestamp: None,
            });

            metrics.push(PrometheusMetric {
                name: format!("{}_phase_duration_seconds_p99", self.metrics_prefix),
                metric_type: PrometheusMetricType::Gauge,
                value: stats.p99_ms / 1000.0,
                labels: {
                    let mut labels = HashMap::new();
                    labels.insert("phase".to_string(), phase.as_str().to_string());
                    labels
                },
                timestamp: None,
            });
        }

        for (table, snapshot) in &state.table_latest_snapshot {
            metrics.push(PrometheusMetric {
                name: format!("{}_quality_score", self.metrics_prefix),
                metric_type: PrometheusMetricType::Gauge,
                value: snapshot.quality_score,
                labels: {
                    let mut labels = HashMap::new();
                    labels.insert("table".to_string(), table.clone());
                    labels
                },
                timestamp: None,
            });

            metrics.push(PrometheusMetric {
                name: format!("{}_validation_duration_seconds", self.metrics_prefix),
                metric_type: PrometheusMetricType::Gauge,
                value: snapshot.total_duration_ms as f64 / 1000.0,
                labels: {
                    let mut labels = HashMap::new();
                    labels.insert("table".to_string(), table.clone());
                    labels
                },
                timestamp: None,
            });

            metrics.push(PrometheusMetric {
                name: format!("{}_rules_passed", self.metrics_prefix),
                metric_type: PrometheusMetricType::Gauge,
                value: snapshot.passed_rules as f64,
                labels: {
                    let mut labels = HashMap::new();
                    labels.insert("table".to_string(), table.clone());
                    labels
                },
                timestamp: None,
            });

            metrics.push(PrometheusMetric {
                name: format!("{}_rules_failed", self.metrics_prefix),
                metric_type: PrometheusMetricType::Gauge,
                value: snapshot.failed_rules as f64,
                labels: {
                    let mut labels = HashMap::new();
                    labels.insert("table".to_string(), table.clone());
                    labels
                },
                timestamp: None,
            });
        }

        let health_value = if state.health_status.healthy { 1.0 } else { 0.0 };
        metrics.push(PrometheusMetric {
            name: format!("{}_health", self.metrics_prefix),
            metric_type: PrometheusMetricType::Gauge,
            value: health_value,
            labels: HashMap::new(),
            timestamp: None,
        });

        metrics
    }

    fn format_prometheus_output(metrics: &[PrometheusMetric]) -> String {
        let mut output = String::new();
        let mut grouped: HashMap<(String, PrometheusMetricType), Vec<&PrometheusMetric>> = HashMap::new();

        for metric in metrics {
            grouped.entry((metric.name.clone(), metric.metric_type))
                .or_default()
                .push(metric);
        }

        for ((name, metric_type), group) in grouped {
            output.push_str(&format!("# HELP {} {}\n", name, Self::generate_help(&name)));
            output.push_str(&format!("# TYPE {} {}\n", name, metric_type.as_str()));

            for metric in group {
                output.push_str(&Self::format_metric_line(metric));
            }
        }

        output
    }

    fn format_metric_line(metric: &PrometheusMetric) -> String {
        let labels_str = if metric.labels.is_empty() {
            String::new()
        } else {
            let labels: Vec<String> = metric.labels.iter()
                .map(|(k, v)| format!("{}=\"{}\"", k, Self::escape_label_value(v)))
                .collect();
            format!("{{{}}}", labels.join(","))
        };

        let timestamp_str = metric.timestamp
            .map(|ts| format!(" {}", ts))
            .unwrap_or_default();

        format!("{}{}{}{}\n", metric.name, labels_str, metric.value, timestamp_str)
    }

    fn escape_label_value(value: &str) -> String {
        value.replace('\\', "\\\\")
            .replace('"', "\\\"")
            .replace('\n', "\\n")
    }

    fn generate_help(name: &str) -> String {
        if name.contains("validations_total") {
            "Total number of data quality validations".to_string()
        } else if name.contains("validations_failed_total") {
            "Total number of failed data quality validations".to_string()
        } else if name.contains("rules_evaluated_total") {
            "Total number of quality rules evaluated".to_string()
        } else if name.contains("failures_total") {
            "Total number of quality check failures by severity".to_string()
        } else if name.contains("quality_score") {
            "Current data quality score (0-100)".to_string()
        } else if name.contains("validation_duration") {
            "Duration of last validation in seconds".to_string()
        } else if name.contains("health") {
            "Data quality monitor health status (1=healthy, 0=unhealthy)".to_string()
        } else if name.contains("phase_duration") {
            "Duration statistics for validation phases".to_string()
        } else if name.contains("rules_passed") {
            "Number of rules passed in last validation".to_string()
        } else if name.contains("rules_failed") {
            "Number of rules failed in last validation".to_string()
        } else {
            "StreamSQL data quality metric".to_string()
        }
    }
}

impl Default for QualityMonitor {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TableQualitySummary {
    pub table: String,
    pub latest_score: f64,
    pub last_validation: DateTime<Utc>,
    pub total_duration_ms: u64,
    pub rules_passed: usize,
    pub rules_failed: usize,
    pub bottleneck: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CriticalPathAnalysis {
    pub table_name: String,
    pub total_duration_ms: u64,
    pub phase_breakdown: Vec<PhaseBreakdown>,
    pub bottleneck_phase: String,
    pub optimization_recommendations: Vec<String>,
    pub historical_trend: Option<HistoricalTrend>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PhaseBreakdown {
    pub phase: String,
    pub duration_ms: u64,
    pub percentage: f64,
    pub status: PhaseStatus,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum PhaseStatus {
    Normal,
    Warning,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoricalTrend {
    pub p50_change_pct: f64,
    pub p99_change_pct: f64,
    pub is_degrading: bool,
    pub trend_direction: String,
}

pub async fn analyze_critical_path(
    monitor: &QualityMonitor,
    table_name: &str,
) -> Option<CriticalPathAnalysis> {
    let snapshot = monitor.get_latest_snapshot(table_name).await?;

    let total = snapshot.total_duration_ms as f64;
    let mut phase_breakdown: Vec<PhaseBreakdown> = snapshot.phase_durations.iter()
        .map(|(phase, duration)| {
            let percentage = if total > 0.0 { (*duration as f64 / total) * 100.0 } else { 0.0 };
            let status = if percentage > 60.0 {
                PhaseStatus::Critical
            } else if percentage > 40.0 {
                PhaseStatus::Warning
            } else {
                PhaseStatus::Normal
            };
            PhaseBreakdown {
                phase: phase.clone(),
                duration_ms: *duration,
                percentage,
                status,
            }
        })
        .collect();

    phase_breakdown.sort_by(|a, b| b.duration_ms.cmp(&a.duration_ms));

    let mut recommendations = Vec::new();
    for breakdown in &phase_breakdown {
        match breakdown.status {
            PhaseStatus::Critical => {
                recommendations.push(format!(
                    "CRITICAL: Phase '{}' takes {:.1}% of total time. Investigate optimization opportunities.",
                    breakdown.phase, breakdown.percentage
                ));
            }
            PhaseStatus::Warning => {
                recommendations.push(format!(
                    "WARNING: Phase '{}' takes {:.1}% of total time. Consider review.",
                    breakdown.phase, breakdown.percentage
                ));
            }
            PhaseStatus::Normal => {}
        }
    }

    if recommendations.is_empty() {
        recommendations.push("All phases within normal range. System performing optimally.".to_string());
    }

    Some(CriticalPathAnalysis {
        table_name: table_name.to_string(),
        total_duration_ms: snapshot.total_duration_ms,
        phase_breakdown,
        bottleneck_phase: snapshot.bottleneck_phase.clone(),
        optimization_recommendations: recommendations,
        historical_trend: None,
    })
}
