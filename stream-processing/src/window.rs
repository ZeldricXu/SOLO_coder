use anyhow::Result;
use chrono::{DateTime, Duration, Utc};
use std::collections::BTreeMap;
use tokio::sync::mpsc::Sender;
use tracing::debug;

use common::log::LogEvent;
use common::metrics::{Label, Labels, MetricAggregation, TimeSeries};

use crate::pipeline::StreamEvent;

#[derive(Debug, Clone)]
pub struct WindowConfig {
    pub window_duration_sec: i64,
    pub slide_duration_sec: i64,
    pub metric_name: String,
    pub labels: Labels,
    pub aggregator: WindowAggregator,
}

impl WindowConfig {
    pub fn new(
        window_duration_sec: i64,
        slide_duration_sec: i64,
        metric_name: String,
        aggregator: WindowAggregator,
    ) -> Self {
        Self {
            window_duration_sec,
            slide_duration_sec,
            metric_name,
            labels: Labels::new(),
            aggregator,
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub enum WindowAggregator {
    Count,
    Sum,
    Avg,
    ErrorRate,
    LatencyPercentile(f64),
}

pub struct SlidingWindow {
    config: WindowConfig,
    events: BTreeMap<DateTime<Utc>, Vec<f64>>,
    last_slide: DateTime<Utc>,
    output_sender: Sender<StreamEvent>,
    window_duration: Duration,
    slide_duration: Duration,
}

impl SlidingWindow {
    pub fn new(config: WindowConfig, output_sender: Sender<StreamEvent>) -> Self {
        let now = Utc::now();
        Self {
            config: config.clone(),
            events: BTreeMap::new(),
            last_slide: now,
            output_sender,
            window_duration: Duration::seconds(config.window_duration_sec),
            slide_duration: Duration::seconds(config.slide_duration_sec),
        }
    }

    pub async fn add_event(&mut self, event: StreamEvent) -> Result<()> {
        match event {
            StreamEvent::Log(log_event) => {
                let value = self.extract_value(&log_event);
                let timestamp = log_event.timestamp;
                self.events
                    .entry(timestamp)
                    .or_insert_with(Vec::new)
                    .push(value);
            }
            _ => {}
        }
        Ok(())
    }

    fn extract_value(&self, log_event: &LogEvent) -> f64 {
        match self.config.aggregator {
            WindowAggregator::Count => 1.0,
            WindowAggregator::Sum => 1.0,
            WindowAggregator::Avg => 1.0,
            WindowAggregator::ErrorRate => {
                if log_event.level.is_error() {
                    1.0
                } else {
                    0.0
                }
            }
            WindowAggregator::LatencyPercentile(_) => {
                log_event.get_duration_ms().unwrap_or(0.0)
            }
        }
    }

    pub async fn check_and_emit(&mut self) -> Result<()> {
        let now = Utc::now();
        if now - self.last_slide >= self.slide_duration {
            self.evict_old_windows(now);
            self.emit_window(now).await?;
            self.last_slide = now;
        }
        Ok(())
    }

    fn evict_old_windows(&mut self, now: DateTime<Utc>) {
        let cutoff = now - self.window_duration;
        self.events.retain(|&ts, _| ts >= cutoff);
    }

    async fn emit_window(&mut self, now: DateTime<Utc>) -> Result<()> {
        let window_start = now - self.window_duration;
        let window_end = now;

        let all_values: Vec<f64> = self
            .events
            .range(window_start..=window_end)
            .flat_map(|(_, vals)| vals.iter().copied())
            .collect();

        if all_values.is_empty() {
            return Ok(());
        }

        let result = self.aggregate(&all_values);
        debug!(
            "Window aggregation: {} - {} values, result: {:?}",
            self.config.metric_name,
            all_values.len(),
            result
        );

        let mut ts = TimeSeries::new(self.config.metric_name.clone(), self.config.labels.clone());
        match self.config.aggregator {
            WindowAggregator::Count => ts.add_point(window_end, result.count as f64),
            WindowAggregator::Sum => ts.add_point(window_end, result.sum),
            WindowAggregator::Avg => ts.add_point(window_end, result.avg),
            WindowAggregator::ErrorRate => {
                let error_rate = if result.count > 0 {
                    result.sum / result.count as f64
                } else {
                    0.0
                };
                ts.add_point(window_end, error_rate);
            }
            WindowAggregator::LatencyPercentile(p) => {
                let pct = match p {
                    0.5 => result.p50,
                    0.9 => result.p90,
                    0.95 => result.p95,
                    0.99 => result.p99,
                    _ => result.p50,
                };
                ts.add_point(window_end, pct);
            }
        }

        let _ = self.output_sender.send(StreamEvent::Metric(ts)).await;
        Ok(())
    }

    fn aggregate(&self, values: &[f64]) -> MetricAggregation {
        MetricAggregation::from_values(values.to_vec()).unwrap_or_else(|| MetricAggregation {
            count: 0,
            sum: 0.0,
            min: 0.0,
            max: 0.0,
            avg: 0.0,
            p50: 0.0,
            p90: 0.0,
            p95: 0.0,
            p99: 0.0,
        })
    }
}

pub fn create_error_rate_window(
    window_sec: i64,
    slide_sec: i64,
    service_name: String,
    output: Sender<StreamEvent>,
) -> SlidingWindow {
    let config = WindowConfig {
        window_duration_sec: window_sec,
        slide_duration_sec: slide_sec,
        metric_name: "error_rate".to_string(),
        labels: Labels::from_vec(vec![Label {
            name: "service".to_string(),
            value: service_name,
        }]),
        aggregator: WindowAggregator::ErrorRate,
    };
    SlidingWindow::new(config, output)
}

pub fn create_latency_p99_window(
    window_sec: i64,
    slide_sec: i64,
    service_name: String,
    output: Sender<StreamEvent>,
) -> SlidingWindow {
    let config = WindowConfig {
        window_duration_sec: window_sec,
        slide_duration_sec: slide_sec,
        metric_name: "latency_p99".to_string(),
        labels: Labels::from_vec(vec![Label {
            name: "service".to_string(),
            value: service_name,
        }]),
        aggregator: WindowAggregator::LatencyPercentile(0.99),
    };
    SlidingWindow::new(config, output)
}
