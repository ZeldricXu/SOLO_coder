use chrono::{DateTime, Utc};
use ordered_float::OrderedFloat;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct Label {
    pub name: String,
    pub value: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct Labels(pub Vec<Label>);

impl Labels {
    pub fn new() -> Self {
        Self(Vec::new())
    }

    pub fn from_vec(labels: Vec<Label>) -> Self {
        Self(labels)
    }

    pub fn add(&mut self, name: String, value: String) {
        self.0.push(Label { name, value });
    }

    pub fn get(&self, name: &str) -> Option<&str> {
        self.0.iter().find(|l| l.name == name).map(|l| l.value.as_str())
    }

    pub fn to_btree(&self) -> BTreeMap<String, String> {
        self.0.iter().map(|l| (l.name.clone(), l.value.clone())).collect()
    }
}

impl Default for Labels {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricPoint {
    pub timestamp: DateTime<Utc>,
    pub value: f64,
}

impl MetricPoint {
    pub fn new(timestamp: DateTime<Utc>, value: f64) -> Self {
        Self { timestamp, value }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TimeSeries {
    pub metric_name: String,
    pub labels: Labels,
    pub points: Vec<MetricPoint>,
}

impl TimeSeries {
    pub fn new(metric_name: String, labels: Labels) -> Self {
        Self {
            metric_name,
            labels,
            points: Vec::new(),
        }
    }

    pub fn add_point(&mut self, timestamp: DateTime<Utc>, value: f64) {
        self.points.push(MetricPoint::new(timestamp, value));
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricAggregation {
    pub count: u64,
    pub sum: f64,
    pub min: f64,
    pub max: f64,
    pub avg: f64,
    pub p50: f64,
    pub p90: f64,
    pub p95: f64,
    pub p99: f64,
}

impl MetricAggregation {
    pub fn from_values(mut values: Vec<f64>) -> Option<Self> {
        if values.is_empty() {
            return None;
        }

        values.sort_by(|a, b| OrderedFloat(*a).cmp(&OrderedFloat(*b)));

        let count = values.len() as u64;
        let sum: f64 = values.iter().sum();
        let min = values[0];
        let max = values[values.len() - 1];
        let avg = sum / count as f64;

        let percentile = |p: f64| -> f64 {
            let idx = ((values.len() - 1) as f64 * p) as usize;
            values[idx]
        };

        Some(Self {
            count,
            sum,
            min,
            max,
            avg,
            p50: percentile(0.5),
            p90: percentile(0.9),
            p95: percentile(0.95),
            p99: percentile(0.99),
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WindowAggregate {
    pub window_start: DateTime<Utc>,
    pub window_end: DateTime<Utc>,
    pub metric_name: String,
    pub labels: Labels,
    pub aggregation: MetricAggregation,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueryResult {
    pub query_id: Uuid,
    pub results: Vec<TimeSeries>,
    pub executed_at: DateTime<Utc>,
    pub duration_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum AggregationFunction {
    Rate,
    Sum,
    Avg,
    Max,
    Min,
    Count,
    Quantile(f64),
}

impl AggregationFunction {
    pub fn from_str(s: &str) -> Option<Self> {
        match s.to_lowercase().as_str() {
            "rate" => Some(AggregationFunction::Rate),
            "sum" => Some(AggregationFunction::Sum),
            "avg" | "average" => Some(AggregationFunction::Avg),
            "max" => Some(AggregationFunction::Max),
            "min" => Some(AggregationFunction::Min),
            "count" => Some(AggregationFunction::Count),
            _ if s.starts_with("quantile") => {
                let p = s.split('(').nth(1)?.split(')').next()?.parse().ok()?;
                Some(AggregationFunction::Quantile(p))
            }
            _ => None,
        }
    }
}
